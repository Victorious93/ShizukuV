# Victorious Bridge (ShizukuV)

**Team Victorious - Victorious Framework Work.** Victorious Bridge is the local privileged-access bridge for Victorious
Android apps. It starts a small service with root or ADB authority, lets the owner authorize apps, and exposes a
**controlled, typed API** whose permissions are enforced inside the privileged service itself.

It is a fork of [Shizuku](https://github.com/RikkaApps/Shizuku) (Apache-2.0, (c) RikkaW). It is **not** Shizuku: it has its
own application id (`ai.victorious.bridge`), permissions, provider authority and artwork so that it can be installed
next to upstream Shizuku and never impersonates it. See [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

| Role | Project |
|---|---|
| Local privileged access, app authorization, capabilities, diagnostics | **this repository** |
| AI planning, orchestration, remote commands, fleet scheduling | [droidcommand-AI](https://github.com/Victorious93/droidcommand-AI) |
| Terminal (`rish`) | [VictorSuite](https://github.com/Victorious93/VictorSuite) |
| File access (scoped, chunked) | [File-Manager](https://github.com/Victorious93/File-Manager) |

## What is different from upstream

- **Scoped, service-enforced authorization.** Five choices: deny; until the service stops; 15 minutes; until revoked;
  ask for every operation. Typed capabilities (diagnostics, settings, files, packages, shell) are checked on every request;
  raw Binder forwarding, `newProcess` and UserServices stay a separately labelled **legacy full access**.
- **Revoke all**: ends every grant, cancels queued/running operations and stops processes/services apps started.
- **Startup repairs**: no main-thread blocking, bounded retries, readiness verification, explicit opt-in for any
  debugging-setting change, specific failure reasons. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
- **Activity history, diagnostics, settings profiles** with preview/apply/undo.
- Pure-JVM `bridge-core` with 120+ unit tests; verified APK packaging; honest release signing.

## Documents

[User manual](docs/USER_MANUAL.md) · [Architecture](docs/ARCHITECTURE.md) · [Integration / SDK](docs/INTEGRATION.md) ·
[Compatibility matrix](docs/COMPATIBILITY.md) · [Migration from Shizuku](docs/MIGRATION.md) ·
[Toolchain](docs/TOOLCHAIN.md) · [Verification report](docs/VERIFICATION.md)

## Build

```sh
git clone https://github.com/Victorious93/ShizukuV      # no submodules: the API sources are vendored (api/UPSTREAM.md)
./gradlew :bridge-core:test                                # unit tests, any JDK 21
./gradlew :manager:assembleDebug                           # secret-free debug APK
./gradlew :manager:assembleRelease                         # UNSIGNED unless signing.properties supplies a real key
bash scripts/verify-apk.sh && bash scripts/release-artifacts.sh   # packaging checks, SHA-256, signing labels
```

Required tools and exact versions: [docs/TOOLCHAIN.md](docs/TOOLCHAIN.md).

---

# Upstream Shizuku README (retained for attribution and for the background it explains)

## Shizuku

## Background

When developing apps that requires root, the most common method is to run some commands in the su shell. For example, there is an app that uses the `pm enable/disable` command to enable/disable components.

This method has very big disadvantages:

1. **Extremely slow** (Multiple process creation)
2. Needs to process texts (**Super unreliable**)
3. The possibility is limited to available commands
4. Even if ADB has sufficient permissions, the app requires root privileges to run

Shizuku uses a completely different way. See detailed description below.

## User guide & Download

<https://shizuku.rikka.app/>

## How does Shizuku work?

First, we need to talk about how app use system APIs. For example, if the app wants to get installed apps, we all know we should use `PackageManager#getInstalledPackages()`. This is actually an interprocess communication (IPC) process of the app process and system server process, just the Android framework did the inner works for us.

Android uses `binder` to do this type of IPC. `Binder` allows the server-side to learn the uid and pid of the client-side, so that the system server can check if the app has the permission to do the operation.

Usually, if there is a "manager" (e.g., `PackageManager`) for apps to use, there should be a "service" (e.g., `PackageManagerService`) in the system server process. We can simply think if the app holds the `binder` of the "service", it can communicate with the "service". The app process will receive binders of system services on start.

Shizuku guides users to run a process, Shizuku server, with root or ADB first. When the app starts, the `binder` to Shizuku server will also be sent to the app.

The most important feature Shizuku provides is something like be a middle man to receive requests from the app, sent them to the system server, and send back the results. You can see the `transactRemote` method in `rikka.shizuku.server.ShizukuService` class, and `moe.shizuku.api.ShizukuBinderWrapper` class for the detail.

So, we reached our goal, to use system APIs with higher permission. And to the app, it is almost identical to the use of system APIs directly.

## Developer guide

### API & sample

https://github.com/RikkaApps/Shizuku-API

### Migrating from pre-v11

> Existing applications still works, of course.

https://github.com/RikkaApps/Shizuku-API#migration-guide-for-existing-applications-use-shizuku-pre-v11

### Attention

1. ADB permissions are limited

   ADB has limited permissions and different on various system versions. You can see permissions granted to ADB [here](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/packages/Shell/AndroidManifest.xml).

   Before calling the API, you can use `ShizukuService#getUid` to check if Shizuku is running user ADB, or use `ShizukuService#checkPermission` to check if the server has sufficient permissions.

2. Hidden API limitation from Android 9

   As of Android 9, the usage of the hidden APIs is limited for normal apps. Please use other methods (such as <https://github.com/LSPosed/AndroidHiddenApiBypass>).

3. Android 8.0 & ADB

   At present, the way Shizuku service gets the app process is to combine `IActivityManager#registerProcessObserver` and `IActivityManager#registerUidObserver` (26+) to ensure that the app process will be sent when the app starts. However, on API 26, ADB lacks permissions to use `registerUidObserver`, so if you need to use Shizuku in a process that might not be started by an Activity, it is recommended to trigger the send binder by starting a transparent activity.

4. Direct use of `transactRemote` requires attention

   * The API may be different under different Android versions, please be sure to check it carefully. Also, the `android.app.IActivityManager` has the aidl form in API 26 and later, and `android.app.IActivityManager$Stub` exists only on API 26.

   * `SystemServiceHelper.getTransactionCode` may not get the correct transaction code, such as `android.content.pm.IPackageManager$Stub.TRANSACTION_getInstalledPackages` does not exist on API 25 and there is `android.content.pm.IPackageManager$Stub.TRANSACTION_getInstalledPackages_47` (this situation has been dealt with, but it is not excluded that there may be other circumstances). This problem is not encountered with the `ShizukuBinderWrapper` method.

## Developing Shizuku itself

### Build

- Plain `git clone` is enough (the former `api` submodule is vendored; see `api/UPSTREAM.md`)
- Run gradle task `:manager:assembleDebug` or `:manager:assembleRelease`

The `:manager:assembleDebug` task generates a debuggable server. You can attach a debugger to `shizuku_server` to debug the server. Be aware that, in Android Studio, "Run/Debug configurations" - "Always install with package manager" should be checked, so that the server will use the latest code.

## License

This fork keeps the upstream license and its Section 6 branding limits, and complies with them: it uses its own name,
application id, permissions and artwork. Original Victorious additions are also Apache-2.0. `api/` is MIT (RikkaW).

Upstream's terms, quoted unchanged:

All code files in this project are licensed under Apache 2.0

Under Apache 2.0 section 6, specifically:

* You are **FORBIDDEN** to use `manager/src/main/res/mipmap*/ic_launcher*.png` image files, unless for displaying Shizuku itself.

* You are **FORBIDDEN** to use `Shizuku` as app name or use `moe.shizuku.privileged.api` as application id or declare `moe.shizuku.manager.permission.*` permission.
