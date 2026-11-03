# Mimizan Lab Mobile

Mimizan Lab on the phone: a monochrome RAW camera and a darkroom, on
Android and on iOS. Both apps call the same Rust core as the desktop.
A frame is developed on the device into a monochrome negative, or into
a three-colour plate (Prokudin-Gorsky's principle, the desktop
darkroom's second process): three mixes through three taking filters,
the same paper, one RGB file.

<p align="center">
  <img src="docs/screenshots/finder.png" alt="The finder, in colour" width="270">
  <img src="docs/screenshots/developer.png" alt="The developer, a plate" width="270">
</p>

Only finished pictures leave the app. The raw frame stays in the app
cache and can be developed again, or deleted there. Android writes no
DNG; the way to the desktop is Export. iOS keeps the captured DNG,
because that is the form AVFoundation hands over.

A short handbook for the phone and the desktop window:
[docs/HANDBOOK.md](https://github.com/Draganito/mimizan/blob/main/docs/HANDBOOK.md).
The phone runs the Mimizan separation only; AMaZE, RCD, DCB and LMMSE
stay on the desktop.

## Which phones

Android 13 or later, arm64, and a back camera that offers `RAW_SENSOR`
to other apps. No Google Play services. The phones that sell the most
keep RAW inside the maker's own camera: the Galaxy A series, the Moto
G and the cheap Redmi phones give this app no frame to develop.

Where RAW is available, the three big chip families have been checked
on one phone each. The chip decides how the finder is pinned to the
paper; the darkroom is the same everywhere.

| Chip | Checked on | Finder |
|---|---|---|
| Google Tensor (Pixel 6 and later) | Pixel 9, GrapheneOS, Android 17 | sRGB curve pinned; matches the paper. |
| Qualcomm Snapdragon | Pixel 4a | The camera's own curve stays (unless it offers an sRGB preset), the exposure stays put. Matches the paper in good light, leaves it in the dark. |
| Samsung Exynos | Galaxy S25 FE (SM-S731B) | sRGB curve pinned, like Tensor. |

On a Samsung phone, Exynos or Snapdragon, the RAW is already
lens-shaded, so the preview keeps that correction; without it the
finder is brighter in the middle than the print. A MediaTek phone that
offers RAW has not been checked.

The only fitted camera file is `cameras/google_pixel_9.json`. Another
phone still develops: the noise model and the colour weights stay the
core defaults until that phone has its own file. Sensor facts and the
device findings are in `docs/RESULTS_MOBILE.md`.

iPhone 12 and later on iOS 17, and the iPad Pro and iPad Air cameras
that offer Bayer RAW. An iPad without one can open the app but cannot
take a frame.

## Install

**Android.** Download `mimizan-lab-mobile-<version>.apk` from the
[releases page](https://github.com/Draganito/mimizan-lab-mobile/releases),
open it (allow installs from the browser or a file manager once) and
grant Camera, Notifications and, on Android 17, Sensors when asked. The
release build is signed with the debug key. Android will not update it
over a build with another key, so uninstall first when switching
between a release download and your own `installDebug`.

**iOS.** The app is on TestFlight; every `v*` tag uploads a build.

## Using the app

The darkroom is the same on both phones. The finder is not. On Android
the preview is linearised, mixed with the filter's channel weights and
printed through the paper at the standing exposures: the finder is the
paper. On iOS the finder is the camera's own preview, desaturated in
mono, untouched in colour.

1. **Finder.** Zebra marks the highlights and starts on; a histogram
   and a grid of thirds start off. The three are remembered. Tap sets
   focus and metering (the label under the box says so); a long press
   clears it. Flash cycles Off / Auto / On and fires on the still, not
   in the finder. A and M switch between the automatics and manual ISO
   and shutter; in M the line turns amber, the two sliders appear, and
   Auto flash does not fire. There is no EV slider: the automatics
   expose, and a print that came out dark is corrected in the
   developer, where the stops are what they say.
2. **Process.** Mono or Plate, kept across starts. In Mono the filter
   row (Pan, Y8, YG11, O16, R25, G58, B47) is the film for the shot and
   can be changed in review without developing again. In Plate the row
   is the three taking filters: Camera (the sensor's colour matrix),
   Wratten (25/58/47) or Channels. The colour space starts at sRGB;
   Adobe RGB is in Settings.
3. **Grade.** Soft and hard are the two paper exposures, in stops from
   the base print (±3, steps of 1/12); zero is about grade 2. The zone
   strip moves both by the same stops and keeps the grade; a double
   tap on it is the base print again. The pair in Settings is the
   standing grade: the Android finder, the automatic JPEG, and where a
   new frame starts. Every frame then keeps a grade of its own, stored
   beside it on the roll: moving a slider in the developer changes
   that picture alone, and the next shot starts from the standing
   grade again, not from the last correction.
4. **Peak** is Off, Auto or Custom. Auto measures radius and amount on
   each negative, Custom shows the two sliders, Off leaves the exports
   unsharpened. The preview is never sharpened; the 1:1 view is.
5. **A shot** is JPEG, Developer or Raw. JPEG writes the picture at
   this grade and stays on the finder. Developer opens the picture and
   the JPEG waits for Export. Raw keeps the frame on the roll and
   writes nothing until you open it. A fresh Android install starts on
   JPEG, a fresh iOS install on Developer.
6. **The shutter** records one RAW frame into the cache and, in JPEG
   and Developer, develops it at once (on Android in a foreground
   service). The JPEG lands in `Pictures/Mimizan Lab` on Android, and
   in Files and, add-only, Photos on iOS.
7. **Review** opens from the thumbnail, or after a Developer shot.
   Pinch to zoom; past the 2048 px preview the window is rendered 1:1
   from the negative. Soft, hard and the zone strip change the preview,
   the histogram and every export, through the same paper. Export
   writes the full-size JPEG, a 16-bit TIFF, or the linear negative
   plus saturation mask for the desktop (in Plate the three-frame
   `.plate.tif`, which `mimizan print` prints in colour). Share sends
   the last export.
8. **The roll** lists every frame in the cache (1.5 GB, oldest first).
   The bin, or a long press, deletes one. Exported pictures stay where
   they were written. Settings shows how much the frames take and can
   delete them all.

Only the most recent developed negative stays in memory (about 0.5 GB
with its preview planes). Import from Files and Photos is off on iOS: a
full-size NEF develops past what an iPad can keep in memory. The roll
is the camera's own frames.

## How it is built

```
Android   Camera2 RAW_SENSOR ──> RawInput (Kotlin) ──> mimizan-core (Rust, UniFFI)
iOS       AVFoundation Bayer RAW as DNG ──> develop_file (the desktop decoder)
both                     └─> app cache (re-developable)   └─> gallery or Files
                                                              MLM_*.jpg / .tif / .negative.tif
                                                              MLM_*_plate.jpg / .tif / .plate.tif
```

* `rust/mimizan-mobile` — the Rust library around `mimizan-core`:
  `develop`, `remix`, `preview`, `detail`, `export_*`, UniFFI for Kotlin
  and Swift. A cdylib for Android; a static library (plus `develop_file`
  / `frame_info` through the desktop's RAW decoder) for iOS.
* `android/` — Kotlin and Jetpack Compose (Camera2, a foreground
  service, review). Gradle runs `cargo ndk` and `uniffi-bindgen`.
* `ios/` — SwiftUI for iPhone and iPad (AVFoundation, a Metal
  viewfinder, the same darkroom and review). `scripts/build_ios.sh`
  builds the core into an xcframework.
* `cameras/google_pixel_9.json` — camera file (schema 1 of Mimizan
  Lab), shipped in the APK.
* `scripts/device_spike.sh` — timing and peak RSS on a phone.
* `docs/ARCHITECTURE.md`, `docs/RESULTS_MOBILE.md`.

A DNG developed on iOS matches `mimizan` on the desktop bit for bit
(`develop_file_matches_develop_bit_for_bit`). The Android negative
matched the desktop CLI on a DNG of the same frame to 0.015 % RMS.

### Build for Android

The Rust crate depends on `mimizan-core` by path. The two repositories
sit next to each other; from `rust/mimizan-mobile` the core is
`../../../mimizan/crates/mimizan-core`. A local build takes whatever
that checkout is at. The workflows check `Draganito/mimizan` out at the
commit in `mimizan-core.ref`, so a TestFlight build and the APK of the
same version carry the same core (see [Release](#release)).

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
./gradlew assembleRelease      # a local release APK; the releases page is CI
```

`gradle.properties` sets `mimizan.rustProfile=release`, so a debug APK
still carries an optimised Rust library. `local.properties`
(`sdk.dir=...`) is git-ignored; create it or set `ANDROID_HOME`.
`build.gradle.kts` takes a release key from `android/keystore.properties`
(git-ignored; `storeFile`, `storePassword`, `keyAlias`, `keyPassword`)
when that file exists; today the releases carry the debug key.

Rust alone:

```bash
cd rust/mimizan-mobile
cargo test                        # synthetic frames
cargo test --features host-raw    # bit-for-bit against the desktop decoder (needs mimizan/testdata)
cargo ndk -t arm64-v8a --platform 33 build --release --lib
```

The `Android` workflow does the same on a Linux runner for a push that
touches `android/`, `rust/`, `cameras/` or `mimizan-core.ref`: cargo-ndk,
the bindings, the Kotlin unit tests, a debug APK as the artifact. A
`v*` or `android-*` tag starts `Android Release`, which builds the
minified APK and puts it on the release.

### Build for iOS

AVFoundation hands the Bayer RAW still over as a DNG
(`AVCapturePhoto.fileDataRepresentation()`). The app caches `<name>.dng`
plus `<name>.json`, and the core reads the file with the desktop decoder
(`develop_file`, the `decode-files` feature). The still is the size the
camera gives by default; a 14 Pro or later is not asked for its 48 MP
frame. The entitlement `com.apple.developer.kernel.increased-memory-limit`
is set. There is no foreground service: iOS grants a few seconds past
backgrounding, which is what one development takes.

A Mac with Xcode 26 builds it, or the `iOS` GitHub workflow, which
builds an unsigned `.app` on a macOS runner when a push touches `ios/`,
`rust/`, `cameras/` or `scripts/build_ios.sh`:

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

### Release

One version number sits in three files: `versionName` and `versionCode`
in `android/app/build.gradle.kts`, `MARKETING_VERSION` in
`ios/project.yml`, and `version` in `rust/mimizan-mobile/Cargo.toml`
(what `version()` reports in the app). `scripts/set_version.sh <version>`
writes all three, raises `versionCode` by one, and pins the core: it puts
the `HEAD` of `../mimizan` into `mimizan-core.ref`, and refuses when that
checkout has uncommitted core changes or a `HEAD` that is not on
`origin/main`, because the workflows check that commit out. It commits
nothing.

```bash
scripts/set_version.sh 0.14.23
git commit -am "0.14.23: ..."
git push
gh release create v0.14.23 --title 0.14.23 --notes "..."
```

The `v*` tag starts `TestFlight` and `Android Release`. A tag
`android-<version>` publishes the APK alone and starts nothing on the
iOS side. The iOS build number is the workflow's run number, not the
`27` in `project.yml`; that one is for a local Xcode build.

## Status

0.15.0 on Android and iOS: RAW capture, the finder as the paper
(Android), Mono and Plate, the split-grade paper with a grade per frame,
the zone strip, Peak, review with remix and 1:1 detail, the exports, the
roll. Checked on a
Pixel 9 (main and ultrawide), a Pixel 4a and a Galaxy S25 FE. Open: a
fitted camera file for any phone but the Pixel 9, and a MediaTek phone.

## License

Copyright (C) 2026 Dragan Bojovic.

Mimizan Lab Mobile is free software under the GNU General Public License,
version 3 or any later version. See [LICENSE](LICENSE). The APK ships that
text and a short notice in its assets. The iOS App Store build does not:
that copy keeps the copyright line and leaves this license out of the
bundle. Releases published before this change stay under the MIT license
they were shipped with.
