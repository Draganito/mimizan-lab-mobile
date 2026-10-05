//! `RawInput` (Camera2 buffer + metadata) -> `mimizan_core::decode::RawFrame`,
//! plus the optional CFA-preserving 2x binning.

use crate::types::{MimizanError, RawInput, Result};
use mimizan_core::decode::{Exposure, RawData, RawFrame, Rect, SensorKind};
use rayon::prelude::*;

/// As-shot balance multipliers (G = 1) from `COLOR_CORRECTION_GAINS`: three
/// values R, G, B or the Camera2 `RggbChannelVector` order R, G_even, G_odd, B.
pub fn as_shot_from_gains(g: &[f64]) -> Result<[f64; 3]> {
    let positive = g.iter().all(|v| v.is_finite() && *v > 0.0);
    match g.len() {
        3 if positive => Ok([g[0] / g[1], 1.0, g[2] / g[1]]),
        4 if positive => {
            let g_mean = 0.5 * (g[1] + g[2]);
            Ok([g[0] / g_mean, 1.0, g[3] / g_mean])
        }
        _ => Err(MimizanError::Invalid { msg: "wb_gains needs 3 (RGB) or 4 (RGGB) positive values".into() }),
    }
}

/// Build the frame the pipeline ingests. Validates sizes and levels; the
/// pixel data is copied once (bytes -> u16).
pub fn to_raw_frame(input: &RawInput) -> Result<RawFrame> {
    let (w, h) = (input.width as usize, input.height as usize);
    if w < 4 || h < 4 {
        return Err(MimizanError::Invalid { msg: format!("frame {w}x{h} is too small") });
    }
    if input.data.len() != w * h * 2 {
        return Err(MimizanError::Invalid {
            msg: format!("data has {} bytes, expected {}x{}x2 = {}", input.data.len(), w, h, w * h * 2),
        });
    }
    if input.black_tile.len() != 4 && input.black_tile.len() != 1 {
        return Err(MimizanError::Invalid {
            msg: format!("black_tile needs 1 or 4 values, got {}", input.black_tile.len()),
        });
    }
    if input.white_level <= 0.0 || !input.white_level.is_finite() {
        return Err(MimizanError::Invalid {
            msg: format!("white level {} is not positive", input.white_level),
        });
    }
    let black_tile: [f64; 4] = if input.black_tile.len() == 1 {
        [input.black_tile[0]; 4]
    } else {
        [input.black_tile[0], input.black_tile[1], input.black_tile[2], input.black_tile[3]]
    };
    for b in black_tile {
        if !b.is_finite() || b < 0.0 || b >= input.white_level {
            return Err(MimizanError::Invalid {
                msg: format!("black level {b} outside [0, white {})", input.white_level),
            });
        }
    }

    let crop = match input.crop {
        Some(r) => {
            let rect = Rect { x: r.x as usize, y: r.y as usize, w: r.width as usize, h: r.height as usize };
            if rect.w < 4 || rect.h < 4 || rect.x + rect.w > w || rect.y + rect.h > h {
                return Err(MimizanError::Invalid {
                    msg: format!("crop {}x{}+{}+{} does not fit {}x{}", rect.w, rect.h, rect.x, rect.y, w, h),
                });
            }
            rect
        }
        None => Rect { x: 0, y: 0, w, h },
    };

    let wb_as_shot = match &input.wb_gains {
        Some(g) => Some(as_shot_from_gains(g)?),
        None => None,
    };

    let xyz_to_cam = match &input.xyz_to_cam {
        Some(m) if m.len() == 9 && m.iter().all(|v| v.is_finite()) && m.iter().any(|v| *v != 0.0) => {
            Some([[m[0], m[1], m[2]], [m[3], m[4], m[5]], [m[6], m[7], m[8]]])
        }
        Some(m) if m.len() == 9 => None,
        Some(_) => return Err(MimizanError::Invalid { msg: "xyz_to_cam needs 9 values".into() }),
        None => None,
    };

    let exposure = match &input.exposure {
        Some(e) => Exposure {
            iso: e.iso,
            time: e.exposure_ns.map(exposure_rational),
            fnumber: e.f_number,
            focal_mm: e.focal_mm,
            lens: e.lens.clone(),
            captured: None,
        },
        None => Exposure::default(),
    };

    let samples: Vec<u16> = input
        .data
        .par_chunks_exact(2 * w)
        .flat_map_iter(|row| row.as_chunks::<2>().0.iter().map(|p| u16::from_le_bytes(*p)))
        .collect();

    let bits = ((input.white_level + 1.0).log2().ceil() as usize).clamp(8, 16);
    let orientation = if (1..=8).contains(&input.orientation) { input.orientation as u16 } else { 0 };

    Ok(RawFrame {
        make: input.make.clone(),
        model: input.model.clone(),
        clean_make: input.make.trim().to_string(),
        clean_model: input.model.trim().to_string(),
        width: w,
        height: h,
        bits,
        kind: SensorKind::Bayer(input.pattern.phase()),
        black_tile,
        white_rgb: [input.white_level; 3],
        crop,
        orientation,
        xyz_to_cam,
        wb_as_shot,
        exposure,
        data: RawData::Integer(samples),
    })
}

/// Nanoseconds -> EXIF-style rational (n, d).
fn exposure_rational(ns: u64) -> (u32, u32) {
    if ns == 0 {
        return (0, 1);
    }
    if ns >= 1_000_000_000 {
        // >= 1 s: whole milliseconds over 1000.
        let ms = ns / 1_000_000;
        return (ms.min(u32::MAX as u64) as u32, 1000);
    }
    // Fractions of a second: 1/d with d rounded.
    let d = (1e9 / ns as f64).round().max(1.0);
    (1, d.min(u32::MAX as f64) as u32)
}

/// CFA-preserving 2x binning: each 4x4 block of the crop becomes one 2x2
/// cell, every new site the mean of the four same-colour sites it covers.
/// The crop origin is made even first so the phase of the full frame stays
/// the phase of the result (the result has no further crop). Levels are
/// unchanged (means of equal black levels), the noise sigma halves.
pub fn bin_cfa_2x(f: &RawFrame) -> Result<RawFrame> {
    let SensorKind::Bayer(_) = f.kind else {
        return Err(MimizanError::Unsupported { msg: "binning needs a Bayer frame".into() });
    };
    let mut c = f.crop;
    if c.x & 1 == 1 {
        c.x += 1;
        c.w = c.w.saturating_sub(1);
    }
    if c.y & 1 == 1 {
        c.y += 1;
        c.h = c.h.saturating_sub(1);
    }
    let bw = c.w / 4;
    let bh = c.h / 4;
    if bw < 2 || bh < 2 {
        return Err(MimizanError::Invalid { msg: format!("crop {}x{} too small to bin", c.w, c.h) });
    }
    let (nw, nh) = (bw * 2, bh * 2);
    let src_w = f.width;
    let data = &f.data;
    let mut out = vec![0f32; nw * nh];
    out.par_chunks_mut(nw).enumerate().for_each(|(ny, row)| {
        let j = ny / 2;
        let dy = ny & 1;
        let y0 = c.y + 4 * j + dy;
        let y1 = y0 + 2;
        for (nx, o) in row.iter_mut().enumerate() {
            let i = nx / 2;
            let dx = nx & 1;
            let x0 = c.x + 4 * i + dx;
            let x1 = x0 + 2;
            let s = data.get(y0 * src_w + x0)
                + data.get(y0 * src_w + x1)
                + data.get(y1 * src_w + x0)
                + data.get(y1 * src_w + x1);
            *o = (0.25 * s) as f32;
        }
    });
    // Black tile: the result's (0,0) is the even crop origin, whose tile
    // position matches the full frame's (0,0) parity, so the tile is reused.
    Ok(RawFrame {
        make: f.make.clone(),
        model: f.model.clone(),
        clean_make: f.clean_make.clone(),
        clean_model: f.clean_model.clone(),
        width: nw,
        height: nh,
        bits: f.bits,
        kind: f.kind,
        black_tile: f.black_tile,
        white_rgb: f.white_rgb,
        crop: Rect { x: 0, y: 0, w: nw, h: nh },
        orientation: f.orientation,
        xyz_to_cam: f.xyz_to_cam,
        wb_as_shot: f.wb_as_shot,
        exposure: f.exposure.clone(),
        data: RawData::Float(out),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::{BayerPattern, RawRect};

    fn input(w: u32, h: u32, f: impl Fn(u32, u32) -> u16) -> RawInput {
        let mut data = Vec::with_capacity((w * h * 2) as usize);
        for y in 0..h {
            for x in 0..w {
                data.extend_from_slice(&f(x, y).to_le_bytes());
            }
        }
        RawInput {
            make: "Google".into(),
            model: "Pixel 9".into(),
            width: w,
            height: h,
            pattern: BayerPattern::Rggb,
            black_tile: vec![64.0; 4],
            white_level: 1023.0,
            crop: None,
            orientation: 6,
            wb_gains: Some(vec![2.0, 1.0, 1.0, 1.5]),
            xyz_to_cam: None,
            exposure: None,
            data,
        }
    }

    #[test]
    fn frame_round_trips_samples_and_metadata() {
        let inp = input(8, 6, |x, y| (x + 10 * y) as u16);
        let f = to_raw_frame(&inp).unwrap();
        assert_eq!((f.width, f.height, f.bits), (8, 6, 10));
        assert_eq!(f.data.get(3 + 2 * 8), 23.0);
        assert_eq!(f.wb_as_shot, Some([2.0, 1.0, 1.5]));
        assert_eq!(f.orientation, 6);
        assert_eq!(f.crop, Rect { x: 0, y: 0, w: 8, h: 6 });
    }

    #[test]
    fn frame_rejects_bad_sizes() {
        let mut inp = input(8, 6, |_, _| 100);
        inp.data.pop();
        assert!(to_raw_frame(&inp).is_err());
        let mut inp = input(8, 6, |_, _| 100);
        inp.crop = Some(RawRect { x: 2, y: 0, width: 8, height: 4 });
        assert!(to_raw_frame(&inp).is_err());
    }

    #[test]
    fn binning_keeps_phase_and_averages_same_colour_sites() {
        // Value encodes the colour position: R=100, G=200, B=300 (+ small ramp).
        let inp = input(16, 12, |x, y| {
            let base = match (x & 1, y & 1) {
                (0, 0) => 100,
                (1, 1) => 300,
                _ => 200,
            };
            base + (x / 4) as u16
        });
        let f = to_raw_frame(&inp).unwrap();
        let b = bin_cfa_2x(&f).unwrap();
        assert_eq!((b.width, b.height), (8, 6));
        // New (0,0) is R: mean of four R sites of block 0 = 100 + 0.
        assert_eq!(b.data.get(0), 100.0);
        // New (1,1) is B of block 0.
        assert_eq!(b.data.get(1 + 8), 300.0);
        // New (2,0) is R of block 1: ramp +1.
        assert_eq!(b.data.get(2), 101.0);
        // Odd crop origin is shifted to even: phase preserved.
        let mut inp2 = input(17, 13, |x, y| if (x & 1, y & 1) == (0, 0) { 100 } else { 200 });
        inp2.crop = Some(RawRect { x: 1, y: 1, width: 16, height: 12 });
        let f2 = to_raw_frame(&inp2).unwrap();
        let b2 = bin_cfa_2x(&f2).unwrap();
        assert_eq!(b2.data.get(0), 100.0);
    }

    #[test]
    fn exposure_rationals() {
        assert_eq!(exposure_rational(8_000_000), (1, 125));
        assert_eq!(exposure_rational(2_500_000_000), (2500, 1000));
    }
}
