# Results (mobile)

Device: Google Pixel 9 (`tokay`), Android 17 (API 37), Tensor G4, 8 worker
threads. Engine `mimizan-mobile 0.1.0` / `mimizan-core 0.4.0`.

## Correctness

### Camera2 path vs. desktop decoder (host, bit-for-bit)

`cargo test --features host-raw` (`tests/host.rs`,
`camera2_style_input_matches_desktop_decoder_bit_for_bit`): the Nikon Z f
NEF from `mimizan/testdata` is decoded with rawler, then fed once as a file
(desktop path) and once as a `RawInput` built the way the Kotlin bridge
builds it (packed 16-bit LE samples, black tile, white level, crop, WB
gains, orientation). The two developments differ in **0 samples** of the
16-bit negative. Host time 3.19 s (`RawInput` path) vs 3.25 s (file path).

### Phone negative vs. desktop CLI on the phone's DNG

Measured with 0.1.0, which still wrote a DNG next to the cache; from 0.2.0
on no DNG is written (the negative export is the path to the desktop).
`MLM_20261004_201222` (main lens, ISO 462, 1/33 s): the negative exported
on the phone (`export_negative`, 4080x3072) against
`mimizan negative` on the DNG the phone wrote (4064x3056: `DngCreator`
insets the default crop by 8 px), compared over the common area with a
64 px margin, 11.5 M samples:

| | value |
|---|---|
| mean difference | -6.7 / 65535 |
| RMS difference | 9.9 / 65535 (0.015 %) |
| within +-4 | 42 % of samples, within +-16: 99.3 % |
| max | 5066 (isolated, border of the separation support and defect pixels) |

The residual is the DNG's own metadata rounding: `AsShotNeutral` and the
black level are stored as rationals (phone WB B 2.9876 vs. DNG 2.9942,
black 63.957 vs 63.95), so the two paths start from slightly different
balance multipliers. The phone path uses the exact Camera2 floats.

## Sensor facts (Camera2, Pixel 9)

| | main (logical 0 / physical 2,4) | ultrawide (physical 3 of logical 0) |
|---|---|---|
| RAW_SENSOR size | 4080 x 3072 | 4032 x 3024 |
| CFA | **GBRG** | **BGGR** |
| bits | 10 in a 16-bit container, white 1023 | same |
| black (dynamic) | 63.93 – 63.96 | 64.02 – 64.07 |
| active = pre-correction array | yes, no crop | yes, no crop |
| focal | 6.9 mm (24 mm equiv.) | 2.02 mm (12 mm equiv.) |
| as-shot WB (tungsten room) | R 1.20 B 2.99 | R 1.09 B 2.84 |
| AE compensation | -24..24 in 1/6 EV | same |

The ultrawide is not in `cameraIdList`; it is reached by routing all three
output configurations to physical id "3" of logical camera "0"
(`OutputConfiguration.setPhysicalCameraId`). Its metadata comes from
`TotalCaptureResult.physicalCameraTotalResults["3"]`, which is what the DNG
and the `RawInput` use. The desktop CLI reads both DNGs (`mimizan info`).

## Timings

### `spike` binary (synthetic 4080x3072, 8 threads, `scripts/device_spike.sh`)

| mode | develop | ingest / separate / mix / preview planes | preview | remix+preview | peak RSS |
|---|---|---|---|---|---|
| Dubois (default) | **3616 ms** | 441 / 2139 / 92 / 607 | 114 ms | 76 ms | 1004 MB |
| mask off | 2181 ms | 369 / 719 / 94 / 757 | 59 ms | 54 ms | 1004 MB |
| Quick mode (2x CFA binning, 2040x1536) | 1118 ms | 112 / 679 / 32 / 97 | 138 ms | 65 ms | 1004 MB |

Peak RSS is the same in all three rows because the process measured all
modes in sequence (VmHWM is a high-water mark); Dubois sets it.

### In the app (`Darkroom` log, real frames, Dubois, full resolution)

| frame | total | ingest / separate / mix / preview |
|---|---|---|
| main, first development after launch | 4512 ms | 335 / 2854 / 119 / 751 |
| same frame developed again from the cache | 2354 ms | 253 / 1400 / 47 / 374 |
| ultrawide 4032x3024 | 3979 ms | 331 / 2334 / 104 / 767 |

So 2.3–4.5 s per 12.5 MP frame on the phone, depending on how warm the
cores are (the host needs 1.9 s with 16 threads). JPEG q100 full size:
5.7–7.4 MB, DNG 24–25 MB.

### Host, same frame size (16 threads, for reference)

Dubois 1923 ms (peak 1016 MB), mask off 1008 ms, Quick mode 457 ms.

## APK

* Debug: 66 MB (unminified, icons-extended), Rust release profile.
* Release (R8, debug-signed): 4.8 MB, `libmimizan_mobile.so` 2.2 MB stripped.

## Things found on the device

* A `RenderEffect` set directly on the preview `TextureView` blacks out
  everything Compose draws above the view (Android 17, Pixel 9). The AGSL
  shader now sits on a Compose `graphicsLayer` around the `AndroidView`.
* Android 17 gates the accelerometer behind the new runtime permission
  `android.permission.OTHER_SENSORS`; without it the
  `OrientationEventListener` is silent and the system posts a "tried to use
  sensors" notification. The app requests it on API >= 37.
* The Pixel 9 main sensor reports **GBRG**, not RGGB; the camera file was
  corrected. The app only loads a camera file whose `bayer_phase` matches
  the frame, so a wrong file degrades to defaults instead of failing.
