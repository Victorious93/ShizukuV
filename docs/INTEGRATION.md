# Integrating an app (SDK) and the Victorious fleet

## Client app

1. Depend on `:bridge-sdk` (it brings `api`, `provider`, `aidl`, `bridge-core`). Version: `VictoriousBridge.SDK_VERSION` (1.0.0);
   wire contract `BridgeIdentity.BRIDGE_API_VERSION` (1); `VictoriousBridge.negotiate()` reports what the service supports.
2. Declare the provider so the service can hand over its Binder (the library manifest adds the `com.victorious.shizukuv.client.SUPPORT`
   meta-data; it does **not** add the legacy permission):
   ```xml
   <provider android:name="rikka.shizuku.ShizukuProvider"
       android:authorities="${applicationId}.shizukuv"
       android:exported="true" android:multiprocess="false"
       android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
   ```
3. Ask, then use:
   ```kotlin
   VictoriousBridge.requestAccess(42, setOf(Capability.FILES_READ), listOf("/storage/emulated/0/Download"))
   Shizuku.addRequestPermissionResultListener { code, _ -> if (code == 42) { val info = VictoriousBridge.negotiate() /* what was granted */ } }
   val r = VictoriousBridge.execute("files.list", mapOf("path" to "/storage/emulated/0/Download"))   // off the main thread
   if (!r.ok) handle(r.errorCode)   // DENIED, EXPIRED, REVOKED, PATH_OUT_OF_SCOPE, BACKEND_POLICY, CANCELLED, ...
   ```
   `submit(...)` is asynchronous and returns a `BridgeCall` with `cancel()`. Results carry the request id, status, error category, duration and
   execution mode. Legacy full access is deliberately not requestable through this API.

## Contract

`docs/contract/bridge-contract-v1.json` is the source of truth for identity, operations, capability mapping, error codes and backend
requirements. `ContractFixtureTest` checks the service against it; DroidCommand-AI and File-Manager hold copies and check their own code against it.

## Fleet

| Project | What exists | State |
|---|---|---|
| droidcommand-AI | `core-shizuku/.../bridge`: contract copy, transport seam, `BridgeTool` per operation, 17 tests | JVM-tested; Android transport not written |
| VictorSuite | `scripts/bridge-rish-doctor`, `docs/BRIDGE_RISH.md`, fake-rish test in CI | script-tested; not run against a real Bridge |
| File-Manager (`Victorious93/File-Manager`) | `core/.../bridge/BridgeFileIo`, 12 tests, `docs/VICTORIOUS_BRIDGE.md` | JVM-tested; Android gateway/UI not wired |

Cross-repository runtime verification (a real app talking to a running Bridge on a device) was **not performed**: there was no device or emulator.
Each repo's doc contains the exact handoff. Companion changes are on branch `claude/shizukuv-complete-upgrade-0angea` in each repository; no PRs were opened.

**DroidCommand-AI rules honoured:** the bridge stays local; remote tasks reach it only through DCA's authenticated pipeline; a remote task id is
correlation only (recorded in the activity log, never consulted for authorization); DCA's own approvals/audit run before the Bridge's grant check.
