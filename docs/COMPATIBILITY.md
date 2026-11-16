# Compatibility and capability matrix

Legend - **Implemented**: code exists. **Verified (JVM)**: covered by unit tests that ran green. **Verified (build)**: checked on built APKs.
**Unverified**: no run on a device/emulator. **Unsupported**: not offered. Nothing is marked verified on a device: none was available.

## Existing Shizuku clients

Victorious Bridge **does not impersonate Shizuku**. It uses a different application id, permissions, provider authority and binder key.

| Client | Works? | Why / what to do |
|---|---|---|
| App built with upstream `dev.rikka.shizuku:provider` | **No (unsupported)** | Its provider looks for `moe.shizuku.privileged.api` and a different binder key and authority; the service never sends it a Binder. It keeps working with upstream Shizuku, which can stay installed. |
| Same app rebuilt with this repo's `:bridge-sdk` (or the vendored `api/provider`) | **Yes after rebuild** (Unverified on device) | Change the dependency and the provider authority to `${applicationId}.victoriousbridge`; use the scoped API, or request legacy full access explicitly. |
| App using only `rikka.shizuku.Shizuku` calls (`newProcess`, `ShizukuBinderWrapper`, UserService) | **Requires rebuild + legacy full access** | Same API surface under the new provider. Granted only if the owner picks the legacy option. |
| Terminal apps using `rish` | **Yes** with a re-exported `rish` + dex (the dex embeds the package id) | Legacy full access, labelled. Unverified on device. |
| Sui-based clients | **Unsupported** | Sui implements upstream's identity. |

## Capabilities

| Capability / feature | Status | Notes and limits |
|---|---|---|
| Grant modes (session, 15 min, until revoked, per operation, deny) | Implemented; Verified (JVM) | Prompt UI unverified on device |
| Execution-time expiry, monotonic clock, restart ends temporary grants | Verified (JVM) | Service restart simulated by a new `GrantStore` |
| Identity binding (uid, package, signer digest), reinstall / uid reuse / user separation | Verified (JVM) | Real PackageManager resolver unverified on device |
| Shared uid | Implemented | Grant is per uid; **package isolation inside one shared uid is not possible** |
| Revoke: cancel queued/running ops, drop results | Verified (JVM) | |
| Revoke: stop `newProcess` children, rish hosts, user services | Implemented; Unverified | Needs a device; detached grandchildren can survive |
| Scoped access cannot reach raw Binder / UserService / `newProcess` | Implemented by construction (`isClientAllowed` is legacy-only) | The scoped dispatcher has no such operation (tested); Binder-level enforcement unverified on device |
| `diagnostics.read` | Implemented | Fields depend on Android version/ROM |
| `settings.read/write` | Implemented; allow-list verified (JVM) | Executes `settings` through the system tool; protected keys are refused; OEM limits apply |
| `files.read/write` | Implemented; scope and chunking verified (JVM) | ADB mode **cannot read other apps' private data** (hard-deny list); root bound by SELinux; TOCTOU residual risk (ARCHITECTURE) |
| `packages.read/manage` | Implemented; argument checks verified (JVM) | Uninstall / clear data are **not offered**; protected packages refused |
| `shell.exec` | Implemented; timeout, output cap, cancel verified (JVM) | Unrestricted by design; separately granted |
| Backend policy (no silent escalation) | Verified (JVM) | |
| Boot start: root | Implemented; Unverified | |
| Boot start: wireless debugging | Implemented; Unverified | Android 13+ and `WRITE_SECURE_SETTINGS` required. **Reboot persistence is not universal** without root; it depends on Android version, ROM, Wi-Fi and the user's setting choices |
| Computer-assisted (adb) start | Existing upstream path, unchanged | Must be repeated after each reboot |
| Activity history, redaction, retention, export | Verified (JVM) | UI unverified on device; legacy full-access actions are invisible |
| Settings profiles (preview/apply/undo/conflict) | Verified (JVM) | |
| Identity consistency, no upstream ids, native libs per ABI | Verified (JVM + build) | `scripts/verify-apk.sh` |
| Coexistence with upstream Shizuku | By construction (distinct ids/names/files); **Unverified** | Not installed side by side in a test |
| Dashboard / Apps search / Activity / Diagnostics / Settings screens | Implemented; **Unverified** (compiles, lint, packages) | No screenshots or device smoke test |
| Accessibility (48 dp targets, content descriptions, system colors, dark theme) | Implemented in new screens; Unverified | Large-text layout not visually checked |

Android version coverage claimed: minSdk 24. Behaviour was **not** exercised on any Android version.
