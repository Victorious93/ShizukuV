#!/usr/bin/env bash
# Packaging checks on the built APKs: identity, permissions, native libraries per ABI, rish dex, no upstream ids.
set -euo pipefail
cd "$(dirname "$0")/.."
BT="${ANDROID_HOME:?ANDROID_HOME is required}/build-tools/$(sed -n 's/.*buildToolsVersion = "\(.*\)"/\1/p' build.gradle | head -1)"
want_id="$(sed -n "s/.*bridgeApplicationId = '\(.*\)'/\1/p" build.gradle)"
shopt -s nullglob; apks=(manager/build/outputs/apk/*/victorious-bridge-*.apk)
[ ${#apks[@]} -gt 0 ] || { echo "no APKs found; build first" >&2; exit 1; }
fail=0
for apk in "${apks[@]}"; do
  echo "== $apk"
  badging="$("$BT/aapt2" dump badging "$apk" 2>/dev/null)"
  grep -q "^package: name='$want_id'" <<<"$badging" || { echo "FAIL: package id is not $want_id"; fail=1; }
  perms="$("$BT/aapt2" dump permissions "$apk" 2>/dev/null)"
  for p in "$want_id.permission.MANAGER" "$want_id.permission.LEGACY_API_V1"; do grep -q "permission: $p" <<<"$perms" || { echo "FAIL: permission $p not declared"; fail=1; }; done
  grep -q "moe.shizuku.manager.permission" <<<"$perms" && { echo "FAIL: upstream permission declared"; fail=1; }
  list="$(unzip -Z1 "$apk")"
  for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    for so in libshizuku.so libadb.so librish.so; do grep -qx "lib/$abi/$so" <<<"$list" || { echo "FAIL: lib/$abi/$so missing"; fail=1; }; done
  done
  grep -qx "assets/rish" <<<"$list" && grep -qx "assets/rish_shizuku.dex" <<<"$list" || { echo "FAIL: rish assets missing"; fail=1; }
  tmp="$(mktemp -d)"; unzip -q -o "$apk" -d "$tmp"
  for f in "$tmp"/classes*.dex "$tmp"/assets/rish_shizuku.dex "$tmp"/lib/*/*.so; do
    for s in moe.shizuku.privileged.api moe.shizuku.manager.permission shizuku_server; do grep -aq "$s" "$f" && { echo "FAIL: '$s' found in ${f#$tmp/}"; fail=1; }; done
  done
  grep -aq "$want_id" "$tmp/assets/rish_shizuku.dex" || { echo "FAIL: rish dex does not target $want_id"; fail=1; }
  rm -rf -- "$tmp"
done
[ $fail -eq 0 ] && echo "OK: all packaging checks passed" || exit 1
