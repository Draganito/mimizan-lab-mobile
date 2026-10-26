//! mimizan-mobile: the Mimizan Lab core (`mimizan-core`) behind a UniFFI
//! surface for the Android app. GPL-3.0-or-later, same as the core. The
//! Kotlin side hands over one Camera2 `RAW_SENSOR` buffer with its metadata;
//! this crate ingests, separates (Alleysson/Dubois, no demosaic), mixes,
//! previews and exports exactly as the desktop Mimizan path does.

#![forbid(unsafe_code)]

uniffi::setup_scaffolding!();

pub mod frame;
pub mod session;
pub mod types;

pub use session::Developed;
pub use types::*;

use std::sync::Arc;

/// Version of this crate.
#[uniffi::export]
pub fn version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// Version of the `mimizan-core` the library was built with.
#[uniffi::export]
pub fn core_version() -> String {
    env!("MIMIZAN_CORE_VERSION").to_string()
}

/// Route `tracing` output to logcat (tag `mimizan`). No-op off Android.
#[uniffi::export]
pub fn init_logging() {
    #[cfg(target_os = "android")]
    {
        use tracing_subscriber::prelude::*;
        let _ = tracing_subscriber::registry().with(tracing_android::layer("mimizan").ok()).try_init();
    }
}

/// Number of worker threads the separation will use.
#[uniffi::export]
pub fn worker_threads() -> u32 {
    rayon::current_num_threads() as u32
}

/// Ingest, separate and mix one frame. Several seconds and several hundred
/// megabytes at 12 MP; call from a background thread. The result keeps the
/// separation so `remix`/`preview`/exports are quick.
#[uniffi::export]
pub fn develop(input: RawInput, params: DevelopParams) -> Result<Arc<Developed>> {
    session::develop_impl(input, params).map(Arc::new)
}

/// The balanced mix weights `develop` will use for `params` and a frame
/// with these `COLOR_CORRECTION_GAINS` (R,G,B or R,G_even,G_odd,B): camera
/// file or explicit weights, converted to the balanced channels, then the
/// filter. The live viewfinder mixes the ISP's linearised picture with
/// exactly these numbers.
#[uniffi::export]
pub fn mix_weights(params: DevelopParams, wb_gains: Option<Vec<f64>>) -> Result<Weights> {
    session::mix_weights_impl(&params, wb_gains.as_deref()).map(Weights::from_core)
}

/// The plate's three taking filters `Developed::set_plate` will resolve
/// for `params`, a frame with these `COLOR_CORRECTION_GAINS` and this
/// `SENSOR_COLOR_TRANSFORM` (9 values, rows R, G, B, XYZ → camera; the
/// D65 one when the device offers two). The live viewfinder mixes its
/// three positives with exactly these numbers. `Camera` without a matrix
/// resolves to `Channels`.
#[uniffi::export]
pub fn plate_weights(
    params: DevelopParams,
    wb_gains: Option<Vec<f64>>,
    xyz_to_cam: Option<Vec<f64>>,
    filters: PlateFilters,
    space: PlateSpace,
) -> Result<PlateWeights> {
    session::plate_weights_impl(&params, wb_gains.as_deref(), xyz_to_cam.as_deref(), filters, space)
        .map(|w| PlateWeights::from_core(&w))
}
