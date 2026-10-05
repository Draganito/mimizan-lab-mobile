//! A developed frame kept in memory: the full-resolution separation (so the
//! weights can be re-mixed without separating again), a downscaled upright
//! copy for the interactive preview, and the print exports. Mirrors what the
//! desktop GUI's `Session` does, with the same core calls.

use crate::frame::{as_shot_from_gains, bin_cfa_2x, to_raw_frame};
use crate::types::*;
use mimizan_core::calib::CameraFile;
use mimizan_core::curve::Curve;
use mimizan_core::icc::GrayTrc;
use mimizan_core::mix::{mix, Weights as CoreWeights};
use mimizan_core::pipeline::{negative_from_frame, NegativeInfo, NegativeParams};
use mimizan_core::plane::Plane;
use mimizan_core::print::{
    self as pr, orient, rotate_gray8_cw, rotated, PrintParams, PrintSize, PrintSource,
};
use mimizan_core::resize::{fit_dims, resize};
use mimizan_core::screen::ScreenSharpen;
use mimizan_core::separate::Separation;
use mimizan_core::tiffout::{write_gray16, write_gray8, TiffMeta};
use mimizan_core::usm::unsharp;
use rayon::prelude::*;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::Instant;
use tracing::info;

/// Upright, downscaled separation for the preview (never sharpened).
struct PreviewPlanes {
    lum: Plane,
    c1: Plane,
    c2: Plane,
}

struct Inner {
    sep: Separation,
    /// 8-bit sidecar: `round(255*mask_max)`, 255 where saturated.
    mask8: Vec<u8>,
    orientation: u16,
    info: NegativeInfo,
    binning: u32,
    separation_mode: SeparationMode,
    weights: CoreWeights,
    filter: Filter,
    default_weights: CoreWeights,
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
        n => {
            return Err(MimizanError::Invalid {
                msg: format!("binning {n} not supported (1 or 2)"),
            })
        }
    };

    let mut p = NegativeParams {
        keep_separation: true,
        ..NegativeParams::default()
    };
    p.ingest.wb = params.white_balance.core();
    p.ingest.fix_defects = params.fix_defects;
    p.separate.mask = params.separation.mask_mode();
    if params.reconstruct {
        p.separate.reconstruct = Some(Default::default());
    }
    if let Some(json) = &params.camera_file_json {
        let cam: CameraFile = serde_json::from_str(json).map_err(|e| MimizanError::Invalid {
            msg: format!("camera file: {e}"),
        })?;
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

    let mut neg = negative_from_frame(&frame, &p, 0)?;
    drop(frame);
    let mut sep = neg
        .separation
        .take()
        .ok_or_else(|| MimizanError::Internal {
            msg: "separation missing".into(),
        })?;
    // The sidecar is already quantised; the f64 mask plane is not needed again.
    sep.mask_max = Plane::zeros(0, 0);
    let weights = neg.info.weights;
    let default_weights = camera_default.0.to_balanced(camera_default.1, neg.info.wb);

    let t = Instant::now();
    let preview = if params.preview_long_edge > 0 {
        Some(preview_planes(
            &sep,
            neg.orientation,
            params.preview_long_edge as usize,
        ))
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
            preview,
            timing,
        }),
    })
}

/// The balanced weights `develop_impl` would mix with for these settings
/// and this frame's balance: the camera file's weights (or `params.weights`)
/// brought into the balanced channels, then the filter. Same calls, same
/// order as `negative_from_frame`, so a viewfinder that mixes elsewhere
/// (the GPU) uses the still's numbers.
pub fn mix_weights_impl(params: &DevelopParams, wb_gains: Option<&[f64]>) -> Result<CoreWeights> {
    let mut p = NegativeParams::default();
    if let Some(json) = &params.camera_file_json {
        let cam: CameraFile = serde_json::from_str(json).map_err(|e| MimizanError::Invalid {
            msg: format!("camera file: {e}"),
        })?;
        cam.validate()?;
        p = p.with_camera_file(cam);
    }
    if let Some(w) = params.weights {
        p = p.with_weights(w.core(), params.weights_space.core());
    }
    p.weights.validate()?;
    // Only raw-space weights depend on the balance; mirror `ingest`'s choice.
    let wb = match (&params.white_balance, wb_gains) {
        (WhiteBalanceMode::AsShot, Some(g)) => as_shot_from_gains(g)?,
        (WhiteBalanceMode::Manual { r, g, b }, _) if *g > 0.0 && *r > 0.0 && *b > 0.0 => {
            [r / g, 1.0, b / g]
        }
        _ => [1.0; 3],
    };
    let w = p
        .weights
        .to_balanced(p.weights_space, wb)
        .filtered(params.filter.core());
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
        lum: if (w, h) == (pw, ph) {
            lum
        } else {
            resize(&lum, pw, ph)
        },
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
    out.data
        .par_chunks_mut(width)
        .enumerate()
        .for_each(|(y, row)| {
            let (l, a, b) = (lum.row(y), c1.row(y), c2.row(y));
            for x in 0..width {
                row[x] = l[x] + k1 * a[x] + k2 * b[x];
            }
        });
    out
}

fn gray8(plane: &Plane, curve: &Curve) -> Vec<u8> {
    plane
        .data
        .par_iter()
        .map(|&v| (curve.eval(v) * 255.0).round() as u8)
        .collect()
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
    let corners = [
        map(x, y),
        map(x + rw - 1, y),
        map(x, y + rh - 1),
        map(x + rw - 1, y + rh - 1),
    ];
    let x0 = corners.iter().map(|c| c.0).min().unwrap_or(0);
    let x1 = corners.iter().map(|c| c.0).max().unwrap_or(0);
    let y0 = corners.iter().map(|c| c.1).min().unwrap_or(0);
    let y1 = corners.iter().map(|c| c.1).max().unwrap_or(0);
    (x0, y0, x1 - x0 + 1, y1 - y0 + 1)
}

fn crop(p: &Plane, x: usize, y: usize, w: usize, h: usize) -> Plane {
    let mut out = Plane::zeros(w, h);
    for r in 0..h {
        out.row_mut(r).copy_from_slice(&p.row(y + r)[x..x + w]);
    }
    out
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
            self.mask8
                .par_iter()
                .map(|&v| f64::from(v) / 255.0)
                .collect(),
        )
    }

    /// Window of the sidecar as a plane, without materialising the whole mask.
    fn mask_crop(&self, x: usize, y: usize, w: usize, h: usize) -> Plane {
        let stride = self.sep.lum.width;
        let mut out = Plane::zeros(w, h);
        for r in 0..h {
            let src = &self.mask8[(y + r) * stride + x..(y + r) * stride + x + w];
            for (o, &v) in out.row_mut(r).iter_mut().zip(src) {
                *o = f64::from(v) / 255.0;
            }
        }
        out
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
            reconstruct: self.info.reconstruct,
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
            timing: self.timing.clone(),
            json: serde_json::to_string(&self.info).unwrap_or_default(),
        }
    }

    /// Full-resolution mix with the current weights and the sidecar as a
    /// plane, ready for the print path.
    fn print_source(&self, turns: u8) -> (Plane, Plane, u16) {
        let plane = mix(&self.sep, &self.weights);
        let mask = self.mask_plane();
        (plane, mask, rotated(self.orientation, turns))
    }

    fn export(&self, path: &Path, params: &PrintParams, turns: u8) -> Result<ExportInfo> {
        let t = Instant::now();
        let (plane, mask, orientation) = self.print_source(turns);
        let src = PrintSource {
            plane: &plane,
            mask: Some(&mask),
            orientation,
            name: "camera".into(),
            description: serde_json::to_value(&self.info).ok(),
        };
        let out = pr::render_planes(&src, params);
        drop(plane);
        drop(mask);
        pr::write(&out, path, params)?;
        Ok(ExportInfo {
            path: path.display().to_string(),
            width: out.info.width as u32,
            height: out.info.height as u32,
            ms: t.elapsed().as_millis() as u64,
        })
    }
}

#[uniffi::export]
impl Developed {
    pub fn info(&self) -> DevelopInfo {
        lock_msg(self.inner.lock()).info()
    }

    /// Change the mix without separating again. `weights` are in `space`
    /// (raw weights are converted with this image's balance), then the
    /// filter is applied. Returns the balanced weights the mix now uses.
    pub fn remix(&self, weights: Weights, space: WeightSpace, filter: Filter) -> Result<Weights> {
        let mut g = lock_msg(self.inner.lock());
        let w = weights.core();
        w.validate()?;
        let w = w
            .to_balanced(space.core(), g.info.wb)
            .filtered(filter.core());
        w.validate()?;
        g.weights = w;
        g.filter = filter;
        g.info.weights = w;
        g.info.filter = filter.core();
        g.info.weights_raw = w.balanced_to_raw(g.info.wb);
        Ok(Weights::from_core(w))
    }

    /// The negative as the viewer shows it: current mix on the cached
    /// upright preview planes, `look` applied, no sharpening, then
    /// `extra_quarter_turns` clockwise.
    pub fn preview(&self, look: Look, extra_quarter_turns: u8) -> Result<GrayImage> {
        let g = lock_msg(self.inner.lock());
        let pv = g.preview.as_ref().ok_or_else(|| MimizanError::Invalid {
            msg: "developed without preview planes".into(),
        })?;
        let curve = Curve::from_look(&look.to_file()?);
        let plane = mix_planes(&pv.lum, &pv.c1, &pv.c2, &g.weights);
        let bytes = gray8(&plane, &curve);
        let (w, h, data) = rotate_gray8_cw(plane.width, plane.height, &bytes, extra_quarter_turns);
        Ok(GrayImage {
            width: w as u32,
            height: h as u32,
            data,
        })
    }

    /// 1:1 window of the full-resolution negative. The rectangle is in the
    /// coordinates of the displayed picture (file orientation plus
    /// `extra_quarter_turns`); it is clamped to the picture. `usm_amount`
    /// > 0 sharpens the window the way the full-size export would (same
    /// radius, saturation mask respected), with a margin so the blur does
    /// not see the window edge.
    #[allow(clippy::too_many_arguments)] // UniFFI method: a flat argument list is the interface
    pub fn detail(
        &self,
        x: u32,
        y: u32,
        width: u32,
        height: u32,
        look: Look,
        extra_quarter_turns: u8,
        usm_amount: f64,
    ) -> Result<GrayImage> {
        let g = lock_msg(self.inner.lock());
        let o = rotated(g.orientation, extra_quarter_turns);
        let (sw, sh) = (g.sep.lum.width, g.sep.lum.height);
        let (dw, dh) = if matches!(o, 5..=8) {
            (sh, sw)
        } else {
            (sw, sh)
        };
        let x0 = (x as usize).min(dw.saturating_sub(1));
        let y0 = (y as usize).min(dh.saturating_sub(1));
        let rw = (width as usize).clamp(1, dw - x0);
        let rh = (height as usize).clamp(1, dh - y0);

        // Radius the export would use, from the same geometry.
        let usm = usm_amount.clamp(0.0, 1.5);
        let r_px = if usm > 0.0 {
            let params = PrintParams {
                look: look.to_file()?,
                usm_amount: usm,
                ..Default::default()
            };
            pr::geometry(sw, sh, o, &params).3
        } else {
            0.0
        };
        let margin = if usm > 0.0 {
            (3.0 * r_px).ceil() as usize + 1
        } else {
            0
        };
        let mx0 = x0.saturating_sub(margin);
        let my0 = y0.saturating_sub(margin);
        let mx1 = (x0 + rw + margin).min(dw);
        let my1 = (y0 + rh + margin).min(dh);
        let (mw, mh) = (mx1 - mx0, my1 - my0);

        let (cx, cy, cw, ch) = source_rect(o, sw, sh, mx0, my0, mw, mh);
        let lum = crop(&g.sep.lum, cx, cy, cw, ch);
        let mixed = if g.weights.is_native() {
            lum
        } else {
            let c1 = crop(&g.sep.c1, cx, cy, cw, ch);
            let c2 = crop(&g.sep.c2, cx, cy, cw, ch);
            mix_planes(&lum, &c1, &c2, &g.weights)
        };
        let up = orient(&mixed, o);
        let curve = Curve::from_look(&look.to_file()?);
        let encoded = if usm > 0.0 {
            let mask = orient(&g.mask_crop(cx, cy, cw, ch), o);
            unsharp(&curve.apply(&up), r_px, usm, Some(&mask))
        } else {
            curve.apply(&up)
        };
        // Cut the margin away again.
        let window = crop(&encoded, x0 - mx0, y0 - my0, rw, rh);
        let data = window
            .data
            .par_iter()
            .map(|&v| (v.clamp(0.0, 1.0) * 255.0).round() as u8)
            .collect();
        Ok(GrayImage {
            width: rw as u32,
            height: rh as u32,
            data,
        })
    }

    /// 256-bin histogram of the current mix after `look`, from the preview
    /// planes (the viewer's picture, not the TIFF's exact clipping counts).
    pub fn histogram(&self, look: Look) -> Result<Vec<u64>> {
        let g = lock_msg(self.inner.lock());
        let pv = g.preview.as_ref().ok_or_else(|| MimizanError::Invalid {
            msg: "developed without preview planes".into(),
        })?;
        let curve = Curve::from_look(&look.to_file()?);
        let plane = mix_planes(&pv.lum, &pv.c1, &pv.c2, &g.weights);
        let mut bins = vec![0u64; 256];
        for &v in &plane.data {
            bins[(curve.eval(v) * 255.0).round() as usize] += 1;
        }
        Ok(bins)
    }

    /// Print as JPEG (8-bit grey, quality 100, grey ICC): `look` applied,
    /// fitted into `long_edge_px` when given, and either screen compensation
    /// (exact inverse of the known resampling and display losses, gain cap
    /// 2x; the desktop export's `_2048px.jpg`), unsharp masking with
    /// `usm_amount` (0..1.5, radius from the print geometry, saturation
    /// masked), Richardson–Lucy on every pixel (`deconvolution`,
    /// `deconv_iterations` passes, same radius, no mask) followed by USM
    /// when `usm_amount` > 0, or nothing sharpened. Screen compensation
    /// wins over both.
    #[allow(clippy::too_many_arguments)] // UniFFI method: a flat argument list is the interface
    pub fn export_jpeg(
        &self,
        path: String,
        look: Look,
        long_edge_px: Option<u32>,
        screen_compensation: bool,
        extra_quarter_turns: u8,
        usm_amount: f64,
        deconvolution: bool,
        deconv_iterations: u32,
    ) -> Result<ExportInfo> {
        let g = lock_msg(self.inner.lock());
        let params = PrintParams {
            look: look.to_file()?,
            size: long_edge_px.map(|n| PrintSize::LongEdgePx(n.max(1) as usize)),
            screen: screen_compensation.then(ScreenSharpen::default),
            usm_amount: usm_amount.clamp(0.0, 1.5),
            deconvolution,
            deconv_iterations: if deconvolution {
                deconv_iterations.clamp(1, 10)
            } else {
                0
            },
            ..Default::default()
        };
        let p = PathBuf::from(path);
        if !pr::is_jpeg_path(&p) {
            return Err(MimizanError::Invalid {
                msg: "export_jpeg needs a .jpg path".into(),
            });
        }
        g.export(&p, &params, extra_quarter_turns)
    }

    /// Print as 16-bit grey TIFF, gamma 2.2 ICC, full resolution, `look`
    /// applied. Richardson–Lucy runs first when `deconvolution` is set,
    /// then USM when `usm_amount` > 0.
    pub fn export_tiff16(
        &self,
        path: String,
        look: Look,
        extra_quarter_turns: u8,
        usm_amount: f64,
        deconvolution: bool,
        deconv_iterations: u32,
    ) -> Result<ExportInfo> {
        let g = lock_msg(self.inner.lock());
        let params = PrintParams {
            look: look.to_file()?,
            usm_amount: usm_amount.clamp(0.0, 1.5),
            deconvolution,
            deconv_iterations: if deconvolution {
                deconv_iterations.clamp(1, 10)
            } else {
                0
            },
            ..Default::default()
        };
        let p = PathBuf::from(path);
        if pr::is_jpeg_path(&p) {
            return Err(MimizanError::Invalid {
                msg: "export_tiff16 needs a .tif path".into(),
            });
        }
        g.export(&p, &params, extra_quarter_turns)
    }

    /// The linear negative exactly as the desktop CLI writes it
    /// (`mimizan negative`): 16-bit grey, linear ICC, orientation as a tag,
    /// JSON description, plus the `<stem>.mask.tif` sidecar. For comparing
    /// on-device and desktop results on the same DNG.
    pub fn export_negative(&self, path: String) -> Result<ExportInfo> {
        let g = lock_msg(self.inner.lock());
        let t = Instant::now();
        let p = PathBuf::from(path);
        let plane = mix(&g.sep, &g.weights);
        let desc = serde_json::to_string(&g.info)
            .map_err(|e| MimizanError::Internal { msg: e.to_string() })?;
        write_gray16(
            &p,
            &plane,
            &TiffMeta {
                description: &desc,
                trc: GrayTrc::Linear,
                orientation: g.orientation,
                dpi: None,
            },
        )?;
        let stem = p
            .file_stem()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_default();
        let side = p.with_file_name(format!("{stem}.mask.tif"));
        write_gray8(
            &side,
            plane.width,
            plane.height,
            &g.mask8,
            "mimizan mask: 255*max(M), 255=saturated",
        )?;
        Ok(ExportInfo {
            path: p.display().to_string(),
            width: plane.width as u32,
            height: plane.height as u32,
            ms: t.elapsed().as_millis() as u64,
        })
    }

    /// 16-bit samples of the current full-resolution mix (`round(clamp(v)*65535)`,
    /// little-endian), sensor frame, for tests and for callers that want to
    /// write their own files.
    pub fn negative_u16_le(&self) -> Vec<u8> {
        let g = lock_msg(self.inner.lock());
        let plane = mix(&g.sep, &g.weights);
        let mut out = vec![0u8; plane.data.len() * 2];
        out.par_chunks_mut(2)
            .zip(plane.data.par_iter())
            .for_each(|(o, &v)| {
                let q = (v.clamp(0.0, 1.0) * 65535.0).round() as u16;
                o.copy_from_slice(&q.to_le_bytes());
            });
        out
    }

    /// Drop the preview planes (about 3 x 8 bytes per preview pixel) when
    /// the viewer is closed but exports may still follow.
    pub fn release_preview(&self) {
        lock_msg(self.inner.lock()).preview = None;
    }
}

impl Developed {
    /// Full-resolution mix plane (host tests).
    pub fn mix_plane(&self) -> Plane {
        let g = lock_msg(self.inner.lock());
        mix(&g.sep, &g.weights)
    }
}
