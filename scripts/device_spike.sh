#!/usr/bin/env bash
# Build the `spike` binary for arm64 Android, push it to a connected phone
# and run it: development time and peak RSS for a 4080x3072 frame in the
# three modes (Dubois, mask off, 2x binned). Needs adb with one device.
set -euo pipefail
cd "$(dirname "$0")/../rust/mimizan-mobile"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/opt/android-sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"

cargo ndk -t arm64-v8a --platform 34 build --release --bin spike
BIN=target/aarch64-linux-android/release/spike
adb push "$BIN" /data/local/tmp/mimizan-spike >/dev/null
adb shell chmod 755 /data/local/tmp/mimizan-spike
echo "device: $(adb shell getprop ro.product.model) / $(adb shell getprop ro.build.version.release)"
adb shell /data/local/tmp/mimizan-spike "$@"
