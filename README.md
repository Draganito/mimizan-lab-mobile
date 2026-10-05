# Mimizan Lab Mobile

Monochrome RAW camera for the Google Pixel 9 / 9a. Tested on a **Pixel 9
running GrapheneOS (Android 17, API 37)**; a ready-to-install APK is on the
[releases page](https://github.com/Draganito/mimizan-lab-mobile/releases).

The phone records
`RAW_SENSOR` frames, and the exact Mimizan Lab pipeline (frequency separation
of the Bayer mosaic, no demosaic, no noise reduction) turns them into a
monochrome negative on the phone, with the same bits the desktop CLI would
produce from a DNG of the same frame.

```
Camera2 RAW_SENSOR ──> RawInput (Kotlin bridge) ──> mimizan-core (Rust, via UniFFI)
                   └─> app cache (.raw + .json, re-developable)   └─> Pictures/Mimizan Lab/
                                                                       MLM_*.jpg / .tif / .negative.tif
```

Nothing but monochrome reaches the gallery: the raw frame stays in the app
(and can be deleted there), the finished picture goes out.

* `rust/mimizan-mobile` — the Rust cdylib around `mimizan-core`: `develop`,
  `remix`, `preview`, `detail`, `export_*`, UniFFI bindings for Kotlin.
* `android/` — Kotlin + Jetpack Compose app (Camera2, foreground service,
  review screen). Gradle runs `cargo ndk` and `uniffi-bindgen` itself.
* `cameras/google_pixel_9.json` — camera file (schema 1 of Mimizan Lab),
  shipped in the APK as an asset.
* `scripts/device_spike.sh` — timing / peak-RSS benchmark on the phone.
* `docs/ARCHITECTURE.md`, `docs/RESULTS_MOBILE.md`.

## Why the sensor is "already binned"

The Pixel 9 exposes its 50 MP quad-Bayer sensor to Camera2 as a 12.5 MP
(4080x3072) regular RGGB mosaic: the hardware bins 2x2 before the frame
leaves the ISP. That is the RGGB base the plan asked for, with no extra
compute on the phone. An optional second, CFA-preserving 2x binning
("Quick mode", `binning = 2`) quarters the pixels again for a fast preview
development; the deliverable is always developed at full resolution.

## Install the APK

Download `mimizan-lab-mobile-<version>.apk` from the releases page, open it
on the phone (allow installs from the browser / file manager once) and grant
Camera, Notifications and — on Android 17 — Sensors when asked. The release
build is minified but signed with a debug key: Android will not update it
over a build with another key, so uninstall first when switching between a
release download and your own `installDebug`.

Tested: Pixel 9, GrapheneOS (Android 17). Any Pixel 9 / 9 Pro / 9a with
stock Android 14+ should behave the same; the app only needs Camera2 with
RAW capability and does not use Google Play services.

## Build

The Rust crate depends on `mimizan-core` by path (`../mimizan` next to this
checkout), so clone both side by side:

```bash
git clone https://github.com/Draganito/mimizan.git
git clone https://github.com/Draganito/mimizan-lab-mobile.git
```

Prerequisites (all user-local, no sudo):

* Rust stable with target `aarch64-linux-android`, `cargo-ndk`
  (`cargo install cargo-ndk`).
* Android SDK at `$ANDROID_HOME` (default `~/.local/opt/android-sdk`) with
  `platforms;android-37.1`, `build-tools`, and NDK r27 (`ndk;27.3.13750724`).
* JDK 21.

```bash
cd android
./gradlew assembleDebug        # cargo ndk -> uniffi-bindgen -> Kotlin -> APK
./gradlew installDebug         # phone attached with USB debugging
```

`gradle.properties` has `mimizan.rustProfile=release`, so even the debug
APK carries an optimised Rust library (the development is CPU-bound).
`local.properties` (`sdk.dir=...`) is git-ignored; create it or set
`ANDROID_HOME`.

Rust alone:

```bash
cd rust/mimizan-mobile
cargo test                                   # synthetic frames
cargo test --features host-raw               # + bit-for-bit check against the desktop decoder (needs mimizan/testdata)
cargo ndk -t arm64-v8a --platform 34 build --release --lib
```

## Using the app

1. The viewfinder shows the scene as the negative will see it: the camera
   preview is linearised, mixed with the channel weights of the chosen
   contrast filter, gamma-encoded and bent by the settings contrast
   S-curve (AGSL shader). Zebra marks the highlights, a 64-bin luma
   histogram sits in the corner, tap sets focus and metering (long press
   clears it). The flash icon left of the exposure switch cycles Off /
   Auto / On; the viewfinder stays dark and the flash fires only on the
   still. The switch changes between automatic (with EV compensation) and
   manual ISO / shutter. With manual exposure, Auto does not fire and On
   fires once.
2. The filter row (Pan, Y8, YG11, O16, R25, G58, B47) is the "film" for
   the shot; it can be changed afterwards in review without developing
   again (`remix`).
3. The shutter records one RAW frame into the app cache (no DNG is
   written). The development runs in a foreground service (notification),
   one frame at a time, and a full-size JPEG (reference look, the default
   contrast, quality 100) lands in `Pictures/Mimizan Lab` when it is done.
   The settings sheet (slider icon) chooses the separation (Dubois /
   adaptive / mask off), Quick mode, whether the JPEG is saved
   automatically, the default contrast, the unsharp-mask amount for the
   automatic JPEG and the full-size exports, the Richardson–Lucy
   deconvolution (1..10 passes on every pixel before the USM, same radius,
   no halo but the noise comes back; `mimizan print --deconv` on the
   desktop), and shows the storage the raw frames take with a "Delete all".
4. The thumbnail opens the review: pinch to zoom (beyond the 2048 px
   preview the visible window is rendered 1:1 from the negative), filter
   chips and R/B sliders change the mix, the look switch chooses reference
   curve or pure gamma 2.2, **Con** adds an S-curve around middle grey
   (-1..1; starts from the settings default and affects preview, histogram
   and every export of this picture). Sharpening and deconvolution are the
   settings amounts: full-size JPEG and TIFF, not the 1:1 view; the 2048 px
   JPEG keeps its screen compensation. Export writes JPEG (full or 2048 px),
   16-bit TIFF, or the linear negative + saturation mask for the desktop
   CLI. Share sends the last export.
5. The roll lists every frame in the cache; the bin icon (or a long press)
   deletes one for good, pictures already in the gallery stay.

Only the most recent developed negative stays in memory (about 0.5 GB with
its preview planes). Every frame is kept in the app cache as packed 16-bit
samples plus metadata (1.5 GB budget, oldest first), so any shot on the
roll can be developed again later, also with other settings. There is no
DNG: the way to the desktop is Export > Negative (linear TIFF + mask).

## Status

Runs on the Pixel 9 (Android 17): main and ultrawide lens (the ultrawide
through its physical camera id), RAW capture, monochrome JPEG in the
gallery, development in 2.3–4.5 s per 12.5 MP frame, review with remix,
contrast, sharpening and exports, roll with delete. The phone's negative
matches the desktop CLI's development of a DNG of the same frame to
0.015 % RMS (the rest is DNG metadata rounding; measured with 0.1.0, which
still wrote DNGs). Numbers and device findings: `docs/RESULTS_MOBILE.md`.

Not yet done: a fitted camera file for the Pixel 9 (noise model and
weights are the core defaults), and the `OTHER_SENSORS` / Android 17
behaviour has only been checked on this one device.

## License

Copyright (C) 2026 Dragan Bojovic.

Mimizan Lab Mobile is free software under the GNU General Public License,
version 3 or any later version. See [LICENSE](LICENSE). The APK ships that
text and a short notice in its assets. Releases published before this change
stay under the MIT license they were shipped with.

The phone runs the Mimizan separation only. AMaZE, RCD, DCB and LMMSE stay
in the desktop app.
