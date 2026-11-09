#!/usr/bin/env bash
# Builds the release APK from a clean tree and checks that it matches a published APK
# byte for byte, apart from the signature block.
#
#   scripts/reproducible-build.sh path/to/published.apk
#
# Without an argument it builds twice and compares the two builds with each other.
# Needs: JDK 17, Android SDK, unzip, the same AGP/Kotlin/Gradle
# versions as pinned in gradle/ and gradle/wrapper/. The signing key is NOT needed: when
# key.properties is absent the release build is left unsigned, and signatures are stripped
# before comparing anyway.
set -euo pipefail

cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Lists every entry of an APK with its SHA-256, except the signature. APK signature scheme
# v2/v3 lives in a block outside the ZIP entries and is dropped by extraction; v1 would be
# META-INF/*.{SF,RSA,EC,MF}. Comparing manifests needs no re-zipping and no extra tools.
entry_manifest() {
  local apk=$1 out=$2 dir
  dir=$(mktemp -d -p "$work")
  unzip -q "$apk" -d "$dir"
  rm -f "$dir/META-INF/"*.SF "$dir/META-INF/"*.RSA "$dir/META-INF/"*.EC "$dir/META-INF/MANIFEST.MF"
  (cd "$dir" && find . -type f | LC_ALL=C sort | xargs sha256sum) > "$out"
}

build() {
  ./gradlew --quiet --no-build-cache --no-configuration-cache clean :app:assembleRelease
  cp app/build/outputs/apk/release/app-release*.apk "$1"
}

echo "Building release APK from clean..."
build "$work/local.apk"

if [[ $# -ge 1 ]]; then
  reference=$1
else
  echo "No reference APK given; building a second time to check determinism..."
  build "$work/second.apk"
  reference="$work/second.apk"
fi

entry_manifest "$work/local.apk" "$work/local.manifest"
entry_manifest "$reference" "$work/reference.manifest"

echo "whole-file SHA-256 (differs only if the signatures differ):"
sha256sum "$work/local.apk" "$reference"
if cmp -s "$work/local.manifest" "$work/reference.manifest"; then
  echo "MATCH: every entry of the published APK is identical to this build."
else
  echo "MISMATCH. Differing entries:"
  diff "$work/local.manifest" "$work/reference.manifest" || true
  exit 1
fi
