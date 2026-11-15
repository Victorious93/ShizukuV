#!/usr/bin/env bash
# Collect built APKs into out/artifacts with SHA-256 checksums and a build-info file that states, per APK, exactly
# how it is signed. Never signs anything, never reads signing material, and refuses to call an APK "release signed"
# unless apksigner verifies it with a non-debug certificate.
#
# Usage: scripts/release-artifacts.sh            (run after ./gradlew :manager:assembleDebug [:manager:assembleRelease])
set -euo pipefail
cd "$(dirname "$0")/.."
BT="${ANDROID_HOME:-}/build-tools/$(sed -n 's/.*buildToolsVersion = "\(.*\)"/\1/p' build.gradle | head -1)"
APKSIGNER="$BT/apksigner"; AAPT2="$BT/aapt2"
out=out/artifacts; mkdir -p "$out"
commit="$(git rev-parse HEAD)"; dirty=""; [ -n "$(git status --porcelain --untracked-files=no)" ] && dirty=" (working tree had uncommitted changes)"
info="$out/BUILD-INFO.txt"
{
  echo "Victorious Bridge build information"
  echo "commit: $commit$dirty"
  echo "branch: $(git rev-parse --abbrev-ref HEAD)"
  echo "built:  $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
} > "$info"
shopt -s nullglob
for apk in manager/build/outputs/apk/*/victorious-bridge-*.apk; do
  base="$(basename "$apk")"; cp "$apk" "$out/$base"
  sig="UNSIGNED"; kind="unsigned release (cannot be installed; sign it first)"
  if [ -x "$APKSIGNER" ] && v="$("$APKSIGNER" verify --print-certs "$apk" 2>&1)"; then
    if grep -q "CN=Android Debug" <<<"$v"; then sig="DEBUG-SIGNED"; kind="debug build, signed with the public Android debug key; NOT a production release"
    else sig="SIGNED"; kind="signed with a non-debug key (verify the fingerprint is the project's release key)"; fi
  fi
  case "$base" in *-release.apk) [ "$sig" != "SIGNED" ] && { echo "ERROR: $base is named release but is $sig" >&2; exit 1; } ;; esac
  id="$("$AAPT2" dump badging "$apk" 2>/dev/null | sed -n "s/^package: name='\([^']*\)' versionCode='\([^']*\)' versionName='\([^']*\)'.*/\1 versionCode=\2 versionName=\3/p")"
  { echo "$base"; echo "  $id"; echo "  signing: $sig - $kind"; } >> "$info"
done
( cd "$out" && sha256sum -- *.apk > SHA256SUMS && cat SHA256SUMS )
echo "wrote $out/SHA256SUMS and $info"
