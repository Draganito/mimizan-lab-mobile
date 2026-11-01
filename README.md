# Mimizan Lab Mobile

Mimizan Lab on the phone: a monochrome RAW camera and a darkroom, on
Android and on iOS. Both apps call the same Rust core as the desktop.
A frame is developed on the device into a monochrome negative, or, since
0.12.0, into a three-colour plate (Prokudin-Gorsky's principle, the
desktop darkroom's second process): three mixes through three taking
filters, the same paper, one RGB file.

<p align="center">
  <img src="docs/screenshots/finder.png" alt="The finder, in colour" width="270">
  <img src="docs/screenshots/developer.png" alt="The developer, a plate" width="270">
</p>

The Android app is a general Camera2 app, not a Pixel-9-only build. It
runs on Android 13 and later (API 33), arm64-v8a, on any phone whose
camera offers `RAW_SENSOR`. It does not use Google Play services. It is
tested on a **Pixel 9 running GrapheneOS (Android 17, API 37)**; a
ready-to-install APK is on the
[releases page](https://github.com/Draganito/mimizan-lab-mobile/releases).
The only fitted camera file today is the Pixel 9. Another phone still
develops: the noise model and the colour weights stay the core defaults
until that phone has its own file.

The iOS app is the same darkroom for iPhone and iPad, iOS 17. It is
built in CI and uploaded to TestFlight. A `v*` tag on this repository
is what starts that upload.

```
Android   Camera2 RAW_SENSOR ──> RawInput (Kotlin) ──> mimizan-core (Rust, UniFFI)
iOS       AVFoundation Bayer RAW as DNG ──> develop_file (the desktop decoder)
both                     └─> app cache (re-developable)   └─> gallery or Files
                                                              MLM_*.jpg / .tif / .negative.tif
                                                              MLM_*_plate.jpg / .tif / .plate.tif
```

Only finished pictures leave the app. The raw frame stays in the cache
and can be deleted there. Android writes no DNG; the way to the desktop
is Export. iOS keeps the captured DNG in the cache, because that is the
form AVFoundation hands over.

* `rust/mimizan-mobile` — the Rust library around `mimizan-core`:
  `develop`, `remix`, `preview`, `detail`, `export_*`, UniFFI for Kotlin
  and Swift. A cdylib for Android; a static library (plus `develop_file`
  / `frame_info` through the desktop's RAW decoder) for iOS.
* `android/` — Kotlin and Jetpack Compose (Camera2, a foreground
  service, review). Gradle runs `cargo ndk` and `uniffi-bindgen`.
* `ios/` — SwiftUI for iPhone and iPad (AVFoundation, a Metal
  viewfinder, the same darkroom and review). `scripts/build_ios.sh`
  builds the core into an xcframework. See [iOS](#ios).
* `cameras/google_pixel_9.json` — camera file (schema 1 of Mimizan
  Lab), shipped in the APK. This is the tested phone, not a limit on
  which Android phone the app installs on.
* `scripts/device_spike.sh` — timing and peak RSS on a phone.
* `docs/ARCHITECTURE.md`, `docs/RESULTS_MOBILE.md`.

## Android

### Why the Pixel 9 sensor is already binned

The Pixel 9 exposes its 50 MP quad-Bayer sensor to Camera2 as a 12.5 MP
(4080×3072) regular RGGB mosaic: the hardware bins 2×2 before the frame
leaves the ISP. An optional second, CFA-preserving 2× binning (Quick
development, `binning = 2`) quarters the pixels again and finishes in
about a quarter of the time. The deliverable is developed at full
resolution unless Quick development was on for that frame. Other phones
report their own RAW size; the pipeline does not assume 4080×3072.

### Install the APK

Download `mimizan-lab-mobile-<version>.apk` from the releases page, open
it (allow installs from the browser or a file manager once) and grant
Camera, Notifications and, on Android 17, Sensors when asked. The
release build is minified and signed with the debug key. Android will
not update it over a build with another key, so uninstall first when
switching between a release download and your own `installDebug`.

### Build

The Rust crate depends on `mimizan-core` by path. The two repositories
sit next to each other; from `rust/mimizan-mobile` the core is
`../../../mimizan/crates/mimizan-core`.

```bash
git clone https://github.com/Draganito/mimizan.git
git clone https://github.com/Draganito/mimizan-lab-mobile.git
```

Prerequisites (user-local, no sudo):

* Rust stable with the target `aarch64-linux-android`, and `cargo-ndk`
  (`cargo install cargo-ndk`).
* Android SDK at `$ANDROID_HOME` (default `~/.local/opt/android-sdk`)
  with `platforms;android-37.1`, build-tools, and NDK r27
  (`ndk;27.3.13750724`).
* JDK 21.

```bash
cd android
./gradlew assembleDebug        # cargo ndk -> uniffi-bindgen -> Kotlin -> APK
./gradlew installDebug         # phone attached, USB debugging on
./gradlew assembleRelease      # the APK that the releases page carries
```

`gradle.properties` sets `mimizan.rustProfile=release`, so a debug APK
still carries an optimised Rust library. The development is CPU-bound.
`local.properties` (`sdk.dir=...`) is git-ignored; create it or set
`ANDROID_HOME`.

Rust alone:

```bash
cd rust/mimizan-mobile
cargo test                        # synthetic frames
cargo test --features host-raw    # bit-for-bit against the desktop decoder (needs mimizan/testdata)
cargo ndk -t arm64-v8a --platform 33 build --release --lib
```

## iOS

The same darkroom, for iPhone and iPad. AVFoundation hands the Bayer RAW
still over as a DNG (`AVCapturePhoto.fileDataRepresentation()`). The app
caches `<name>.dng` plus `<name>.json`, and the core reads the file with
the desktop decoder (`develop_file`, the `decode-files` feature). A DNG
developed here matches `mimizan` on the desktop bit for bit
(`develop_file_matches_develop_bit_for_bit`).

Import from Files and Photos is off. A full-size NEF develops past what
an iPad can keep in memory, and the system kills the app. The roll is
the camera's own frames. An iPad whose camera has no Bayer RAW can open
the app; it cannot take a frame, and it cannot import one.

What is the same as on Android: Mono and Plate, the contrast filters,
the paper's two exposures, the zone strip, Peak, review with remix and
1:1 detail, the exports (JPEG, 16-bit TIFF, linear negative or plate
plus mask), the roll. What differs: the viewfinder is the camera's own
preview, desaturated in mono and untouched in colour. The grade and
Peak do not reach it. They are where a picture opens, and what the
automatic JPEG uses. There is no foreground service. iOS grants a few
seconds past backgrounding, which is what one development takes.

A Mac with Xcode 26 builds it, or the `iOS` GitHub workflow, which
builds an unsigned `.app` on a macOS runner when a push touches `ios/`,
`rust/` or `cameras/`:

```bash
rustup target add aarch64-apple-ios aarch64-apple-ios-sim
scripts/build_ios.sh              # static libs, Swift bindings, ios/MimizanCore/MimizanMobileFFI.xcframework
brew install xcodegen
cd ios && xcodegen generate       # ios/MimizanLab.xcodeproj (not committed)
open MimizanLab.xcodeproj         # set your team; the simulator has no RAW camera
```

The Rust half of the script also runs on Linux (`--no-xcframework`).
The xcframework step needs `xcodebuild`. A free Apple ID signs for seven
days. The Developer Program signs for a year and for TestFlight and the
App Store. The unsigned CI artifact is for re-signing tools.

The `TestFlight` workflow runs on a `v*` tag, and by hand. The archive
is built unsigned. `xcodebuild -exportArchive` signs it with Apple's
cloud-managed distribution certificate through an App Store Connect API
key (Admin role). The repository secrets are `APPLE_TEAM_ID`,
`ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_API_KEY_P8` (the `.p8`, base64).
App Store Connect needs an app record with the bundle id
`ch.bojovic.mimizanlab`.

Requirements: iOS 17, and a camera that offers Bayer RAW (iPhone 12 and
later; the iPad Pro and iPad Air cameras do, older iPads do not).
iPhones from the 14 Pro on deliver 48 MP DNGs from the main camera. The
entitlement `com.apple.developer.kernel.increased-memory-limit` is set
for those frames.

## Using the app

The darkroom is the same on both phones. The finder is not.

1. **Finder.** On Android the preview is linearised, mixed with the
   filter's channel weights, then printed through the paper at the
   standing exposures. That is the stored grade. The automatic JPEG, and
   the picture when it opens, start there; with Balance the zone on they
   also take this negative's shift and the compensation. Peak is on the
   JPEG and on the opened picture, not on the finder. On iOS the picture
   is the camera's own preview: desaturated in mono, untouched in colour.
   Zebra marks the highlights, a histogram sits in the corner, a grid
   draws thirds.
   The three are remembered across launches and start off. Tap sets
   focus and metering; a long press clears it. The flash icon cycles
   Off / Auto / On. The viewfinder stays dark and the flash fires on
   the still. With manual exposure, Auto does not fire and On fires
   once. The exposure switch changes between automatic (with EV
   compensation) and manual ISO and shutter.
2. **Process.** Mono or Plate, kept across starts. In Mono the filter
   row (Pan, Y8, YG11, O16, R25, G58, B47) is the film for the shot. It
   can be changed afterwards in review without developing again. In
   Plate the row is the three taking filters: Camera (the sensor's
   colour matrix; the chip reads "Camera (no matrix)" when the camera
   file has none), Wratten (25/58/47, the softer historical palette),
   Channels (the balanced channels as they are). The colour space starts
   at sRGB; Adobe RGB is the other choice, in Settings, not on this row. On Android the finder shows the plate
   in colour. On iOS the finder stays the camera's colour picture.
3. **Grade.** Soft and hard are the two paper exposures, in stops from
   the base print (±3, steps of 1/12). Zero is about grade 2. The zone
   strip under them moves both by the same stops: drag left and the
   print gets darker, drag right and it gets lighter. The gap, and so
   the grade, stays. Balance the zone is off until you turn it on. On,
   a picture places its middle on the grey card and leaves the grade.
   Compensation, shown only then, adds paper time on top of that
   placement: plus prints darker, and the same amount is kept for the
   next picture. Moving the strip or a grade slider keeps what you set. The standing
   pair is not overwritten by the balance itself.
4. **Peak** is Off, Auto, or Custom. Auto measures radius and amount on
   each negative. Custom shows the two sliders and remembers them. Off
   leaves the exports unsharpened. The 1:1 view shows the result. The
   preview is never sharpened.
5. **A shot** is JPEG, Developer, or Raw. JPEG writes the picture at
   this grade and Peak and stays on the finder. Developer opens the
   picture, and the JPEG waits for Export. Raw keeps the frame on the
   roll and stays on the finder: nothing is developed and nothing is
   written until you open it and export. A fresh Android install starts
   on JPEG, because the finder is already the paper. A fresh iOS
   install starts on Developer, so the picture opens to be graded.
6. **The shutter** records one RAW frame into the app cache. In JPEG
   and Developer the development runs at once (on Android, in a
   foreground service, one frame at a time). The JPEG lands in
   `Pictures/Mimizan Lab` on Android, and in Files and, add-only, Photos
   on iOS, only for a JPEG shot. Opening a frame from the roll develops
   it for the screen and waits for Export.
7. **Review** opens from the thumbnail, or on its own after a Developer
   shot. Mono and Plate sit at the left, Export at the right.
   The filters are the row under them. Pinch to zoom; past the 2048 px
   preview the visible window is rendered 1:1 from the negative. Soft,
   hard and the zone strip change the preview, the histogram, the 1:1
   window and every export of this picture, through the same paper.
   Export writes the full-size JPEG, a 16-bit TIFF (RGB with the
   plate's profile in the plate process), or the linear negative plus
   saturation mask for the desktop. In the plate process that stack is
   the three-frame plate (`.plate.tif`), which `mimizan print` prints
   in colour. Share sends the last export. A frame shot with Quick
   development can be developed again at full size.
8. **The roll** lists every frame in the cache. The bin, or a long
   press, deletes one. Pictures already exported stay where they were
   written. Settings shows how much the raw frames take, and can delete
   them all.

Only the most recent developed negative stays in memory (about 0.5 GB
with its preview planes; a colour export holds about 50 MB more while
it runs). Every frame stays in the cache (1.5 GB budget, oldest
first), so a shot on the roll can be developed again, also with other
settings. Android keeps packed samples plus metadata. iOS keeps the DNG.

## Status

Android 0.14.18 runs on the Pixel 9: main and ultrawide (the ultrawide
through its physical camera id), RAW capture, gallery JPEG, review,
the plate, the split-grade paper, Peak, the zone strip, the roll.
Development of a 12.5 MP frame was measured at 2.3–4.5 s before core
0.12.0; the numbers and the device findings are in
`docs/RESULTS_MOBILE.md`. The phone's negative matched the desktop CLI
on a DNG of the same frame to 0.015 % RMS (measured with 0.1.0, which
still wrote DNGs). The fitted camera file for the Pixel 9 is still the
core defaults for noise and weights. `OTHER_SENSORS` and Android 17
have been checked on this one device.

iOS 0.14.18 is the same darkroom, built for device and uploaded to
TestFlight from a `v*` tag. The finder is the camera preview. Import
of a RAW from Files or Photos is switched off.

## License

Copyright (C) 2026 Dragan Bojovic.

Mimizan Lab Mobile is free software under the GNU General Public License,
version 3 or any later version. See [LICENSE](LICENSE). The APK ships that
text and a short notice in its assets. The iOS App Store build does not:
that copy keeps the copyright line and leaves this license out of the bundle. Releases published before this change
stay under the MIT license they were shipped with.

The phone runs the Mimizan separation only. AMaZE, RCD, DCB and LMMSE stay
on the desktop branch `demosaic` and in desktop release 0.10.12.
