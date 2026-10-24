//! The core no longer exports a version constant; read it from the
//! workspace manifest the path dependency lives in, for `core_version()`.

use std::{env, fs, path::Path};

fn main() {
    let manifest = Path::new(&env::var("CARGO_MANIFEST_DIR").unwrap()).join("../../../mimizan/Cargo.toml");
    println!("cargo:rerun-if-changed={}", manifest.display());
    let version = fs::read_to_string(&manifest)
        .ok()
        .and_then(|text| {
            text.lines().map(str::trim).find_map(|l| {
                l.strip_prefix("version = \"").and_then(|r| r.strip_suffix('"')).map(str::to_string)
            })
        })
        .unwrap_or_else(|| "unknown".to_string());
    println!("cargo:rustc-env=MIMIZAN_CORE_VERSION={version}");
}
