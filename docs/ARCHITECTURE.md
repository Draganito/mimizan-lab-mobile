# Architecture

## Layers

```
┌──────────────────────────── Android app (Kotlin / Compose) ────────────────────────────┐
│ ui/        CameraScreen · MonoViewfinder (AGSL) · ReviewScreen (zoom, remix) · RollScreen │
│ camera/    Lenses (RAW-capable back cameras) · CameraController (Camera2 session)          │
│ raw/       RawFrameBridge (Characteristics + CaptureResult -> RawInput) · Orientation     │
│ engine/    Darkroom (roll state, queue, exports) · DevelopService (foreground)              │
│ store/     RawCache (app cache: .raw + .json + .grade + .jpg) · Prefs · MediaStoreWriter (Pictures/…) │
│ engine/*   generated UniFFI bindings (package ch.bojovic.mimizanlab.engine)                 │
└───────────────────────────────────────┬─────────────────────────────────────────────────┘
                                        │ JNA / UniFFI (libmimizan_mobile.so)
┌───────────────────────────────────────▼─────────────────────────────────────────────────┐
│ rust/mimizan-mobile   types.rs (FFI records/enums) · frame.rs (RawInput -> RawFrame)      │
│                       session.rs (Developed: remix/plate/preview/detail/peak/export)       │
│ mimizan-core          ingest -> separation (Dubois/adaptive/off) -> mix -> print path       │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

`mimizan-core` is a git dependency on `Draganito/mimizan`, pinned to one
commit (`rev` in `Cargo.toml`, moved by `scripts/set_version.sh`), with
`default-features = false`.
On Android the frame arrives as a buffer and the pipeline entry is
`negative_from_frame(&RawFrame, &NegativeParams, 0)`; the iOS build turns
on `decode-files` (`mimizan-core/decode-rawler`) and reads the DNG with
`pipeline::decode` first, then takes the same entry. Nothing in the
algorithm is duplicated in the mobile crate.

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
   (no exposure compensation: a dark print is corrected in the developer,
   where the stops are linear), or `CONTROL_AE_MODE_OFF` with `SENSOR_SENSITIVITY` /
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
   leaves an orphan `.raw` that the next trim removes) together with
   `<name>.grade`, the frame's recipe (`Recipe` / `RecipeFile`): the
   print (`Print`: grade and Peak), which starts as the standing pair
   of that moment (`Prefs.standingPrint`), and the process, filter,
   plate filters and plate primaries (`plateSpace`) the frame was
   developed or last re-mixed in (enum names; every key past `soft` and
   `hard` is optional, so an older file still reads). Unless the shot
   mode is Raw, it queues the development. Two shots within one second get `_2`, `_3` after the
   time stamp, so the roll never lists one name twice. A finished development adds
   `<name>.jpg`, the roll's 256 px thumbnail, written to `.jpg.tmp` and
   renamed the same way; the roll shows it after a restart without
   developing again. The trim budget (1.5 GB) counts all three files per
   frame and sweeps stale `.tmp` leftovers first. No DNG is written (0.1.0
   did; dropped so that nothing but monochrome reaches the gallery).
   `Darkroom.delete` / `deleteAll` remove frames from the cache, the list
   and, if it is the one in memory, close the `Developed`.
7. The shot mode (`Prefs.shotMode`) decides what follows the shutter.
   *JPEG*: develop now with `autoJpeg = true`, the finder stays. *Developer*:
   develop now, no JPEG, the review opens on the frame
   (`CameraViewModel.pendingReview`). *Raw*: the frame is cached as
   `ShotStatus.Cached` and nothing runs until it is opened from the roll;
   a development started from review always has `autoJpeg = false`, so
   the picture waits for Export.

## Development

`DevelopService` (`foregroundServiceType` `dataSync|mediaProcessing` in
the manifest, both at once) drains `Darkroom.runNext` one frame at a time
under a partial wake lock and stops itself when the queue is empty.
`Darkroom.enqueue` starts it for every frame; the worker reads the latest
start id before it finds the queue empty and leaves through
`stopSelfResult(id)`, so a frame queued (and a start delivered) in that
moment is taken by the same worker instead of being orphaned. Android
refuses a foreground start while the app is in the background; such a
frame stays queued and `Darkroom.resumeQueue`, called from the camera's
`ON_RESUME`, starts the service again when the app returns. For each
frame:

* `RawMeta.toRawInput(pixels)` -> `develop(input, params)`; `params` are
  `default_develop_params()` with the shot's filter and the camera file
  text (the separation is Dubois and the balance the camera's: the
  core's defaults, the phone offers no other). Every development is at
  full size; a small print is the same development on a smaller sheet.
* Rust (`session.rs::develop_impl`): `to_raw_frame` validates and converts
  LE bytes to `u16` (rayon), then `negative_from_frame` with
  `keep_separation = true`. The three separation planes (L̂, Ĉ1', Ĉ2')
  of the full negative stay in the `Developed` object (3 x 100 MB at
  12.5 MP) plus downscaled upright preview planes (2048 px long edge); the
  saturation mask of the full frame and the first mix plane are dropped
  before the preview planes are made (every picture is mixed from the
  separation).
* The `Developed` handle replaces the previous one in `Darkroom.current`
  (the old one is closed -> native memory freed); the thumbnail and, for
  a JPEG shot, a full-size JPEG (the paper at the frame's print,
  `Shot.print`: its grade and its Peak) are produced right away. Only
  the development itself can fail the frame: the thumbnail, its file,
  Peak's measurement and the JPEG each fail on their own with a log
  line, and a handle that does not reach `Darkroom.current` is closed.

Memory budget per development at 12.5 MP (f64 planes): ingest ~200 MB,
Dubois separation ~1 GB peak (measured 1016 MB on the host for the same
frame size), steady state afterwards ~375 MB (3 planes + previews). "Mask
off" needs about 60 % of that.

## Review

`ReviewViewModel` follows `Darkroom.current`; if the shot's negative is no
longer in memory it offers "Develop again" (queue from the cache). All
interactions go through the `Developed` object without touching the
separation:

* filter -> `remix(default_weights, BALANCED, filter)` (the camera's
  balanced weights, then the filter; returns the weights the mix now
  uses) -> `preview(soft, hard)` + `histogram(soft, hard)`. There are no
  weight sliders on the phone. The re-mix also writes the process and
  filter to the frame's recipe (`Darkroom.setSettings`), so the roll's
  line and a later "Develop again" start from the choice.
* soft / hard -> stops of time from the paper's base exposure, ±3, in
  twelfths. Zero is the base print (about grade 2). The taking filter
  stands in `Prefs.filter` (`"filter"`, the engine's enum name) beside
  the process and the plate's filters; `withStandingProcess` puts it
  into the next frame's `DevelopSettings`, the finder's tone follows
  it, and the developer's `setFilter` writes the frame's choice back to
  it. `FilterStrip` (Compose and SwiftUI) shows the seven glasses or
  the three plate filters in equal fields over the paper strip, the
  chosen one in the glass's colour, tap or drag. Both apps show
  them as one strip (`PaperStrip`, Compose and SwiftUI alike), drawn
  from `paper_lut` for the pair over the scene, from five stops under
  the grey card to the clip (`GradeMath.stripLinearAt` on Android,
  `PaperStrip.linearAt` on iOS; the desktop's strip has the same axis)
  — a one-finger drag shifts the pair (`shiftGrade`), a
  two-finger spread sets the grade (`spreadNotches`: one stop of
  hard light over soft per doubling of the spread, a twelfth to the
  notch), a double tap
  is the base print and a second one brings the pair back.
  The step of grade is the core's, `SplitGrade::graded` behind
  `paper_graded` (`Prefs.graded` in both apps), the same the desktop's
  wheel takes: the print's mid-grey stays where it is, the grade stops
  at 00 and at 5 and before an exposure would leave ±3, both exposures
  stay on the twelfth grid, and the same steps back give the same pair.
  The pair is what reaches the core, the files and the exports, exactly
  as two sliders set it before. Preview, histogram and
  the 1:1 window evaluate `Tone::Paper` per pixel, the same map the JPEG
  and TIFF use, so the screen and the file agree. The viewfinder cannot
  do that per pixel in Rust, so it indexes `paper_lut` (4097 samples of
  that map over linear grey). The pair shown is the frame's own grade
  (`Shot.print.grade`, read from `<name>.grade` on open). The controls
  write it back to the frame only (`Darkroom.setPrint`:
  the roll entry and the sidecar), never to `Prefs.grade`, so the
  standing grade — the finder, the automatic JPEG, the start of the next
  frame — does not drift with a correction made on one negative. On iOS
  the standing pair is only offered under a paper finder;
  `CaptureViewModel.startingPrint` hands `Darkroom.submit` the base grade
  where the finder is the camera's picture, and `shotModes` leaves out
  JPEG there (a kept JPEG setting reads as Developer). Each preview
  render also refreshes the roll's thumbnail (`Darkroom.setThumbnail`),
  so the roll shows the frame as it was last left. Exports use the shown
  pair.
* after a development -> `measure_peak(soft, hard)`: the native-size
  print of the monochrome mix (kept while a plate is on, so Peak matches
  the desktop's edit weights), nothing sharpened, through the core's
  `peak::tune` — the same search as the desktop's `peak` program and GUI.
  The radius (Gaussian σ, pixels of the negative) and amount are kept with
  the negative in memory (`Darkroom.Negative.peak`). Peak is part of the
  frame's print (`Shot.print.peak`, the `peak` object in `<name>.grade`),
  like the grade: *Off* applies nothing, *Auto* applies the measurement of
  each negative, *Custom* applies the frame's own pair, which starts from
  this negative's measurement the moment Custom is chosen in review
  (`Peak.applied`). The standing Peak in the settings (`Prefs.peakMode`)
  is Off or Auto only — what a new frame starts with and the automatic
  JPEG applies; a hand pair belongs to one negative, so Custom is the
  developer's alone and never a setting. On iOS the setting is only
  offered under a paper finder, where a JPEG leaves without the
  developer; elsewhere `startingPrint` hands every frame Auto. Android's
  finder is always the paper, so `Darkroom.submit` always starts from
  `prefs.standingPrint`. A `.grade` file from 0.15.0 has no `peak` object
  and reads with the standing Peak. The 1:1 view and every export follow
  the mode; the preview is never sharpened.
* after a development on iOS with the Apple Log finder ->
  `mix_median()`: the median of the current monochrome mix on the linear
  axis from the preview planes, near-black and clip left out (the
  core's own cut). The app sets it against the median the finder
  measured at the shutter; see *iOS* below.
* pinch zoom beyond the preview resolution -> `detail(x, y, w, h, soft,
  hard, peak_radius, peak_amount)` for the visible window, drawn
  1:1 on top of the scaled preview. With an amount above zero the window
  is mixed with a margin of `kernel_radius(σ) + 1` px, the paper is
  applied, `usm::unsharp` runs with the radius as given (the window is
  1:1, so the print's scale factor is 1) and no mask, as
  `print::apply_peak_picture` does on the full print, then the margin is
  cut off again.
* export -> `export_jpeg` (full size, or `binned` to half the edge through
  the print pass's `PrintSize::Physical` at the recipe's dpi, with
  `peak_radius`, `peak_amount`; the recipe's `sharpen` is `Off`),
  `export_tiff16` (with the same, never binned),
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

* *Mono · Color* (the button in the viewfinder's line, `ProcessChoice` /
  the chips in review; "Color" is the user's word for the plate) ->
  `set_plate(filters, space)` resolves the three weight triples for this
  frame (`Camera`: the frame's `SENSOR_COLOR_TRANSFORM` for D65 towards the
  primaries; `Wratten`: 25/58/47 in front of the camera file's response or
  the native one; `Channels`) and keeps the monochrome mix stored;
  `remix(...)` is the way back. The choice is a standing setting
  (`Prefs`), so the next shot opens in the same process and a
  development done in review carries over.
* the lamps (`set_lamps(red, blue)`, stops within ±2, green the
  reference, applied as `v · 2^lamp` before the paper) stay in the
  crate for the `.plate.tif` interchange with the desktop; neither
  phone calls them, both print the plate with the lamps at zero.
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
the file as it is); Adobe RGB is under *Color space* in Settings, a
standing choice, not a per-frame one. The preview bitmap carries
the paper's gamma 2.2 values in the plate's primaries and is displayed as
sRGB, the same small difference the grey preview has had all along.

## Viewfinder shader

`MonoViewfinder` wraps the preview `TextureView` in a Compose
`graphicsLayer` whose `renderEffect` is a `RuntimeShader` (a `RenderEffect`
on the `TextureView` itself blacked out the UI above it on the Pixel 9).
The AGSL program linearises the preview as sRGB (the device preset, or
the fixed curve from the first frame where the meter allows it; assumed
sRGB on a Qualcomm meter), mixes with the
development's weights pulled back through the frame's ISP matrix, and
indexes `paper_lut` (the print curve over linear grey, no second gamma).
In the plate process it mixes three times with the taking filters
(`plate_weights`, the numbers `set_plate` will use for the still, each
pulled back the same way) and indexes the same table per channel, so
the viewfinder shows the plate in colour. Zebra
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

Two product flavors in the `store` dimension. `store/Entitlements.kt`
in `main` is the interface (`fullResolution`, `price`, `busy`,
`message`, `storeBound`, `purchase(activity)`, `restore()`) and
`Entitlements.Owned`; each flavor's `store/EntitlementsFactory.kt`
provides `createEntitlements(context)`. `github` returns `Owned` and
carries no Play Billing; `play` returns `PlayEntitlements`, a
`BillingClient` (billing-ktx, pending one-time purchases on, auto
reconnection) that loads the product, reads `queryPurchasesAsync` on
every connection, acknowledges a purchase before granting it, and
caches the answer in the `entitlements` shared preferences so a cold
start does not wait on the store. `Darkroom.entitlements` is the one
instance; `exportJpeg(binned = !fullResolution)` for the automatic and
the manual JPEG, and `ReviewViewModel` turns a locked TIFF or negative
export into `unlockRequested`, which the screen answers with
`UnlockDialog`. The workflows build `assembleGithubRelease` for the
releases page and `assembleDebug` (both flavors) on every push.

## iOS

`ios/` is a second front end on the same crate. The layers mirror the
Android ones file for file:

| Android | iOS | Notes |
| --- | --- | --- |
| `CameraController.kt` + `Lenses.kt` | `Capture/CaptureSession.swift`, `Capture/LensCatalog.swift` | one physical back camera at a time (the virtual fusion cameras give no Bayer RAW); `AVCapturePhotoOutput` with the first `isBayerRAWPixelFormat` type; the still arrives as a DNG. The stream: where a format offers `.appleLog` in 10-bit 4:2:0 (`x420`) or 4:2:2 (`x422`; the iPhone 16 Pro's log formats are all 4:2:2) at 30 fps, the largest 4:3 one up to about 2 MP is made the `activeFormat` (`.inputPriority`, `maxPhotoDimensions` the format's largest), and the video output asks for that format's own subtype (the Metal pass samples the chroma plane normalised, the CPU analysis scales the chroma row and column by the plane's size, so both pitches read alike); each step is checked (the 10-bit stream, the Bayer RAW still). The Bayer RAW is offered under the `.photo` preset alone: a format set by hand puts the session on `.inputPriority`, and there `availableRawPhotoPixelFormatTypes` is empty whichever format it is (the iPhone 17 Pro's `formats.json`: the same 4032×3024 format, no RAW by hand, `bgg4` under the preset), so `configure` sets the preset first and takes the RAW type from there, then the Log format by hand for the finder. With the finder in the Log, `capture()` swaps the session: `swapToPhoto` stops it and rebuilds the graph the lens opened with, through the same two helpers as `configure` — `clearGraph` (input and outputs out, the preset back to `.photo`, the video output back to BGRA) and `addGraph` (the device back to sRGB at its own frame rate, its input, a fresh `AVCapturePhotoOutput` since one that has been through the Log keeps an empty RAW list, the video output, each add caught) — then one commit; the RAW type is read after the commit, then after a start if still empty — `settleExposure` (the finder's exposure set by hand in the preset's format with `setExposureModeCustom`'s completion — the hand's own ISO and time, or the automatics' as they stood at the shutter, frozen before the swap, so nothing is measured twice; only with the flash on Auto or On are the automatics left to decide there, polled until `isAdjustingExposure` drops, 0.1–0.8 s), the RAW, and back to the Log format with its still size and 10-bit stream (`swapToLog`) with `applyExposure`; `swapping` drops the preset's frames so the finder holds its last Log frame, and refuses a second shutter meanwhile. `photoFormat(of:)` (8-bit 4:2:0, `isHighestPhotoQualitySupported`, the largest 4:3) is the format the preset is expected to settle on, for the still's ISO and time ranges. Without a Log format the path is the `.photo` preset with sRGB BGRA, which is also the path on cameras without Apple Log. `CaptureState.paperFinder` says which. The finder's rotation follows the interface orientation (fixed to portrait on the phone, turning with the iPad); the still's `videoRotationAngle`, and so the DNG's orientation tag, follows the device. Every AVFoundation call that raises an `NSException` on refusal (preset, commit, `startRunning`, `addInput`/`addOutput`, `activeFormat`, `videoSettings`, the photo settings, `capturePhoto`) runs inside `MimizanTry` (`ExceptionCatch.m` through the bridging header), so a refusal is a Swift error and a message, not an abort. No lens correction of the camera's: `isGeometricDistortionCorrectionEnabled` is set off where the device has it (on by default on the ultra-wide; the Android side sets `DISTORTION_CORRECTION_MODE_OFF` and `SHADING_MODE_OFF` where offered), so the stream shows the lens as the Bayer still does; lens shading has no switch in AVFoundation and the Bayer RAW is written before it. The DNG's `OpcodeList2/3` (GainMap, WarpRectilinear, FixVignette) are carried by `rawler` as tags only and never applied by the core, on any phone. `teardown` (`clearGraph`) leaves nothing of a Log finder on the session for the next `configure` — the preset back from `.inputPriority`, the video output on BGRA — and `addGraph` resets the device before its input goes in; each of the three made the next `addInput`/`addOutput` raise "Unsupported format ((null))" on the 17 Pro. Interruptions and runtime errors are observed: a message while interrupted, the lens opened again after a media-services reset |
| `RawFrameBridge.kt` (RAW_SENSOR → `RawInput`) | — | not needed: the DNG goes to `develop_file`, the core's `pipeline::decode` reads it with `rawler` as the desktop does |
| `MonoViewfinder.kt` (AGSL) | `Capture/Finder.metal`, `Capture/FinderView.swift` | two Metal compute kernels. `finderLog` on an Apple Log frame (luma `r16Unorm` + chroma `rg16Unorm` from the two planes): video-range Y'CbCr → R'G'B' by the buffer's matrix tag, Apple's published log inverse, the mix with the pulled-back weights times the match gain, the paper's 4097-entry table in an `MTLBuffer`, the plate's three positives when on; `finder` on BGRA: Rec. 709 luma for mono, the frame itself in colour. Zebra above 0.985 on either; `CVMetalTextureCache` (the textures are held until the command buffer completes, the cache flushed every 120 draws), `MTKView` on the renderer's device |
| `FinderTone.kt` + `IspControls.kt` | `Capture/FinderTone.swift` | `FinderTone` is mono/colour and the zebra threshold, plus a `PaperTone` on the Log stream: the weights on linear BT.2020 (`Rec2020ToCamera` — the DNG's XYZ → camera matrix times the BT.2020 primaries, rows scaled white to white, as the Android pull-back through the ISP matrix), the paper table and the gain `2^FinderMatch.stops`. `AppleLog.linear` and `YccMatrix` are the kernel's constants on the CPU, for the histogram and the median |
| — | `Store/FinderMatch.swift` | the stops between the Log axis (grey card at 0.18 of the ISP's exposure) and the negative's (1 at the sensor's clip) are not published, so they are measured: at the shutter the session keeps the median of the finder's linear mix (1/32-stop histogram over the sampled grid, the same near-black/clip cut as the core); after the first development `Developed.mixMedian` gives the negative's; `log2(negative / finder)` goes to `Prefs.finderMatch` (a `FinderMatches` table by lens label: the last eight per lens, the median applied from the next tone; a lens without a frame yet takes the median of every lens's frames, since the 17 Pro's three cameras sat within half a stop of one another), to `Documents/finder-match.json` (one record per frame: device, iOS, lens, ISO, time, format, both medians, stops, what was applied) and to the log. Before the first frame of all the prior is Apple Log's code 1 taken as the clip (−log2 12 ≈ −3.59 stops). Settings shows the current lens's match, every lens's in one line, and resets them |
| — | `Store/FormatDump.swift` | for the devices nobody here holds: each time a lens opens, every `AVCaptureDevice.Format` (size, subtype fourcc, frame rates, colour spaces, binned, HDR, photo-quality flags, `supportedMaxPhotoDimensions`, ISO range), the format settled on, the RAW and stream types after the commit, the RAW types seen while the Apple Log format was active (empty by construction under `.inputPriority`, kept as the record), and the outcome sentence go to `Documents/formats.json` by lens label. `CaptureState.finderNote` is that sentence for Settings: which step of the Apple Log path passed or failed, with the counts; `formatsFile` says whether the file was written (time, count, size) or why not. Settings shares both files with `ShareLink`, so a tester never has to find them in Files |
| `CameraViewModel.kt` | `Capture/CaptureViewModel.swift` | `withObservationTracking` instead of `combine` for the tone (the standing grade, the process and plate filters, the lens matrix remembered per lens in `Prefs`, the match); a start under way is not started a second time; the capture carries the finder's `FinderSample` into `Darkroom.submit` |
| `Darkroom.kt` + `DevelopService.kt` | `Darkroom/Darkroom.swift` | two serial `DispatchQueue`s behind `Gate` (intake: write and read the frame; worker: the development), so the core never runs on Swift's cooperative pool; `beginBackgroundTask` instead of a foreground service, ended by the expiry handler too; a frame deleted while it develops is dropped on completion |
| `RawCache.kt` | `Store/RawCache.swift` | `Caches/raw/<name>.raw`/`.dng` + `.json` + `.grade`, excluded from backup, 1.5 GB budget; an orphan `.raw`/`.dng`, or a sidecar without its frame, is swept only once it is a minute old; a frame `.json` the decoder refuses is set aside as `<name>.bad-<stamp>.json` from `loadMeta` on both sides, as are `finder-match.json` and `formats.json` (`FormatDump.readOrSetAside`) |
| `MediaStoreWriter.kt` | `Store/Exporter.swift` | `Documents/Mimizan Lab` (visible in Files, `UIFileSharingEnabled`) plus `PHPhotoLibrary` add-only for JPEGs; the DNG export (`ReviewViewModel.exportDng`, iOS only — Android writes no DNG) copies the cached frame there under its name, behind the full-resolution purchase like the TIFFs; the mask sidecar takes the final file's name, so `(2)` pairs stay paired |
| `Prefs.kt` | `Store/Prefs.swift` | same keys in `UserDefaults` (no lamp keys: the lamps are not a setting on either phone). Fresh install: Android `ShotMode.JPEG` (the finder is already the paper), iOS `.developer` (the finder may be the camera's picture) |
| `store/Entitlements.kt` (`play` flavor) | `Store/Entitlements.swift` | StoreKit 2: `Transaction.currentEntitlements` on every start, `Transaction.updates` for purchases made on the Mac, by the family or refunded, `AppStore.sync()` behind Restore; the answer cached under `entitlement_fullres`. No flavor on iOS, the App Store is the only channel; `UI/UnlockView.swift` is the Settings section and the sheet a locked export opens |
| `ReviewViewModel.kt` / `ReviewScreen.kt` | `Review/` | the `Developed` calls go through `Gate`, a serial `DispatchQueue` wrapped in `withCheckedContinuation`, never the main thread; the sliders and Peak move the frame's own print (`Darkroom.setPrint` → `<name>.grade`) and each render hands the roll a fresh thumbnail, as on Android; `MagnifyGesture` + `DragGesture` for the 1:1 viewer |
| `RollScreen.kt` | `UI/RollView.swift` | no import from Files or Photos (a full-size NEF develops past the iPad's memory); the roll is the camera's own frames |
| `MainActivity.kt` | `UI/RootView.swift` | `NavigationStack` on compact width, `NavigationSplitView` (roll beside camera / review) on the iPad |

There is no `Info.plist` in `ios/MimizanLab`: Xcode generates it from the
`INFOPLIST_KEY_*` settings in `project.yml` (`GENERATE_INFOPLIST_FILE`),
which is where a usage description or an orientation is added. The
privacy manifest is `PrivacyInfo.xcprivacy`, the entitlements
`MimizanLab.entitlements`.

### Rust side for iOS

`rust/mimizan-mobile` adds `crate-type = ["staticlib"]` and the feature
`decode-files = ["mimizan-core/decode-rawler"]`, which exports

* `develop_file(path, params) -> Developed` — the DNG through
  `pipeline::decode`, then the same `develop_frame` the Android `develop`
  uses after the bridge; `tests/host.rs` checks the two give the same
  negative sample for sample on a NEF;
* `frame_info(path) -> FrameInfo` — make, model, size, CFA, orientation,
  WB gains, XYZ → camera, exposure, capture time — what the roll stores in
  the `.json` sidecar without decoding again.

On iOS `init_logging` writes `tracing` to stderr (Xcode's console);
`tracing-oslog` would need the Apple SDK at build time and could not be
cross-checked from Linux.

### Build wiring (iOS)

`scripts/build_ios.sh`:

1. `cargo rustc --target aarch64-apple-ios[-sim] --lib --features decode-files --crate-type staticlib --release`
   with `IPHONEOS_DEPLOYMENT_TARGET=17.0`;
2. a host release cdylib, from which `uniffi-bindgen generate --library … --language swift`
   writes `mimizan_mobile.swift`, `mimizan_mobileFFI.h` and the modulemap;
3. `xcodebuild -create-xcframework` with the static libraries and the
   header → `ios/MimizanCore/MimizanMobileFFI.xcframework`.

`ios/MimizanCore/Package.swift` is a local Swift package: a binary target
on the xcframework and a Swift target with the generated file (both
git-ignored). `ios/project.yml` (XcodeGen) builds the app target against
it, with `../cameras` as a folder reference. The App Store build
does not bundle `../LICENSE`. `.github/workflows/ios.yml` runs the script on a macOS runner,
generates the project and builds an unsigned Release `.app` for a generic
iOS device; the artifact is the `.app` and a `Payload/` `.ipa`.
