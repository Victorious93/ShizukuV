#!/usr/bin/env bash
# Builds the optional ShizukuV root module zip reproducibly and checks its layout.
#
#   bash module/build.sh [output.zip]        default output: out/shizukuv-root-module.zip
#
# Reproducible: files are staged with normalised modes and a fixed timestamp (SOURCE_DATE_EPOCH, defaulting to the
# commit time of HEAD), added in sorted order without extra attributes, so the same commit yields the same bytes
# with the same `zip` version. No secrets are read or written.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
out="${1:-$root/out/shizukuv-root-module.zip}"
case "$out" in /*) ;; *) out="$PWD/$out" ;; esac

files=(
  META-INF/com/google/android/update-binary
  META-INF/com/google/android/updater-script
  customize.sh
  module.prop
  service.sh
  uninstall.sh
)
executables=(
  META-INF/com/google/android/update-binary
  service.sh
  uninstall.sh
)

for f in "${files[@]}"; do
  [ -f "$here/$f" ] || { echo "missing module file: module/$f" >&2; exit 1; }
  if grep -q $'\r' "$here/$f"; then echo "CRLF line endings in module/$f" >&2; exit 1; fi
done

# Version follows the app (build.gradle): <api_version_major>.6.0.r<commit count>.<short commit>; versionCode = commit count.
if git -C "$root" rev-parse --git-dir > /dev/null 2>&1 && count="$(git -C "$root" rev-list --count HEAD 2> /dev/null)"; then
  major="$(sed -n 's/.*api_version_major *= *\([0-9][0-9]*\).*/\1/p' "$root/api/manifest.gradle" | head -n 1)"
  [ -n "$major" ] || { echo "cannot read api_version_major from api/manifest.gradle" >&2; exit 1; }
  version="v${major}.6.0.r${count}.$(git -C "$root" rev-parse --short HEAD)"
  version_code="$count"
  epoch="${SOURCE_DATE_EPOCH:-$(git -C "$root" log -1 --format=%ct)}"
else
  version="vdev"
  version_code=1
  epoch="${SOURCE_DATE_EPOCH:-1700000000}"
fi

stage="$(mktemp -d)"
trap 'rm -rf -- "$stage"' EXIT
export TZ=UTC LC_ALL=C

for f in "${files[@]}"; do
  mkdir -p "$stage/$(dirname "$f")"
  cp "$here/$f" "$stage/$f"
  chmod 0644 "$stage/$f"
done
for f in "${executables[@]}"; do chmod 0755 "$stage/$f"; done

sed -i "s/^version=.*/version=$version/; s/^versionCode=.*/versionCode=$version_code/" "$stage/module.prop"
for f in "${files[@]}" META-INF/com/google/android META-INF/com/google META-INF .; do
  touch -d "@$epoch" "$stage/$f"
done

mkdir -p "$(dirname "$out")"
rm -f -- "$out"
(cd "$stage" && printf '%s\n' "${files[@]}" | sort | zip -q -X -D -9 "$out" -@)

# --- layout checks on the finished zip ---
listing="$(unzip -Z1 "$out")"
expected="$(printf '%s\n' "${files[@]}" | sort)"
[ "$listing" = "$expected" ] || { echo "unexpected zip contents:"; echo "$listing"; exit 1; }
verbose="$(unzip -Z "$out")"
for f in "${executables[@]}"; do
  grep -Eq "^-rwxr-xr-x .* $f\$" <<< "$verbose" || { echo "$f is not stored executable" >&2; exit 1; }
done
prop="$(unzip -p "$out" module.prop)"
for key in id name version versionCode author description; do
  grep -q "^$key=." <<< "$prop" || { echo "module.prop is missing $key" >&2; exit 1; }
done
grep -q '^id=shizukuv$' <<< "$prop" || { echo "module.prop id must be shizukuv" >&2; exit 1; }
grep -Eq '^versionCode=[0-9]+$' <<< "$prop" || { echo "module.prop versionCode must be an integer" >&2; exit 1; }
[ "$(unzip -p "$out" META-INF/com/google/android/updater-script)" = "#MAGISK" ] || { echo "updater-script must be #MAGISK" >&2; exit 1; }

echo "built $out"
echo "version $version (versionCode $version_code), file timestamps $(date -u -d "@$epoch" '+%Y-%m-%d %H:%M:%S') UTC"
(cd "$(dirname "$out")" && sha256sum "$(basename "$out")" | tee "$(basename "$out").sha256")
