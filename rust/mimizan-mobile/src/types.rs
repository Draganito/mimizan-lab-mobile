//! The FFI surface: plain records and enums the Kotlin side builds and reads.
//! Every number keeps the unit and meaning of its `mimizan-core` counterpart
//! (`docs/SPEC.md` in the mimizan repository).

use mimizan_core::calib::{LookEncoding, LookFile};
use mimizan_core::cfa::BayerPhase;
use mimizan_core::curve::Curve;
use mimizan_core::ingest::WhiteBalance;
use mimizan_core::mix::{ColorFilter, WeightSpace as CoreWeightSpace, Weights as CoreWeights};
use mimizan_core::separate::MaskMode;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum MimizanError {
    #[error("invalid input: {msg}")]
    Invalid { msg: String },
    #[error("unsupported: {msg}")]
    Unsupported { msg: String },
    #[error("io: {msg}")]
    Io { msg: String },
    #[error("{msg}")]
    Internal { msg: String },
}

impl From<mimizan_core::Error> for MimizanError {
    fn from(e: mimizan_core::Error) -> Self {
        use mimizan_core::Error as E;
        match e {
            E::Invalid(m) => Self::Invalid { msg: m },
            E::Unsupported(m) | E::Decode(m) => Self::Unsupported { msg: m },
            E::Io(m) => Self::Io { msg: m.to_string() },
            other => Self::Internal { msg: other.to_string() },
        }
    }
}

impl From<std::io::Error> for MimizanError {
    fn from(e: std::io::Error) -> Self {
        Self::Io { msg: e.to_string() }
    }
}

pub type Result<T> = std::result::Result<T, MimizanError>;

/// Colour at (0,0),(1,0),(0,1),(1,1) of the repeating 2x2 tile of the full
/// sensor frame. Camera2 `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`:
/// 0 = RGGB, 1 = GRBG, 2 = GBRG, 3 = BGGR.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum BayerPattern {
    Rggb,
    Grbg,
    Gbrg,
    Bggr,
}

impl BayerPattern {
    pub fn phase(self) -> BayerPhase {
        match self {
            Self::Rggb => BayerPhase::RGGB,
            Self::Grbg => BayerPhase::GRBG,
            Self::Gbrg => BayerPhase::GBRG,
            Self::Bggr => BayerPhase::BGGR,
        }
    }
    pub fn from_phase(p: BayerPhase) -> Self {
        match p {
            BayerPhase::RGGB => Self::Rggb,
            BayerPhase::GRBG => Self::Grbg,
            BayerPhase::GBRG => Self::Gbrg,
            BayerPhase::BGGR => Self::Bggr,
        }
    }
}

/// Camera2 `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT` value -> pattern.
#[uniffi::export]
pub fn bayer_pattern_from_camera2(arrangement: i32) -> Result<BayerPattern> {
    match arrangement {
        0 => Ok(BayerPattern::Rggb),
        1 => Ok(BayerPattern::Grbg),
        2 => Ok(BayerPattern::Gbrg),
        3 => Ok(BayerPattern::Bggr),
        other => Err(MimizanError::Unsupported {
            msg: format!("colour filter arrangement {other} is not a 2x2 Bayer pattern (RGB/MONO/NIR sensors are out of scope)"),
        }),
    }
}

/// Pixel rectangle in the sensor frame.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Record)]
pub struct RawRect {
    pub x: u32,
    pub y: u32,
    pub width: u32,
    pub height: u32,
}

/// Exposure data, informational only (nothing in the pipeline depends on it).
#[derive(Clone, Debug, Default, PartialEq, uniffi::Record)]
pub struct ExposureInfo {
    #[uniffi(default = None)]
    pub iso: Option<u32>,
    /// `SENSOR_EXPOSURE_TIME`, nanoseconds.
    #[uniffi(default = None)]
    pub exposure_ns: Option<u64>,
    #[uniffi(default = None)]
    pub f_number: Option<f64>,
    #[uniffi(default = None)]
    pub focal_mm: Option<f64>,
    #[uniffi(default = None)]
    pub lens: Option<String>,
}

/// One RAW_SENSOR frame plus the metadata the pipeline needs. Levels are in
/// raw DN. The Kotlin side fills this from `CameraCharacteristics` and the
/// `TotalCaptureResult` of the shot (see `RawFrameBridge.kt`).
#[derive(Clone, Debug, uniffi::Record)]
pub struct RawInput {
    #[uniffi(default = "")]
    pub make: String,
    #[uniffi(default = "")]
    pub model: String,
    pub width: u32,
    pub height: u32,
    pub pattern: BayerPattern,
    /// Black level per 2x2 tile position, row-major (0,0),(1,0),(0,1),(1,1):
    /// `SENSOR_BLACK_LEVEL_PATTERN` of the capture result (dynamic) or of the
    /// characteristics.
    pub black_tile: Vec<f64>,
    /// `SENSOR_INFO_WHITE_LEVEL`.
    pub white_level: f64,
    /// Active pixels inside the buffer (`SENSOR_INFO_ACTIVE_ARRAY_SIZE`
    /// relative to `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`); `None` =
    /// the whole buffer.
    #[uniffi(default = None)]
    pub crop: Option<RawRect>,
    /// TIFF orientation (1..8) that makes the picture upright; 0 = unknown.
    #[uniffi(default = 1)]
    pub orientation: u32,
    /// As-shot balance multipliers R,G,B (any scale; normalised to G = 1
    /// internally), from `COLOR_CORRECTION_GAINS`.
    #[uniffi(default = None)]
    pub wb_gains: Option<Vec<f64>>,
    /// XYZ -> camera matrix, 9 values row-major (rows R,G,B), from
    /// `SENSOR_COLOR_TRANSFORM1` (D65) if available.
    #[uniffi(default = None)]
    pub xyz_to_cam: Option<Vec<f64>>,
    #[uniffi(default = None)]
    pub exposure: Option<ExposureInfo>,
    /// `width*height` little-endian 16-bit samples, row stride removed.
    pub data: Vec<u8>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SeparationMode {
    /// Pixel-adaptive Dubois weighting (default, best measured).
    Dubois,
    /// Block-spectrum mask.
    Adaptive,
    /// Fixed low-pass separation: fewer planes, faster.
    Off,
}

impl SeparationMode {
    pub fn mask_mode(self) -> MaskMode {
        match self {
            Self::Dubois => MaskMode::Dubois,
            Self::Adaptive => MaskMode::Adaptive,
            Self::Off => MaskMode::Off,
        }
    }
}

#[derive(Clone, Debug, PartialEq, uniffi::Enum)]
pub enum WhiteBalanceMode {
    /// `wb_gains` of the input; gray-world when absent.
    AsShot,
    GrayWorld,
    Manual { r: f64, g: f64, b: f64 },
    None,
}

impl WhiteBalanceMode {
    pub fn core(&self) -> WhiteBalance {
        match self {
            Self::AsShot => WhiteBalance::AsShot,
            Self::GrayWorld => WhiteBalance::GrayWorld,
            Self::Manual { r, g, b } => WhiteBalance::Manual([*r, *g, *b]),
            Self::None => WhiteBalance::None,
        }
    }
}

/// The classic black-and-white contrast filters (`mix.rs`).
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum Filter {
    None,
    Yellow8,
    YellowGreen11,
    Orange16,
    Red25,
    Green58,
    Blue47,
}

impl Filter {
    pub fn core(self) -> ColorFilter {
        match self {
            Self::None => ColorFilter::None,
            Self::Yellow8 => ColorFilter::Yellow8,
            Self::YellowGreen11 => ColorFilter::YellowGreen11,
            Self::Orange16 => ColorFilter::Orange16,
            Self::Red25 => ColorFilter::Red25,
            Self::Green58 => ColorFilter::Green58,
            Self::Blue47 => ColorFilter::Blue47,
        }
    }
    pub const ALL: [Filter; 7] = [
        Filter::None,
        Filter::Yellow8,
        Filter::YellowGreen11,
        Filter::Orange16,
        Filter::Red25,
        Filter::Green58,
        Filter::Blue47,
    ];
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct FilterInfo {
    pub filter: Filter,
    /// `yellow-8`, `red-25`, ...
    pub name: String,
    pub wratten: Option<u8>,
    pub effect: String,
    /// Transmission of the balanced R, G, B channels.
    pub transmission: Vec<f64>,
}

#[uniffi::export]
pub fn filters() -> Vec<FilterInfo> {
    Filter::ALL
        .iter()
        .map(|&f| {
            let c = f.core();
            FilterInfo {
                filter: f,
                name: c.name().to_string(),
                wratten: c.wratten(),
                effect: c.effect().to_string(),
                transmission: c.transmission().to_vec(),
            }
        })
        .collect()
}

/// Which channels a weight triple refers to (`mix.rs`).
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum WeightSpace {
    /// After white balance; what the mix consumes.
    Balanced,
    /// Sensor channels as recorded: a fixed spectral response, independent
    /// of the illuminant (what a camera file stores).
    Raw,
}

impl WeightSpace {
    pub fn core(self) -> CoreWeightSpace {
        match self {
            Self::Balanced => CoreWeightSpace::Balanced,
            Self::Raw => CoreWeightSpace::Raw,
        }
    }
}

/// Mix weights; must be non-negative and sum to 1. `0.25/0.50/0.25` is the
/// Bayer luminance itself (no chrominance added).
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct Weights {
    pub r: f64,
    pub g: f64,
    pub b: f64,
}

impl Weights {
    pub const NATIVE: Weights = Weights { r: 0.25, g: 0.5, b: 0.25 };
    pub fn core(self) -> CoreWeights {
        CoreWeights { r: self.r, g: self.g, b: self.b }
    }
    pub fn from_core(w: CoreWeights) -> Self {
        Self { r: w.r, g: w.g, b: w.b }
    }
}

/// Weights with the sum normalised to 1 (sliders need not be exact).
#[uniffi::export]
pub fn normalise_weights(w: Weights) -> Result<Weights> {
    if w.r < 0.0 || w.g < 0.0 || w.b < 0.0 {
        return Err(MimizanError::Invalid { msg: "weights must be non-negative".into() });
    }
    let s = w.r + w.g + w.b;
    if s <= 0.0 {
        return Err(MimizanError::Invalid { msg: "weights must not all be zero".into() });
    }
    Ok(Weights { r: w.r / s, g: w.g / s, b: w.b / s })
}

/// Everything a development needs (ingest + separation + first mix).
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct DevelopParams {
    pub separation: SeparationMode,
    /// Nonlinear reconstruction of the last octave: off by default, because a
    /// pixel that passes through it is computed, not measured.
    #[uniffi(default = false)]
    pub reconstruct: bool,
    #[uniffi(default = true)]
    pub fix_defects: bool,
    pub white_balance: WhiteBalanceMode,
    /// 1 = full resolution; 2 = CFA-preserving 2x binning before ingest
    /// (each 4x4 block becomes one 2x2 RGGB cell: quarter of the pixels,
    /// quarter of the memory, about a quarter of the time). A quick mode
    /// for previews or low battery, never the deliverable.
    #[uniffi(default = 1)]
    pub binning: u32,
    /// Noise model `sigma(s) = a*sqrt(s) + b` in normalised units; `None` =
    /// core defaults (0.005, 0.0002) or the camera file.
    #[uniffi(default = None)]
    pub noise_a: Option<f64>,
    #[uniffi(default = None)]
    pub noise_b: Option<f64>,
    /// First mix. `None` = the camera file's weights, else native.
    #[uniffi(default = None)]
    pub weights: Option<Weights>,
    pub weights_space: WeightSpace,
    pub filter: Filter,
    /// A camera file (`cameras` directory of the mimizan repository, JSON
    /// schema 1) as text: noise and weights for this sensor. Its
    /// `bayer_phase` must match the input.
    #[uniffi(default = None)]
    pub camera_file_json: Option<String>,
    /// Longest edge of the cached, upright preview planes (the re-mix target
    /// of `preview()`); 0 = no preview planes.
    #[uniffi(default = 2048)]
    pub preview_long_edge: u32,
}

impl Default for DevelopParams {
    fn default() -> Self {
        Self {
            separation: SeparationMode::Dubois,
            reconstruct: false,
            fix_defects: true,
            white_balance: WhiteBalanceMode::AsShot,
            binning: 1,
            noise_a: None,
            noise_b: None,
            weights: None,
            weights_space: WeightSpace::Balanced,
            filter: Filter::None,
            camera_file_json: None,
            preview_long_edge: 2048,
        }
    }
}

#[uniffi::export]
pub fn default_develop_params() -> DevelopParams {
    DevelopParams::default()
}

#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct CurvePoint {
    pub x: f64,
    pub y: f64,
}

/// Print look: `gamma22` encodes linear -> x^(1/2.2) first, then the monotone
/// PCHIP through `points` (identity when the points are (0,0),(1,1)).
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct Look {
    pub name: String,
    pub points: Vec<CurvePoint>,
    #[uniffi(default = true)]
    pub gamma22: bool,
}

impl Look {
    pub fn from_file(l: &LookFile) -> Self {
        Self {
            name: l.name.clone(),
            points: l.points.iter().map(|p| CurvePoint { x: p[0], y: p[1] }).collect(),
            gamma22: l.encoding == LookEncoding::Gamma22,
        }
    }
    pub fn to_file(&self) -> Result<LookFile> {
        let l = LookFile {
            schema: 1,
            name: self.name.clone(),
            points: self.points.iter().map(|p| [p.x, p.y]).collect(),
            encoding: if self.gamma22 { LookEncoding::Gamma22 } else { LookEncoding::None },
            fitted_against: None,
        };
        l.validate()?;
        Ok(l)
    }
}

const REFERENCE_LOOK_JSON: &str = include_str!("../../../../mimizan/look/reference.json");

/// Pure gamma 2.2.
#[uniffi::export]
pub fn look_neutral() -> Look {
    Look::from_file(&LookFile::neutral())
}

/// The fitted reference print curve shipped with Mimizan Lab.
#[uniffi::export]
pub fn look_reference() -> Look {
    let l: LookFile = serde_json::from_str(REFERENCE_LOOK_JSON).expect("bundled reference look parses");
    Look::from_file(&l)
}

/// Contrast on top of a look: an S-curve around middle grey in the encoded
/// (display) domain, end points fixed. `contrast` in -1..1, 0 = the look as
/// it is; positive steepens the middle (`tanh`), negative flattens it (the
/// exact inverse). Returns a look with the composed curve sampled at 65
/// points, so every consumer (preview, histogram, export) sees the same
/// tones.
#[uniffi::export]
pub fn look_with_contrast(look: Look, contrast: f64) -> Result<Look> {
    let c = contrast.clamp(-1.0, 1.0);
    if c.abs() < 1e-6 {
        return Ok(look);
    }
    let file = look.to_file()?;
    // The points alone (without the encoding) as a curve: encoded -> output.
    let pts = Curve::from_look(&LookFile { encoding: LookEncoding::None, ..file.clone() });
    let s = c.abs() * 4.0;
    let t = (0.5 * s).tanh();
    let f = |y: f64| -> f64 {
        let d = y - 0.5;
        let out = if c > 0.0 { 0.5 + 0.5 * (s * d).tanh() / t } else { 0.5 + ((2.0 * t * d).clamp(-0.999_999, 0.999_999)).atanh() / s };
        out.clamp(0.0, 1.0)
    };
    const N: usize = 64;
    let points = (0..=N)
        .map(|i| {
            let x = i as f64 / N as f64;
            CurvePoint { x, y: f(pts.eval(x)) }
        })
        .collect();
    Ok(Look { name: format!("{} contrast {:+.2}", look.name, c), points, gamma22: look.gamma22 })
}

/// 8-bit grey image, row-major, upright.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct GrayImage {
    pub width: u32,
    pub height: u32,
    pub data: Vec<u8>,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct Timing {
    pub ingest_ms: u64,
    pub separate_ms: u64,
    pub mix_ms: u64,
    pub preview_ms: u64,
    pub total_ms: u64,
}

/// What a development produced (the negative's `ImageDescription` JSON is
/// in `json`, verbatim as the desktop CLI writes it).
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct DevelopInfo {
    /// Negative size in the sensor frame (after crop and binning).
    pub width: u32,
    pub height: u32,
    /// Size after the file orientation is applied.
    pub upright_width: u32,
    pub upright_height: u32,
    pub orientation: u32,
    pub pattern: BayerPattern,
    pub binning: u32,
    pub separation: SeparationMode,
    pub reconstruct: bool,
    /// Balance multipliers R,G,B applied before the separation (G = 1).
    pub wb: Vec<f64>,
    pub wb_source: String,
    pub noise_a: f64,
    pub noise_b: f64,
    /// Weights of the current mix on the balanced channels (filter included).
    pub weights: Weights,
    /// The same weights on the raw channels.
    pub weights_raw: Weights,
    pub filter: Filter,
    /// Weights the camera file suggests for this balance (native if none).
    pub default_weights: Weights,
    /// Fraction of pixels flagged saturated (after dilation).
    pub saturated_fraction: f64,
    pub defects: u64,
    pub timing: Timing,
    pub json: String,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ExportInfo {
    pub path: String,
    pub width: u32,
    pub height: u32,
    pub ms: u64,
}
