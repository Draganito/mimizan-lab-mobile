//! Exposure metering on the sensor's own numbers.
//!
//! The app meters on `RAW_SENSOR` probes taken between the viewfinder frames,
//! not on the ISP's picture: the statistics here are computed on the same
//! samples the negative will be developed from, before anything the phone's
//! pipeline does to them. The Kotlin side hands over a sparse sample of the
//! frame (every n-th 2x2 tile of the active area, phase kept) so the probe
//! costs a few hundred kilobytes and well under a millisecond.
//!
//! Two numbers drive the exposure:
//!
//! * `highlight`: the 99th percentile of the brightest *raw* channel of
//!   each tile, as a fraction of the sensor's range above black. Raw, not
//!   balanced: the sensor clips each channel at its white level regardless of
//!   the white balance. Placing this near the clip is ETTR; on the paper the
//!   clip is Zone VIII, so the brightest textured tone of the scene lands
//!   just below it at the base print. The percentile is a budget of picture
//!   area that may clip: one percent covers lamps, speculars and the sun,
//!   which carry no texture to keep, while a window or a wall is held.
//! * `grey`: the geometric mean of the balanced mix (the negative's own grey,
//!   with the current filter), over the frame or over a spot. Placing it at
//!   the grey card (0.18) is the classical average meter; the paper's base
//!   exposure prints that grey at Zone V.

use crate::frame::as_shot_from_gains;
use crate::types::{BayerPattern, MimizanError, RawRect, Result, Weights};

/// Bins of the luma histogram over linear [0, 1]; the clip goes in the last.
pub const LUMA_BINS: usize = 1024;
/// Percentile of the tile maxima that `highlight` reports: the picture area
/// above it (one percent) is the clip budget for light sources.
pub const HIGHLIGHT_PERCENTILE: f64 = 0.99;
/// A tile is counted as clipped when a channel is at or above this fraction
/// of the range: the top of a 10-bit sensor rounds here.
const CLIP_FRACTION: f64 = 0.99;
/// Bins over [0, 1] for the percentile of the tile maxima.
const MAX_BINS: usize = 4096;
/// Floor for the geometric mean, well under the noise of any sensor.
const LUMA_FLOOR: f64 = 1e-5;

/// A sparse sample of one `RAW_SENSOR` frame: whole 2x2 tiles taken on a
/// regular grid of the active area, packed as a small Bayer image whose
/// (0,0) has the phase of the sensor frame's (0,0). Levels in raw DN.
#[derive(Clone, Debug, uniffi::Record)]
pub struct MeterInput {
    /// Even sizes: `width/2` by `height/2` tiles.
    pub width: u32,
    pub height: u32,
    pub pattern: BayerPattern,
    /// Black level per tile position, row-major (0,0),(1,0),(0,1),(1,1), or one value.
    pub black_tile: Vec<f64>,
    pub white_level: f64,
    /// `COLOR_CORRECTION_GAINS` (R, G_even, G_odd, B) or R, G, B; `None` = no balance.
    #[uniffi(default = None)]
    pub wb_gains: Option<Vec<f64>>,
    /// Mix weights on the balanced channels (`mix_weights`).
    pub weights: Weights,
    /// Spot to meter the grey in, in pixels of this sample; `None` = the whole sample.
    #[uniffi(default = None)]
    pub spot: Option<RawRect>,
    /// `width*height` little-endian 16-bit samples.
    pub data: Vec<u8>,
}

/// What one probe measured. Fractions are of the range above black, 1 = clip.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct MeterStats {
    /// [`HIGHLIGHT_PERCENTILE`] of the per-tile maximum over the raw channels.
    pub highlight: f64,
    /// Fraction of tiles with a raw channel at or above the clip.
    pub clipped: f64,
    /// Geometric mean of the balanced mix over the whole sample.
    pub grey: f64,
    /// The same over `spot`, when one was given and covers at least one tile.
    pub spot_grey: Option<f64>,
    /// Histogram of the balanced mix, [`LUMA_BINS`] bins over linear [0, 1].
    pub luma_hist: Vec<u32>,
    pub tiles: u32,
}

/// Statistics of one metering sample. Sub-millisecond at the sizes the app sends.
#[uniffi::export]
pub fn meter_raw(input: MeterInput) -> Result<MeterStats> {
    meter_impl(&input)
}

pub fn meter_impl(input: &MeterInput) -> Result<MeterStats> {
    let (w, h) = (input.width as usize, input.height as usize);
    if w < 2 || h < 2 || w % 2 != 0 || h % 2 != 0 {
        return Err(MimizanError::Invalid {
            msg: format!("meter sample {w}x{h} must be even and at least 2x2"),
        });
    }
    if input.data.len() != w * h * 2 {
        return Err(MimizanError::Invalid {
            msg: format!("meter data has {} bytes, expected {}", input.data.len(), w * h * 2),
        });
    }
    if !input.white_level.is_finite() || input.white_level <= 0.0 {
        return Err(MimizanError::Invalid {
            msg: format!("white level {} is not positive", input.white_level),
        });
    }
    let black: [f64; 4] = match input.black_tile.len() {
        1 => [input.black_tile[0]; 4],
        4 => [input.black_tile[0], input.black_tile[1], input.black_tile[2], input.black_tile[3]],
        n => return Err(MimizanError::Invalid { msg: format!("black_tile needs 1 or 4 values, got {n}") }),
    };
    let mut scale = [0.0; 4];
    for i in 0..4 {
        let range = input.white_level - black[i];
        if !range.is_finite() || range <= 0.0 {
            return Err(MimizanError::Invalid {
                msg: format!("black level {} is not below white {}", black[i], input.white_level),
            });
        }
        scale[i] = 1.0 / range;
    }
    let balance = match &input.wb_gains {
        Some(g) => as_shot_from_gains(g)?,
        None => [1.0, 1.0, 1.0],
    };
    let wt = input.weights;
    if !(wt.r >= 0.0 && wt.g >= 0.0 && wt.b >= 0.0) || wt.r + wt.g + wt.b <= 0.0 {
        return Err(MimizanError::Invalid { msg: "weights must be non-negative and not all zero".into() });
    }
    let wsum = wt.r + wt.g + wt.b;
    // Balanced weight per raw channel, normalised to sum 1 on a grey subject.
    let (kr, kg, kb) = (wt.r * balance[0] / wsum, wt.g / wsum, wt.b * balance[2] / wsum);
    // Which raw channel sits at each tile position (0 = R, 1 = G, 2 = B).
    let chan: [u8; 4] = match input.pattern {
        BayerPattern::Rggb => [0, 1, 1, 2],
        BayerPattern::Grbg => [1, 0, 2, 1],
        BayerPattern::Gbrg => [1, 2, 0, 1],
        BayerPattern::Bggr => [2, 1, 1, 0],
    };
    let spot = input.spot.map(|r| {
        let x0 = (r.x as usize / 2).min(w / 2);
        let y0 = (r.y as usize / 2).min(h / 2);
        let x1 = ((r.x + r.width) as usize).div_ceil(2).min(w / 2);
        let y1 = ((r.y + r.height) as usize).div_ceil(2).min(h / 2);
        (x0, y0, x1, y1)
    });

    let sample = |x: usize, y: usize| -> f64 {
        let i = 2 * (y * w + x);
        u16::from_le_bytes([input.data[i], input.data[i + 1]]) as f64
    };

    let mut max_hist = vec![0u32; MAX_BINS];
    let mut luma_hist = vec![0u32; LUMA_BINS];
    let mut log_sum = 0.0f64;
    let mut spot_log_sum = 0.0f64;
    let mut spot_n = 0u32;
    let mut clipped = 0u32;
    let tiles = ((w / 2) * (h / 2)) as u32;

    for ty in 0..h / 2 {
        for tx in 0..w / 2 {
            let (x, y) = (2 * tx, 2 * ty);
            let v = [
                (sample(x, y) - black[0]) * scale[0],
                (sample(x + 1, y) - black[1]) * scale[1],
                (sample(x, y + 1) - black[2]) * scale[2],
                (sample(x + 1, y + 1) - black[3]) * scale[3],
            ];
            let mut rgb = [0.0f64; 3];
            let mut tile_max = 0.0f64;
            for p in 0..4 {
                rgb[chan[p] as usize] += v[p];
                tile_max = tile_max.max(v[p]);
            }
            rgb[1] *= 0.5;
            if tile_max >= CLIP_FRACTION {
                clipped += 1;
            }
            let mi = ((tile_max.clamp(0.0, 1.0) * (MAX_BINS as f64 - 1.0)) + 0.5) as usize;
            max_hist[mi.min(MAX_BINS - 1)] += 1;
            let luma = (kr * rgb[0] + kg * rgb[1] + kb * rgb[2]).max(0.0);
            let li = ((luma.min(1.0) * (LUMA_BINS as f64 - 1.0)) + 0.5) as usize;
            luma_hist[li.min(LUMA_BINS - 1)] += 1;
            let l = luma.max(LUMA_FLOOR).ln();
            log_sum += l;
            if let Some((x0, y0, x1, y1)) = spot {
                if tx >= x0 && tx < x1 && ty >= y0 && ty < y1 {
                    spot_log_sum += l;
                    spot_n += 1;
                }
            }
        }
    }

    let highlight = {
        let want = ((tiles as f64) * HIGHLIGHT_PERCENTILE).ceil().max(1.0) as u64;
        let mut seen = 0u64;
        let mut bin = MAX_BINS - 1;
        for (i, &n) in max_hist.iter().enumerate() {
            seen += n as u64;
            if seen >= want {
                bin = i;
                break;
            }
        }
        bin as f64 / (MAX_BINS as f64 - 1.0)
    };

    Ok(MeterStats {
        highlight,
        clipped: clipped as f64 / tiles as f64,
        grey: (log_sum / tiles as f64).exp(),
        spot_grey: if spot_n > 0 { Some((spot_log_sum / spot_n as f64).exp()) } else { None },
        luma_hist,
        tiles,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const BLACK: f64 = 64.0;
    const WHITE: f64 = 1023.0;

    /// A sample whose tile at (tx, ty) holds the raw values `f(tx, ty) -> [p0, p1, p2, p3]`.
    fn sample(
        tiles_x: usize,
        tiles_y: usize,
        pattern: BayerPattern,
        f: impl Fn(usize, usize) -> [f64; 4],
    ) -> MeterInput {
        let (w, h) = (2 * tiles_x, 2 * tiles_y);
        let mut data = vec![0u8; w * h * 2];
        for ty in 0..tiles_y {
            for tx in 0..tiles_x {
                let v = f(tx, ty);
                for (p, &dn) in v.iter().enumerate() {
                    let (x, y) = (2 * tx + (p & 1), 2 * ty + (p >> 1));
                    let i = 2 * (y * w + x);
                    let b = (dn.round().clamp(0.0, 65535.0) as u16).to_le_bytes();
                    data[i] = b[0];
                    data[i + 1] = b[1];
                }
            }
        }
        MeterInput {
            width: w as u32,
            height: h as u32,
            pattern,
            black_tile: vec![BLACK; 4],
            white_level: WHITE,
            wb_gains: None,
            weights: Weights::NATIVE,
            spot: None,
            data,
        }
    }

    fn dn(fraction: f64) -> f64 {
        BLACK + fraction * (WHITE - BLACK)
    }

    #[test]
    fn uniform_grey_meters_itself() {
        let s = sample(64, 48, BayerPattern::Rggb, |_, _| [dn(0.25); 4]);
        let m = meter_impl(&s).unwrap();
        assert!((m.highlight - 0.25).abs() < 2e-3, "{}", m.highlight);
        assert!((m.grey - 0.25).abs() < 2e-3, "{}", m.grey);
        assert_eq!(m.clipped, 0.0);
        assert_eq!(m.tiles, 64 * 48);
        assert_eq!(m.luma_hist.iter().sum::<u32>(), 64 * 48);
        assert!(m.spot_grey.is_none());
    }

    #[test]
    fn highlight_is_the_raw_channel_before_balance() {
        // A red subject: R at 0.8 of the range, the rest low. With the strong
        // blue gain the balanced mix is dim, the raw highlight is still 0.8.
        let mut s = sample(32, 32, BayerPattern::Rggb, |_, _| [dn(0.8), dn(0.1), dn(0.1), dn(0.05)]);
        s.wb_gains = Some(vec![1.2, 1.0, 1.0, 3.0]);
        let m = meter_impl(&s).unwrap();
        assert!((m.highlight - 0.8).abs() < 2e-3, "{}", m.highlight);
        // 0.25*0.8*1.2 + 0.5*0.1 + 0.25*0.05*3 = 0.3275
        assert!((m.grey - 0.3275).abs() < 3e-3, "{}", m.grey);
    }

    #[test]
    fn light_sources_within_the_budget_do_not_move_the_percentile() {
        // Lamps on 0.8 % of the frame (two rows of tiles out of 250): the
        // percentile stays with the scene, `clipped` still reports them.
        let s = sample(100, 250, BayerPattern::Rggb, |_, ty| if ty < 2 { [WHITE; 4] } else { [dn(0.4); 4] });
        let m = meter_impl(&s).unwrap();
        assert!((m.highlight - 0.4).abs() < 2e-3, "{}", m.highlight);
        assert!((m.clipped - 0.008).abs() < 1e-9, "{}", m.clipped);
        // A window on 2 % of the frame: the percentile reaches it.
        let s = sample(100, 250, BayerPattern::Rggb, |_, ty| if ty < 5 { [WHITE; 4] } else { [dn(0.4); 4] });
        let m = meter_impl(&s).unwrap();
        assert!(m.highlight >= 0.99, "{}", m.highlight);
    }

    #[test]
    fn pattern_decides_which_site_is_which_channel() {
        // Same tile values; under GBRG position 2 is red, under RGGB position 0.
        let tile = [dn(0.1), dn(0.2), dn(0.9), dn(0.3)];
        let weights = Weights { r: 1.0, g: 0.0, b: 0.0 };
        let mut a = sample(8, 8, BayerPattern::Rggb, |_, _| tile);
        a.weights = weights;
        let mut b = sample(8, 8, BayerPattern::Gbrg, |_, _| tile);
        b.weights = weights;
        let ma = meter_impl(&a).unwrap();
        let mb = meter_impl(&b).unwrap();
        assert!((ma.grey - 0.1).abs() < 2e-3, "{}", ma.grey);
        assert!((mb.grey - 0.9).abs() < 2e-3, "{}", mb.grey);
        // The raw highlight does not care about the pattern.
        assert!((ma.highlight - mb.highlight).abs() < 1e-9);
    }

    #[test]
    fn spot_meters_only_its_tiles() {
        // Left half dark, right half bright; the spot sits in the right half.
        let mut s =
            sample(40, 20, BayerPattern::Rggb, |tx, _| if tx < 20 { [dn(0.02); 4] } else { [dn(0.5); 4] });
        s.spot = Some(RawRect { x: 50, y: 10, width: 20, height: 10 });
        let m = meter_impl(&s).unwrap();
        assert!((m.spot_grey.unwrap() - 0.5).abs() < 2e-3, "{:?}", m.spot_grey);
        // Geometric mean of the whole: sqrt(0.02 * 0.5) = 0.1
        assert!((m.grey - 0.1).abs() < 2e-3, "{}", m.grey);
        // A spot outside the sample yields nothing rather than a wrong number.
        s.spot = Some(RawRect { x: 200, y: 0, width: 10, height: 10 });
        assert!(meter_impl(&s).unwrap().spot_grey.is_none());
    }

    #[test]
    fn dark_frames_stay_finite() {
        let s = sample(8, 8, BayerPattern::Bggr, |_, _| [BLACK - 2.0; 4]);
        let m = meter_impl(&s).unwrap();
        assert_eq!(m.highlight, 0.0);
        assert!(m.grey > 0.0 && m.grey <= LUMA_FLOOR * 1.0001);
        assert_eq!(m.luma_hist[0], 64);
    }

    #[test]
    fn rejects_odd_or_short_input() {
        let mut s = sample(8, 8, BayerPattern::Rggb, |_, _| [dn(0.5); 4]);
        s.width = 15;
        assert!(meter_impl(&s).is_err());
        let mut s = sample(8, 8, BayerPattern::Rggb, |_, _| [dn(0.5); 4]);
        s.data.pop();
        assert!(meter_impl(&s).is_err());
        let mut s = sample(8, 8, BayerPattern::Rggb, |_, _| [dn(0.5); 4]);
        s.black_tile = vec![WHITE];
        assert!(meter_impl(&s).is_err());
    }
}
