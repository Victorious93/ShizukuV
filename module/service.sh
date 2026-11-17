#!/system/bin/sh
# ShizukuV Boot Start (optional root module) - late_start service script.
#
# Starts the ShizukuV server at boot using exactly the command the app's own "start with root" path uses
# (manager/.../starter/Starter.kt `internalCommand`):
#
#     <nativeLibraryDir>/libshizuku.so --apk=<base.apk path>
#
# libshizuku.so is the starter built from manager/src/main/jni/starter.cpp. It refuses to run unless uid is 0 or 2000,
# kills a stale shizukuv_server, forks the server through app_process and exits with
# "info: shizukuv_starter exit with 0". This script adds nothing to that: no setuid, no sepolicy changes.
#
# The app works without this module. If the app is not installed the script logs that and exits cleanly.

MODDIR=$(cd "$(dirname "$0")" && pwd)

PKG=com.victorious.shizukuv
SERVER_NAME=shizukuv_server
STARTER_NAME=libshizuku.so
DONE_MARKER='shizukuv_starter exit with 0'

BOOT_TIMEOUT=${SHIZUKUV_BOOT_TIMEOUT:-300}
APP_TIMEOUT=${SHIZUKUV_APP_TIMEOUT:-120}
ATTEMPTS=${SHIZUKUV_START_ATTEMPTS:-3}
RETRY_DELAY=${SHIZUKUV_RETRY_DELAY:-10}

export PATH="$PATH:/system/bin:/system/xbin"

LOG_DIR=${SHIZUKUV_LOG_DIR:-/data/local/tmp}
LOG="$LOG_DIR/shizukuv-module.log"
if ! { mkdir -p "$LOG_DIR" && : >> "$LOG"; } 2>/dev/null; then
    LOG="$MODDIR/boot.log"
fi
if [ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 100000 ]; then
    mv -f "$LOG" "$LOG.1"
fi
chmod 0644 "$LOG" 2>/dev/null

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') [shizukuv-module] $*" >> "$LOG"
}

server_running() {
    if pidof "$SERVER_NAME" > /dev/null 2>&1; then
        return 0
    fi
    for p in /proc/[0-9]*; do
        [ "$(tr '\0' '\n' < "$p/cmdline" 2> /dev/null | head -n 1)" = "$SERVER_NAME" ] && return 0
    done
    return 1
}

wait_for_boot() {
    waited=0
    while [ "$(getprop sys.boot_completed)" != 1 ]; do
        if [ "$waited" -ge "$BOOT_TIMEOUT" ]; then
            return 1
        fi
        sleep 2
        waited=$((waited + 2))
    done
    return 0
}

# Prints the base APK path of the installed app (same source as the starter's own fallback: `pm path`).
find_apk() {
    waited=0
    while :; do
        paths=$(pm path "$PKG" 2> /dev/null | sed -n 's/^package://p')
        if [ -n "$paths" ]; then
            apk=$(printf '%s\n' "$paths" | grep '/base\.apk$' | head -n 1)
            [ -n "$apk" ] || apk=$(printf '%s\n' "$paths" | head -n 1)
            echo "$apk"
            return 0
        fi
        if [ "$waited" -ge "$APP_TIMEOUT" ]; then
            return 1
        fi
        sleep 5
        waited=$((waited + 5))
    done
}

# Prints the starter inside the app's extracted native library dir (<apk dir>/lib/<abi>/), preferring the primary ABI.
find_starter() {
    lib_root="$1/lib"
    abis="$(getprop ro.product.cpu.abilist),$(getprop ro.product.cpu.abi)"
    old_ifs=$IFS
    IFS=,
    for abi in $abis; do
        case "$abi" in
            arm64-v8a) dir=arm64 ;;
            armeabi-v7a | armeabi) dir=arm ;;
            x86) dir=x86 ;;
            x86_64) dir=x86_64 ;;
            *) continue ;;
        esac
        if [ -f "$lib_root/$dir/$STARTER_NAME" ]; then
            IFS=$old_ifs
            echo "$lib_root/$dir/$STARTER_NAME"
            return 0
        fi
    done
    IFS=$old_ifs
    for f in "$lib_root"/*/"$STARTER_NAME"; do
        if [ -f "$f" ]; then
            echo "$f"
            return 0
        fi
    done
    return 1
}

run_starter() {
    starter="$1"
    apk="$2"
    tmp_out="$LOG_DIR/.shizukuv-module-starter.$$"
    "$starter" "--apk=$apk" > "$tmp_out" 2>&1
    rc=$?
    while IFS= read -r line; do
        log "starter: $line"
    done < "$tmp_out"
    ok=1
    if [ "$rc" -eq 0 ] && grep -q "$DONE_MARKER" "$tmp_out"; then
        ok=0
    fi
    rm -f "$tmp_out"
    log "starter exit code $rc"
    return "$ok"
}

main() {
    log "start (module dir $MODDIR)"

    if ! wait_for_boot; then
        log "sys.boot_completed not set after ${BOOT_TIMEOUT}s; giving up"
        return 0
    fi
    log "boot completed"

    if server_running; then
        log "$SERVER_NAME is already running (for example started by the app); nothing to do"
        return 0
    fi

    if ! apk=$(find_apk); then
        log "$PKG is not installed (waited ${APP_TIMEOUT}s); nothing to do. Install the app and reboot, or start the service from the app"
        return 0
    fi
    log "app apk: $apk"

    if ! starter=$(find_starter "$(dirname "$apk")"); then
        log "no $STARTER_NAME under $(dirname "$apk")/lib; the installed app build does not ship the starter. Not starting"
        return 0
    fi
    if [ ! -x "$starter" ]; then
        log "$starter is not executable. Not starting"
        return 0
    fi
    log "starter: $starter"

    attempt=1
    while [ "$attempt" -le "$ATTEMPTS" ]; do
        log "start attempt $attempt of $ATTEMPTS"
        if run_starter "$starter" "$apk"; then
            sleep 3
            if server_running; then
                log "$SERVER_NAME is running"
            else
                log "starter reported success but $SERVER_NAME is not visible yet; open the app to check the status"
            fi
            return 0
        fi
        attempt=$((attempt + 1))
        [ "$attempt" -le "$ATTEMPTS" ] && sleep "$RETRY_DELAY"
    done
    log "giving up after $ATTEMPTS attempts; start the service from the app instead"
    return 0
}

# Run detached so a slow boot or a missing app never delays other modules' service scripts.
main < /dev/null >> "$LOG" 2>&1 &
exit 0
