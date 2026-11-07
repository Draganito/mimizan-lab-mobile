#!/usr/bin/env bash
# Build the `spike` binary for arm64 Android, push it to a connected phone
# and run it: development time and peak RSS for a 4080x3072 frame with the
# separation on and with the mask off, plus the two JPEG exports. Needs adb
# with one device.
set -euo pipefail
cd "$(dirname "$0")/../rust/mimizan-mobile"

ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/opt/android-sdk}"
export ANDROID_HOME
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    # Assigned apart from the export: `export X=$(...)` hides a failed command.
    ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)"
fi
export ANDROID_NDK_HOME

# The same platform as the app's minSdk (gradle/libs.versions.toml).
cargo ndk -t arm64-v8a --platform 33 build --release --bin spike
BIN=target/aarch64-linux-android/release/spike
adb push "$BIN" /data/local/tmp/mimizan-spike >/dev/null
adb shell chmod 755 /data/local/tmp/mimizan-spike
echo "device: $(adb shell getprop ro.product.model) / $(adb shell getprop ro.build.version.release)"
adb shell /data/local/tmp/mimizan-spike "$@"
