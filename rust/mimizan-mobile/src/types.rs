//! The FFI surface: plain records and enums the Kotlin side builds and reads.
//! Every number keeps the unit and meaning of its `mimizan-core` counterpart
//! (`docs/SPEC.md` in the mimizan repository).

use mimizan_core::cfa::BayerPhase;
use mimizan_core::ingest::WhiteBalance;
use mimizan_core::mix::{ColorFilter, WeightSpace as CoreWeightSpace, Weights as CoreWeights};
use mimizan_core::plate::{
    PlateFilters as CorePlateFilters, PlateSpace as CorePlateSpace, PlateWeights as CorePlateWeights,
};
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

/// What a RAW file is (`frame_info`): the sensor and the metadata the roll
/// shows and the camera file lookup needs, without the pixels.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct FrameInfo {
    pub make: String,
    pub model: String,
    /// Sensor frame size, before the crop.
    pub width: u32,
    pub height: u32,
    pub pattern: BayerPattern,
    /// TIFF orientation (1..8) that makes the picture upright.
    pub orientation: u32,
    /// As-shot balance multipliers R,G,B, if the file carries them.
    pub wb_gains: Option<Vec<f64>>,
    /// XYZ → camera, 9 values row-major, if the file carries a matrix.
    pub xyz_to_cam: Option<Vec<f64>>,
    pub exposure: ExposureInfo,
    /// EXIF `DateTimeOriginal`, `YYYY:MM:DD HH:MM:SS`, if present.
    pub captured: Option<String>,
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
    Manual {
        r: f64,
        g: f64,
        b: f64,
    },
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
    if !(w.r >= 0.0 && w.g >= 0.0 && w.b >= 0.0) {
        return Err(MimizanError::Invalid { msg: "weights must be finite and non-negative".into() });
    }
    let s = w.r + w.g + w.b;
    if s <= 0.0 {
        return Err(MimizanError::Invalid { msg: "weights must not all be zero".into() });
    }
    Ok(Weights { r: w.r / s, g: w.g / s, b: w.b / s })
}

/// The taking filters of the three-colour plate (`plate.rs` in the core):
/// how the three positives are derived from the balanced channels.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum PlateFilters {
    /// The camera's colour matrix towards the output primaries
    /// (colorimetric; falls back to `Channels` without a matrix).
    Camera,
    /// Wratten 25 / 58 / 47 in front of the panchromatic emulsion.
    Wratten,
    /// The balanced channels as they are (Library of Congress).
    Channels,
}

impl PlateFilters {
    pub fn core(self) -> CorePlateFilters {
        match self {
            Self::Camera => CorePlateFilters::Camera,
            Self::Wratten => CorePlateFilters::Wratten,
            Self::Channels => CorePlateFilters::Channels,
        }
    }
    pub fn from_core(f: CorePlateFilters) -> Self {
        match f {
            CorePlateFilters::Camera => Self::Camera,
            CorePlateFilters::Wratten => Self::Wratten,
            CorePlateFilters::Channels => Self::Channels,
        }
    }
}

/// The projection primaries: the RGB colour space of the colour file.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum PlateSpace {
    /// Adobe RGB (1998): its curve is the paper's gamma 2.2, its gamut
    /// holds the saturated primaries.
    AdobeRgb,
    /// sRGB primaries with the paper's gamma 2.2 (the gallery, sharing).
    Srgb,
}

impl PlateSpace {
    pub fn core(self) -> CorePlateSpace {
        match self {
            Self::AdobeRgb => CorePlateSpace::AdobeRgb,
            Self::Srgb => CorePlateSpace::Srgb,
        }
    }
    pub fn from_core(s: CorePlateSpace) -> Self {
        match s {
            CorePlateSpace::AdobeRgb => Self::AdobeRgb,
            CorePlateSpace::Srgb => Self::Srgb,
        }
    }
}

/// The three taking filters as weight triples on the balanced channels,
/// by output channel. Each sums to one so grey gives three equal plates;
/// the `Camera` set has negative weights off the diagonal.
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct PlateWeights {
    pub red: Weights,
    pub green: Weights,
    pub blue: Weights,
    /// The set the rows came from (`Camera` becomes `Channels` when the
    /// frame carries no colour matrix).
    pub filters: PlateFilters,
}

impl PlateWeights {
    pub fn from_core(w: &CorePlateWeights) -> Self {
        let [r, g, b] = w.weights();
        Self {
            red: Weights::from_core(r),
            green: Weights::from_core(g),
            blue: Weights::from_core(b),
            filters: PlateFilters::from_core(w.filters),
        }
    }
}

/// The plate process of a development, as `DevelopInfo` reports it.
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct PlateState {
    pub weights: PlateWeights,
    pub space: PlateSpace,
    /// Stops of exposure added to the red and the blue positive; green is
    /// the reference.
    pub lamp_red: f64,
    pub lamp_blue: f64,
}

/// How far either lamp may move, in stops (the core's `print::LAMP_LIMIT`).
#[uniffi::export]
pub fn lamp_limit() -> f64 {
    mimizan_core::print::LAMP_LIMIT
}

/// 8-bit RGB image, interleaved, row-major, upright. The values are the
/// paper's encoding (gamma 2.2) in the primaries of the plate's space.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct RgbImage {
    pub width: u32,
    pub height: u32,
    pub data: Vec<u8>,
}

/// Everything a development needs (ingest + separation + first mix).
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct DevelopParams {
    pub separation: SeparationMode,
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

/// The split-grade print the desktop enlarger uses. `soft` and `hard` are
/// stops of time from the paper's base exposure, within ±[`paper_stop_limit`].
pub(crate) fn split_grade(soft: f64, hard: f64) -> Result<mimizan_core::paper::SplitGrade> {
    let s = mimizan_core::paper::SplitGrade::new(soft, hard);
    s.validate()?;
    Ok(s)
}

/// How far either exposure may move, in stops. The desktop slider uses the
/// same limit.
#[uniffi::export]
pub fn paper_stop_limit() -> f64 {
    mimizan_core::paper::STOP_LIMIT
}

/// `n + 1` display values of the paper over the *linear* axis: entry `i` is
/// the print value of linear `i/n`, the same function the file uses. The
/// viewfinder indexes this table with linear grey (no extra gamma: the
/// paper's encoding is already in the value).
#[uniffi::export]
pub fn paper_lut(soft: f64, hard: f64, n: u32) -> Result<Vec<f32>> {
    let map = mimizan_core::tone::Tone::Paper(split_grade(soft, hard)?).compile();
    let n = n.clamp(1, 4096) as usize;
    Ok((0..=n).map(|i| map.eval(i as f64 / n as f64) as f32).collect())
}

/// The single-filter grade this pair of exposures equals, and the scene
/// stops between the paper's first tone and its near-black.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct PaperGrade {
    pub label: String,
    pub scene_stops: f64,
}

#[uniffi::export]
pub fn paper_grade(soft: f64, hard: f64) -> Result<PaperGrade> {
    let curve = split_grade(soft, hard)?.curve();
    Ok(PaperGrade {
        label: mimizan_core::paper::grade_label(curve.grade()),
        scene_stops: curve.scene_range_stops(),
    })
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
    /// The frame came with a colour matrix (`SENSOR_COLOR_TRANSFORM`), so
    /// the `Camera` plate filters are the camera's own.
    pub has_colour_matrix: bool,
    /// Present while the development is in the plate process.
    pub plate: Option<PlateState>,
    pub timing: Timing,
    pub json: String,
}

/// Peak's high-pass as measured on one negative (`peak.rs` in the core):
/// the Gaussian σ in pixels of the negative and the coefficient in
/// `x + amount · (x − blur)`. `found` is false when no clean edge was
/// measured; radius is then the default and amount zero.
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct PeakMeasured {
    pub radius: f64,
    pub amount: f64,
    pub found: bool,
}

/// The range the core accepts for the peak fields (`recipe.rs`).
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct PeakLimits {
    pub radius_default: f64,
    pub radius_min: f64,
    pub radius_max: f64,
    pub amount_max: f64,
}

#[uniffi::export]
pub fn peak_limits() -> PeakLimits {
    use mimizan_core::recipe::{PEAK_AMOUNT_MAX, PEAK_RADIUS_DEFAULT, PEAK_RADIUS_MAX, PEAK_RADIUS_MIN};
    PeakLimits {
        radius_default: PEAK_RADIUS_DEFAULT,
        radius_min: PEAK_RADIUS_MIN,
        radius_max: PEAK_RADIUS_MAX,
        amount_max: PEAK_AMOUNT_MAX,
    }
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ExportInfo {
    pub path: String,
    pub width: u32,
    pub height: u32,
    pub ms: u64,
}
