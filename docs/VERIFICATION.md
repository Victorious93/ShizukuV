# Verification report

## Optional root module (`module/`, see [ROOT-MODULE.md](ROOT-MODULE.md))

Run on Linux x86_64, OpenJDK 21, ShellCheck 0.11.0, dash, Android SDK 36 / build-tools 36.0.0 / NDK 29.0.13113456 / CMake 3.31.6.

| Check | Result |
|---|---|
| `shellcheck -s sh -x` on `service.sh`, `uninstall.sh`, `customize.sh`, `update-binary` | no findings |
| `shellcheck -s bash` on `build.sh`, `tests/service-test.sh` | no findings |
| `SH=dash bash module/tests/service-test.sh` (stubbed `getprop`, `pm`, `pidof`, starter) | 17 of 17 checks: base.apk chosen over split APKs, ABI choice, exact `--apk=` argument, app missing, server already running, starter failure with retries, boot never completes, starter library missing; `service.sh` always exits 0 |
| `bash module/build.sh`, built twice 2 s apart | identical SHA-256; 6 entries (`META-INF/.../update-binary`, `updater-script`, `customize.sh`, `module.prop`, `service.sh`, `uninstall.sh`); scripts stored `rwxr-xr-x`; `module.prop` id/version/versionCode valid |
| `./gradlew :bridge-core:test` | 127 tests, 0 failures |
| `./gradlew :manager:assembleDebug`, `bash scripts/verify-apk.sh` | built; packaging checks passed. The APK has `extractNativeLibs=true` and `lib/<abi>/libshizuku.so` for all four ABIs, which is what `service.sh` looks for |

**Not verified**: flashing in Magisk, KernelSU or APatch; the installer scripts (`customize.sh`, `update-binary`) running inside a
manager; the boot start on any real device or emulator; SELinux behaviour of the starter under each root solution; a real
`pm`, `getprop` or starter (stubs only). The host test checks the script's logic, not Android behaviour.

## ShizukuV foundation rebrand (identity, icons, theme, English-only, debug signing, CI)

Run on Linux x86_64, OpenJDK 21, Android SDK 36 / build-tools 36.0.0 / NDK 29.0.13113456 / CMake 3.31.6.

| Check | Result |
|---|---|
| `./gradlew :bridge-core:test` | 127 tests, 0 failures (includes `IdentityConsistencyTest`: identity, permissions, icons, dark neon palette, English-only, fixed debug key, no extra keystores, vendored API, license/attribution) |
| `./gradlew :manager:assembleDebug` | built; `aapt2 dump badging`: package `com.victorious.shizukuv`, label `ShizukuV`, `locales: '--_--'` only, adaptive launcher icon |
| `./gradlew :manager:assembleRelease` | built **unsigned** (`...-release-unsigned.apk`), no fallback to the debug key |
| `apksigner verify --print-certs` (debug APK) | signer `CN=ShizukuV Debug Only`, SHA-256 `0152ad3e...3cfb` = `keystore/shizukuv-debug.keystore` |
| `:manager:lintDebug :server:lintDebug :bridge-sdk:lintDebug` | no new issues (baseline regenerated after the locale folders were removed) |
| `scripts/verify-apk.sh`, `scripts/release-artifacts.sh` | passed; debug labelled DEBUG-SIGNED, release labelled UNSIGNED |

**Not verified**: installing or launching the app, any screen rendering (the dark neon theme was checked only at the resource level, not
visually), the service in any start mode, coexistence with upstream Shizuku, or discovery by any client app. No emulator or device was
available. The GitHub Actions workflow was edited but could not be run before the pull request existed.

The sections below are the earlier verification record (identity strings in it predate the rename to ShizukuV).

Honest record of what was run, what it showed, and what was **not** done. Nothing below was run on a device or emulator.

## Environment
Linux x86_64, OpenJDK 21, Android SDK 36 / build-tools 36.0.0 / NDK 29.0.13113456 / CMake 3.31.6, AGP 8.10.1, Kotlin 2.1.21.
No `/dev/kvm`, no CPU virtualization, no attached device (`adb devices` empty). No root, no Shizuku, no second Android user.
Maven Central answered HTTP 429 through the shared egress proxy; a user-level Gradle mirror init script (not committed) worked around it.
Baseline: unmodified `master` `b844bc4` built (`:manager:assembleDebug`) after fetching the SSH-only submodule over HTTPS and installing CMake >= 3.31.

## Results

| Check | Command | Result |
|---|---|---|
| Unit tests (grants, dispatcher, path scope, redaction, activity log, profiles, BinderWait, debug-settings ledger, startup orchestrator, identity consistency, contract fixture) | `./gradlew :bridge-core:test` | **121 tests, 0 failures** |
| Debug APK | `./gradlew :manager:assembleDebug` | built, 13.9 MB, debug-signed |
| Release APK (R8, resource shrinking) | `./gradlew :manager:assembleRelease` | built, 2.5 MB, **unsigned** (no release key available) |
| Packaging | `bash scripts/verify-apk.sh` | package id, both permissions, 4 ABIs x 3 native libs, rish assets, no upstream identifiers in any dex/native lib, rish dex targets the new package: **passed** |
| Signing labels + checksums | `bash scripts/release-artifacts.sh` | SHA-256 + `BUILD-INFO.txt` generated; release verified UNSIGNED, debug verified debug-key |
| Android lint | `:manager:lintDebug :server:lintDebug :bridge-sdk:lintDebug` | **no new issues**; pre-existing findings are baselined (manager 183 errors, mostly missing translations; see below) |
| DroidCommand-AI | `:core-shizuku:test`, `:core-shizuku:ktlintCheck` | 75 tests (17 new), 0 failures; ktlint clean |
| File-Manager | `:core:test` (bridge tests) | 12 new tests, 0 failures |
| VictorSuite | `bash tests/ai-agents/test_bridge_rish_doctor.sh` | 10 checks passed (fake `rish`) |

**Mutation checks.** To confirm the tests actually guard the behaviour, three guards were removed one at a time (backend policy,
identity re-verification, leaf-symlink refusal); each made the suite fail, and the code was restored.

**Defects found by the tests during development (and fixed):** existence of out-of-scope paths leaked through `NOT_FOUND` vs
`PATH_OUT_OF_SCOPE` ordering; a `Skipped` start was recorded as *interrupted*; my own explanatory comment tripped the
upstream-identifier test; lint found guards that lint could not see (fixed with explicit SDK checks).

## Regression coverage for the reported defects
| Defect | Fix | Evidence |
|---|---|---|
| `waitForBinder()` ignored the `await` result and reported success after timeout | `BinderWait` | `BinderWaitTest` (timeout != success; one terminal state; cleanup once; sticky-before-start) |
| Wait blocked the main thread in `onCreate` | async wait + fail-closed | code; no device run |
| Timeout handling for `CountDownLatch` | removed; same bug fixed in `ShizukuManagerProvider.sendUserService` | code |
| `adbStart()` swallowed failures | `StartupOrchestrator` + persisted reasons | `StartupTest` (13 cases) |
| Debugging settings changed without clear opt-in | `DebugSettingsController`, off by default, restore without clobbering | `DebugSettingsAndProfilesTest` |
| Overlapping/duplicate boot events, leaks, `goAsync` | single flight, `startFromBroadcast` finishes once | `StartupTest` (overlap, duplicate boot, cancel); receiver itself not run |
| Leaked mDNS discovery listener | `AdbMdns` stop-before-start fix | code only; needs a device |

## NOT done (blocked or out of reach)
- **Nothing ran on a device or emulator**: install, launch, permission prompts, service start in any mode (root, wireless debugging, adb),
  boot persistence, service recovery, real revocation of running processes, crash logs, Binder-level enforcement, the rewritten
  `ServiceBase`/`BridgeController` path, coexistence with upstream Shizuku, rish. The privileged service code has **compiled and been
  packaged but has never executed** on Android. Treat it as untested until a device run.
- No Android-version/ROM/OEM matrix; no root or ADB-mode runtime comparison.
- No real consuming app: the SDK compiles and its Bundle codec is exercised only indirectly. DroidCommand-AI's Android transport,
  File-Manager's Android gateway/UI, and VictorSuite against a live Bridge are **handoffs, not integrations that run**.
- No UI screenshots, large-text, TalkBack or contrast check; the new screens are unverified beyond lint and compilation.
- Release signing: no key was available, so there is **no production-signed APK**. This is the only release blocker.
- The path-scope check has a documented TOCTOU window (`O_NOFOLLOW` open not implemented).
- Translations were removed: the UI is English only.

## Pre-existing lint findings (baselined, not introduced)
`MissingTranslation` (174 strings missing in some locales), `StringFormat*`, `MissingQuantity` (cs), `MissingLeanbackLauncher`
(upstream deliberately disabled leanback), unused resources, style. Baselines: `manager/lint-baseline.xml`, `server/lint-baseline.xml`.
Remaining baselined findings in files added here are style warnings only (`ObsoleteSdkInt`, `UseKtx`).
