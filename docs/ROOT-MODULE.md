# Optional root module (Magisk / KernelSU / APatch)

`shizukuv-root-module.zip` starts the ShizukuV server automatically at boot on a rooted device. It is a convenience for
devices **you own and have rooted yourself**.

**It is optional.** ShizukuV works fully without it: start with ADB from a computer, with Wireless debugging on the device,
or with the app's own root start, exactly as before. Nothing in the app depends on the module, and the module changes nothing
in the app.

## What it does

At boot (`late_start` service stage) `service.sh`, running as root:

1. waits for `sys.boot_completed` (up to 5 minutes);
2. does nothing if a `shizukuv_server` process is already running (for example because the app's *Start on boot (root)*
   setting started it first);
3. waits up to 2 minutes for the app `com.victorious.shizukuv` to be installed. If it is not installed it logs that and exits
   with status 0;
4. resolves the app's APK with `pm path com.victorious.shizukuv` and finds the starter `libshizuku.so` in the app's extracted
   native library directory (`<apk dir>/lib/<abi>/`, the same file the app uses);
5. runs the same command as the app's root start (`Starter.internalCommand` in
   `manager/.../starter/Starter.kt`): `libshizuku.so --apk=<base.apk>`. The starter
   (`manager/src/main/jni/starter.cpp`) stops a stale server, starts the new one through `app_process` and prints
   `info: shizukuv_starter exit with 0`. If it fails the script retries (3 attempts, 10 s apart) and then gives up.

The script runs detached so it never delays other modules, always exits 0, and writes a log (see below).

### What it does not do

- No setuid binaries, no extra files outside the module directory, no changes to system partitions (the module has no `system/`
  tree and does not mount anything).
- No SELinux policy changes. The starter itself checks that the root context may talk Binder to apps and refuses to start
  otherwise; the module does not alter that.
- It does not grant any app access. Apps still have to be authorized inside ShizukuV, one by one.
- It does not start anything on a device that is not rooted by its owner: it only runs where a root manager already runs
  module scripts.

## Requirements

- Android 7.0 (API 24) or newer, the same minimum as the app.
- Magisk 20.4 or newer, KernelSU, or APatch.
- The ShizukuV app installed.

## Get the zip

- CI: the **Root module** workflow (`.github/workflows/root-module.yml`) uploads the artifact `shizukuv-root-module`
  (`shizukuv-root-module.zip` plus its `.sha256`). Check the checksum before flashing.
- Locally: `bash module/build.sh` writes `out/shizukuv-root-module.zip`. The build is reproducible (same commit and `zip`
  version give the same bytes). It needs `bash`, `zip`, `unzip` and `git`.

`module.prop` version follows the app: `v<api major>.6.0.r<commit count>.<commit>`, `versionCode` = commit count, as in
`build.gradle`.

## Install

Install the app first (the module still installs without it but does nothing until the app is present).

| Manager | Steps |
|---|---|
| **Magisk** | Modules -> *Install from storage* -> pick `shizukuv-root-module.zip` -> reboot |
| **KernelSU** | Module tab -> *Install* -> pick the zip -> reboot |
| **APatch** | Module tab -> *Install module* (the install icon) -> pick the zip -> reboot |

The installer prints which of Magisk, KernelSU or APatch it detected and the Android API level, and refuses to install below
API 24 (or below Magisk 20.4).

If the app's *Start on boot (root)* setting is also on, only one of the two starts the service at boot; the other either sees it
already running (module) or restarts it (app). Turn the app setting off if you want the module to be the only boot starter.

## Uninstall

Remove the module in the same manager (Magisk: Modules -> remove -> reboot; KernelSU and APatch: Module tab -> uninstall ->
reboot). `uninstall.sh` deletes only the module's log files. A server that is already running is not stopped; stop it in the app
or reboot.

## Logs

`/data/local/tmp/shizukuv-module.log` (readable from `adb shell` without root; rotated to `.log.1` above 100 kB). If that
directory is not writable the log is `boot.log` inside the module directory (`/data/adb/modules/shizukuv/`, root only).

```sh
adb shell cat /data/local/tmp/shizukuv-module.log
```

A good boot looks like:

```
[shizukuv-module] start (module dir /data/adb/modules/shizukuv)
[shizukuv-module] boot completed
[shizukuv-module] app apk: /data/app/~~.../com.victorious.shizukuv-.../base.apk
[shizukuv-module] starter: /data/app/~~.../lib/arm64/libshizuku.so
[shizukuv-module] start attempt 1 of 3
[shizukuv-module] starter: info: shizukuv_starter exit with 0
[shizukuv-module] starter exit code 0
[shizukuv-module] shizukuv_server is running
```

The script's timeouts can be overridden with environment variables for testing (`SHIZUKUV_BOOT_TIMEOUT`, `SHIZUKUV_APP_TIMEOUT`,
`SHIZUKUV_START_ATTEMPTS`, `SHIZUKUV_RETRY_DELAY`, `SHIZUKUV_LOG_DIR`); the defaults are what a boot uses.

## Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| No log file | The module is disabled or removed, or the manager did not run module scripts (check the manager's module list and that the module is enabled), or you did not reboot after installing |
| `... is not installed ... nothing to do` | The app was missing for 2 minutes after boot. Install it and reboot, or start the service from the app |
| `no libshizuku.so under ... /lib` | The installed app build does not ship the starter or the native libraries were not extracted. Reinstall a build from this repository |
| `starter: fatal: the su you are using does not allow app ... binder` | Starter exit code 10. The root solution's SELinux context blocks Binder from apps. The app's own root start fails the same way; fix it in the root manager's settings, the module will not weaken policy |
| `starter: fatal: can't kill ...` | The starter could not kill an existing server process (permission denied). Stop it from the app, then reboot |
| `already running` | Something already started the server (usually the app's *Start on boot (root)*). Nothing to fix |
| Log says started but the app shows stopped | Open the app and use *Diagnostics*; confirm the app's package/permissions are unchanged and try the in-app root start to compare |
| Unsure whether the script works on your device | Re-run it by hand and read the log again: `su -c 'sh /data/adb/modules/shizukuv/service.sh'`. It is safe to repeat: it does nothing if the server is already running |

## Status of verification

See [VERIFICATION.md](VERIFICATION.md). The scripts are checked with ShellCheck and a host-side test with stubbed commands; the
zip is checked for layout and reproducibility. Flashing in each manager and the boot start on a real device have **not** been
tested by the author of this change.

## License

Apache License 2.0, like the rest of the repository (see [LICENSE](../LICENSE)). The starter it launches is part of the ShizukuV
app, derived from RikkaApps/Shizuku; upstream copyright and notices are unchanged (see the README).
