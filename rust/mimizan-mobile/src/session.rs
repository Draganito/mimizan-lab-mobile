//! A developed frame kept in memory: the full-resolution separation (so the
//! weights can be re-mixed without separating again), a downscaled upright
//! copy for the interactive preview, and the print exports. Mirrors what the
//! desktop GUI's `Session` does, with the same core calls.

use crate::frame::{as_shot_from_gains, bin_cfa_2x, matrix_of, to_raw_frame};
use crate::types::*;
use mimizan_core::calib::CameraFile;
use mimizan_core::icc::GrayTrc;
use mimizan_core::mix::{mix, Weights as CoreWeights};
use mimizan_core::pipeline::{negative_from_frame, plate_base, NegativeInfo, NegativeParams};
use mimizan_core::plane::Plane;
use mimizan_core::plate::{
    mix_plate_stacked, PlateInfo, PlateParams, PlateSpace as CorePlateSpace, PlateWeights as CorePlateWeights,
};
use mimizan_core::print::{self as pr, orient, rotated, PrintOutput, PrintParams, PrintSource, LAMP_LIMIT};
use mimizan_core::recipe::{Peak, Recipe, Sharpen};
use mimizan_core::resize::{fit_dims, resize};
use mimizan_core::separate::Separation;
use mimizan_core::tiffout::{write_gray16, write_gray8, TiffMeta};
use mimizan_core::tone::{render, Tone, ToneMap};
use mimizan_core::usm::{kernel_radius, unsharp};
use rayon::prelude::*;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::Instant;
use tracing::info;

/// The peak high-pass of an export from the bridge's flat arguments: the
/// pair pulled into the core's range, on when the amount is above zero. A
/// non-finite amount counts as zero, a non-finite radius as the default.
fn peak_of(radius: f64, amount: f64) -> Peak {
    let (radius, amount) = Peak::clamp_measured(radius, amount);
    Peak { on: amount > 0.0, radius, amount }
}

/// The print recipe of an export: the paper, no USM or deconvolution, the
/// peak as given.
fn export_recipe(soft: f64, hard: f64, peak_radius: f64, peak_amount: f64) -> Result<Recipe> {
    Ok(Recipe {
        curve: Tone::Paper(split_grade(soft, hard)?),
        sharpen: Sharpen::Off,
        peak: peak_of(peak_radius, peak_amount),
        ..Recipe::default()
    })
}

/// Upright, downscaled separation for the preview (never sharpened).
struct PreviewPlanes {
    lum: Plane,
    c1: Plane,
    c2: Plane,
}

/// The plate process: the three taking filters resolved for this frame,
/// the primaries of the colour file.
#[derive(Clone, Copy)]
struct PlateProcess {
    weights: CorePlateWeights,
    space: CorePlateSpace,
}

struct Inner {
    sep: Separation,
    /// 8-bit sidecar: `round(255*mask_max)`, 255 where saturated.
    mask8: Vec<u8>,
    orientation: u16,
    info: NegativeInfo,
    binning: u32,
    separation_mode: SeparationMode,
    /// The monochrome mix (filter included). Kept while the plate is on,
    /// so the luminance histogram and the way back stay what they were.
    weights: CoreWeights,
    filter: Filter,
    default_weights: CoreWeights,
    /// `Some` while the development is in the plate process.
    plate: Option<PlateProcess>,
    /// The red and the blue lamp, stops; green is the reference.
    lamps: [f64; 2],
    /// What the plate's filters are resolved from: the camera file's
    /// panchromatic response (or the native one) and the frame's matrix.
    camera_file: Option<CameraFile>,
    xyz_to_cam: Option<[[f64; 3]; 3]>,
    preview: Option<PreviewPlanes>,
    timing: Timing,
}

/// One development. Thread-safe; every method takes the lock for its
/// duration, so a long export blocks a concurrent preview (call from one
/// worker).
#[derive(uniffi::Object)]
pub struct Developed {
    inner: Mutex<Inner>,
}

fn lock_msg<T>(r: std::sync::LockResult<T>) -> T {
    match r {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    }
}

/// Ingest, separate and mix one frame.
pub fn develop_impl(input: RawInput, params: DevelopParams) -> Result<Developed> {
    let t_all = Instant::now();
    let frame = to_raw_frame(&input)?;
    // The input buffer is no longer needed; free the 2 bytes/px copy early.
    drop(input);
    let (frame, binning) = match params.binning {
        0 | 1 => (frame, 1),
        2 => (bin_cfa_2x(&frame)?, 2),
        n => return Err(MimizanError::Invalid { msg: format!("binning {n} not supported (1 or 2)") }),
    };

    let mut p = NegativeParams { keep_separation: true, ..NegativeParams::default() };
    p.ingest.wb = params.white_balance.core();
    // Defects are counted, not repaired (core 0.15: every pixel measured).
    p.separate.mask = params.separation.mask_mode();
    if let Some(json) = &params.camera_file_json {
        let cam: CameraFile = serde_json::from_str(json)
            .map_err(|e| MimizanError::Invalid { msg: format!("camera file: {e}") })?;
        cam.validate()?;
        p = p.with_camera_file(cam);
    }
    if let Some(a) = params.noise_a {
        p.ingest.noise.a = a;
    }
    if let Some(b) = params.noise_b {
        p.ingest.noise.b = b;
    }
    if binning == 2 {
        // Mean of four independent sites: sigma halves.
        p.ingest.noise.a *= 0.5;
        p.ingest.noise.b *= 0.5;
    }
    let camera_default = (p.weights, p.weights_space);
    if let Some(w) = params.weights {
        p = p.with_weights(w.core(), params.weights_space.core());
    }
    p.filter = params.filter.core();

    let xyz_to_cam = frame.xyz_to_cam;
    let mut neg = negative_from_frame(&frame, &p, 0)?;
    drop(frame);
    let mut sep =
        neg.separation.take().ok_or_else(|| MimizanError::Internal { msg: "separation missing".into() })?;
    // The sidecar is already quantised; the f64 mask plane is not needed again.
    sep.mask_max = Plane::zeros(0, 0);
    let weights = neg.info.weights;
    let default_weights = camera_default.0.to_balanced(camera_default.1, neg.info.wb);

    let t = Instant::now();
    let preview = if params.preview_long_edge > 0 {
        Some(preview_planes(&sep, neg.orientation, params.preview_long_edge as usize))
    } else {
        None
    };
    let preview_ms = t.elapsed().as_millis() as u64;
    info!("preview planes {} ms", preview_ms);

    let timing = Timing {
        ingest_ms: neg.info.timing.ingest_ms as u64,
        separate_ms: neg.info.timing.separate_ms as u64,
        mix_ms: neg.info.timing.mix_ms as u64,
        preview_ms,
        total_ms: t_all.elapsed().as_millis() as u64,
    };

    Ok(Developed {
        inner: Mutex::new(Inner {
            sep,
            mask8: neg.mask8,
            orientation: neg.orientation,
            info: neg.info,
            binning,
            separation_mode: params.separation,
            weights,
            filter: params.filter,
            default_weights,
            plate: None,
            lamps: [0.0; 2],
            camera_file: p.camera_file,
            xyz_to_cam,
            preview,
            timing,
        }),
    })
}

/// The camera file in `params`, parsed and checked.
fn camera_file_of(params: &DevelopParams) -> Result<Option<CameraFile>> {
    match &params.camera_file_json {
        Some(json) => {
            let cam: CameraFile = serde_json::from_str(json)
                .map_err(|e| MimizanError::Invalid { msg: format!("camera file: {e}") })?;
            cam.validate()?;
            Ok(Some(cam))
        }
        None => Ok(None),
    }
}

/// The balance `ingest` would apply for these settings and gains (only
/// `AsShot` and `Manual` are known before the frame is ingested).
fn balance_of(params: &DevelopParams, wb_gains: Option<&[f64]>) -> Result<[f64; 3]> {
    Ok(match (&params.white_balance, wb_gains) {
        (WhiteBalanceMode::AsShot, Some(g)) => as_shot_from_gains(g)?,
        (WhiteBalanceMode::Manual { r, g, b }, _) if *g > 0.0 && *r > 0.0 && *b > 0.0 => [r / g, 1.0, b / g],
        _ => [1.0; 3],
    })
}

/// The plate's three taking filters `set_plate` would resolve for these
/// settings, this balance and this colour matrix: the same calls, so a
/// viewfinder that mixes the three positives on the GPU uses the still's
/// numbers.
pub fn plate_weights_impl(
    params: &DevelopParams,
    wb_gains: Option<&[f64]>,
    xyz_to_cam: Option<&[f64]>,
    filters: PlateFilters,
    space: PlateSpace,
) -> Result<CorePlateWeights> {
    let cam = camera_file_of(params)?;
    let wb = balance_of(params, wb_gains)?;
    let m = matrix_of(xyz_to_cam)?;
    let base = plate_base(cam.as_ref(), wb);
    let p = PlateParams { filters: filters.core(), space: space.core() };
    Ok(CorePlateWeights::resolve(&p, base, m.as_ref())?)
}

/// Stops within ±`LAMP_LIMIT`, finite.
fn check_lamp(name: &str, v: f64) -> Result<f64> {
    if !v.is_finite() || v.abs() > LAMP_LIMIT {
        return Err(MimizanError::Invalid {
            msg: format!("{name} lamp {v} not within ±{LAMP_LIMIT} stops")
        });
    }
    Ok(v)
}

/// The balanced weights `develop_impl` would mix with for these settings
/// and this frame's balance: the camera file's weights (or `params.weights`)
/// brought into the balanced channels, then the filter. Same calls, same
/// order as `negative_from_frame`, so a viewfinder that mixes elsewhere
/// (the GPU) uses the still's numbers.
pub fn mix_weights_impl(params: &DevelopParams, wb_gains: Option<&[f64]>) -> Result<CoreWeights> {
    let mut p = NegativeParams::default();
    if let Some(cam) = camera_file_of(params)? {
        p = p.with_camera_file(cam);
    }
    if let Some(w) = params.weights {
        p = p.with_weights(w.core(), params.weights_space.core());
    }
    p.weights.validate()?;
    // Only raw-space weights depend on the balance; mirror `ingest`'s choice.
    let wb = balance_of(params, wb_gains)?;
    let w = p.weights.to_balanced(p.weights_space, wb).filtered(params.filter.core());
    w.validate()?;
    Ok(w)
}

fn preview_planes(sep: &Separation, o: u16, long_edge: usize) -> PreviewPlanes {
    let lum = orient(&sep.lum, o);
    let (w, h) = (lum.width, lum.height);
    let (pw, ph) = fit_dims(w, h, long_edge, long_edge);
    let (pw, ph) = (pw.min(w), ph.min(h));
    let small = |p: &Plane| {
        let up = orient(p, o);
        if (up.width, up.height) == (pw, ph) {
            up
        } else {
            resize(&up, pw, ph)
        }
    };
    PreviewPlanes {
        lum: if (w, h) == (pw, ph) { lum } else { resize(&lum, pw, ph) },
        c1: small(&sep.c1),
        c2: small(&sep.c2),
    }
}

/// `lum + k1*c1 + k2*c2` on any three equally sized planes (the mix formula).
fn mix_planes(lum: &Plane, c1: &Plane, c2: &Plane, w: &CoreWeights) -> Plane {
    if w.is_native() {
        return lum.clone();
    }
    let k1 = w.g - w.r - w.b;
    let k2 = 2.0 * (w.r - w.b);
    let width = lum.width;
    let mut out = Plane::zeros(width, lum.height);
    out.data.par_chunks_mut(width).enumerate().for_each(|(y, row)| {
        let (l, a, b) = (lum.row(y), c1.row(y), c2.row(y));
        for x in 0..width {
            row[x] = l[x] + k1 * a[x] + k2 * b[x];
        }
    });
    out
}

/// The print map for these exposures. Preview, histogram, the 1:1 window and
/// the file all evaluate this map, so the screen and the file agree.
fn paper(soft: f64, hard: f64) -> Result<ToneMap> {
    Ok(Tone::Paper(split_grade(soft, hard)?).compile())
}

fn gray8(plane: &Plane, map: &ToneMap) -> Vec<u8> {
    plane.data.par_iter().map(|&v| (map.eval(v) * 255.0).round() as u8).collect()
}

/// The three positives of a plate as 8-bit RGB: channel `c` placed by its
/// lamp (`v · 2^lamp`, the print's placing), then the same paper as the
/// grey, interleaved. `lamps` are R, G, B.
fn rgb8(planes: &[Plane; 3], lamps: [f64; 3], map: &ToneMap) -> Vec<u8> {
    let n = planes[0].data.len();
    debug_assert!(planes.iter().all(|p| p.data.len() == n));
    let gains = lamps.map(|l| 2f64.powf(l));
    let mut out = vec![0u8; 3 * n];
    out.par_chunks_mut(3).enumerate().for_each(|(i, px)| {
        for c in 0..3 {
            let v = planes[c].data[i];
            let placed = if lamps[c] == 0.0 { v } else { v * gains[c] };
            px[c] = (map.eval(placed) * 255.0).round() as u8;
        }
    });
    out
}

/// The lamps by output channel R, G, B from the stored red and blue.
fn lamps_rgb(l: [f64; 2]) -> [f64; 3] {
    [l[0], 0.0, l[1]]
}

/// Map a rectangle of the upright image (after `orientation`) back to the
/// sensor frame of a `w`×`h` source, using the same table as `orient`.
fn source_rect(
    orientation: u16,
    w: usize,
    h: usize,
    x: usize,
    y: usize,
    rw: usize,
    rh: usize,
) -> (usize, usize, usize, usize) {
    let map = |ox: usize, oy: usize| -> (usize, usize) {
        match orientation {
            2 => (w - 1 - ox, oy),
            3 => (w - 1 - ox, h - 1 - oy),
            4 => (ox, h - 1 - oy),
            5 => (oy, ox),
            6 => (oy, h - 1 - ox),
            7 => (w - 1 - oy, h - 1 - ox),
            8 => (w - 1 - oy, ox),
            _ => (ox, oy),
        }
    };
    let corners = [map(x, y), map(x + rw - 1, y), map(x, y + rh - 1), map(x + rw - 1, y + rh - 1)];
    let x0 = corners.iter().map(|c| c.0).min().unwrap_or(0);
    let x1 = corners.iter().map(|c| c.0).max().unwrap_or(0);
    let y0 = corners.iter().map(|c| c.1).min().unwrap_or(0);
    let y1 = corners.iter().map(|c| c.1).max().unwrap_or(0);
    (x0, y0, x1 - x0 + 1, y1 - y0 + 1)
}

/// Rotate an 8-bit image of `C` interleaved channels 90° clockwise
/// `quarter_turns` times; returns the new (width, height, pixels). The core
/// keeps its copy for its own tests.
fn rotate8_cw<const C: usize>(w: usize, h: usize, g: &[u8], quarter_turns: u8) -> (usize, usize, Vec<u8>) {
    debug_assert_eq!(g.len(), w * h * C);
    let k = quarter_turns % 4;
    if k == 0 {
        return (w, h, g.to_vec());
    }
    let (ow, oh) = if k % 2 == 1 { (h, w) } else { (w, h) };
    let mut out = vec![0u8; ow * oh * C];
    for (oy, row) in out.chunks_mut((ow * C).max(1)).enumerate() {
        for (ox, o) in row.chunks_mut(C).enumerate() {
            let (sx, sy) = match k {
                1 => (oy, h - 1 - ox),
                2 => (w - 1 - ox, h - 1 - oy),
                _ => (w - 1 - oy, ox),
            };
            let i = (sy * w + sx) * C;
            o.copy_from_slice(&g[i..i + C]);
        }
    }
    (ow, oh, out)
}

fn crop(p: &Plane, x: usize, y: usize, w: usize, h: usize) -> Plane {
    let mut out = Plane::zeros(w, h);
    for r in 0..h {
        out.row_mut(r).copy_from_slice(&p.row(y + r)[x..x + w]);
    }
    out
}

/// The geometry of a 1:1 window: the asked rectangle in display
/// coordinates, clamped; the margin the peak's blur needs so it does not
/// see the window edge; the source rectangle in the sensor frame.
struct DetailWindow {
    o: u16,
    x0: usize,
    y0: usize,
    rw: usize,
    rh: usize,
    mx0: usize,
    my0: usize,
    /// Source rectangle (sensor frame) of the window plus margin.
    src: (usize, usize, usize, usize),
    map: ToneMap,
    peak: Peak,
    /// The peak's radius in the window's pixels: the window is 1:1, so the
    /// print's scale is 1 and the radius is the negative's.
    r_px: f64,
}

impl DetailWindow {
    #[allow(clippy::too_many_arguments)]
    fn new(
        g: &Inner,
        x: u32,
        y: u32,
        width: u32,
        height: u32,
        soft: f64,
        hard: f64,
        extra_quarter_turns: u8,
        peak_radius: f64,
        peak_amount: f64,
    ) -> Result<Self> {
        let o = rotated(g.orientation, extra_quarter_turns);
        let (sw, sh) = (g.sep.lum.width, g.sep.lum.height);
        let (dw, dh) = if matches!(o, 5..=8) { (sh, sw) } else { (sw, sh) };
        let x0 = (x as usize).min(dw.saturating_sub(1));
        let y0 = (y as usize).min(dh.saturating_sub(1));
        let rw = (width as usize).clamp(1, (dw - x0).max(1));
        let rh = (height as usize).clamp(1, (dh - y0).max(1));

        let map = paper(soft, hard)?;
        let peak = peak_of(peak_radius, peak_amount);
        let r_px = pr::peak_print_radius(peak.radius, 1.0);
        let margin = if peak.applies() { kernel_radius(r_px) + 1 } else { 0 };
        let mx0 = x0.saturating_sub(margin);
        let my0 = y0.saturating_sub(margin);
        let mx1 = (x0 + rw + margin).min(dw);
        let my1 = (y0 + rh + margin).min(dh);
        let src = source_rect(o, sw, sh, mx0, my0, mx1 - mx0, my1 - my0);
        Ok(Self { o, x0, y0, rw, rh, mx0, my0, src, map, peak, r_px })
    }

    /// The window (plus margin) of the three separation planes, sensor frame.
    fn crops(&self, g: &Inner) -> (Plane, Plane, Plane) {
        let (cx, cy, cw, ch) = self.src;
        (crop(&g.sep.lum, cx, cy, cw, ch), crop(&g.sep.c1, cx, cy, cw, ch), crop(&g.sep.c2, cx, cy, cw, ch))
    }

    /// One positive of the window: upright, placed by `lamp`, the paper,
    /// the peak on every pixel (no mask, as the print), the margin cut
    /// away. Print values in [0, 1], `rw × rh`.
    fn positive(&self, mixed: Plane, lamp: f64) -> Plane {
        let mut up = orient(&mixed, self.o);
        render(&mut up, lamp, &self.map);
        let encoded = if self.peak.applies() { unsharp(&up, self.r_px, self.peak.amount, None) } else { up };
        crop(&encoded, self.x0 - self.mx0, self.y0 - self.my0, self.rw, self.rh)
    }
}

impl Inner {
    fn upright_size(&self) -> (usize, usize) {
        let (w, h) = (self.sep.lum.width, self.sep.lum.height);
        if matches!(self.orientation, 5..=8) {
            (h, w)
        } else {
            (w, h)
        }
    }

    fn mask_plane(&self) -> Plane {
        Plane::from_vec(
            self.sep.lum.width,
            self.sep.lum.height,
            self.mask8.par_iter().map(|&v| f64::from(v) / 255.0).collect(),
        )
    }

    fn info(&self) -> DevelopInfo {
        let (uw, uh) = self.upright_size();
        let n = (self.sep.lum.width * self.sep.lum.height).max(1) as f64;
        DevelopInfo {
            width: self.sep.lum.width as u32,
            height: self.sep.lum.height as u32,
            upright_width: uw as u32,
            upright_height: uh as u32,
            orientation: self.orientation as u32,
            pattern: BayerPattern::from_phase(self.sep.phase),
            binning: self.binning,
            separation: self.separation_mode,
            wb: self.info.wb.to_vec(),
            wb_source: self.info.report.wb_source.clone(),
            noise_a: self.info.noise.a,
            noise_b: self.info.noise.b,
            weights: Weights::from_core(self.weights),
            weights_raw: Weights::from_core(self.weights.balanced_to_raw(self.info.wb)),
            filter: self.filter,
            default_weights: Weights::from_core(self.default_weights),
            saturated_fraction: self.info.report.saturated_dilated as f64 / n,
            defects: self.info.report.defects as u64,
            has_colour_matrix: self.xyz_to_cam.is_some(),
            plate: self.plate.map(|p| PlateState {
                weights: PlateWeights::from_core(&p.weights),
                space: PlateSpace::from_core(p.space),
                lamp_red: self.lamps[0],
                lamp_blue: self.lamps[1],
            }),
            timing: self.timing.clone(),
            json: serde_json::to_string(&self.info).unwrap_or_default(),
        }
    }

    /// The taking filters for this frame: the camera file's panchromatic
    /// response (or the native) behind the Wratten glass, the frame's own
    /// matrix for the camera set. Same call as `negative --plate`.
    fn resolve_plate(&self, filters: PlateFilters, space: PlateSpace) -> Result<CorePlateWeights> {
        let base = plate_base(self.camera_file.as_ref(), self.info.wb);
        let p = PlateParams { filters: filters.core(), space: space.core() };
        Ok(CorePlateWeights::resolve(&p, base, self.xyz_to_cam.as_ref())?)
    }

    /// The plate's three positives as `[red, green, blue]` of a mixed set
    /// of planes (the preview planes, or a window of the separation).
    fn plate_planes(lum: &Plane, c1: &Plane, c2: &Plane, w: &CorePlateWeights) -> [Plane; 3] {
        let [r, g, b] = w.weights();
        [mix_planes(lum, c1, c2, &r), mix_planes(lum, c1, c2, &g), mix_planes(lum, c1, c2, &b)]
    }

    /// One positive through the print pass: the full-resolution mix with
    /// `weights`, the sidecar as the mask, the recipe placed by `lamp`.
    fn render_positive(
        &self,
        weights: &CoreWeights,
        mask: &Plane,
        params: &PrintParams,
        orientation: u16,
        lamp: f64,
    ) -> PrintOutput {
        let plane = mix(&self.sep, weights);
        let mut q = params.clone();
        q.recipe.place += lamp;
        let src = PrintSource {
            plane: &plane,
            mask: Some(mask),
            orientation,
            name: "camera".into(),
            description: serde_json::to_value(&self.info).ok(),
        };
        pr::render_planes(&src, &q)
    }

    /// The print: one grey positive, or the three of the plate rendered one
    /// after the other (one full-resolution mix alive at a time beside the
    /// finished sheets) and written as one RGB file.
    fn export(&self, path: &Path, params: &PrintParams, turns: u8) -> Result<ExportInfo> {
        let t = Instant::now();
        let orientation = rotated(self.orientation, turns);
        let mask = self.mask_plane();
        let out = match self.plate {
            None => self.render_positive(&self.weights, &mask, params, orientation, 0.0),
            Some(p) => {
                let [wr, wg, wb] = p.weights.weights();
                let lamps = lamps_rgb(self.lamps);
                let mut r = self.render_positive(&wr, &mask, params, orientation, lamps[0]);
                let g = self.render_positive(&wg, &mask, params, orientation, lamps[1]);
                let b = self.render_positive(&wb, &mask, params, orientation, lamps[2]);
                if lamps.iter().any(|&l| l != 0.0) {
                    info!("lamps R {:+.2} G {:+.2} B {:+.2} stops", lamps[0], lamps[1], lamps[2]);
                }
                r.info.plate = Some(pr::PlatePrintInfo { filters: p.weights.filters, space: p.space, lamps });
                r.plate = Some(pr::PlateSheet { green: g.plane, blue: b.plane, space: p.space });
                r
            }
        };
        drop(mask);
        pr::write(&out, path, params)?;
        Ok(ExportInfo {
            path: path.display().to_string(),
            width: out.info.width as u32,
            height: out.info.height as u32,
            ms: t.elapsed().as_millis() as u64,
        })
    }

    /// The print map and the preview planes, or the error the caller returns.
    fn preview_and_paper(&self, soft: f64, hard: f64) -> Result<(&PreviewPlanes, ToneMap)> {
        let pv = self
            .preview
            .as_ref()
            .ok_or_else(|| MimizanError::Invalid { msg: "developed without preview planes".into() })?;
        Ok((pv, paper(soft, hard)?))
    }

    fn plate_or_err(&self) -> Result<PlateProcess> {
        self.plate.ok_or_else(|| MimizanError::Invalid {
            msg: "the development is not in the plate process".into(),
        })
    }
}

#[uniffi::export]
impl Developed {
    pub fn info(&self) -> DevelopInfo {
        lock_msg(self.inner.lock()).info()
    }

    /// Change the mix without separating again, and return to the
    /// monochrome process if the plate was on. `weights` are in `space`
    /// (raw weights are converted with this image's balance), then the
    /// filter is applied. Returns the balanced weights the mix now uses.
    pub fn remix(&self, weights: Weights, space: WeightSpace, filter: Filter) -> Result<Weights> {
        let mut g = lock_msg(self.inner.lock());
        let w = weights.core();
        w.validate()?;
        let w = w.to_balanced(space.core(), g.info.wb).filtered(filter.core());
        w.validate()?;
        g.weights = w;
        g.filter = filter;
        g.info.weights = w;
        g.info.filter = filter.core();
        g.info.weights_raw = w.balanced_to_raw(g.info.wb);
        g.plate = None;
        g.info.plate = None;
        Ok(Weights::from_core(w))
    }

    /// Turn the development into a three-colour plate (or change its
    /// filters / primaries): a re-mix, not a re-development. The monochrome
    /// mix stays stored; `remix` returns to it. Returns the three taking
    /// filters as resolved for this frame (`Camera` becomes `Channels`
    /// without a colour matrix).
    pub fn set_plate(&self, filters: PlateFilters, space: PlateSpace) -> Result<PlateWeights> {
        let mut g = lock_msg(self.inner.lock());
        let w = g.resolve_plate(filters, space)?;
        for (name, row) in ["red", "green", "blue"].iter().zip(w.rows) {
            info!(
                "plate {name}: weights R {:+.3} G {:+.3} B {:+.3} ({})",
                row[0],
                row[1],
                row[2],
                w.filters.name()
            );
        }
        g.plate = Some(PlateProcess { weights: w, space: space.core() });
        g.info.plate = Some(PlateInfo::new(&w, space.core(), g.sep.lum.height));
        Ok(PlateWeights::from_core(&w))
    }

    /// The projector's two lamps: stops of exposure added to the red and
    /// the blue positive, within ±`lamp_limit()`. Green is the reference.
    /// Preview, 1:1 window and every export of the plate follow.
    pub fn set_lamps(&self, red: f64, blue: f64) -> Result<()> {
        let lamps = [check_lamp("red", red)?, check_lamp("blue", blue)?];
        lock_msg(self.inner.lock()).lamps = lamps;
        Ok(())
    }

    /// The negative as the viewer shows it: current mix on the cached
    /// upright preview planes, the paper applied pixel by pixel (the same
    /// evaluation the file uses), no sharpening, then
    /// `extra_quarter_turns` clockwise. `soft` and `hard` are stops from
    /// the base exposure. In the plate process this is the luminance of
    /// the plate (the monochrome mix); `preview_rgb` is the colour.
    pub fn preview(&self, soft: f64, hard: f64, extra_quarter_turns: u8) -> Result<GrayImage> {
        let g = lock_msg(self.inner.lock());
        let (pv, map) = g.preview_and_paper(soft, hard)?;
        let plane = mix_planes(&pv.lum, &pv.c1, &pv.c2, &g.weights);
        let bytes = gray8(&plane, &map);
        let (w, h, data) = rotate8_cw::<1>(plane.width, plane.height, &bytes, extra_quarter_turns);
        Ok(GrayImage { width: w as u32, height: h as u32, data })
    }

    /// The plate as the viewer shows it: the three positives from the
    /// cached preview planes, each placed by its lamp and put through the
    /// same paper, interleaved RGB. Needs the plate process.
    pub fn preview_rgb(&self, soft: f64, hard: f64, extra_quarter_turns: u8) -> Result<RgbImage> {
        let g = lock_msg(self.inner.lock());
        let p = g.plate_or_err()?;
        let (pv, map) = g.preview_and_paper(soft, hard)?;
        let planes = Inner::plate_planes(&pv.lum, &pv.c1, &pv.c2, &p.weights);
        let bytes = rgb8(&planes, lamps_rgb(g.lamps), &map);
        let (w, h, data) = rotate8_cw::<3>(pv.lum.width, pv.lum.height, &bytes, extra_quarter_turns);
        Ok(RgbImage { width: w as u32, height: h as u32, data })
    }

    /// Three 256-bin histograms, R then G then B, of the plate's positives
    /// after the paper (preview planes, lamps applied). Needs the plate
    /// process; `histogram` stays the luminance's.
    pub fn histogram_rgb(&self, soft: f64, hard: f64) -> Result<Vec<u64>> {
        let g = lock_msg(self.inner.lock());
        let p = g.plate_or_err()?;
        let (pv, map) = g.preview_and_paper(soft, hard)?;
        let planes = Inner::plate_planes(&pv.lum, &pv.c1, &pv.c2, &p.weights);
        let lamps = lamps_rgb(g.lamps);
        let mut bins = vec![0u64; 3 * 256];
        for (c, plane) in planes.iter().enumerate() {
            let gain = 2f64.powf(lamps[c]);
            for &v in &plane.data {
                let placed = if lamps[c] == 0.0 { v } else { v * gain };
                bins[c * 256 + ((map.eval(placed) * 255.0).round() as usize).min(255)] += 1;
            }
        }
        Ok(bins)
    }

    /// 1:1 window of the full-resolution negative. The rectangle is in the
    /// coordinates of the displayed picture (file orientation plus
    /// `extra_quarter_turns`); it is clamped to the picture. A
    /// `peak_amount` > 0 sharpens the window the way the full-size export
    /// does (`peak_radius` in pixels of the negative, which at 1:1 are the
    /// window's), with a margin so the blur does not see the window edge.
    #[allow(clippy::too_many_arguments)] // UniFFI method: a flat argument list is the interface
    pub fn detail(
        &self,
        x: u32,
        y: u32,
        width: u32,
        height: u32,
        soft: f64,
        hard: f64,
        extra_quarter_turns: u8,
        peak_radius: f64,
        peak_amount: f64,
    ) -> Result<GrayImage> {
        let g = lock_msg(self.inner.lock());
        let win = DetailWindow::new(
            &g,
            x,
            y,
            width,
            height,
            soft,
            hard,
            extra_quarter_turns,
            peak_radius,
            peak_amount,
        )?;
        let (lum, c1, c2) = win.crops(&g);
        let mixed = if g.weights.is_native() { lum } else { mix_planes(&lum, &c1, &c2, &g.weights) };
        let plane = win.positive(mixed, 0.0);
        let data = plane.data.par_iter().map(|&v| (v.clamp(0.0, 1.0) * 255.0).round() as u8).collect();
        Ok(GrayImage { width: win.rw as u32, height: win.rh as u32, data })
    }

    /// The 1:1 window of the plate: the same geometry, margin and
    /// sharpening as `detail`, three positives placed by their lamps,
    /// interleaved RGB. Needs the plate process.
    #[allow(clippy::too_many_arguments)] // UniFFI method: a flat argument list is the interface
    pub fn detail_rgb(
        &self,
        x: u32,
        y: u32,
        width: u32,
        height: u32,
        soft: f64,
        hard: f64,
        extra_quarter_turns: u8,
        peak_radius: f64,
        peak_amount: f64,
    ) -> Result<RgbImage> {
        let g = lock_msg(self.inner.lock());
        let p = g.plate_or_err()?;
        let win = DetailWindow::new(
            &g,
            x,
            y,
            width,
            height,
            soft,
            hard,
            extra_quarter_turns,
            peak_radius,
            peak_amount,
        )?;
        let (lum, c1, c2) = win.crops(&g);
        let lamps = lamps_rgb(g.lamps);
        let rows = p.weights.weights();
        let mut planes: [Option<Plane>; 3] = [None, None, None];
        for c in 0..3 {
            let mixed = mix_planes(&lum, &c1, &c2, &rows[c]);
            planes[c] = Some(win.positive(mixed, lamps[c]));
        }
        let [Some(r), Some(gr), Some(b)] = planes else { unreachable!() };
        let n = r.data.len();
        let mut data = vec![0u8; 3 * n];
        data.par_chunks_mut(3).enumerate().for_each(|(i, px)| {
            px[0] = (r.data[i].clamp(0.0, 1.0) * 255.0).round() as u8;
            px[1] = (gr.data[i].clamp(0.0, 1.0) * 255.0).round() as u8;
            px[2] = (b.data[i].clamp(0.0, 1.0) * 255.0).round() as u8;
        });
        Ok(RgbImage { width: win.rw as u32, height: win.rh as u32, data })
    }

    /// 256-bin histogram of the current mix after the paper, from the preview
    /// planes (the viewer's picture, not the TIFF's exact clipping counts).
    pub fn histogram(&self, soft: f64, hard: f64) -> Result<Vec<u64>> {
        let g = lock_msg(self.inner.lock());
        let pv = g
            .preview
            .as_ref()
            .ok_or_else(|| MimizanError::Invalid { msg: "developed without preview planes".into() })?;
        let map = paper(soft, hard)?;
        let plane = mix_planes(&pv.lum, &pv.c1, &pv.c2, &g.weights);
        let mut bins = vec![0u64; 256];
        for &v in &plane.data {
            // `as usize` saturates and maps NaN to 0; the `min` keeps a
            // display value a hair above 1 inside the table.
            bins[((map.eval(v) * 255.0).round() as usize).min(255)] += 1;
        }
        Ok(bins)
    }

    /// Peak's radius and amount for this negative, measured on the
    /// native-size print at these paper exposures the way the desktop does
    /// (`peak::tune` in the core): the current mix, or the plate's green
    /// positive; nothing sharpened for the render, no border, no scaling.
    /// The result is already inside the range `export_jpeg` accepts. Costs
    /// one full-resolution print pass.
    pub fn measure_peak(&self, soft: f64, hard: f64) -> Result<PeakMeasured> {
        let g = lock_msg(self.inner.lock());
        let t = Instant::now();
        let params = PrintParams { recipe: export_recipe(soft, hard, 0.0, 0.0)?, ..Default::default() };
        let weights = match g.plate {
            Some(p) => p.weights.weights()[1],
            None => g.weights,
        };
        let mask = g.mask_plane();
        let out = g.render_positive(&weights, &mask, &params, g.orientation, 0.0);
        drop(mask);
        let samples: Vec<f32> = out.plane.data.par_iter().map(|&v| v as f32).collect();
        let (w, h) = (out.plane.width, out.plane.height);
        drop(out);
        let tuned = mimizan_core::peak::tune(&samples, w, h);
        let (radius, amount) = Peak::clamp_measured(tuned.radius, tuned.amount);
        info!(
            "peak measured: radius {radius:.2} px, amount {amount:.2}, edge {} in {} ms",
            if tuned.found { "found" } else { "not found" },
            t.elapsed().as_millis()
        );
        Ok(PeakMeasured { radius, amount, found: tuned.found })
    }

    /// Print as JPEG (8-bit grey, quality 100, grey ICC) at full size:
    /// the paper applied, then Peak's high-pass on the picture when
    /// `peak_amount` > 0 (`peak_radius` σ in pixels of the negative, the
    /// pair `measure_peak` returns or the user's; no mask, as the desktop
    /// print). `soft` and `hard` are stops from the base exposure. In the
    /// plate process the file is 8-bit RGB with the plate's ICC profile:
    /// the three positives through the same pass, placed by their lamps.
    #[allow(clippy::too_many_arguments)] // UniFFI method: a flat argument list is the interface
    pub fn export_jpeg(
        &self,
        path: String,
        soft: f64,
        hard: f64,
        extra_quarter_turns: u8,
        peak_radius: f64,
        peak_amount: f64,
    ) -> Result<ExportInfo> {
        let g = lock_msg(self.inner.lock());
        let params = PrintParams {
            recipe: export_recipe(soft, hard, peak_radius, peak_amount)?,
            ..Default::default()
        };
        let p = PathBuf::from(path);
        if !pr::is_jpeg_path(&p) {
            return Err(MimizanError::Invalid { msg: "export_jpeg needs a .jpg path".into() });
        }
        g.export(&p, &params, extra_quarter_turns)
    }

    /// Print as 16-bit grey TIFF, gamma 2.2 ICC, full resolution, the paper
    /// applied, the peak as in `export_jpeg`. In the plate process: 16-bit
    /// RGB with the plate's ICC profile.
    #[allow(clippy::too_many_arguments)] // UniFFI method: a flat argument list is the interface
    pub fn export_tiff16(
        &self,
        path: String,
        soft: f64,
        hard: f64,
        extra_quarter_turns: u8,
        peak_radius: f64,
        peak_amount: f64,
    ) -> Result<ExportInfo> {
        let g = lock_msg(self.inner.lock());
        let params = PrintParams {
            recipe: export_recipe(soft, hard, peak_radius, peak_amount)?,
            ..Default::default()
        };
        let p = PathBuf::from(path);
        if pr::is_jpeg_path(&p) {
            return Err(MimizanError::Invalid { msg: "export_tiff16 needs a .tif path".into() });
        }
        g.export(&p, &params, extra_quarter_turns)
    }

    /// The linear negative exactly as the desktop CLI writes it
    /// (`mimizan negative`): 16-bit grey, linear ICC, orientation as a tag,
    /// JSON description, plus the `<stem>.mask.tif` sidecar. In the plate
    /// process it is the stacked plate (`negative --plate`: blue, green,
    /// red frames, the `plate` object in the description), which
    /// `mimizan print` on the desktop prints in colour. The sidecar is one
    /// frame, the same for all three.
    pub fn export_negative(&self, path: String) -> Result<ExportInfo> {
        let g = lock_msg(self.inner.lock());
        let t = Instant::now();
        let p = PathBuf::from(path);
        let plane = match g.plate {
            Some(pp) => mix_plate_stacked(&g.sep, &pp.weights)?,
            None => mix(&g.sep, &g.weights),
        };
        let desc =
            serde_json::to_string(&g.info).map_err(|e| MimizanError::Internal { msg: e.to_string() })?;
        write_gray16(
            &p,
            &plane,
            &TiffMeta { description: &desc, trc: GrayTrc::Linear, orientation: g.orientation, dpi: None },
        )?;
        let stem = p.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
        let side = p.with_file_name(format!("{stem}.mask.tif"));
        // One frame's mask, also under a three-frame plate.
        let (mw, mh) = (g.sep.lum.width, g.sep.lum.height);
        write_gray8(&side, mw, mh, &g.mask8, "mimizan mask: 255*max(M), 255=saturated")?;
        Ok(ExportInfo {
            path: p.display().to_string(),
            width: plane.width as u32,
            height: plane.height as u32,
            ms: t.elapsed().as_millis() as u64,
        })
    }
}

impl Developed {
    /// 16-bit samples of the current full-resolution mix, little-endian,
    /// sensor frame (host tests).
    pub fn negative_u16_le(&self) -> Vec<u8> {
        let g = lock_msg(self.inner.lock());
        let plane = mix(&g.sep, &g.weights);
        let mut out = vec![0u8; plane.data.len() * 2];
        out.par_chunks_mut(2).zip(plane.data.par_iter()).for_each(|(o, &v)| {
            let q = (v.clamp(0.0, 1.0) * 65535.0).round() as u16;
            o.copy_from_slice(&q.to_le_bytes());
        });
        out
    }

    /// Drop the preview planes (about 3 x 8 bytes per preview pixel).
    pub fn release_preview(&self) {
        lock_msg(self.inner.lock()).preview = None;
    }

    /// Full-resolution mix plane (host tests).
    pub fn mix_plane(&self) -> Plane {
        let g = lock_msg(self.inner.lock());
        mix(&g.sep, &g.weights)
    }
}
