//! Host tests: the FFI path (RawInput -> develop -> preview/remix/export)
//! on a synthetic mosaic, and, with `--features host-raw`, the proof that a
//! frame handed over Camera2-style gives the bit-identical negative of the
//! desktop decoder path on a real NEF.

use mimizan_mobile::*;
use std::path::PathBuf;

fn tmp_dir(name: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("mimizan-mobile-test-{name}-{}", std::process::id()));
    std::fs::create_dir_all(&d).unwrap();
    d
}

/// Synthetic colour patches as a 12-bit RGGB RAW_SENSOR frame.
fn synthetic_input(w: usize, h: usize, orientation: u32) -> RawInput {
    use mimizan_core::synth::{mosaic, Scene, SynthParams};
    let p = SynthParams { gains: [1.0, 1.0, 1.0], noise: None, ..Default::default() };
    let s = mosaic(Scene::Patches, w, h, &p);
    let (black, white) = (256.0, 4095.0);
    let mut data = Vec::with_capacity(w * h * 2);
    for &v in &s.mosaic.plane.data {
        let dn = (black + v.clamp(0.0, 1.0) * (white - black)).round() as u16;
        data.extend_from_slice(&dn.to_le_bytes());
    }
    RawInput {
        make: "Google".into(),
        model: "Pixel 9".into(),
        width: w as u32,
        height: h as u32,
        pattern: BayerPattern::Rggb,
        black_tile: vec![black; 4],
        white_level: white,
        crop: None,
        orientation,
        wb_gains: Some(vec![1.0, 1.0, 1.0, 1.0]),
        xyz_to_cam: None,
        exposure: Some(ExposureInfo { iso: Some(100), exposure_ns: Some(8_000_000), ..Default::default() }),
        data,
    }
}

#[test]
fn synthetic_develop_preview_remix_export() {
    let (w, h) = (320usize, 240usize);
    let input = synthetic_input(w, h, 6);
    let params = DevelopParams { preview_long_edge: 160, ..DevelopParams::default() };
    let dev = develop(input, params).expect("develop");
    let info = dev.info();
    assert_eq!((info.width, info.height), (w as u32, h as u32));
    assert_eq!((info.upright_width, info.upright_height), (h as u32, w as u32), "orientation 6 swaps");
    assert_eq!(info.pattern, BayerPattern::Rggb);
    assert_eq!(info.weights, Weights::NATIVE);
    assert_eq!(info.wb, vec![1.0, 1.0, 1.0]);
    assert!(info.json.contains("\"stage\":\"negative\""));

    // Preview: upright, long edge 160, then one more quarter turn.
    let pv = dev.preview(look_neutral(), 0).unwrap();
    assert_eq!((pv.width, pv.height), (120, 160));
    assert_eq!(pv.data.len(), 120 * 160);
    let pv_turned = dev.preview(look_neutral(), 1).unwrap();
    assert_eq!((pv_turned.width, pv_turned.height), (160, 120));

    // Histogram covers every preview pixel.
    let hist = dev.histogram(look_reference()).unwrap();
    assert_eq!(hist.iter().sum::<u64>(), 120 * 160);

    // Detail is 1:1 in display coordinates.
    let d = dev.detail(10, 20, 64, 48, look_neutral(), 0, 0.0).unwrap();
    assert_eq!((d.width, d.height), (64, 48));
    let d_edge = dev.detail(230, 300, 100, 100, look_neutral(), 0, 0.0).unwrap();
    assert_eq!((d_edge.width, d_edge.height), (10, 20), "clamped to the upright 240x320 picture");
    // Sharpened detail keeps the window size (the radius for this tiny
    // 240x320 "print" is 0.12 px, so the picture itself barely moves).
    let d_usm = dev.detail(10, 20, 64, 48, look_neutral(), 0, 1.0).unwrap();
    assert_eq!((d_usm.width, d_usm.height), (64, 48));
    let d_corner = dev.detail(0, 0, 32, 32, look_neutral(), 0, 1.0).unwrap();
    assert_eq!((d_corner.width, d_corner.height), (32, 32), "margin clamps at the picture edge");

    // Contrast: a composed look keeps the end points and steepens the middle.
    let flat = look_neutral();
    let hard = look_with_contrast(flat.clone(), 0.6).unwrap();
    assert_eq!(hard.points.len(), 65);
    assert!((hard.points[0].y).abs() < 1e-9 && (hard.points[64].y - 1.0).abs() < 1e-9);
    assert!(hard.points[48].y > 0.75, "{:?}", hard.points[48]);
    assert!(hard.points[16].y < 0.25, "{:?}", hard.points[16]);
    let soft = look_with_contrast(flat.clone(), -0.6).unwrap();
    assert!(soft.points[48].y < 0.75 && soft.points[16].y > 0.25);
    assert_eq!(look_with_contrast(flat.clone(), 0.0).unwrap(), flat);
    let pv_hard = dev.preview(hard, 0).unwrap();
    assert_ne!(pv_hard.data, pv.data);

    // Remix: a red filter changes the picture on the colour patches.
    let before = dev.negative_u16_le();
    let used = dev.remix(Weights::NATIVE, WeightSpace::Balanced, Filter::Red25).unwrap();
    assert!((used.r + used.g + used.b - 1.0).abs() < 1e-9);
    assert!(used.r > 0.7, "red filter weights lean red: {used:?}");
    let after = dev.negative_u16_le();
    assert_eq!(before.len(), after.len());
    assert_ne!(before, after);
    assert_eq!(dev.info().filter, Filter::Red25);
    // Native weights with no filter give the luminance plane back.
    dev.remix(Weights::NATIVE, WeightSpace::Balanced, Filter::None).unwrap();
    assert_eq!(dev.negative_u16_le(), before);

    // Exports.
    let dir = tmp_dir("synth");
    let jpg = dev.export_jpeg(dir.join("p.jpg").display().to_string(), look_reference(), Some(128), true, 0, 0.0).unwrap();
    assert_eq!((jpg.width, jpg.height), (96, 128));
    let jpg_usm = dev.export_jpeg(dir.join("u.jpg").display().to_string(), look_reference(), None, false, 0, 0.8).unwrap();
    assert_eq!((jpg_usm.width, jpg_usm.height), (240, 320));
    let tif = dev.export_tiff16(dir.join("p.tif").display().to_string(), look_neutral(), 1, 0.0).unwrap();
    assert_eq!((tif.width, tif.height), (320, 240), "orientation 6 plus one turn is upside down, same size as the sensor frame");
    let neg = dev.export_negative(dir.join("neg.tif").display().to_string()).unwrap();
    assert_eq!((neg.width, neg.height), (w as u32, h as u32));
    assert!(dir.join("neg.mask.tif").exists());
    assert!(dev.export_jpeg(dir.join("x.tif").display().to_string(), look_neutral(), None, false, 0, 0.0).is_err());
    std::fs::remove_dir_all(&dir).unwrap();

    dev.release_preview();
    assert!(dev.preview(look_neutral(), 0).is_err());
}

#[test]
fn binning_halves_the_frame() {
    let input = synthetic_input(320, 240, 1);
    let params = DevelopParams { binning: 2, separation: SeparationMode::Off, preview_long_edge: 0, ..DevelopParams::default() };
    let dev = develop(input, params).unwrap();
    let info = dev.info();
    assert_eq!((info.width, info.height), (160, 120));
    assert_eq!(info.binning, 2);
    assert!((info.noise_a - 0.0025).abs() < 1e-12, "sigma halves");
}

#[test]
fn camera_file_sets_weights_and_noise() {
    let cam = r#"{
      "schema": 1, "make": "Google", "model": "Pixel 9", "bayer_phase": "RGGB",
      "noise_a": 0.004, "noise_b": 0.0001, "weights_rgb": [0.3, 0.4, 0.3],
      "weights_space": "raw", "usm_amount": 0.0, "matrix_fallback": false,
      "fitted": {"noise": false, "weights": false}
    }"#;
    let input = synthetic_input(160, 120, 1);
    let params = DevelopParams {
        camera_file_json: Some(cam.into()),
        separation: SeparationMode::Off,
        preview_long_edge: 0,
        ..DevelopParams::default()
    };
    let dev = develop(input, params).unwrap();
    let info = dev.info();
    assert!((info.noise_a - 0.004).abs() < 1e-12);
    // Balance is 1,1,1 here, so raw weights equal balanced weights.
    assert!((info.weights.r - 0.3).abs() < 1e-9 && (info.weights.g - 0.4).abs() < 1e-9);
    assert_eq!(info.default_weights, info.weights);

    let wrong = cam.replace("RGGB", "BGGR");
    let params = DevelopParams { camera_file_json: Some(wrong), preview_long_edge: 0, ..DevelopParams::default() };
    assert!(develop(synthetic_input(160, 120, 1), params).is_err(), "phase mismatch is refused");
}

#[test]
fn invalid_input_is_an_error_not_a_panic() {
    let mut input = synthetic_input(64, 48, 1);
    input.data.truncate(10);
    assert!(matches!(develop(input, DevelopParams::default()), Err(MimizanError::Invalid { .. })));
    assert!(bayer_pattern_from_camera2(4).is_err());
    assert_eq!(bayer_pattern_from_camera2(3).unwrap(), BayerPattern::Bggr);
    assert!(normalise_weights(Weights { r: -1.0, g: 1.0, b: 1.0 }).is_err());
    let n = normalise_weights(Weights { r: 1.0, g: 1.0, b: 2.0 }).unwrap();
    assert!((n.b - 0.5).abs() < 1e-12);
    assert_eq!(filters().len(), 7);
    assert_eq!(look_reference().name, "reference");
    assert!(look_reference().points.len() > 2);
}

/// The proof the port rests on: a RAW decoded by `rawler` on the desktop,
/// handed over as a `RawInput` (what the Kotlin bridge builds from Camera2),
/// must give the bit-identical negative of `pipeline::negative_from_frame`.
#[cfg(feature = "host-raw")]
#[test]
fn camera2_style_input_matches_desktop_decoder_bit_for_bit() {
    use mimizan_core::decode::{RawData, SensorKind};
    use mimizan_core::pipeline::{negative_from_frame, NegativeParams};

    let path = PathBuf::from(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../mimizan/testdata/NikonZF.nef"));
    if !path.exists() {
        eprintln!("skipping: {} not present", path.display());
        return;
    }
    let frame = mimizan_core::pipeline::decode(&path).expect("rawler decode");
    let SensorKind::Bayer(phase) = frame.kind else { panic!("Bayer file expected") };
    let RawData::Integer(samples) = &frame.data else { panic!("integer samples expected") };
    assert_eq!(frame.white_rgb[0], frame.white_rgb[1], "single white level expected for this test");

    let mut data = Vec::with_capacity(samples.len() * 2);
    for &s in samples {
        data.extend_from_slice(&s.to_le_bytes());
    }
    let input = RawInput {
        make: frame.make.clone(),
        model: frame.model.clone(),
        width: frame.width as u32,
        height: frame.height as u32,
        pattern: BayerPattern::from_phase(phase),
        black_tile: frame.black_tile.to_vec(),
        white_level: frame.white_rgb[1],
        crop: Some(RawRect {
            x: frame.crop.x as u32,
            y: frame.crop.y as u32,
            width: frame.crop.w as u32,
            height: frame.crop.h as u32,
        }),
        orientation: frame.orientation as u32,
        wb_gains: frame.wb_as_shot.map(|w| w.to_vec()),
        xyz_to_cam: frame.xyz_to_cam.map(|m| m.iter().flatten().copied().collect()),
        exposure: None,
        data,
    };

    let t = std::time::Instant::now();
    let dev = develop(input, DevelopParams { preview_long_edge: 0, ..DevelopParams::default() }).unwrap();
    let mobile_ms = t.elapsed().as_millis();
    let mobile = dev.mix_plane();

    let t = std::time::Instant::now();
    let desktop = negative_from_frame(&frame, &NegativeParams::default(), 0).unwrap();
    let desktop_ms = t.elapsed().as_millis();
    eprintln!("{}x{}: mobile path {mobile_ms} ms, desktop path {desktop_ms} ms", mobile.width, mobile.height);

    assert_eq!((mobile.width, mobile.height), (desktop.plane.width, desktop.plane.height));
    let diff = mobile.data.iter().zip(&desktop.plane.data).filter(|(a, b)| a != b).count();
    assert_eq!(diff, 0, "{diff} samples differ between the Camera2-style and the file path");
    let info = dev.info();
    assert_eq!(info.wb, desktop.info.wb.to_vec());
    assert_eq!(info.orientation as u16, desktop.orientation);
}
