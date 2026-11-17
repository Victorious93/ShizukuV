# ShizukuV

<p align="center"><img src="docs/assets/shizukuv-stamp.jpg" alt="ShizukuV stamp" width="256"></p>

ShizukuV is the local privileged-access manager for Victorious Android apps (Team Victorious, Victorious Framework Work).
You start a small service once with root or ADB authority, authorize apps one by one, and apps then call it through
a controlled API whose permissions are enforced inside the service. It never gives anyone access to a device the owner
has not set up and approved themselves.

| | |
|---|---|
| App name | **ShizukuV** |
| Application ID | `com.victorious.shizukuv` |
| Permissions | `com.victorious.shizukuv.permission.MANAGER`, `com.victorious.shizukuv.permission.LEGACY_API_V1` |
| Theme | Dark neon only: background `#0B0F1A`, cyan `#00E5FF`, magenta `#FF2BD6`. There is no light theme |
| Languages | English only |
| Min / compile SDK | 24 / 36 |

## Attribution

ShizukuV is a fork of **[Shizuku](https://github.com/RikkaApps/Shizuku)** by **RikkaApps / RikkaW**, licensed under the
[Apache License 2.0](LICENSE). The upstream license, copyright headers and notices are kept unchanged; the vendored
[Shizuku-API](https://github.com/RikkaApps/Shizuku-API) in `api/` is MIT-licensed (see `api/LICENSE` and `api/UPSTREAM.md`).
Modified and added files are also Apache-2.0. This project is not affiliated with or endorsed by RikkaApps.

Shizuku's design (a service started with ADB or root that hands out a Binder to approved apps) is theirs; read the upstream
[README](https://github.com/RikkaApps/Shizuku#readme) and [site](https://shizuku.rikka.app/) for how it works.

### Upstream branding terms and how this fork complies

Upstream's README states, under Apache 2.0 section 6:

> You are **FORBIDDEN** to use `manager/src/main/res/mipmap*/ic_launcher*.png` image files, unless for displaying Shizuku itself.
>
> You are **FORBIDDEN** to use `Shizuku` as app name or use `moe.shizuku.privileged.api` as application id or declare
> `moe.shizuku.manager.permission.*` permission.

| Upstream restriction | ShizukuV |
|---|---|
| Upstream launcher mipmaps | Not shipped. Launcher and adaptive icons are the ShizukuV stamp (`docs/assets/shizukuv-stamp.jpg`) |
| `moe.shizuku.privileged.api` | Not used. Application ID is `com.victorious.shizukuv` |
| `moe.shizuku.manager.permission.*` | Not declared. Permissions are `com.victorious.shizukuv.permission.*` |
| `Shizuku` as the app name | The app is named **ShizukuV**. This is the owner's chosen brand and it still contains the word "Shizuku"; whether upstream considers that acceptable has **not** been checked with RikkaApps. If upstream objects, change `DISPLAY_NAME` in `BridgeIdentity.kt`, `bridgeDisplayName` in `build.gradle` and `app_name` in `strings.xml` (a test keeps the three equal) |

`IdentityConsistencyTest` fails the build if an upstream identifier reappears in the sources.

## Compatibility with official Shizuku clients

ShizukuV installs next to Shizuku and shares nothing with it (different package, permissions, provider authority, binder key,
process name and state files). Apps built against the official `dev.rikka.shizuku:provider` look for
`moe.shizuku.privileged.api`, so **they do not discover ShizukuV**. This is intentional. Apps that should use ShizukuV must be
built against this repository's `:bridge-sdk` / vendored `api/provider`. See [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

## Build

```sh
git clone https://github.com/Victorious93/ShizukuV      # no submodules: the Shizuku-API sources are vendored (api/UPSTREAM.md)
export ANDROID_HOME=...                                   # SDK 36, build-tools 36.0.0, NDK 29.0.13113456, CMake 3.31.6 (docs/TOOLCHAIN.md)
./gradlew :bridge-core:test                               # unit tests (JDK 21)
./gradlew :manager:assembleDebug                          # debug APK, no secrets required
bash scripts/verify-apk.sh && bash scripts/release-artifacts.sh
```

The debug APK is `manager/build/outputs/apk/debug/shizukuv-v<version>-debug.apk`.

### Signing

* **Debug**: signed with the committed, public, debug-only key `keystore/shizukuv-debug.keystore` (password `android`,
  alias `androiddebugkey`) so every machine and CI run produces the same signature. It is **not** a release key and proves
  nothing about who built the APK. See [keystore/README.md](keystore/README.md).
* **Release**: `:manager:assembleRelease` is **unsigned** unless a git-ignored `signing.properties` provides a real key.
  There is no fallback to the debug key. No release key or secret is stored in this repository.

### CI

`.github/workflows/app.yml` runs on every push and pull request: unit tests and lint, then a debug APK (uploaded as the
`shizukuv-apks` artifact) after packaging checks. The `api` code is vendored, so no submodule or SSH access is needed; the
checkout step is also configured to fetch submodules over HTTPS should one ever be added. Debug builds read no secrets.

## Optional: start at boot on rooted devices

`module/` contains an **optional** Magisk / KernelSU / APatch module that starts the ShizukuV server at boot on a device you have
rooted yourself. The app works fully without it (ADB, Wireless debugging and the in-app root start are unchanged). The module runs
the same starter command as the app's root start, adds no setuid files and no SELinux changes, and logs to
`/data/local/tmp/shizukuv-module.log`. Build it with `bash module/build.sh` or download `shizukuv-root-module` from the *Root module*
workflow. Install, uninstall and troubleshooting: [docs/ROOT-MODULE.md](docs/ROOT-MODULE.md).

## What is different from upstream

- Own identity, permissions, artwork and dark neon theme; English-only UI.
- **Scoped, service-enforced authorization** with five choices (deny, until the service stops, 15 minutes, until revoked, ask
  for every operation) and typed capabilities. Raw Binder forwarding, `newProcess` and UserServices stay a separately labelled
  **legacy full access**.
- Revoke-all, activity history, diagnostics and settings profiles; a pure-JVM `bridge-core` with unit tests.

Details: [User manual](docs/USER_MANUAL.md) · [Architecture](docs/ARCHITECTURE.md) · [Integration / SDK](docs/INTEGRATION.md) ·
[Compatibility](docs/COMPATIBILITY.md) · [Migration from Shizuku](docs/MIGRATION.md) · [Optional root module](docs/ROOT-MODULE.md) · [Toolchain](docs/TOOLCHAIN.md) ·
[Verification report](docs/VERIFICATION.md)

| Role | Project |
|---|---|
| Local privileged access, app authorization, diagnostics | **this repository** |
| AI planning, orchestration, fleet scheduling | [droidcommand-AI](https://github.com/Victorious93/droidcommand-AI) |
| Terminal (`rish`) | [VictorSuite](https://github.com/Victorious93/VictorSuite) |
| File access | [File-Manager](https://github.com/Victorious93/File-Manager) |

## Responsible use

ShizukuV is a tool for the owner of a device to grant their own apps elevated access on that device. It must not be used to
gain access to a device you do not own or to bypass another person's security.

## License

Apache License 2.0, see [LICENSE](LICENSE). Copyright of the original work remains with RikkaApps / RikkaW and the Shizuku
contributors. The text of the upstream branding restrictions is quoted above, unchanged.
