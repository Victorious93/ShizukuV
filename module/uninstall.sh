#!/system/bin/sh
# ShizukuV Boot Start - runs when the module is removed. Only deletes files this module created.
# A server that is already running is left alone; it stops with the normal in-app stop or at the next reboot.

rm -f /data/local/tmp/shizukuv-module.log /data/local/tmp/shizukuv-module.log.1
rm -f /data/local/tmp/.shizukuv-module-starter.*
