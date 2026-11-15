# Verification report

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
- Not translated: new strings are English-only (fallback); existing translations are preserved and rebranded.
- No PR was opened and nothing was merged or released.

## Pre-existing lint findings (baselined, not introduced)
`MissingTranslation` (174 strings missing in some locales), `StringFormat*`, `MissingQuantity` (cs), `MissingLeanbackLauncher`
(upstream deliberately disabled leanback), unused resources, style. Baselines: `manager/lint-baseline.xml`, `server/lint-baseline.xml`.
Remaining baselined findings in files added here are style warnings only (`ObsoleteSdkInt`, `UseKtx`).
