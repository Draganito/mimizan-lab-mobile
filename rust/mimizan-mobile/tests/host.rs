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

    // Preview: upright, long edge 160.
    let pv = dev.preview(0.0, 0.0).unwrap();
    assert_eq!((pv.width, pv.height), (120, 160));
    assert_eq!(pv.data.len(), 120 * 160);

    // Histogram covers every preview pixel.
    let hist = dev.histogram(0.0, 0.0).unwrap();
    assert_eq!(hist.iter().sum::<u64>(), 120 * 160);

    // Detail is 1:1 in display coordinates.
    let d = dev.detail(10, 20, 64, 48, 0.0, 0.0, 0.7, 0.0).unwrap();
    assert_eq!((d.width, d.height), (64, 48));
    let d_edge = dev.detail(230, 300, 100, 100, 0.0, 0.0, 0.7, 0.0).unwrap();
    assert_eq!((d_edge.width, d_edge.height), (10, 20), "clamped to the upright 240x320 picture");
    // The peak keeps the window size and changes the pixels.
    let d_peak = dev.detail(10, 20, 64, 48, 0.0, 0.0, 0.7, 1.0).unwrap();
    assert_eq!((d_peak.width, d_peak.height), (64, 48));
    assert_ne!(d_peak.data, d.data, "a peak amount of 1 sharpens the window");
    let d_corner = dev.detail(0, 0, 32, 32, 0.0, 0.0, 2.8, 1.0).unwrap();
    assert_eq!((d_corner.width, d_corner.height), (32, 32), "margin clamps at the picture edge");
    // Out-of-range values are pulled into the core's range, not refused.
    let d_wild = dev.detail(10, 20, 64, 48, 0.0, 0.0, f64::NAN, 99.0).unwrap();
    assert_eq!((d_wild.width, d_wild.height), (64, 48));

    // The measurement answers in range; a synthetic chart has clean edges.
    let m = dev.measure_peak(0.0, 0.0).unwrap();
    let lim = peak_limits();
    assert!((lim.radius_min..=lim.radius_max).contains(&m.radius), "radius {}", m.radius);
    assert!((0.0..=lim.amount_max).contains(&m.amount), "amount {}", m.amount);
    assert_eq!(lim.radius_default, 0.7);

    // A stop of soft light is a different print from the base exposure.
    let pv_soft = dev.preview(1.0, 0.0).unwrap();
    assert_ne!(pv_soft.data, pv.data);
    assert!(dev.preview(4.0, 0.0).is_err(), "exposures stay inside the slider");

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
    let jpg = dev.export_jpeg(dir.join("p.jpg").display().to_string(), 0.0, 0.0, 0.7, 0.0, false).unwrap();
    assert_eq!((jpg.width, jpg.height), (240, 320), "full size, upright");
    let jpg_peak =
        dev.export_jpeg(dir.join("u.jpg").display().to_string(), 0.0, 0.0, m.radius, 0.8, false).unwrap();
    assert_eq!((jpg_peak.width, jpg_peak.height), (240, 320));
    let binned = dev.export_jpeg(dir.join("b.jpg").display().to_string(), 0.0, 0.0, 0.7, 0.0, true).unwrap();
    assert_eq!((binned.width, binned.height), (120, 160), "binned: half the edge, upright");
    let tif = dev.export_tiff16(dir.join("p.tif").display().to_string(), 0.0, 0.0, 0.7, 0.0).unwrap();
    assert_eq!((tif.width, tif.height), (240, 320), "orientation 6: upright, the sensor frame turned");
    let neg = dev.export_negative(dir.join("neg.tif").display().to_string()).unwrap();
    assert_eq!((neg.width, neg.height), (w as u32, h as u32));
    assert!(dir.join("neg.mask.tif").exists());
    assert!(dev.export_jpeg(dir.join("x.tif").display().to_string(), 0.0, 0.0, 0.7, 0.0, false).is_err());
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn viewfinder_weights_are_the_developments_weights() {
    // The GPU viewfinder mixes with `mix_weights`; the still mixes inside
    // `develop`. Same settings, same gains, same numbers.
    let input = synthetic_input(320, 240, 1);
    let gains = input.wb_gains.clone();
    let params = DevelopParams { filter: Filter::Yellow8, preview_long_edge: 0, ..DevelopParams::default() };
    let dev = develop(input, params.clone()).unwrap();
    let w = mix_weights(params, gains).unwrap();
    let d = dev.info().weights;
    assert!(
        (w.r - d.r).abs() < 1e-12 && (w.g - d.g).abs() < 1e-12 && (w.b - d.b).abs() < 1e-12,
        "{w:?} vs {d:?}"
    );
    // Yellow 8 on the native triple: 0.25, 0.45, 0.025 normalised.
    assert!((w.r - 0.25 / 0.725).abs() < 1e-9);
}

#[test]
fn paper_lut_is_the_print_curve() {
    let lut = paper_lut(0.0, 0.0, 4096).unwrap();
    let curve = mimizan_core::paper::SplitGrade::default().curve();
    for &i in &[0usize, 184, 736, 2048, 3000, 4096] {
        let l = i as f64 / 4096.0;
        assert!((lut[i] as f64 - curve.eval(l)).abs() < 1e-6, "{i}");
    }
    // Grey card, interpolated the way the viewfinder reads the table.
    let x = 0.18f64 * 4096.0;
    let i = x.floor() as usize;
    let f = x - i as f64;
    let v = lut[i] as f64 + (lut[i + 1] as f64 - lut[i] as f64) * f;
    assert!((v - curve.eval(0.18)).abs() < 1e-4, "viewfinder grey {v}");
    assert!(paper_lut(3.5, 0.0, 16).is_err());
    assert!((paper_stop_limit() - 3.0).abs() < 1e-12);
}

/// The spread of two fingers on the paper strip: the grade and nothing
/// else, from 00 to 5.
#[test]
fn paper_graded_changes_the_grade_and_leaves_the_grey_card_at_its_tone() {
    use mimizan_core::paper::{grade_label, SplitGrade, GREY_DENSITY};
    let split = |p: &PaperPair| SplitGrade::new(p.soft, p.hard);
    let density = |p: &PaperPair| split(p).curve().density(0.18);
    let label = |p: &PaperPair| grade_label(split(p).curve().grade());
    let limit = paper_stop_limit();

    // From the base print, as far as the fingers go: grade 5 one way,
    // grade 00 the other, and no further on another step.
    let hardest = paper_graded(0.0, 0.0, 1000);
    let softest = paper_graded(0.0, 0.0, -1000);
    assert_eq!((label(&hardest).as_str(), label(&softest).as_str()), ("5", "00"));
    assert!((hardest.hard - hardest.soft - 2.0).abs() < 1e-12, "grade 5 is two stops of hard over soft");
    assert_eq!(paper_graded(hardest.soft, hardest.hard, 1), hardest);
    assert_eq!(paper_graded(softest.soft, softest.hard, -1), softest);

    // On the way the grey card keeps its tone, to the twelfth the pair is
    // written in, and the pair is one the files can carry.
    let reach = ((softest.hard - softest.soft) * 12.0).round() as i32..=24;
    assert_eq!(*reach.start(), -38, "the hard light is at its limit before the soft end");
    for n in reach {
        let p = paper_graded(0.0, 0.0, n);
        assert!((density(&p) - GREY_DENSITY).abs() < 0.06, "{n}: grey card at {}", density(&p));
        assert!(((p.hard - p.soft) * 12.0 - f64::from(n)).abs() < 1e-9, "{n}: the grade");
        for v in [p.soft, p.hard] {
            assert!((v * 12.0 - (v * 12.0).round()).abs() < 1e-9 && v.abs() <= limit, "{n}: {v}");
        }
        assert_eq!(paper_graded(p.soft, p.hard, -n), PaperPair { soft: 0.0, hard: 0.0 }, "{n}: and back");
        assert_eq!(split(&p), SplitGrade::default().graded(n), "{n}: the core's step");
    }
    // The pinch this replaces kept the mean of the two exposures: at grade
    // 5 that pair prints the grey card two thirds of a density darker.
    let mean_held = PaperPair { soft: -1.0, hard: 1.0 };
    assert_eq!(label(&mean_held), "5");
    assert!(density(&mean_held) - GREY_DENSITY > 0.6, "{}", density(&mean_held));

    // No step is the pair itself; a pair that is no number is the base print.
    assert_eq!(paper_graded(0.5, -0.25, 0), PaperPair { soft: 0.5, hard: -0.25 });
    assert_eq!(paper_graded(f64::NAN, f64::INFINITY, 0), PaperPair { soft: 0.0, hard: 0.0 });
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
    let params =
        DevelopParams { camera_file_json: Some(wrong), preview_long_edge: 0, ..DevelopParams::default() };
    assert!(develop(synthetic_input(160, 120, 1), params).is_err(), "phase mismatch is refused");
}

/// A flat grey frame: every photosite at `v` of the range.
fn flat_input(w: usize, h: usize, v: f64) -> RawInput {
    let mut input = synthetic_input(w, h, 1);
    let (black, white) = (256.0, 4095.0);
    let dn = (black + v * (white - black)).round() as u16;
    input.data = dn.to_le_bytes().repeat(w * h);
    input
}

/// A print value as a file in sRGB holds it: the sRGB encoding of the
/// light `v^2.2` (`print::write_as` in the core).
fn in_srgb_file(v: f64) -> f64 {
    mimizan_core::kodak::srgb_encode(v.clamp(0.0, 1.0).powf(2.2))
}

/// Nikon Z f, D65, rows R, G, B: a matrix with negative off-diagonals.
const ZF_MATRIX: [f64; 9] = [1.1607, -0.4491, -0.0977, -0.4522, 1.246, 0.2304, -0.0458, 0.1519, 0.7616];

#[test]
fn plate_is_a_remix_grey_stays_grey_and_the_lamps_move_one_channel() {
    let (w, h) = (160usize, 120usize);
    let mut input = flat_input(w, h, 0.3);
    input.xyz_to_cam = Some(ZF_MATRIX.to_vec());
    let gains = input.wb_gains.clone();
    let params = DevelopParams { preview_long_edge: 80, ..DevelopParams::default() };
    let dev = develop(input, params.clone()).unwrap();
    assert!(dev.info().has_colour_matrix);
    assert!(dev.info().plate.is_none());
    assert!(dev.preview_rgb(0.0, 0.0).is_err(), "colour needs the plate process");

    // The viewfinder's numbers are the still's.
    let pw = dev.set_plate(PlateFilters::Camera, PlateSpace::Srgb).unwrap();
    assert_eq!(pw.filters, PlateFilters::Camera);
    let vf = plate_weights(
        params.clone(),
        gains,
        Some(ZF_MATRIX.to_vec()),
        PlateFilters::Camera,
        PlateSpace::Srgb,
    )
    .unwrap();
    assert_eq!(vf, pw);
    for row in [pw.red, pw.green, pw.blue] {
        assert!((row.r + row.g + row.b - 1.0).abs() < 1e-9, "{row:?}");
    }
    assert!(pw.red.r > 1.0 && pw.red.g < 0.0, "the camera set sharpens the colour: {:?}", pw.red);
    let state = dev.info().plate.expect("plate state");
    assert_eq!(state.space, PlateSpace::Srgb);
    assert_eq!((state.lamp_red, state.lamp_blue), (0.0, 0.0));
    assert!(dev.info().json.contains("\"plate\""));

    // Grey in, grey out on all three positives, whatever the set.
    for f in [PlateFilters::Camera, PlateFilters::Wratten, PlateFilters::Channels] {
        dev.set_plate(f, PlateSpace::Srgb).unwrap();
        let rgb = dev.preview_rgb(0.0, 0.0).unwrap();
        assert_eq!((rgb.width, rgb.height), (80, 60));
        assert_eq!(rgb.data.len(), 80 * 60 * 3);
        let c = rgb.data.len() / 2 - rgb.data.len() / 2 % 3;
        let (r, g, b) = (rgb.data[c], rgb.data[c + 1], rgb.data[c + 2]);
        assert!(r.abs_diff(g) <= 1 && g.abs_diff(b) <= 1, "{f:?}: {r} {g} {b}");
        assert!(r > 20 && r < 235, "{f:?}: a mid grey, not clipped: {r}");
        let d = dev.detail_rgb(10, 10, 32, 24, 0.0, 0.0, 0.7, 0.0).unwrap();
        assert_eq!((d.width, d.height), (32, 24));
        let i = 3 * (12 * 32 + 16);
        assert!(d.data[i].abs_diff(d.data[i + 1]) <= 1 && d.data[i + 1].abs_diff(d.data[i + 2]) <= 1);
        // The grey preview is the same picture as the plate's luminance:
        // the print's value there, which the plate in sRGB shows as its
        // file's sample.
        let grey = dev.preview(0.0, 0.0).unwrap();
        let shown = (in_srgb_file(f64::from(grey.data[c / 3]) / 255.0) * 255.0).round() as u8;
        assert!(
            shown.abs_diff(g) <= 1,
            "{f:?}: luminance {} shown as {shown} vs green {g}",
            grey.data[c / 3]
        );
    }

    // Lamps: half a stop on red lifts red, green and blue stay.
    let before = dev.preview_rgb(0.0, 0.0).unwrap();
    dev.set_lamps(0.5, 0.0).unwrap();
    let after = dev.preview_rgb(0.0, 0.0).unwrap();
    assert!(after.data[0] > before.data[0], "red {} -> {}", before.data[0], after.data[0]);
    assert_eq!(after.data[1], before.data[1]);
    assert_eq!(after.data[2], before.data[2]);
    assert_eq!(dev.info().plate.unwrap().lamp_red, 0.5);
    assert!(dev.set_lamps(2.5, 0.0).is_err(), "lamps stay within the limit");
    assert!((lamp_limit() - 2.0).abs() < 1e-12);
    let hist = dev.histogram_rgb(0.0, 0.0).unwrap();
    assert_eq!(hist.len(), 3 * 256);
    for c in 0..3 {
        assert_eq!(hist[c * 256..(c + 1) * 256].iter().sum::<u64>(), 80 * 60, "channel {c}");
    }
    // Exports: RGB files, and the stacked plate the desktop prints.
    let dir = tmp_dir("plate");
    let tif = dev.export_tiff16(dir.join("p.tif").display().to_string(), 0.0, 0.0, 0.7, 0.5).unwrap();
    assert_eq!((tif.width, tif.height), (w as u32, h as u32));
    let planes = mimizan_core::tiffin::read_rgb16(&dir.join("p.tif")).expect("an RGB TIFF");
    assert_eq!((planes[0].width, planes[0].height), (w, h));
    assert!(planes[0].at(80, 60) > planes[1].at(80, 60), "the red lamp is in the file");
    assert!((planes[1].at(80, 60) - planes[2].at(80, 60)).abs() < 1.0 / 65535.0);
    // The preview of the plate in sRGB is that file, lamp and all.
    let pv = dev.preview_rgb(0.0, 0.0).unwrap();
    let c = pv.data.len() / 2 - pv.data.len() / 2 % 3;
    for (k, plane) in planes.iter().enumerate() {
        let face = (plane.at(80, 60) * 255.0).round();
        assert!((face - f64::from(pv.data[c + k])).abs() <= 1.0, "channel {k}: {face} vs {}", pv.data[c + k]);
    }
    let jpg = dev.export_jpeg(dir.join("p.jpg").display().to_string(), 0.0, 0.0, 0.7, 0.0, false).unwrap();
    assert_eq!((jpg.width, jpg.height), (w as u32, h as u32));
    assert!(std::fs::metadata(dir.join("p.jpg")).unwrap().len() > 0);

    let neg = dev.export_negative(dir.join("plate.tif").display().to_string()).unwrap();
    assert_eq!((neg.width, neg.height), (w as u32, 3 * h as u32), "three frames stacked");
    let p = mimizan_core::print::PrintParams {
        recipe: mimizan_core::recipe::Recipe::on_paper(),
        lamps: [0.5, 0.0, 0.0],
        ..Default::default()
    };
    let out = mimizan_core::print::render(&dir.join("plate.tif"), &p)
        .expect("the desktop prints the phone's plate");
    assert!(out.plate.is_some(), "recognised as a plate");
    assert_eq!((out.plane.width, out.plane.height), (w, h));
    let sheet = out.plate.as_ref().unwrap();
    // The print's values are the paper's gamma 2.2; a file in sRGB is
    // written on the sRGB curve (`print::write_as`), the same light.
    let in_file = in_srgb_file;
    assert!(
        (in_file(out.plane.at(80, 60)) - planes[0].at(80, 60)).abs() < 2.0 / 65535.0,
        "same red as the phone's TIFF"
    );
    assert!((in_file(sheet.green.at(80, 60)) - planes[1].at(80, 60)).abs() < 2.0 / 65535.0);
    // The Adobe space keeps the print's values: its gamma is the paper's.
    dev.set_plate(PlateFilters::Channels, PlateSpace::AdobeRgb).unwrap();
    dev.export_tiff16(dir.join("a.tif").display().to_string(), 0.0, 0.0, 0.7, 0.0).unwrap();
    let adobe = mimizan_core::tiffin::read_rgb16(&dir.join("a.tif")).unwrap();
    let pv = dev.preview_rgb(0.0, 0.0).unwrap();
    let c = pv.data.len() / 2 - pv.data.len() / 2 % 3;
    assert!(
        (adobe[1].at(80, 60) * 255.0 - f64::from(pv.data[c + 1])).abs() <= 1.0,
        "the preview's green is the file's"
    );
    std::fs::remove_dir_all(&dir).unwrap();

    // Back to the monochrome process.
    let used = dev.remix(Weights::NATIVE, WeightSpace::Balanced, Filter::None).unwrap();
    assert_eq!(used, Weights::NATIVE);
    assert!(dev.info().plate.is_none());
    assert!(!dev.info().json.contains("\"plate\""));
    assert!(dev.preview_rgb(0.0, 0.0).is_err());
    let grey = dev.export_negative(dir.join("neg.tif").display().to_string());
    assert!(grey.is_err(), "the directory is gone");
}

#[test]
fn camera_filters_without_a_matrix_are_the_channels() {
    let input = flat_input(64, 48, 0.4);
    assert!(input.xyz_to_cam.is_none());
    let dev = develop(input, DevelopParams { preview_long_edge: 0, ..DevelopParams::default() }).unwrap();
    assert!(!dev.info().has_colour_matrix);
    let pw = dev.set_plate(PlateFilters::Camera, PlateSpace::AdobeRgb).unwrap();
    assert_eq!(pw.filters, PlateFilters::Channels);
    assert_eq!(pw.red, Weights { r: 1.0, g: 0.0, b: 0.0 });
    let vf = plate_weights(DevelopParams::default(), None, None, PlateFilters::Camera, PlateSpace::AdobeRgb)
        .unwrap();
    assert_eq!(vf, pw);
    // Wratten in front of the native emulsion: the core's crosstalk numbers.
    let wr =
        plate_weights(DevelopParams::default(), None, None, PlateFilters::Wratten, PlateSpace::Srgb).unwrap();
    assert_eq!(wr.filters, PlateFilters::Wratten);
    assert!(wr.red.r > 0.8 && wr.red.b == 0.0, "{:?}", wr.red);
    assert!(dev.preview_rgb(0.0, 0.0).is_err(), "no preview planes");
}

/// The table a lamp's positive is read from in the viewfinder is the
/// placing the still gives it: `v · 2^lamp`, then the paper.
#[test]
fn the_lamp_table_is_the_stills_placing() {
    let n = 4096u32;
    let base = paper_lut(0.25, -0.5, n).unwrap();
    assert_eq!(paper_lut_lamp(0.25, -0.5, n, 0.0).unwrap(), base, "a lamp at zero is the paper's own table");
    let curve = mimizan_core::paper::SplitGrade::new(0.25, -0.5).curve();
    for lamp in [-2.0, -0.75, 1.0 / 12.0, 0.5, 2.0] {
        let lut = paper_lut_lamp(0.25, -0.5, n, lamp).unwrap();
        assert_eq!(lut.len(), n as usize + 1);
        for &i in &[0usize, 184, 736, 2048, 3000, 4096] {
            let l = i as f64 / n as f64 * 2f64.powf(lamp);
            assert!((lut[i] as f64 - curve.eval(l)).abs() < 1e-6, "lamp {lamp}, entry {i}");
        }
    }
    // More light on a positive is a lighter print of it, past the clip's tone too.
    let up = paper_lut_lamp(0.0, 0.0, n, 1.0).unwrap();
    let flat = paper_lut(0.0, 0.0, n).unwrap();
    assert!(up[2048] > flat[2048] && up[4096] > flat[4096], "{} {}", up[4096], flat[4096]);
    assert!(paper_lut_lamp(0.0, 0.0, n, 2.5).is_err(), "lamps stay within the limit");
    assert!(paper_lut_lamp(0.0, 0.0, n, f64::NAN).is_err());

    // The still: a flat grey plate under both lamps. Grey stays grey on
    // the three positives, so each is the frame's linear grey; the
    // preview's colour is the table's value there.
    let (w, h) = (160usize, 120usize);
    let mut input = flat_input(w, h, 0.3);
    input.xyz_to_cam = Some(ZF_MATRIX.to_vec());
    let dev = develop(input, DevelopParams { preview_long_edge: 80, ..DevelopParams::default() }).unwrap();
    // Before the plate is on: the pair is kept for it.
    dev.set_lamps(0.5, -0.75).unwrap();
    dev.set_plate(PlateFilters::Camera, PlateSpace::Srgb).unwrap();
    let state = dev.info().plate.unwrap();
    assert_eq!((state.lamp_red, state.lamp_blue), (0.5, -0.75));
    let grey = dev.mix_plane().at(w / 2, h / 2);
    let read = |lut: &[f32]| {
        let x = grey * n as f64;
        let i = x.floor() as usize;
        let v = lut[i] as f64 + (lut[i + 1] as f64 - lut[i] as f64) * (x - i as f64);
        (v * 255.0).round() as u8
    };
    // The viewfinder reads a plate from `plate_lut`, the lamp's table as
    // the plate's file holds it; in both spaces it is the still's preview.
    for space in [PlateSpace::Srgb, PlateSpace::AdobeRgb] {
        dev.set_plate(PlateFilters::Camera, space).unwrap();
        let rgb = dev.preview_rgb(0.0, 0.0).unwrap();
        let c = rgb.data.len() / 2 - rgb.data.len() / 2 % 3;
        let want = [
            read(&plate_lut(0.0, 0.0, n, 0.5, space).unwrap()),
            read(&plate_lut(0.0, 0.0, n, 0.0, space).unwrap()),
            read(&plate_lut(0.0, 0.0, n, -0.75, space).unwrap()),
        ];
        for (k, (&got, &want)) in rgb.data[c..c + 3].iter().zip(&want).enumerate() {
            assert!(got.abs_diff(want) <= 1, "{space:?}, channel {k}: {got} vs {want}");
        }
        assert!(want[0] > want[1] && want[1] > want[2], "{space:?}: red up, blue down: {want:?}");
    }
}

/// `plate_lut` is the lamp's table as the plate's file holds it: in Adobe
/// RGB the print's values, in sRGB the sRGB encoding of the light they
/// stand for. The curves part in the shadows and meet above the middle.
#[test]
fn the_plates_table_is_the_lamps_table_in_the_files_samples() {
    let n = 4096u32;
    for lamp in [0.0, -0.75, 0.5, 2.0] {
        let print = paper_lut_lamp(0.25, -0.5, n, lamp).unwrap();
        let adobe = plate_lut(0.25, -0.5, n, lamp, PlateSpace::AdobeRgb).unwrap();
        assert_eq!(adobe, print, "lamp {lamp}: the Adobe space keeps the print's values");
        let srgb = plate_lut(0.25, -0.5, n, lamp, PlateSpace::Srgb).unwrap();
        assert_eq!(srgb.len(), print.len());
        let mut last = -1.0f32;
        for (i, (&shown, &v)) in srgb.iter().zip(&print).enumerate() {
            assert!((f64::from(shown) - in_srgb_file(f64::from(v))).abs() < 1e-5, "lamp {lamp}, entry {i}");
            assert!(shown >= last, "lamp {lamp}: the table falls at {i}");
            last = shown;
        }
    }
    assert_eq!(plate_lut(0.0, 0.0, n, 0.0, PlateSpace::AdobeRgb).unwrap(), paper_lut(0.0, 0.0, n).unwrap());
    // Where the two curves stand: the file's 5, 18 and 48 of 255 for the
    // print's 13, 25 and 51, and no more than two levels apart from 102 up.
    let level = |v: u8| (in_srgb_file(f64::from(v) / 255.0) * 255.0).round() as u8;
    assert_eq!((level(13), level(25), level(51)), (5, 18, 48));
    assert_eq!((level(0), level(255)), (0, 255));
    for v in 102..=255u8 {
        assert!(level(v).abs_diff(v) <= 2, "{v} is shown as {}", level(v));
    }
    assert!(plate_lut(0.0, 0.0, n, 2.5, PlateSpace::Srgb).is_err(), "lamps stay within the limit");
    assert!(plate_lut(3.5, 0.0, n, 0.0, PlateSpace::Srgb).is_err(), "exposures stay inside the slider");
}

/// The 1:1 window of a plate is the file the export writes, sample for
/// sample, in sRGB and in the Adobe space, with lamps and a paper off its
/// base that prints the patches into the shadows, where the two curves
/// part. The preview of a plate in sRGB is darker there than the same
/// plate in the Adobe space, and the histogram, which counts the print's
/// values, is the same in both.
#[test]
fn the_plates_window_is_the_written_file_and_the_histogram_counts_the_print() {
    let (w, h) = (320usize, 240usize);
    let mut input = synthetic_input(w, h, 1);
    input.xyz_to_cam = Some(ZF_MATRIX.to_vec());
    let dev = develop(input, DevelopParams { preview_long_edge: 160, ..DevelopParams::default() }).unwrap();
    dev.set_lamps(0.25, -0.5).unwrap();
    let (soft, hard) = (1.5, 1.0);
    let dir = tmp_dir("screen");
    let mut shadows = 0usize;
    let mut previews = Vec::new();
    let mut histograms = Vec::new();
    for space in [PlateSpace::Srgb, PlateSpace::AdobeRgb] {
        dev.set_plate(PlateFilters::Channels, space).unwrap();
        let path = dir.join(format!("{space:?}.tif"));
        dev.export_tiff16(path.display().to_string(), soft, hard, 0.7, 0.0).unwrap();
        let file = mimizan_core::tiffin::read_rgb16(&path).unwrap();
        assert_eq!((file[0].width, file[0].height), (w, h));
        let shown = dev.detail_rgb(0, 0, w as u32, h as u32, soft, hard, 0.7, 0.0).unwrap();
        assert_eq!((shown.width, shown.height), (w as u32, h as u32));
        let mut off_by_one = 0usize;
        for y in 0..h {
            for x in 0..w {
                for (c, plane) in file.iter().enumerate() {
                    let face = (plane.at(x, y) * 255.0).round() as i32;
                    let d = (i32::from(shown.data[(y * w + x) * 3 + c]) - face).abs();
                    assert!(
                        d <= 1,
                        "{space:?}, channel {c} at {x},{y}: the screen is {d} levels from the file"
                    );
                    off_by_one += (d == 1) as usize;
                    shadows += (space == PlateSpace::Srgb && (4..40).contains(&face)) as usize;
                }
            }
        }
        // The window and the file are one evaluation; a level apart only
        // where the file's 16 bits round the other way.
        assert!(off_by_one * 1000 <= 3 * w * h, "{space:?}: {off_by_one} of {} a level off", 3 * w * h);
        previews.push(dev.preview_rgb(soft, hard).unwrap());
        histograms.push(dev.histogram_rgb(soft, hard).unwrap());
    }
    assert!(shadows > 5000, "only {shadows} shadow samples compared");
    // The same plate in the two spaces: the print's values are the Adobe
    // preview, the sRGB preview is each of them as its file's sample.
    let (srgb, adobe) = (&previews[0], &previews[1]);
    let mut darker = 0usize;
    for (&s, &a) in srgb.data.iter().zip(&adobe.data) {
        let want = (in_srgb_file(f64::from(a) / 255.0) * 255.0).round() as u8;
        assert!(s.abs_diff(want) <= 1, "print value {a}: shown {s}, the file's sample {want}");
        darker += (a < 60 && s < a) as usize;
    }
    assert!(darker > 1000, "only {darker} shadow samples darker in sRGB");
    assert_eq!(histograms[0], histograms[1], "the histogram counts the print's values in both spaces");
    std::fs::remove_dir_all(&dir).unwrap();
}

/// A burnt-out highlight stands above 1 in the negative (1 is the level
/// at which green is full; red and blue carry their multipliers). The
/// file keeps it the way the desktop's does, the desktop reads it back,
/// and the preview prints it lighter than the clip's tone.
#[test]
fn a_burnt_highlight_stands_above_one_and_the_file_keeps_it() {
    use mimizan_core::tiffin::{described_range, read_gray, read_negative};
    let (w, h) = (160usize, 120usize);
    let burnt = |on: bool| {
        let mut input = flat_input(w, h, 0.3);
        input.wb_gains = Some(vec![2.0, 1.0, 1.0, 1.5]);
        if on {
            let white = (input.white_level as u16).to_le_bytes();
            for y in 44..76 {
                for x in 64..96 {
                    input.data[2 * (y * w + x)..2 * (y * w + x) + 2].copy_from_slice(&white);
                }
            }
        }
        develop(input, DevelopParams { preview_long_edge: 80, ..DevelopParams::default() }).unwrap()
    };
    let dir = tmp_dir("range");

    let dev = burnt(true);
    let mix = dev.mix_plane();
    let top = mix.data.iter().copied().fold(1.0, f64::max);
    // Every channel full: (2 + 2·1 + 1.5) / 4.
    assert!((mix.at(80, 60) - 1.375).abs() < 1e-6, "{}", mix.at(80, 60));
    assert!((1.375..1.7).contains(&top), "{top}");
    let path = dir.join("burnt.tif");
    dev.export_negative(path.display().to_string()).unwrap();
    let plain = read_gray(&path).unwrap();
    let range = described_range(plain.description.as_deref()).unwrap();
    assert_eq!(range, top, "the description states the highest value of the plane");
    let back = read_negative(&path).unwrap();
    let step = range / 65535.0;
    for (a, b) in back.plane.data.iter().zip(&mix.data) {
        assert!((a - b).abs() <= 0.5 * step + 1e-12, "{a} vs {b}");
    }
    // The preview: the paper goes on above 1, so the burnt square is
    // lighter than the tone of the clip.
    let clip = (paper_lut(0.0, 0.0, 16).unwrap()[16] * 255.0).round() as u8;
    let pv = dev.preview(0.0, 0.0).unwrap();
    let centre = pv.data[(pv.height / 2 * pv.width + pv.width / 2) as usize];
    assert!(centre > clip, "{centre} vs the clip's {clip}");
    // The stored information describes the development, not a file.
    assert!(!dev.info().json.contains("\"range\""));

    // Nothing above 1: no range, the file as it always was.
    let calm = burnt(false);
    let mix = calm.mix_plane();
    let path = dir.join("calm.tif");
    calm.export_negative(path.display().to_string()).unwrap();
    let plain = read_gray(&path).unwrap();
    assert!(!plain.description.as_deref().unwrap().contains("\"range\""));
    for (a, b) in plain.plane.data.iter().zip(&mix.data) {
        assert_eq!((a * 65535.0).round(), (b.clamp(0.0, 1.0) * 65535.0).round());
    }
    std::fs::remove_dir_all(&dir).unwrap();
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
        // testdata/ is gitignored on the desktop; CI must set this so a skip
        // is an explicit choice, not a silent green.
        assert!(
            std::env::var_os("MIMIZAN_ALLOW_SKIP_HOST").is_some(),
            "missing {}; set MIMIZAN_ALLOW_SKIP_HOST=1 to skip the bit-for-bit proof",
            path.display()
        );
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

/// The iOS entrance: `develop_file` on the path (the DNG AVFoundation
/// wrote, here any RAW) is the Camera2-style path bit for bit, and
/// `frame_info` reads what the decoder reads.
#[cfg(feature = "decode-files")]
#[test]
fn develop_file_matches_develop_bit_for_bit() {
    use mimizan_core::decode::{RawData, SensorKind};

    let path =
        PathBuf::from(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../mimizan/testdata/nikon_zf_01.nef"));
    if !path.exists() {
        assert!(
            std::env::var_os("MIMIZAN_ALLOW_SKIP_HOST").is_some(),
            "missing {}; set MIMIZAN_ALLOW_SKIP_HOST=1 to skip the bit-for-bit proof",
            path.display()
        );
        eprintln!("skipping: {} not present", path.display());
        return;
    }
    let frame = mimizan_core::pipeline::decode(&path).expect("rawler decode");
    let SensorKind::Bayer(phase) = frame.kind else { panic!("Bayer file expected") };
    let RawData::Integer(samples) = &frame.data else { panic!("integer samples expected") };

    let info = frame_info(path.to_string_lossy().into_owned()).unwrap();
    assert_eq!((info.width, info.height), (frame.width as u32, frame.height as u32));
    assert_eq!(info.pattern, BayerPattern::from_phase(phase));
    assert_eq!(info.orientation, frame.orientation as u32);
    assert_eq!(info.make, frame.make);
    assert_eq!(info.exposure.iso, frame.exposure.iso);

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
    let params = DevelopParams { preview_long_edge: 0, ..DevelopParams::default() };
    let a = develop(input, params.clone()).unwrap().mix_plane();
    let b = develop_file(path.to_string_lossy().into_owned(), params).unwrap().mix_plane();
    assert_eq!((a.width, a.height), (b.width, b.height));
    let diff = a.data.iter().zip(&b.data).filter(|(x, y)| x != y).count();
    assert_eq!(diff, 0, "{diff} samples differ between develop and develop_file");
}
