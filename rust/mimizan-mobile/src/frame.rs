//! `RawInput` (Camera2 buffer + metadata) -> `mimizan_core::decode::RawFrame`.

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

/// A Camera2 `SENSOR_COLOR_TRANSFORM` (9 values, rows R, G, B) as the
/// core's XYZ → camera matrix; `None` when absent or all zero.
pub fn matrix_of(m: Option<&[f64]>) -> Result<Option<[[f64; 3]; 3]>> {
    match m {
        Some(m) if m.len() == 9 && m.iter().any(|v| !v.is_finite()) => {
            Err(MimizanError::Invalid { msg: "xyz_to_cam has a non-finite value".into() })
        }
        Some(m) if m.len() == 9 && m.iter().all(|v| *v == 0.0) => Ok(None),
        Some(m) if m.len() == 9 => Ok(Some([[m[0], m[1], m[2]], [m[3], m[4], m[5]], [m[6], m[7], m[8]]])),
        Some(_) => Err(MimizanError::Invalid { msg: "xyz_to_cam needs 9 values".into() }),
        None => Ok(None),
    }
}

/// Build the frame the pipeline ingests. Validates sizes and levels; the
/// pixel data is copied once (bytes -> u16).
pub fn to_raw_frame(input: &RawInput) -> Result<RawFrame> {
    let (w, h) = (input.width as usize, input.height as usize);
    if w < 4 || h < 4 {
        return Err(MimizanError::Invalid { msg: format!("frame {w}x{h} is too small") });
    }
    let expected = w.checked_mul(h).and_then(|n| n.checked_mul(2));
    if expected != Some(input.data.len()) {
        return Err(MimizanError::Invalid {
            msg: format!("data has {} bytes, expected {}x{}x2", input.data.len(), w, h),
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

    let xyz_to_cam = matrix_of(input.xyz_to_cam.as_deref())?;

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
        // The bridge chooses the D65 SENSOR_COLOR_TRANSFORM.
        illuminant: xyz_to_cam.map(|_| 21),
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
    fn exposure_rationals() {
        assert_eq!(exposure_rational(8_000_000), (1, 125));
        assert_eq!(exposure_rational(2_500_000_000), (2500, 1000));
    }
}
