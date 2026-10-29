#!/usr/bin/env bash
# Build the Rust core for iOS and assemble the Swift package the app links:
#
#   ios/MimizanCore/MimizanMobileFFI.xcframework   static library + C header, device and simulator
#   ios/MimizanCore/Sources/MimizanCore/mimizan_mobile.swift   UniFFI's Swift side
#
# Needs: rustup targets aarch64-apple-ios and aarch64-apple-ios-sim, and
# Xcode (`xcodebuild -create-xcframework`). The Rust part also builds on
# Linux (`--no-xcframework`), which is how it is cross-checked from the
# desktop; only the xcframework step needs a Mac.
#
#   scripts/build_ios.sh [--debug] [--no-xcframework] [--device-only]
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
crate="$here/rust/mimizan-mobile"
pkg="$here/ios/MimizanCore"
profile=release
xcframework=1
simulator=1
for arg in "$@"; do
    case "$arg" in
        --debug) profile=debug ;;
        --no-xcframework) xcframework=0 ;;
        --device-only) simulator=0 ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

targets=(aarch64-apple-ios)
if [ "$simulator" = 1 ]; then targets+=(aarch64-apple-ios-sim); fi

cargo_flags=(--lib --features decode-files --crate-type staticlib)
if [ "$profile" = release ]; then cargo_flags+=(--release); fi

echo "==> Rust ($profile): ${targets[*]}"
for t in "${targets[@]}"; do
    rustup target list --installed | grep -qx "$t" || rustup target add "$t"
    # IPHONEOS_DEPLOYMENT_TARGET is read by rustc for the Mach-O min version.
    (cd "$crate" && IPHONEOS_DEPLOYMENT_TARGET=17.0 cargo rustc --target "$t" "${cargo_flags[@]}")
done

echo "==> Swift bindings"
# Library mode reads the UniFFI metadata from a built library. A host
# cdylib is the easiest to build everywhere; it carries the same metadata
# as the iOS static libraries (same features).
(cd "$crate" && cargo build --quiet --lib --features decode-files --release)
host_lib=""
for f in "$crate/target/release/libmimizan_mobile.dylib" "$crate/target/release/libmimizan_mobile.so"; do
    if [ -f "$f" ]; then host_lib="$f"; break; fi
done
if [ -z "$host_lib" ]; then echo "host library not found" >&2; exit 1; fi
gen="$pkg/generated"
rm -rf "$gen"
mkdir -p "$gen"
(cd "$crate" && cargo run --quiet --features bindgen --bin uniffi-bindgen -- \
    generate --library "$host_lib" --language swift --no-format --out-dir "$gen")

mkdir -p "$pkg/Sources/MimizanCore"
cp "$gen/mimizan_mobile.swift" "$pkg/Sources/MimizanCore/mimizan_mobile.swift"
headers="$gen/include"
mkdir -p "$headers"
cp "$gen/mimizan_mobileFFI.h" "$headers/"
cp "$gen/mimizan_mobileFFI.modulemap" "$headers/module.modulemap"

if [ "$xcframework" = 0 ]; then
    echo "==> skipping the xcframework (--no-xcframework)"
    exit 0
fi

echo "==> xcframework"
out="$pkg/MimizanMobileFFI.xcframework"
rm -rf "$out"
args=()
for t in "${targets[@]}"; do
    args+=(-library "$crate/target/$t/$profile/libmimizan_mobile.a" -headers "$headers")
done
xcodebuild -create-xcframework "${args[@]}" -output "$out"
echo "==> $out"
