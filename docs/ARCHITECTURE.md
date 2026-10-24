# Architecture

## Layers

```
┌──────────────────────────── Android app (Kotlin / Compose) ────────────────────────────┐
│ ui/        CameraScreen · MonoViewfinder (AGSL) · ReviewScreen (zoom, remix) · RollScreen │
│ camera/    Lenses · CameraController (Camera2 session) · ExposureMeter · RawSubsample      │
│ raw/       RawFrameBridge (Characteristics + CaptureResult -> RawInput) · Orientation     │
│ engine/    Darkroom (roll state, queue, exports) · DevelopService (foreground)              │
│ store/     RawCache (app cache: .raw + .json) · MediaStoreWriter (Pictures/Mimizan Lab)     │
│ engine/*   generated UniFFI bindings (package ch.bojovic.mimizanlab.engine)                 │
└───────────────────────────────────────┬─────────────────────────────────────────────────┘
                                        │ JNA / UniFFI (libmimizan_mobile.so)
┌───────────────────────────────────────▼─────────────────────────────────────────────────┐
│ rust/mimizan-mobile   types.rs (FFI records/enums) · frame.rs (RawInput -> RawFrame,      │
│                       2x CFA binning) · meter.rs (RAW probe statistics) · session.rs        │
│                       (Developed: remix/preview/detail/export)                              │
│ mimizan-core          ingest -> separation (Dubois/adaptive/off) -> mix -> print path       │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

`mimizan-core` is used as a path dependency with `default-features = false`:
the `decode-rawler` feature (file decoding) stays off on the phone, the
pipeline entry is `negative_from_frame(&RawFrame, &NegativeParams)`. Nothing
in the algorithm is duplicated in the mobile crate.

## Capture path

1. `CameraController.start` opens the chosen lens with three streams:
   preview `SurfaceTexture` (largest 4:3 size <= 1600 px), a small
   `YUV_420_888` stream for the histogram, and a `RAW_SENSOR` `ImageReader`
   at the sensor size (the still's, and the meter's probes', see
   *Metering*). `Lenses.enumerate` lists the logical back cameras
   and the physical cameras behind them with another focal length; for a
   physical lens every `OutputConfiguration` gets `setPhysicalCameraId`,
   the characteristics and the capture result used for metadata are the
   physical ones (`physicalCameraTotalResults`).
2. The repeating request carries the user's controls: `CONTROL_AE_MODE_ON`
   + EV compensation, or `CONTROL_AE_MODE_OFF` with `SENSOR_SENSITIVITY` /
   `SENSOR_EXPOSURE_TIME` (manual, or the app's meter); AF continuous-picture, or AUTO plus a trigger
   after a tap (regions set for AF and AE); AWB auto. Flash stays off on
   this request, so the viewfinder never lights the LED.
3. `capture()` locks AF (continuous mode only) and, when exposure is
   automatic, runs AE precapture. Flash is armed only there and on the
   still: Auto uses `CONTROL_AE_MODE_ON_AUTO_FLASH`, On uses
   `CONTROL_AE_MODE_ON_ALWAYS_FLASH`. With AE off, On sets
   `FLASH_MODE_SINGLE` on the still and Auto does not fire. The wait is
   1.5 s for a converged/locked AE state, or 3 s when the flash is armed
   (precapture must be seen and then left). The still is a
   `TEMPLATE_STILL_CAPTURE` with the RAW reader and the preview surface as
   targets, `CONTROL_ENABLE_ZSL = false`, lens shading map on. Image and
   `TotalCaptureResult` are awaited separately and joined.
4. The RAW plane is copied with the row stride removed (`RawCapture.pixels`,
   `width*height*2` bytes, native endian = little endian on arm64) and the
   `Image` is closed immediately.
5. `RawFrameBridge.meta` maps the metadata:

   | RawInput field | Camera2 source |
   |---|---|
   | `pattern` | `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT` -> `bayer_pattern_from_camera2` |
   | `black_tile` | `SENSOR_DYNAMIC_BLACK_LEVEL` (result) else `SENSOR_BLACK_LEVEL_PATTERN`, row-major 2x2 |
   | `white_level` | `SENSOR_DYNAMIC_WHITE_LEVEL` else `SENSOR_INFO_WHITE_LEVEL` |
   | `crop` | `ACTIVE_ARRAY_SIZE` relative to `PRE_CORRECTION_ACTIVE_ARRAY_SIZE` (only if the buffer is the pre-correction size) |
   | `orientation` | TIFF tag from `SENSOR_ORIENTATION` + device rotation (`OrientationEventListener`), the JPEG_ORIENTATION rule |
   | `wb_gains` | `COLOR_CORRECTION_GAINS` as R, G even, G odd, B |
   | `xyz_to_cam` | `SENSOR_COLOR_TRANSFORM1`, row-major |
   | `exposure` | `SENSOR_SENSITIVITY`, `SENSOR_EXPOSURE_TIME`, `LENS_APERTURE`, `LENS_FOCAL_LENGTH` |

   `make`/`model` are `Build.MANUFACTURER` / `Build.MODEL` and select the
   camera file in `assets/cameras` (only if its `bayer_phase` matches).
6. `Darkroom.submit` writes the frame into the `RawCache` (`<name>.raw` +
   `<name>.json`, app-private) and queues the development. No DNG is
   written (0.1.0 did; dropped so that nothing but monochrome reaches the
   gallery). `Darkroom.delete` / `deleteAll` remove frames from the cache,
   the list and, if it is the one in memory, close the `Developed`.

## Metering

`MeteringMode.PHONE` leaves the exposure to the HAL (`CONTROL_AE_MODE_ON`
plus EV compensation, precapture before the still). `HIGHLIGHTS` and
`GREY` put the camera in `CONTROL_AE_MODE_OFF` and the app drives
`SENSOR_SENSITIVITY` / `SENSOR_EXPOSURE_TIME` itself, with
`CONTROL_POST_RAW_SENSITIVITY_BOOST = 100` so the preview is the sensor's
exposure and not a boosted copy of it.

* Probes: `CameraController.probe` submits one `TEMPLATE_PREVIEW` request
  every 200 ms with the RAW reader as an extra target beside the preview
  (and YUV) surfaces, the same controls as the repeating request. The RAW
  stream is in the session anyway, so the request has the preview's frame
  duration; Camera2's model promises no frame-rate loss for an interleaved
  request whose previous RAW buffer has been delivered. The RAW reader is
  shared with the still through a FIFO of waiters (`rawWaiters`): the HAL
  returns RAW buffers in request order.
* Sample: `RawSubsample.of` copies two rows per tile row out of the
  `Image` in bulk and keeps one 2x2 tile every 16 px of the active area
  (255 x 192 tiles at 4080 x 3072, 392 kB), origin even so the frame's
  phase and black tile apply. The `Image` is closed before the Rust call.
* Statistics: `meter_raw` (`meter.rs`) computes, per tile, the maximum
  over the raw channels (above black, over the range to white, before any
  balance) and the balanced mix luma with the viewfinder's weights
  (`FinderTone.weights`, the development's `mix_weights`) and the frame's
  `COLOR_CORRECTION_GAINS`. It returns the 99.9th percentile of the tile
  maxima (`highlight`), the clipped fraction, the geometric mean luma over
  the frame and over the tapped spot, and a 1024-bin luma histogram (used
  for the corner histogram when a HAL refused the YUV stream).
* Control: `ExposureMeter` (pure Kotlin, unit-tested) keeps one number,
  `H = ISO · s`. A probe at `H_p` whose statistic is `s` gives
  `H = H_p · target / s` in one step (the sensor is linear). Targets:
  `0.7071` for the highlight (half a stop under the clip = Zone VIII on
  the paper), `0.18` for the grey (Zone V), times `2^EV`. A clipped
  highlight steps down one stop per probe until it shows; a step down is
  refused once it would push the scene's grey five stops under the card
  (a lamp in a dark room). Dead band 1/6 stop; a probe whose exposure is
  not the commanded one yet (pipeline depth) is skipped up to three
  times. `H` is split twice into the lowest ISO whose time stays within a
  cap: 1/30 s for the viewfinder (the stream keeps its rate; the frame
  duration is left to the template), `min(1/8 s, 2/f_equiv)` for the
  still. With `STATISTICS_SCENE_FLICKER` at 50/60 Hz, times at or above
  the half-period are whole multiples of it. `flashRequired` is set when
  the still's limits fall half a stop short; `Auto` flash then fires
  `FLASH_MODE_SINGLE` on the still.
* Guard: `noteFrameInterval` keeps a running mean of the `SENSOR_TIMESTAMP`
  deltas and counts gaps above 2.5x. Six gaps within twelve probes, or
  five failed probes in a row, stand the meter down for the session
  (`meteringNote`, shown once) and the phone's AE takes over. Choosing a
  mode again in the settings gives it a fresh try.

The still under the app's meter takes no precapture: it is fired with the
plan's still pair as soon as the focus lock (continuous AF only) is seen.

## Development

`DevelopService` (`foregroundServiceType` `mediaProcessing` on API 35+,
`dataSync` on 34) drains `Darkroom.runNext` one frame at a time under a
partial wake lock and stops itself when the queue is empty. For each frame:

* `RawMeta.toRawInput(pixels)` -> `develop(input, params)`; `params` are
  `default_develop_params()` with the shot's separation, binning, filter,
  white balance, and the camera file text.
* Rust (`session.rs::develop_impl`): `to_raw_frame` validates and converts
  LE bytes to `u16` (rayon), optional `bin_cfa_2x`, then `negative_from_frame`
  with `keep_separation = true`. The three separation planes (L̂, Ĉ1', Ĉ2')
  of the full negative stay in the `Developed` object (3 x 100 MB at
  12.5 MP) plus downscaled upright preview planes (2048 px long edge); the
  saturation mask of the full frame is dropped after the first mix.
* The `Developed` handle replaces the previous one in `Darkroom.current`
  (the old one is closed -> native memory freed); the thumbnail and, if
  enabled, a full-size JPEG (the paper at the settings exposures, with the
  settings sharpening) are produced right away.

Memory budget per development at 12.5 MP (f64 planes): ingest ~200 MB,
Dubois separation ~1 GB peak (measured 1016 MB on the host for the same
frame size), steady state afterwards ~375 MB (3 planes + previews). "Mask
off" needs about 60 % of that, Quick mode a quarter.

## Review

`ReviewViewModel` follows `Darkroom.current`; if the shot's negative is no
longer in memory it offers "Develop again" (queue from the cache). All
interactions go through the `Developed` object without touching the
separation:

* filter / sliders -> `remix(weights, BALANCED, filter)` (returns the weights
  the mix now uses, including the filter) -> `preview(soft, hard)` +
  `histogram(soft, hard)`
* soft / hard -> stops of time from the paper's base exposure, ±3, in
  twelfths. Zero is the base print (about grade 2). Preview, histogram and
  the 1:1 window evaluate `Tone::Paper` per pixel, the same map the JPEG
  and TIFF use, so the screen and the file agree. The viewfinder cannot
  do that per pixel in Rust, so it indexes `paper_lut` (4097 samples of
  that map over linear grey). The starting pair comes from `Prefs` (the
  settings sheet) and is also the automatic JPEG; a review does not write
  the pair back.
* pinch zoom beyond the preview resolution -> `detail(x, y, w, h, soft,
  hard, turns, usm_amount)` for the visible window, drawn 1:1 on top of the
  scaled preview. The app passes `usm_amount = 0` (the 1:1 window is the
  plain mix); with `usm_amount > 0` the window is mixed with a margin of
  `3r + 1` px, the paper is applied, `usm::unsharp` runs with the radius
  `print::geometry` gives for the full-size print (1.49 px for 4080x3072 at
  300 dpi) and the saturation mask window (`mask8` crop), then the margin
  is cut off again.
* export -> `export_jpeg` (full size, with `usm_amount`, `deconvolution`,
  `deconv_iterations`), `export_tiff16` (with the same),
  `export_negative` (linear 16-bit TIFF + `.mask.tif`), written to a
  scratch file and imported into MediaStore. JPEG quality is the core's
  fixed 100.

Gray8 buffers cross the FFI as `ByteArray` (UniFFI fast path) and are
expanded to ARGB bitmaps on the Kotlin side.

## Viewfinder shader

`MonoViewfinder` wraps the preview `TextureView` in a Compose
`graphicsLayer` whose `renderEffect` is a `RuntimeShader` (a `RenderEffect`
on the `TextureView` itself blacked out the UI above it on the Pixel 9).
The AGSL program linearises the pinned sRGB preview, mixes with the
development's weights pulled back through the frame's ISP matrix, and
indexes `paper_lut` (the print curve over linear grey, no second gamma).
Zebra stripes mark display values above 0.985. It is an approximation of
the mix on balanced raw channels (the camera's preview is already white
balanced and tone mapped), good enough to see what a filter and the paper
do to the tonal separation; the negative itself never passes through it.

## Build wiring

`android/app/build.gradle.kts` registers two `Exec` tasks before `preBuild`:

* `cargoBuild`: `cargo ndk -t arm64-v8a --platform <minSdk> -o build/rust/jniLibs build --lib [--release]`
* `generateUniffiBindings`: `cargo run --features bindgen --bin uniffi-bindgen -- generate --library <so> --language kotlin --no-format --out-dir build/generated/uniffi/kotlin`

The generated Kotlin directory is added to the `main` source set
(`kotlin.directories`), the jniLibs directory likewise. `ndkVersion` is the
NDK `cargo-ndk` links against so AGP strips the library. R8 keeps
`com.sun.jna.**` and `ch.bojovic.mimizanlab.engine.**`.
