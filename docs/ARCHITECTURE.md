# Architecture

## Layers

```
┌──────────────────────────── Android app (Kotlin / Compose) ────────────────────────────┐
│ ui/        CameraScreen · MonoViewfinder (AGSL) · ReviewScreen (zoom, remix) · RollScreen │
│ camera/    Lenses (RAW-capable back cameras) · CameraController (Camera2 session)          │
│ raw/       RawFrameBridge (Characteristics + CaptureResult -> RawInput) · Orientation     │
│ engine/    Darkroom (roll state, queue, exports) · DevelopService (foreground)              │
│ store/     RawCache (app cache: .raw + .json) · MediaStoreWriter (Pictures/Mimizan Lab)     │
│ engine/*   generated UniFFI bindings (package ch.bojovic.mimizanlab.engine)                 │
└───────────────────────────────────────┬─────────────────────────────────────────────────┘
                                        │ JNA / UniFFI (libmimizan_mobile.so)
┌───────────────────────────────────────▼─────────────────────────────────────────────────┐
│ rust/mimizan-mobile   types.rs (FFI records/enums) · frame.rs (RawInput -> RawFrame,      │
│                       2x CFA binning) · session.rs (Developed: remix/preview/detail/export) │
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
   at the sensor size. `Lenses.enumerate` lists the logical back cameras
   and the physical cameras behind them with another focal length; for a
   physical lens every `OutputConfiguration` gets `setPhysicalCameraId`,
   the characteristics and the capture result used for metadata are the
   physical ones (`physicalCameraTotalResults`).
2. The repeating request carries the user's controls: `CONTROL_AE_MODE_ON`
   + EV compensation (the viewfinder's EV slider plus the standing
   compensation from the settings, `Prefs.compensationThirds`, summed in
   the HAL's steps by `aeCompensationSteps`), or `CONTROL_AE_MODE_OFF` with `SENSOR_SENSITIVITY` /
   `SENSOR_EXPOSURE_TIME`; AF continuous-picture, or AUTO plus a trigger
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
   `<name>.json`, app-private; the metadata lands through a rename, so a
   frame exists only once both files are complete and an interrupted write
   leaves an orphan `.raw` that the next trim removes) and queues the
   development. No DNG is written (0.1.0 did; dropped so that nothing but
   monochrome reaches the gallery). `Darkroom.delete` / `deleteAll` remove
   frames from the cache, the list and, if it is the one in memory, close
   the `Developed`.

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

### The plate

The second process of the darkroom (`PLATE.md` in the mimizan
repository): three mixes of the same separation through three "taking
filters", printed through the same paper into one RGB file. On the phone it
is, as on the desktop, a re-mix and not a re-development:

* *Mono · Plate* (`ProcessRow`, in the viewfinder and in review) ->
  `set_plate(filters, space)` resolves the three weight triples for this
  frame (`Camera`: the frame's `SENSOR_COLOR_TRANSFORM` for D65 towards the
  primaries; `Wratten`: 25/58/47 in front of the camera file's response or
  the native one; `Channels`) and keeps the monochrome mix stored;
  `remix(...)` is the way back. The choice and the lamps are standing
  settings (`Prefs`), so the next shot opens in the same process and a
  development done in review carries over.
* lamps R / B -> `set_lamps(red, blue)`, stops within ±2, green the
  reference. Preview, histogram, 1:1 window and every export apply them as
  `v · 2^lamp` before the paper, the print's placing.
* `preview_rgb`, `detail_rgb`, `histogram_rgb` are the colour versions of
  the three grey calls (interleaved RGB8; `histogram` stays the
  luminance's and is drawn as bars under the three channel outlines).
* `export_jpeg` / `export_tiff16` render the three positives one after the
  other (one full-resolution mix alive at a time beside the finished
  sheets; about +50 MB over the grey export at 12.5 MP) and write RGB with
  the plate's ICC profile. `export_negative` writes the stacked
  three-frame plate the desktop `mimizan print` recognises
  (`<name>.plate.tif` + one mask frame).
* Files are named `_plate`, `_plate_wratten`, `_plate_channels`, so the
  negative and the plate of one frame sit side by side in the gallery.

`PlateSpace` defaults to sRGB on the phone (the gallery and sharing take
the file as it is); Adobe RGB is a chip away. The preview bitmap carries
the paper's gamma 2.2 values in the plate's primaries and is displayed as
sRGB, the same small difference the grey preview has had all along.

## Viewfinder shader

`MonoViewfinder` wraps the preview `TextureView` in a Compose
`graphicsLayer` whose `renderEffect` is a `RuntimeShader` (a `RenderEffect`
on the `TextureView` itself blacked out the UI above it on the Pixel 9).
The AGSL program linearises the pinned sRGB preview, mixes with the
development's weights pulled back through the frame's ISP matrix, and
indexes `paper_lut` (the print curve over linear grey, no second gamma).
In the plate process it mixes three times with the taking filters
(`plate_weights`, the numbers `set_plate` will use for the still, each
pulled back the same way), multiplies by the lamps and indexes the same
table per channel, so the viewfinder shows the plate in colour. Zebra
stripes mark display values above 0.985 (of the brightest channel). It is an approximation of
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
