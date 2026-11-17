#!/usr/bin/env bash
# Host-side test of module/service.sh with stubbed getprop / pm / pidof / starter. No device, no root.
# It checks the script logic only (waiting, path resolution, arguments, retries, clean exits).
# It does NOT start a real server and says nothing about behaviour on Android.
#
#   bash module/tests/service-test.sh        (SH=dash|ash|busybox... selects the interpreter, default sh)
set -u

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
service="$here/../service.sh"
SH="${SH:-sh}"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
fails=0
total=0

check() { # description, command...
  local desc="$1"; shift
  total=$((total + 1))
  if "$@"; then echo "  ok   $desc"; else echo "  FAIL $desc"; fails=$((fails + 1)); fi
}
has() { grep -q -- "$1" "$2"; }
hasnt() { ! grep -q -- "$1" "$2"; }

# shellcheck disable=SC2016  # the generated stub scripts must expand $0/$1 when they run, not now
# scenario <name> <boot_completed 0|1> <abilist> <app installed yes|no> <starter behaviour ok|fail> <pidof running yes|no> [extra env...]
scenario() {
  local name="$1" boot="$2" abilist="$3" installed="$4" behaviour="$5" running="$6"; shift 6
  local d="$work/$name"
  mkdir -p "$d/bin" "$d/log" "$d/app/lib/arm64" "$d/app/lib/arm"
  printf '#!/bin/sh\ncase "$1" in\n sys.boot_completed) echo %s ;;\n ro.product.cpu.abilist) echo %s ;;\n *) ;;\nesac\n' "$boot" "$abilist" > "$d/bin/getprop"
  if [ "$installed" = yes ]; then
    printf '#!/bin/sh\necho package:%s/app/split_config.arm64_v8a.apk\necho package:%s/app/base.apk\n' "$d" "$d" > "$d/bin/pm"
  else
    printf '#!/bin/sh\nexit 1\n' > "$d/bin/pm"
  fi
  if [ "$running" = yes ]; then printf '#!/bin/sh\necho 4242\n' > "$d/bin/pidof"; else printf '#!/bin/sh\nexit 1\n' > "$d/bin/pidof"; fi
  for abi in arm64 arm; do
    if [ "$behaviour" = ok ]; then
      printf '#!/bin/sh\necho "$0 $*" >> %s/starter-calls\necho "info: starter begin"\necho "info: shizukuv_starter exit with 0"\n' "$d" > "$d/app/lib/$abi/libshizuku.so"
    else
      printf '#!/bin/sh\necho "$0 $*" >> %s/starter-calls\necho "fatal: can'"'"'t access manager"\nexit 7\n' "$d" > "$d/app/lib/$abi/libshizuku.so"
    fi
    chmod 0755 "$d/app/lib/$abi/libshizuku.so"
  done
  : > "$d/starter-calls"
  chmod 0755 "$d/bin/"*
  env PATH="$d/bin:$PATH" SHIZUKUV_LOG_DIR="$d/log" SHIZUKUV_BOOT_TIMEOUT=0 SHIZUKUV_APP_TIMEOUT=0 SHIZUKUV_RETRY_DELAY=0 \
    "$@" "$SH" "$service"
  local rc=$? i=0 log="$d/log/shizukuv-module.log"
  # service.sh detaches its worker; wait for its final line.
  while [ $i -lt 100 ]; do
    grep -Eq 'nothing to do|giving up|is running|not visible yet|Not starting' "$log" 2> /dev/null && break
    sleep 0.2; i=$((i + 1))
  done
  echo "$rc" > "$d/rc"
}

echo "interpreter: $SH"

echo "scenario: happy path (arm64, split APK listed first, base.apk must win)"
scenario happy 1 arm64-v8a,armeabi-v7a yes ok no
d="$work/happy"
check "service.sh returns 0 immediately" test "$(cat "$d/rc")" = 0
check "starter called once" test "$(wc -l < "$d/starter-calls")" -eq 1
check "starter is the arm64 libshizuku.so" has "app/lib/arm64/libshizuku.so --apk=$d/app/base.apk" "$d/starter-calls"
check "log records the attempt and exit code 0" has "starter exit code 0" "$d/log/shizukuv-module.log"

echo "scenario: ABI list prefers the device primary ABI"
scenario abi 1 armeabi-v7a yes ok no
check "arm starter used" has "app/lib/arm/libshizuku.so --apk=" "$work/abi/starter-calls"

echo "scenario: app not installed"
scenario missing 1 arm64-v8a no ok no
d="$work/missing"
check "exits 0" test "$(cat "$d/rc")" = 0
check "starter never called" test ! -s "$d/starter-calls"
check "log says app is not installed" has "is not installed" "$d/log/shizukuv-module.log"

echo "scenario: server already running"
scenario running 1 arm64-v8a yes ok yes
d="$work/running"
check "starter never called" test ! -s "$d/starter-calls"
check "log says already running" has "already running" "$d/log/shizukuv-module.log"

echo "scenario: starter fails every time (2 attempts)"
scenario fail 1 arm64-v8a yes fail no SHIZUKUV_START_ATTEMPTS=2
d="$work/fail"
check "exits 0 even though the starter failed" test "$(cat "$d/rc")" = 0
check "starter called twice" test "$(wc -l < "$d/starter-calls")" -eq 2
check "log shows exit code 7 and giving up" has "giving up after 2 attempts" "$d/log/shizukuv-module.log"
check "starter output is logged" has "can't access manager" "$d/log/shizukuv-module.log"

echo "scenario: boot never completes"
scenario noboot 0 arm64-v8a yes ok no
d="$work/noboot"
check "starter never called" test ! -s "$d/starter-calls"
check "log says boot_completed not set" has "sys.boot_completed not set" "$d/log/shizukuv-module.log"

echo "scenario: package installed without starter library"
scenario nolib 1 x86_64 yes ok no
rm -f "$work/nolib/app/lib/"*/libshizuku.so
# re-run against the same directory now that the libraries are gone
: > "$work/nolib/log/shizukuv-module.log"
env PATH="$work/nolib/bin:$PATH" SHIZUKUV_LOG_DIR="$work/nolib/log" SHIZUKUV_BOOT_TIMEOUT=0 SHIZUKUV_APP_TIMEOUT=0 "$SH" "$service"
sleep 1
check "log says starter is missing" has "Not starting" "$work/nolib/log/shizukuv-module.log"

echo
echo "$((total - fails)) of $total checks passed"
[ "$fails" -eq 0 ]
