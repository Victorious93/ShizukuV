# shellcheck shell=sh
# ShizukuV Boot Start - installer customization, sourced by the Magisk / KernelSU / APatch module installer.
# Variables such as MODPATH, API and ZIPFILE are provided by the installer.

MIN_API=24
MIN_MAGISK_VER_CODE=20400

ui_print "- ShizukuV Boot Start (optional)"

# APatch also sets KSU=true for compatibility, so it has to be checked first.
if [ -n "$APATCH" ]; then
    ROOT_MANAGER="APatch"
    ui_print "- Root solution: APatch (version code ${APATCH_VER_CODE:-unknown})"
elif [ -n "$KSU" ]; then
    ROOT_MANAGER="KernelSU"
    ui_print "- Root solution: KernelSU (version ${KSU_VER:-unknown}, code ${KSU_VER_CODE:-unknown})"
else
    ROOT_MANAGER="Magisk"
    ui_print "- Root solution: Magisk (${MAGISK_VER:-unknown}, code ${MAGISK_VER_CODE:-unknown})"
    if [ -n "$MAGISK_VER_CODE" ] && [ "$MAGISK_VER_CODE" -lt "$MIN_MAGISK_VER_CODE" ]; then
        abort "! Magisk 20.4 or newer is required"
    fi
fi

DEVICE_API=${API:-$(getprop ro.build.version.sdk)}
ui_print "- Android API level: ${DEVICE_API:-unknown}"
if [ -z "$DEVICE_API" ] || [ "$DEVICE_API" -lt "$MIN_API" ]; then
    abort "! Android API $MIN_API (7.0) or newer is required"
fi

if pm path com.victorious.shizukuv > /dev/null 2>&1; then
    ui_print "- ShizukuV app found"
else
    ui_print "! ShizukuV app (com.victorious.shizukuv) not found right now."
    ui_print "  The module is still installed; it does nothing until the app is installed."
fi

ui_print "- This module is optional. The app also starts with ADB, wireless debugging or its own root start."
ui_print "- Installing for $ROOT_MANAGER"
ui_print "- Boot log: /data/local/tmp/shizukuv-module.log"

# Called by the installer after the module files are extracted into $MODPATH.
set_permissions() {
    set_perm_recursive "$MODPATH" 0 0 0755 0644
    set_perm "$MODPATH/service.sh" 0 0 0755
    set_perm "$MODPATH/uninstall.sh" 0 0 0755
}
