# Architecture

```
 client app ──(SDK: ai.victorious.bridge.sdk)──► Binder ──► privileged service (root or adb shell)
                                                       │   ShizukuService (vendored upstream core)
 manager app (this APK) ◄── approval UI ───────────────┤   BridgeController ─► GrantStore ─► BridgeDispatcher ─► handlers
                                                       │   legacy path: isClientAllowed() ─► GrantStore (LEGACY_FULL)
 bridge-core (pure JVM, unit-tested): grants, dispatcher, path scope, policies, redaction, activity log,
                                      profiles, BinderWait, startup orchestrator, debug-settings ledger
```

## Modules

| Module | Kind | Role |
|---|---|---|
| `bridge-core` | JVM | All decision logic. No Android dependency, so it is unit-tested on any JDK |
| `bridge-sdk` | Android lib | Typed client (`VictoriousBridge`), Bundle codec |
| `server` | Android lib | The privileged process: `ShizukuService` + `BridgeController` (wires core to PackageManager, files, processes) |
| `starter`, `shell`, `common` | Android libs/app | Upstream components, re-identified |
| `manager` | App | UI, startup, approval, history, diagnostics, profiles; native starter/ADB-pairing code |
| `api/*` | vendored | Shizuku-API at a pinned commit; changes marked `Victorious:` (`api/UPSTREAM.md`) |

## Identity (one source, checked)

`build.gradle` (`bridgeApplicationId`) feeds the manager id, the manifest placeholders, the native starter
(`-DBRIDGE_PACKAGE_NAME`) and the rish loader (`BuildConfig`). `BridgeIdentity.kt` is the JVM copy. The vendored provider and
the starter keep literals (they cannot depend on `bridge-core`). `IdentityConsistencyTest` fails the build if any copy drifts or if an
upstream identifier (`moe.shizuku.privileged.api`, `moe.shizuku.manager.permission.*`, `shizuku_server`, the upstream config path)
reappears in source. `scripts/verify-apk.sh` repeats the check on the built APKs (dex, rish dex, native libraries).

What is *not* renamed, deliberately: the AIDL package `moe.shizuku.server`, the `rikka.shizuku` API package and the Kotlin package
`moe.shizuku.manager`. They are wire-protocol/code names, not identity; renaming would break the Binder descriptor for no benefit.
Distinct from upstream so both install together: application id, the two permissions, provider authority suffix, binder extra key,
client meta-data, server process name (the native starter kills processes by name), server config/state files, rish dex target.

## Authorization model

A **grant** belongs to a uid (so to an Android user too) and records its packages and signing-certificate SHA-256 digests.

| Mode | Valid until | Persisted |
|---|---|---|
| Until the service stops | service process ends (restart, crash, reboot) or revoke | no |
| 15 minutes | 15 min of *monotonic* time, never past the process | no |
| Until revoked | revoke, or the caller's identity changes | yes (`shizukuv_grants.json`) |
| Per operation | no standing access; each operation is confirmed in the manager and one approval covers one request | n/a |
| Deny | n/a | n/a |

Invariants (each has a test): temporary grants exist only in memory; expiry uses a monotonic clock so wall-clock changes cannot
extend access; **every** check re-resolves identity (reinstall with another key, or a recycled uid owned by another package, fails closed
and ends the grant - it does not return when the original key does); a scoped grant never implies `legacy.full`; the owner can narrow a
request but not widen it; checks repeat at execution time after queueing, and a timed grant expiring mid-operation cancels it.

**Capabilities** (`diagnostics.read`, `settings.read`, `settings.write`, `files.read`, `files.write`, `packages.read`, `packages.manage`,
`shell.exec`) each map to fixed operations; an operation maps to exactly one capability. Settings are an allow-list with value patterns;
file access is limited to owner-approved roots; protected packages cannot be disabled or force-stopped; shell is separate.

**Backend policy.** A request (`any|adb|root`) and the service-wide policy are checked against what the service actually runs as. A mismatch
returns `BACKEND_POLICY`. No code path selects another backend, and `su` is never invoked as a fallback.

**Legacy full access** (`legacy.full`) is the upstream capability set (raw Binder forwarding, `newProcess`, UserServices, rish). It is a
distinct, warned-about choice, evaluated per call via `Service.isClientAllowed`, so revocation reaches existing connections. Scoped apps
cannot reach those entry points: they never set that flag.

**Revocation** (one app, or all) ends the grant, cancels queued and running controlled operations (their data is dropped, not delivered),
kills processes started by `newProcess` and rish hosts for that uid, and removes its user services. *Limits:* actions already completed are
not undone; capabilities already used cannot be recalled; a command that detached a background process from a legitimately started shell
may leave it running; process-group kill needs `Process.pid()` (API 33+), older Android kills the direct child only.

**Path scope** (`PathScope`): absolute paths only; any `..` segment, NUL, or over-long path is rejected before any filesystem call; the
resolved (symlink-followed) path must lie in an approved root and outside a hard-deny list (`/data/data`, `/data/user*`, `/data/system`,
`/proc`...); final-component symlinks are refused; scope is checked before existence so protected paths cannot be probed.
*Residual risk:* between the check and the open, someone with write access to an intermediate directory could swap it for a symlink.
Not closed by an `O_NOFOLLOW` open in this version.

## Startup

`BootCompleteReceiver` -> `BridgeStartup` -> `StartupOrchestrator`: single flight (overlapping LOCKED_BOOT/BOOT/manual triggers run one
launch), already-running detection, bounded retries with exponential backoff, cancellation recorded as *interrupted*, success only after
`BinderReadinessProbe` sees the Binder, and the last result (with a failure reason and recovery text) persisted. `goAsync()` is released
exactly once within 8 s while work continues in the application scope. Debugging settings (wireless debugging, USB debugging, ADB
authorization timeout) are changed **only** for options the user enabled; the previous value is captured and, if requested, restored
without overwriting a value the user changed since.

`RequestPermissionActivity` no longer blocks the main thread: `BinderWait` waits for the Binder asynchronously and ends in exactly one of
received / timed out / died / cancelled, always unregistering its listeners; everything except "received" fails closed.
`AdbMdns` now stops discovery correctly even when `stop()` races the platform's start callback (the old code leaked the listener).
`ShizukuManagerProvider.sendUserService` returns failure (not an empty success) when the Binder never arrives.

## Activity history and profiles

Bounded ring buffer (default 500 entries / 30 days, adjustable), persisted locally, redacted on write (`Redactor`), never uploaded. Records time,
caller, capability, request id, correlation id, duration, outcome, error category. Command arguments and file contents are never recorded;
file paths are. Legacy full-access actions are **not** visible. Profiles hold supported setting values only; import ignores any other field.
