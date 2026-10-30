# Mimizan Lab Mobile

Monochrome RAW camera for the Google Pixel 9 / 9a. Tested on a **Pixel 9
running GrapheneOS (Android 17, API 37)**; a ready-to-install APK is on the
[releases page](https://github.com/Draganito/mimizan-lab-mobile/releases).

The phone records
`RAW_SENSOR` frames, and the exact Mimizan Lab pipeline (frequency separation
of the Bayer mosaic, no demosaic, no noise reduction) turns them into a
monochrome negative on the phone, with the same bits the desktop CLI would
produce from a DNG of the same frame. Since 0.12.0 the same separation can
be printed as a three-colour plate (Prokudin-Gorsky's principle, the
second process of the desktop darkroom): three mixes through three taking
filters, the same paper, one RGB file.

```
Camera2 RAW_SENSOR ──> RawInput (Kotlin bridge) ──> mimizan-core (Rust, via UniFFI)
                   └─> app cache (.raw + .json, re-developable)   └─> Pictures/Mimizan Lab/
                                                                       MLM_*.jpg / .tif / .negative.tif
                                                                       MLM_*_plate.jpg / .tif / .plate.tif
```

Only finished pictures reach the gallery: the raw frame stays in the app
(and can be deleted there), the negative or the plate goes out.

* `rust/mimizan-mobile` — the Rust library around `mimizan-core`: `develop`,
  `remix`, `preview`, `detail`, `export_*`, UniFFI bindings for Kotlin and
  Swift. A cdylib for Android; a static library (plus `develop_file` /
  `frame_info` through the desktop's RAW decoder) for iOS.
* `android/` — Kotlin + Jetpack Compose app (Camera2, foreground service,
  review screen). Gradle runs `cargo ndk` and `uniffi-bindgen` itself.
* `ios/` — SwiftUI app for iPhone and iPad (AVFoundation Bayer RAW as DNG,
  Metal viewfinder, the same darkroom and review); `scripts/build_ios.sh`
  builds the core into an xcframework. See [iOS](#ios) below.
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

## iOS

The same app for iPhone and iPad, from the same Rust crate. The route is
different from Android's: AVFoundation hands over the Bayer RAW still as a
**DNG** (`AVCapturePhoto.fileDataRepresentation()`), the app caches it as
`<name>.dng` + `<name>.json`, and the core reads it with the desktop's
decoder (`develop_file`, the `decode-files` feature) — which is what
`mimizan` on the desktop does with the same file, so the negative is the
desktop's bit for bit (test `develop_file_matches_develop_bit_for_bit`).
The iPad also imports RAW files from Files or Photos into the roll.

```
AVCapturePhotoOutput (Bayer RAW) ──> DNG ──> mimizan-core (develop_file, via UniFFI Swift)
                                 └─> Caches/raw (re-developable)   └─> Files › Mimizan Lab/ (+ Photos, add-only)
```

What is the same as on Android: the process (Mono / Plate), the contrast
filters, the paper's two exposures, the lamps, Peak, the review with remix
and 1:1 detail, the exports (JPEG, TIFF 16-bit, linear negative / plate +
mask), the roll. What is different: the viewfinder's pull-back from the
ISP's preview to the sensor goes through the sensor's XYZ → camera matrix
(from the last DNG of that lens) instead of Camera2's per-frame colour
matrix; no exposure compensation beyond the camera's own EV bias; no
foreground service (iOS grants a few seconds past backgrounding, which is
what one development takes).

Building needs a Mac with Xcode 16 (or the `iOS` GitHub workflow, which
builds an unsigned `.app` / `.ipa` on a macOS runner on every push that
touches `ios/`, `rust/` or `cameras/`):

```bash
rustup target add aarch64-apple-ios aarch64-apple-ios-sim
scripts/build_ios.sh              # Rust static libs, Swift bindings, ios/MimizanCore/MimizanMobileFFI.xcframework
brew install xcodegen
cd ios && xcodegen generate       # ios/MimizanLab.xcodeproj (not committed)
open MimizanLab.xcodeproj         # set your team, run on a device (the simulator has no RAW camera)
```

The Rust half of the script also runs on Linux (`--no-xcframework`); only
the xcframework step needs `xcodebuild`. Running on a phone needs a signing
identity: a free Apple ID signs for seven days, the Developer Program for a
year and for TestFlight / the App Store. The unsigned CI artifact is for
re-signing tools.

The `TestFlight` workflow (manual, or on a `v*` tag) signs and uploads
without any certificate on the runner: the archive is built unsigned and
`xcodebuild -exportArchive` signs it with Apple's cloud-managed
distribution certificate through an App Store Connect API key (Admin
role). It needs the repository secrets `APPLE_TEAM_ID`, `ASC_KEY_ID`,
`ASC_ISSUER_ID`, `ASC_API_KEY_P8` (the `.p8`, base64) and an app record
with the bundle ID `ch.bojovic.mimizanlab` in App Store Connect.

Requirements: iOS 17, a device whose camera offers Bayer RAW (iPhone 12 and
later; the iPad Pro / Air cameras do, older iPads do not — those can still
import and develop files). iPhones from the 14 Pro on deliver 48 MP DNGs
from the main camera; the entitlement
`com.apple.developer.kernel.increased-memory-limit` is set for them.

## Using the app

1. The viewfinder shows the scene as the negative will see it: the camera
   preview is linearised, mixed with the channel weights of the chosen
   filter, then the paper at the settings exposures (AGSL shader, a table
   of the print curve over linear grey). Zebra marks the highlights, a 64-bin luma
   histogram sits in the corner, tap sets focus and metering (long press
   clears it). The flash icon left of the exposure switch cycles Off /
   Auto / On; the viewfinder stays dark and the flash fires only on the
   still. The switch changes between automatic (with EV compensation) and
   manual ISO / shutter. With manual exposure, Auto does not fire and On
   fires once.
2. The process row, *Mono · Plate*, chooses the darkroom's process, kept
   across starts. In *Mono* the filter row (Pan, Y8, YG11, O16, R25, G58,
   B47) is the "film" for the shot; it can be changed afterwards in review
   without developing again (`remix`). In *Plate* the row shows the three
   taking filters (*Camera*: the sensor's own colour matrix towards the
   primaries, colorimetric; *Wratten*: 25/58/47 in front of the
   panchromatic emulsion, the softer palette of the historical glass;
   *Channels*: the balanced channels as they are) and the primaries of the
   file (sRGB for the gallery, Adobe RGB), and the viewfinder shows the
   plate in colour. The plate is three mixes of the same separation,
   printed through the same paper at the same two exposures: grey stays
   grey at every density, saturated colours run towards white in the
   shoulder like a slide. Its only colour control is the two lamps of the
   projector (red and blue, stops, green the reference), under *Lamps* in
   the settings sheet and as sliders in review.
3. The shutter records one RAW frame into the app cache (no DNG is
   written). The development runs in a foreground service (notification),
   one frame at a time, and a full-size JPEG (the paper at the settings
   exposures, quality 100) lands in `Pictures/Mimizan Lab` when it
   is done.
   The settings sheet (slider icon) chooses the separation (Dubois /
   adaptive / mask off), Quick mode, whether the JPEG is saved
   automatically, the soft and hard exposures, the Peak checkbox (the
   desktop's high-pass: radius and amount measured on each negative after
   development, the smallest pair that brings its edges to the target
   without a rim; off leaves the exports as they are), and shows the
   storage the raw frames take with a "Delete all".
4. The thumbnail opens the review: pinch to zoom (beyond the 2048 px
   preview the visible window is rendered 1:1 from the negative), filter
   chips and R/B sliders change the mix, **soft** and **hard** set the two
   paper exposures in stops from the base print (±3, steps of 1/12; zero
   is about grade 2). They start from the settings and change the preview,
   the histogram, the 1:1 window and every export of this picture, all
   through the same paper evaluation. *Mono · Plate* switches the picture
   between the processes at once (a re-mix, the separation stays); in the
   plate the R/B sliders are the two lamps (±2 stops, twelfths), and the
   histogram shows the luminance with the three positives as outlines.
   With Peak on, two sliders show the
   measured radius (σ, pixels of the negative) and amount and let you move
   them, "Measured" goes back; the 1:1 view shows the result, the exports
   apply it. Export writes the
   full-size JPEG, 16-bit TIFF (RGB with the plate's profile in the plate
   process), or the linear negative + saturation mask for the desktop CLI;
   in the plate process that is the stacked three-frame plate
   (`.plate.tif`), which `mimizan print` prints in colour. Share sends the
   last export.
5. The roll lists every frame in the cache; the bin icon (or a long press)
   deletes one for good, pictures already in the gallery stay.

Only the most recent developed negative stays in memory (about 0.5 GB with
its preview planes; a colour export holds about 50 MB more while it
runs). Every frame is kept in the app cache as packed 16-bit
samples plus metadata (1.5 GB budget, oldest first), so any shot on the
roll can be developed again later, also with other settings. There is no
DNG: the way to the desktop is Export > Negative (linear TIFF + mask) or
Export > Plate.

## Status

Runs on the Pixel 9 (Android 17): main and ultrawide lens (the ultrawide
through its physical camera id), RAW capture, monochrome JPEG in the
gallery, development in 2.3–4.5 s per 12.5 MP frame (measured before core
0.12.0; core 0.15.5, which 0.11.0 and later are built on, is level with
that on the host; 0.12.1 is on core 0.15.6, where a burnt-out green
photosite takes the colour out of the plate around it, so clipped
highlights print white instead of pink; 0.12.2 is on core 0.16.0, no
change on the phone; 0.14.0 is on core 0.16.3 and replaces USM and
deconvolution with the desktop's Peak; 0.14.1 is on core 0.16.4 and
drops the standing exposure compensation from the settings), review with remix, the three-colour plate (viewfinder,
review, exports; the plate's preview takes about 250 ms and the colour
JPEG about three times the grey one on the host),
the split-grade paper, sharpening and exports, roll with delete. The phone's negative
matches the desktop CLI's development of a DNG of the same frame to
0.015 % RMS (the rest is DNG metadata rounding; measured with 0.1.0, which
still wrote DNGs). Numbers and device findings: `docs/RESULTS_MOBILE.md`.

Not yet done: a fitted camera file for the Pixel 9 (noise model and
weights are the core defaults), and the `OTHER_SENSORS` / Android 17
behaviour has only been checked on this one device.

iOS: the app and the build are complete (0.14.1: Rust static library,
Swift bindings, xcframework, SwiftUI app for iPhone and iPad, CI build of
an unsigned `.app`), but it has not run on a device yet — there is no Mac
or iPhone at hand. The first device run will show what the Bayer DNG of a
given iPhone looks like to `rawler` and whether the finder's pull-back
through the DNG's matrix is close enough.

## License

Copyright (C) 2026 Dragan Bojovic.

Mimizan Lab Mobile is free software under the GNU General Public License,
version 3 or any later version. See [LICENSE](LICENSE). The APK ships that
text and a short notice in its assets. The iOS App Store build does not:
that copy keeps the copyright line and leaves this license out of the bundle. Releases published before this change
stay under the MIT license they were shipped with.

The phone runs the Mimizan separation only. AMaZE, RCD, DCB and LMMSE stay
on the desktop branch `demosaic` and in desktop release 0.10.12.
