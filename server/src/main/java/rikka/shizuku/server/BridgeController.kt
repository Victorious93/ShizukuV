package rikka.shizuku.server

import ai.victorious.bridge.core.*
import ai.victorious.bridge.core.BridgeProtocol as P
import ai.victorious.bridge.sdk.BundleCodec
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.SystemProperties
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import moe.shizuku.server.IBridgeCallback
import org.json.JSONArray
import org.json.JSONObject
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.OsUtils
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Victorious: Android-side wiring of the pure-JVM bridge core inside the privileged service process.
 * It owns the grant store, dispatcher, activity log and approval broker, and answers the `bridge*` Binder calls.
 */
class BridgeController(private val svc: ShizukuService) {

    private val log = Logger("BridgeController")
    private val clock = MonotonicClock { SystemClock.elapsedRealtime() }
    private val execMode: ExecMode = ExecMode.ofUid(Os.getuid()) ?: ExecMode.SHELL
    private val resolver = PackageIdentityResolver()
    private val state = JsonFileState(File(BridgeIdentity.SERVER_GRANTS_FILE))

    @Volatile
    var servicePolicy: BackendRequirement = state.loadPolicy()
        private set

    val activity = ActivityLog(FileActivityStore(File(BridgeIdentity.SERVER_ACTIVITY_FILE)))

    private data class PendingAccess(val caps: Set<Capability>, val roots: List<String>, val legacy: Boolean)

    private val pending = ConcurrentHashMap<String, PendingAccess>()
    private fun pkey(uid: Int, pid: Int, code: Int) = "$uid:$pid:$code"

    private val executor = ThreadPoolExecutor(2, 6, 30, TimeUnit.SECONDS, LinkedBlockingQueue(128)).apply { allowCoreThreadTimeOut(true) }
    private val scheduler = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }

    private val approvals = ApprovalBroker()

    lateinit var grants: GrantStore
        private set
    private lateinit var dispatcher: BridgeDispatcher

    private val startedElapsed = clock.elapsedMs()

    fun start() {
        grants = GrantStore(clock, resolver, persistence = state, onGrantEnded = ::onGrantEnded)
        val cmds = CommandExecutor { argv, timeout, cancel -> ProcessRunner().run(argv, timeout, cancel) }
        val shellCmds = CommandExecutor { argv, timeout, cancel -> ProcessRunner(killGroup = true).run(argv, timeout, cancel) }
        dispatcher = BridgeDispatcher(
            grants, resolver, activity, execMode, { servicePolicy },
            DefaultHandlers(SystemInfo { snapshot() }, cmds, HandlerConfig(symlinks = OsSymlinkProbe), shellCmds),
            approvals, executor, scheduler, clock,
        )
        scheduler.scheduleWithFixedDelay({ runCatching { grants.sweepExpired() } }, 5, 5, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({ runCatching { activity.flushIfDirty() } }, 5, 5, TimeUnit.SECONDS)
        lifecycle("service started (${execMode.id}, uid ${Os.getuid()})")
    }

    fun lifecycle(text: String) = activity.append(
        ActivityEvent(System.currentTimeMillis(), EventType.LIFECYCLE, Os.getuid(), null, null, null, null, null, Outcome.INFO, null, text),
    )

    private fun auth(uid: Int, text: String, outcome: Outcome = Outcome.INFO, err: String? = null, cap: String? = null) {
        activity.append(
            ActivityEvent(System.currentTimeMillis(), EventType.AUTHORIZATION, uid, resolver.resolve(uid)?.packages?.sorted()?.firstOrNull(),
                cap, null, null, null, outcome, err, text),
        )
    }

    /** Called by the grant store whenever a grant ends. Stops everything that grant could still be driving. */
    private fun onGrantEnded(uid: Int, reason: GrantEndReason) {
        if (reason == GrantEndReason.REPLACED) {
            auth(uid, "grant replaced")
            return
        }
        val code = when (reason) {
            GrantEndReason.EXPIRED -> ErrorCode.EXPIRED
            GrantEndReason.IDENTITY_MISMATCH -> ErrorCode.IDENTITY_MISMATCH
            else -> ErrorCode.REVOKED
        }
        dispatcher.cancelAllFor(uid, code)
        var stopped = 0
        if (!svc.hasLegacyRouteOtherThanGrantStore(uid)) {
            stopped += svc.terminateProcessesForUid(uid)
            stopped += svc.removeUserServicesForUid(uid)
        }
        auth(uid, "access ended: ${reason.name.lowercase()}; stopped $stopped managed process(es)/service(s)", Outcome.INFO, reason.name)
    }

    // ---- client-facing -----------------------------------------------------------------------

    fun negotiate(record: ClientRecord?): Bundle {
        val b = Bundle()
        b.putInt(P.K_BRIDGE_API_VERSION, BridgeIdentity.BRIDGE_API_VERSION)
        b.putInt(P.K_SERVER_VERSION, ShizukuApiConstants.SERVER_VERSION)
        b.putString(P.K_EXEC_MODE, execMode.id)
        b.putString(P.K_SERVICE_POLICY, servicePolicy.id)
        b.putStringArray(P.K_SUPPORTED_OPERATIONS, Operation.values().map { it.id }.toTypedArray())
        b.putStringArray(P.K_SUPPORTED_CAPABILITIES, Capability.values().map { it.id }.toTypedArray())
        if (record == null) {
            b.putString(P.K_ACCESS_LEVEL, "none")
            return b
        }
        val g = grants.find(record.uid)
        val legacy = svc.isLegacyAllowedFor(record)
        val held = HashSet<Capability>()
        if (g != null && g.identityStillMatches()) held.addAll(g.capabilities)
        if (legacy) held.add(Capability.LEGACY_FULL)
        b.putStringArray(P.K_HELD_CAPABILITIES, held.map { it.id }.toTypedArray())
        b.putString(P.K_ACCESS_LEVEL, if (legacy) "legacy" else if (held.isNotEmpty()) "scoped" else "none")
        if (g != null) {
            b.putString(P.K_GRANT_MODE, g.mode.id)
            grants.remainingMs(g)?.let { b.putLong(P.K_GRANT_REMAINING_MS, it) }
            b.putStringArray(P.K_FILE_ROOTS, g.fileRoots.toTypedArray())
        }
        return b
    }

    private fun Grant.identityStillMatches(): Boolean = resolver.resolve(uid)?.let { it.signerDigests == signerDigests } == true

    fun submit(callingUid: Int, record: ClientRecord?, request: Bundle?, callback: IBridgeCallback?) {
        if (callback == null) return
        val reqId = request?.getString(P.K_REQUEST_ID) ?: "unknown"
        fun reply(r: BridgeResult) = try { callback.onResult(encode(r)) } catch (t: Throwable) { log.w(t, "callback") }
        if (record == null || request == null) {
            reply(BridgeResult(reqId, ResultStatus.ERROR, ErrorCode.NOT_ATTACHED, "caller is not an attached client", emptyMap(), 0, execMode)); return
        }
        val req = BridgeRequest(
            requestId = reqId,
            operation = request.getString(P.K_OPERATION) ?: "",
            args = BundleCodec.toMap(request.getBundle(P.K_ARGS)),
            backend = BackendRequirement.fromId(request.getString(P.K_BACKEND)),
            remoteTaskId = request.getString(P.K_REMOTE_TASK_ID),
        )
        dispatcher.submit(callingUid, req) { reply(it) }
    }

    fun cancel(callingUid: Int, requestId: String?): Boolean = requestId != null && dispatcher.cancel(callingUid, requestId)

    private fun encode(r: BridgeResult): Bundle = Bundle().apply {
        putString(P.K_REQUEST_ID, r.requestId); putString(P.K_STATUS, r.status.name)
        r.errorCode?.let { putString(P.K_ERROR_CODE, it.name) }
        r.message?.let { putString(P.K_MESSAGE, it) }
        putBundle(P.K_DATA, BundleCodec.toBundle(r.data))
        putLong(P.K_DURATION_MS, r.durationMs)
        r.execMode?.let { putString(P.K_EXEC_MODE, it.id) }
    }

    // ---- access requests ---------------------------------------------------------------------

    /** Scoped request from an attached client: sanitise, remember what was asked, ask the owner. */
    fun requestScopedAccess(record: ClientRecord, requestCode: Int, caps: Array<String>?, roots: Array<String>?, userId: Int) {
        val wanted = caps.orEmpty().mapNotNull { Capability.fromId(it) }.filter { !it.legacy }.toSet()
        if (wanted.isEmpty()) { record.dispatchRequestPermissionResult(requestCode, false); return }
        val wantedRoots = PathScope(roots.orEmpty().toList()).effectiveRoots
        pending[pkey(record.uid, record.pid, requestCode)] = PendingAccess(wanted, wantedRoots, legacy = false)
        auth(record.uid, "requested scoped access: ${wanted.joinToString { it.id }}")
        svc.showBridgeAccessConfirmation(requestCode, record, userId, wanted.map { it.id }.toTypedArray(), wantedRoots.toTypedArray())
    }

    fun noteLegacyRequest(uid: Int, pid: Int, requestCode: Int) {
        pending[pkey(uid, pid, requestCode)] = PendingAccess(setOf(Capability.LEGACY_FULL), emptyList(), legacy = true)
        auth(uid, "requested LEGACY full access")
    }

    // ---- manager-only administration ---------------------------------------------------------

    fun manage(cmd: Bundle?): Bundle {
        val out = Bundle()
        val c = cmd?.getString(P.M_COMMAND) ?: return out.apply { putBoolean(P.M_OK, false) }
        when (c) {
            P.CMD_STATUS -> {
                out.putAll(snapshot().let { m -> BundleCodec.toBundle(m) })
                out.putString(P.K_SERVICE_POLICY, servicePolicy.id)
                out.putBoolean(P.M_OK, true)
            }
            P.CMD_LIST_GRANTS -> out.putParcelableArrayList(P.M_LIST, ArrayList(grants.list().map { g ->
                Bundle().apply {
                    putInt(P.M_UID, g.uid); putStringArray("packages", g.packages.toTypedArray())
                    putStringArray(P.M_CAPS, g.capabilities.map { it.id }.toTypedArray()); putString(P.M_MODE, g.mode.id)
                    putStringArray(P.M_ROOTS, g.fileRoots.toTypedArray())
                    grants.remainingMs(g)?.let { putLong(P.K_GRANT_REMAINING_MS, it) }
                    putLong("created_wall_ms", g.createdWallMs)
                }
            }))
            P.CMD_REVOKE -> out.putBoolean(P.M_OK, svc.revokeEverythingFor(cmd.getInt(P.M_UID)))
            P.CMD_REVOKE_ALL -> out.putInt("count", svc.revokeEverything().size).also { out.putBoolean(P.M_OK, true) }
            P.CMD_ACTIVE_OPS -> out.putParcelableArrayList(P.M_LIST, ArrayList(dispatcher.activeOperations().map { a ->
                Bundle().apply {
                    putInt(P.M_UID, a.uid); putString("package", a.packageName); putString(P.K_REQUEST_ID, a.requestId)
                    putString(P.K_OPERATION, a.operation); putString("capability", a.capability.id)
                    putString("state", a.state.name); putLong("started_wall_ms", a.startedWallMs)
                }
            }))
            P.CMD_CANCEL_OP -> out.putBoolean(P.M_OK, dispatcher.cancelByManager(cmd.getInt(P.M_UID), cmd.getString(P.K_REQUEST_ID) ?: ""))
            P.CMD_ACTIVITY -> out.putString(P.M_TEXT, activity.toJsonArray().toString())
            P.CMD_ACTIVITY_EXPORT -> out.putString(P.M_TEXT, activity.exportRedacted(listOf("exec_mode=${execMode.id}", "server_version=${ShizukuApiConstants.SERVER_VERSION}")))
            P.CMD_ACTIVITY_CLEAR -> { activity.clear(); activity.flushIfDirty(); out.putBoolean(P.M_OK, true) }
            P.CMD_RETENTION -> {
                val r = runCatching { RetentionPolicy(cmd.getInt(P.M_MAX_ENTRIES, 500), cmd.getLong(P.M_MAX_AGE_MS, 30L * 24 * 3600 * 1000)) }.getOrNull()
                if (r != null) activity.setRetention(r)
                out.putBoolean(P.M_OK, r != null)
            }
            P.CMD_SET_POLICY -> {
                servicePolicy = BackendRequirement.fromId(cmd.getString(P.M_BACKEND)); state.savePolicy(servicePolicy)
                lifecycle("backend policy set to ${servicePolicy.id}"); out.putBoolean(P.M_OK, true)
            }
            P.CMD_APPROVAL_RESULT -> out.putBoolean(P.M_OK, approvals.complete(cmd.getString(P.K_REQUEST_ID) ?: "", cmd.getBoolean(P.M_APPROVED)))
            P.CMD_APPLY_ACCESS -> out.putBoolean(P.M_OK, applyAccess(cmd))
            P.CMD_DENY_ACCESS -> {
                val uid = cmd.getInt(P.M_UID); val pid = cmd.getInt(P.M_PID); val code = cmd.getInt(P.M_REQUEST_CODE)
                pending.remove(pkey(uid, pid, code))
                svc.clientManager().findClient(uid, pid)?.dispatchRequestPermissionResult(code, false)
                auth(uid, "owner denied the access request", Outcome.INFO, "DENIED")
                out.putBoolean(P.M_OK, true)
            }
            else -> out.putBoolean(P.M_OK, false)
        }
        return out
    }

    private fun applyAccess(cmd: Bundle): Boolean {
        val uid = cmd.getInt(P.M_UID); val pid = cmd.getInt(P.M_PID); val code = cmd.getInt(P.M_REQUEST_CODE)
        val p = pending.remove(pkey(uid, pid, code)) ?: return false
        val mode = GrantMode.fromId(cmd.getString(P.M_MODE)) ?: return false
        val chosen = cmd.getStringArray(P.M_CAPS).orEmpty().mapNotNull { Capability.fromId(it) }.toSet()
        // The owner may narrow a request but never widen it beyond what the app asked for.
        if (chosen.isEmpty() || !p.caps.containsAll(chosen)) return false
        if (p.legacy && mode == GrantMode.PER_OPERATION) return false
        val roots = PathScope(cmd.getStringArray(P.M_ROOTS).orEmpty().toList()).effectiveRoots
            .filter { r -> p.roots.any { req -> r == req || r.startsWith(req.trimEnd('/') + "/") } }
        val record = svc.clientManager().findClient(uid, pid)
        grants.grant(uid, chosen, mode, if (Capability.FILES_READ in chosen || Capability.FILES_WRITE in chosen) roots else emptyList())
        auth(uid, "owner granted ${chosen.joinToString { it.id }} (${mode.id})", Outcome.OK)
        record?.dispatchRequestPermissionResult(code, true)
        return true
    }

    /** The manager's own UI is the owner acting directly: give it a session-scoped, identity-bound set of read/settings capabilities. */
    fun grantManager(uid: Int) {
        runCatching {
            grants.grant(uid, setOf(Capability.DIAGNOSTICS_READ, Capability.SETTINGS_READ, Capability.SETTINGS_WRITE, Capability.PACKAGES_READ), GrantMode.SESSION)
        }
    }

    // ---- diagnostics -------------------------------------------------------------------------

    fun snapshot(): Map<String, Any?> = mapOf(
        "sdk_int" to Build.VERSION.SDK_INT.toLong(),
        "android_release" to Build.VERSION.RELEASE,
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}".take(64),
        "build_fingerprint" to Build.FINGERPRINT.take(128),
        "server_uid" to Os.getuid().toLong(),
        "server_version" to ShizukuApiConstants.SERVER_VERSION.toLong(),
        "server_patch" to ShizukuApiConstants.SERVER_PATCH_VERSION.toLong(),
        "selinux_context" to runCatching { OsUtils.getSELinuxContext() }.getOrNull(),
        "boot_id" to runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull(),
        "uptime_ms" to SystemClock.elapsedRealtime(),
        "service_uptime_ms" to (clock.elapsedMs() - startedElapsed),
        "grants_active" to (if (::grants.isInitialized) grants.list().size.toLong() else 0L),
        "active_operations" to (if (::dispatcher.isInitialized) dispatcher.activeOperations().size.toLong() else 0L),
        "service_policy" to servicePolicy.id,
        "secure_settings_hint" to SystemProperties.get("ro.build.version.security_patch", ""),
    )

    fun shutdown() {
        runCatching { activity.flushIfDirty() }
        executor.shutdownNow(); scheduler.shutdownNow()
    }

    fun cancelAllOperations(reason: ErrorCode) = dispatcher.cancelEverything(reason)

    // ---- approval broker ---------------------------------------------------------------------

    private inner class ApprovalBroker : ApprovalGate {
        private val waiting = ConcurrentHashMap<String, CompletableFuture<Boolean>>()

        override fun requestApproval(uid: Int, packageName: String?, operation: Operation, summary: String, requestId: String, timeoutMs: Long, cancel: CancelToken): ApprovalOutcome {
            val f = CompletableFuture<Boolean>()
            val key = "$uid:$requestId"
            if (waiting.putIfAbsent(key, f) != null) return ApprovalOutcome.DENIED
            val unhook = cancel.onCancel { f.cancel(true) }
            try {
                val intent = Intent(P.ACTION_CONFIRM_OPERATION).setPackage(BridgeIdentity.APPLICATION_ID)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    .putExtra(P.EXTRA_UID, uid).putExtra(P.EXTRA_OPERATION_REQUEST_ID, requestId)
                    .putExtra(P.EXTRA_OPERATION_ID, operation.id).putExtra(P.EXTRA_OPERATION_SUMMARY, summary)
                    .putExtra("package", packageName)
                if (!svc.startManagerActivity(intent, uid / 100000)) return ApprovalOutcome.DENIED
                return try {
                    if (f.get(timeoutMs, TimeUnit.MILLISECONDS)) ApprovalOutcome.APPROVED else ApprovalOutcome.DENIED
                } catch (_: java.util.concurrent.TimeoutException) {
                    ApprovalOutcome.TIMEOUT
                } catch (_: java.util.concurrent.CancellationException) {
                    ApprovalOutcome.CANCELLED
                } catch (_: Exception) {
                    ApprovalOutcome.DENIED
                }
            } finally {
                unhook(); waiting.remove(key)
            }
        }

        /** Only requests that are actually waiting can be answered; the key includes the uid so one app cannot answer another's. */
        fun complete(requestKeyOrId: String, approved: Boolean): Boolean {
            val f = waiting.entries.firstOrNull { it.key.endsWith(":$requestKeyOrId") }?.value ?: return false
            return f.complete(approved)
        }
    }
}

/** Resolves uid -> packages + signing-certificate digests straight from the package manager, every time. */
class PackageIdentityResolver : IdentityResolver {
    override fun resolve(uid: Int): CallerIdentity? {
        val pkgs = PackageManagerApis.getPackagesForUidNoThrow(uid)
        if (pkgs.isEmpty()) return null
        val userId = uid / 100000
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val digests = HashSet<String>()
        for (p in pkgs) {
            val pi = PackageManagerApis.getPackageInfoNoThrow(p, flags.toLong(), userId) ?: continue
            val sigs = if (Build.VERSION.SDK_INT >= 28) pi.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") pi.signatures
            sigs?.forEach { s -> digests.add(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }) }
        }
        return CallerIdentity(uid, pkgs.toSet(), digests)
    }
}

object OsSymlinkProbe : SymlinkProbe {
    override fun isSymlink(file: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(file.path).st_mode)
    } catch (_: Exception) {
        false
    }
}

class FileActivityStore(private val file: File) : ActivityStore {
    private val atomic = AtomicFile(file)
    override fun load(): List<String> = try {
        atomic.openRead().bufferedReader().use { it.readLines() }
    } catch (_: FileNotFoundException) {
        emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    override fun save(lines: List<String>) {
        val out = try { atomic.startWrite() } catch (_: Exception) { return }
        try {
            out.write(lines.joinToString("\n").toByteArray()); atomic.finishWrite(out)
        } catch (_: Exception) {
            atomic.failWrite(out)
        }
    }
}

/** Persisted UNTIL_REVOKED grants and the backend policy, in one atomically-written JSON file. */
class JsonFileState(file: File) : GrantPersistence {
    private val atomic = AtomicFile(file)
    private val lock = Any()
    private var policy: BackendRequirement = BackendRequirement.ANY
    private var grants: List<PersistedGrant> = emptyList()

    init {
        try {
            val o = JSONObject(atomic.openRead().bufferedReader().use { it.readText() })
            policy = BackendRequirement.fromId(o.optString("policy"))
            val a = o.optJSONArray("grants") ?: JSONArray()
            grants = (0 until a.length()).mapNotNull { i ->
                runCatching {
                    val g = a.getJSONObject(i)
                    fun strs(k: String) = g.getJSONArray(k).let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                    PersistedGrant(g.getInt("uid"), strs("packages").toSet(), strs("signers").toSet(),
                        strs("caps").mapNotNull { Capability.fromId(it) }.toSet(), strs("roots"), g.getLong("t"))
                }.getOrNull()
            }
        } catch (_: Exception) {
        }
    }

    fun loadPolicy(): BackendRequirement = policy
    fun savePolicy(p: BackendRequirement) = synchronized(lock) { policy = p; write() }
    override fun load(): List<PersistedGrant> = grants
    override fun save(grants: List<PersistedGrant>) = synchronized(lock) { this.grants = grants; write() }

    private fun write() {
        val o = JSONObject().put("version", 1).put("policy", policy.id).put("grants", JSONArray().also { a ->
            grants.forEach { g ->
                a.put(JSONObject().put("uid", g.uid).put("packages", JSONArray(g.packages.toList())).put("signers", JSONArray(g.signerDigests.toList()))
                    .put("caps", JSONArray(g.capabilities.map { it.id })).put("roots", JSONArray(g.fileRoots)).put("t", g.createdWallMs))
            }
        })
        val out = try { atomic.startWrite() } catch (_: Exception) { return }
        try { out.write(o.toString().toByteArray()); atomic.finishWrite(out) } catch (_: Exception) { atomic.failWrite(out) }
    }
}
