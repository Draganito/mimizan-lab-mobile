//! `core_version()` is the version of the locked `mimizan-core` package.
//! The core is a git dependency (`Cargo.toml` `rev`); the sibling checkout
//! is optional and only a fallback when the lock file is not there yet.

use std::{env, fs, path::Path};

fn main() {
    let here = Path::new(&env::var("CARGO_MANIFEST_DIR").unwrap()).to_path_buf();
    let lock = here.join("Cargo.lock");
    let cargo = here.join("Cargo.toml");
    println!("cargo:rerun-if-changed={}", lock.display());
    println!("cargo:rerun-if-changed={}", cargo.display());
    let sibling = here.join("../../../mimizan/Cargo.toml");
    println!("cargo:rerun-if-changed={}", sibling.display());

    let version = version_from_lock(&lock)
        .or_else(|| version_from_manifest(&sibling))
        .unwrap_or_else(|| "unknown".to_string());
    println!("cargo:rustc-env=MIMIZAN_CORE_VERSION={version}");
}

fn version_from_lock(path: &Path) -> Option<String> {
    let text = fs::read_to_string(path).ok()?;
    let mut want = false;
    for line in text.lines() {
        let line = line.trim();
        if line == "name = \"mimizan-core\"" {
            want = true;
            continue;
        }
        if want {
            if let Some(rest) = line.strip_prefix("version = \"") {
                if let Some(v) = rest.strip_suffix('"') {
                    return Some(v.to_string());
                }
            }
            if line.starts_with('[') || line.starts_with("name = ") {
                want = false;
            }
        }
    }
    None
}

fn version_from_manifest(path: &Path) -> Option<String> {
    fs::read_to_string(path).ok().and_then(|text| {
        text.lines().map(str::trim).find_map(|l| {
            l.strip_prefix("version = \"").and_then(|r| r.strip_suffix('"')).map(str::to_string)
        })
    })
}
