#!/usr/bin/env bash
# Put one version on everything that carries it, and pin the core.
#
#   scripts/set_version.sh <version>          e.g. scripts/set_version.sh 0.14.22
#
# Writes
#   android/app/build.gradle.kts        versionName = "<version>", versionCode + 1
#   ios/project.yml                     MARKETING_VERSION: "<version>"
#   rust/mimizan-mobile/Cargo.toml      version = "<version>"  (and Cargo.lock)
#   mimizan-core.ref                    HEAD of ../mimizan, the core the CI builds against
#
# It refuses when ../mimizan has uncommitted changes under crates/mimizan-core
# or when its HEAD is not on origin: the workflows check that commit out.
# Nothing is committed; the diff is yours to read and commit ("<version>: ...").
#
# Then commit, push, and
#   gh release create v<version> --title <version> --notes "..."
# The v* tag starts TestFlight and the Android Release APK. For Android
# alone, tag android-<version> instead; nothing on the iOS side listens
# to that.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
core="$here/../mimizan"
version="${1:-}"

if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "usage: scripts/set_version.sh <major.minor.patch>" >&2
    exit 2
fi

gradle="$here/android/app/build.gradle.kts"
# GNU and BSD sed differ on `-i`; a backup suffix works on both.
sedi() { sed -i.bak "$@" && rm -f "${@: -1}.bak"; }
project="$here/ios/project.yml"
cargo="$here/rust/mimizan-mobile/Cargo.toml"
ref="$here/mimizan-core.ref"

# --- the core -----------------------------------------------------------------
[[ -d "$core/.git" ]] || { echo "ERROR: $core is not a git checkout of Draganito/mimizan" >&2; exit 1; }
if [[ -n "$(git -C "$core" status --porcelain -- crates/mimizan-core Cargo.toml Cargo.lock)" ]]; then
    echo "ERROR: ../mimizan has uncommitted changes in the core; commit or stash them first" >&2
    exit 1
fi
core_sha=$(git -C "$core" rev-parse HEAD)
git -C "$core" fetch --quiet origin || echo "NOTE: could not fetch ../mimizan; checking against the last known origin" >&2
if ! git -C "$core" merge-base --is-ancestor "$core_sha" origin/main; then
    echo "ERROR: ../mimizan HEAD $core_sha is not on origin/main; push it first, the CI checks it out" >&2
    exit 1
fi

# --- the three version carriers ----------------------------------------------
old_name=$(sed -n 's/^ *versionName = "\([^"]*\)"/\1/p' "$gradle")
old_code=$(sed -n 's/^ *versionCode = \([0-9]*\)/\1/p' "$gradle")
[[ -n "$old_name" && -n "$old_code" ]] || { echo "ERROR: versionName/versionCode not found in $gradle" >&2; exit 1; }
new_code=$((old_code + 1))

sedi "s/^\( *\)versionCode = $old_code\$/\1versionCode = $new_code/" "$gradle"
sedi "s/^\( *\)versionName = \"$old_name\"\$/\1versionName = \"$version\"/" "$gradle"
sedi "s/^\( *\)MARKETING_VERSION: \"[^\"]*\"/\1MARKETING_VERSION: \"$version\"/" "$project"
# Only the package's own version line: the first `version = "..."` after [package].
awk -v v="$version" '
    /^\[/ { in_pkg = ($0 == "[package]") }
    in_pkg && !done && /^version = "/ { $0 = "version = \"" v "\""; done = 1 }
    { print }
' "$cargo" > "$cargo.tmp" && mv "$cargo.tmp" "$cargo"
(cd "$here/rust/mimizan-mobile" && cargo update --quiet --offline -p mimizan-mobile)
printf '%s\n' "$core_sha" > "$ref"

# --- check --------------------------------------------------------------------
grep -q "versionName = \"$version\"" "$gradle"
grep -q "versionCode = $new_code" "$gradle"
grep -q "MARKETING_VERSION: \"$version\"" "$project"
grep -q "^version = \"$version\"" "$cargo"
# This package's entry in the lock file, not any dependency at the same number.
awk -v v="$version" '
    /^name = "mimizan-mobile"$/ { here = 1; next }
    here && /^version = / { found = ($0 == "version = \"" v "\""); exit }
    { if ($0 ~ /^\[\[package\]\]/) here = 0 }
    END { exit !found }
' "$here/rust/mimizan-mobile/Cargo.lock"

echo "==> $old_name ($old_code) -> $version ($new_code)"
echo "==> core $core_sha ($(git -C "$core" log -1 --format='%s'))"
echo
git -C "$here" status --short -- android/app/build.gradle.kts ios/project.yml rust/mimizan-mobile/Cargo.toml rust/mimizan-mobile/Cargo.lock mimizan-core.ref
