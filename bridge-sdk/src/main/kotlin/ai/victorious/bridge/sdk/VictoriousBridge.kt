package ai.victorious.bridge.sdk

import ai.victorious.bridge.core.BackendRequirement
import ai.victorious.bridge.core.BridgeIdentity
import ai.victorious.bridge.core.BridgeProtocol as P
import ai.victorious.bridge.core.BridgeRequest
import ai.victorious.bridge.core.BridgeResult
import ai.victorious.bridge.core.Capability
import ai.victorious.bridge.core.ErrorCode
import ai.victorious.bridge.core.ExecMode
import ai.victorious.bridge.core.ResultStatus
import android.os.Bundle
import android.os.IBinder
import moe.shizuku.server.IBridgeCallback
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** What this app currently holds, as reported by the service (never inferred client-side). */
data class BridgeInfo(
    val bridgeApiVersion: Int,
    val serverVersion: Int,
    val execMode: ExecMode?,
    val accessLevel: AccessLevel,
    val heldCapabilities: Set<Capability>,
    val supportedOperations: Set<String>,
    val grantMode: String?,
    val grantRemainingMs: Long?,
    val fileRoots: List<String>,
    val servicePolicy: BackendRequirement,
)

enum class AccessLevel { NONE, SCOPED, LEGACY }

/** A cancellable in-flight request. */
class BridgeCall internal constructor(val requestId: String, private val cancelImpl: () -> Boolean) {
    fun cancel(): Boolean = cancelImpl()
}

/**
 * Client for the Victorious controlled API. Versioned: [SDK_VERSION] is the library version,
 * [BridgeIdentity.BRIDGE_API_VERSION] the wire contract; [negotiate] reports what the service supports.
 *
 * Apps still need the standard provider declared in their manifest (authority
 * `${applicationId}.victoriousbridge`) so the service can deliver its Binder. This SDK only works with
 * the Victorious Bridge service; it does not talk to upstream Shizuku and never pretends to.
 *
 * Methods that wait ([negotiate], [execute]) must not be called on the main thread.
 */
object VictoriousBridge {
    const val SDK_VERSION = "1.0.0"
    const val SDK_VERSION_CODE = 1

    fun isServiceAvailable(): Boolean = Shizuku.pingBinder()

    private fun service(): IShizukuService? {
        val b: IBinder = Shizuku.getBinder() ?: return null
        if (!b.pingBinder()) return null
        return IShizukuService.Stub.asInterface(b)
    }

    /** Capability negotiation. Returns null when the service is not running or does not speak this contract. */
    fun negotiate(): BridgeInfo? {
        val s = service() ?: return null
        val reply = try {
            s.bridgeNegotiate(Bundle().apply { putLong(P.K_SDK_VERSION, SDK_VERSION_CODE.toLong()) })
        } catch (_: Throwable) {
            return null
        } ?: return null
        val strings = { k: String -> reply.getStringArray(k)?.toList().orEmpty() }
        return BridgeInfo(
            bridgeApiVersion = reply.getInt(P.K_BRIDGE_API_VERSION, 0),
            serverVersion = reply.getInt(P.K_SERVER_VERSION, 0),
            execMode = ExecMode.values().firstOrNull { it.id == reply.getString(P.K_EXEC_MODE) },
            accessLevel = when (reply.getString(P.K_ACCESS_LEVEL)) { "legacy" -> AccessLevel.LEGACY; "scoped" -> AccessLevel.SCOPED; else -> AccessLevel.NONE },
            heldCapabilities = strings(P.K_HELD_CAPABILITIES).mapNotNull { Capability.fromId(it) }.toSet(),
            supportedOperations = strings(P.K_SUPPORTED_OPERATIONS).toSet(),
            grantMode = reply.getString(P.K_GRANT_MODE),
            grantRemainingMs = if (reply.containsKey(P.K_GRANT_REMAINING_MS)) reply.getLong(P.K_GRANT_REMAINING_MS) else null,
            fileRoots = strings(P.K_FILE_ROOTS),
            servicePolicy = BackendRequirement.fromId(reply.getString(P.K_SERVICE_POLICY)),
        )
    }

    /**
     * Ask the owner for scoped capabilities. The manager shows the owner exactly what is requested and
     * the owner may narrow it. Listen with [Shizuku.addRequestPermissionResultListener] using [requestCode],
     * then call [negotiate] to learn what was actually granted. Legacy full access is deliberately not
     * requestable through this method.
     */
    fun requestAccess(requestCode: Int, capabilities: Set<Capability>, fileRoots: List<String> = emptyList()): Boolean {
        val s = service() ?: return false
        val caps = capabilities.filter { !it.legacy }.map { it.id }.toTypedArray()
        if (caps.isEmpty()) return false
        return try {
            s.bridgeRequestAccess(requestCode, caps, fileRoots.toTypedArray()); true
        } catch (_: Throwable) {
            false
        }
    }

    /** Asynchronous submit; [callback] is invoked once on a Binder thread. */
    fun submit(
        operation: String,
        args: Map<String, Any?> = emptyMap(),
        backend: BackendRequirement = BackendRequirement.ANY,
        remoteTaskId: String? = null,
        requestId: String = UUID.randomUUID().toString(),
        callback: (BridgeResult) -> Unit,
    ): BridgeCall {
        val s = service()
        if (s == null) {
            callback(BridgeResult(requestId, ResultStatus.ERROR, ErrorCode.NOT_ATTACHED, "Victorious Bridge service is not running"))
            return BridgeCall(requestId) { false }
        }
        val req = Bundle().apply {
            putString(P.K_REQUEST_ID, requestId); putString(P.K_OPERATION, operation)
            putString(P.K_BACKEND, backend.id); remoteTaskId?.let { putString(P.K_REMOTE_TASK_ID, it) }
            putBundle(P.K_ARGS, BundleCodec.toBundle(args))
        }
        try {
            s.bridgeSubmit(req, object : IBridgeCallback.Stub() {
                override fun onResult(result: Bundle?) = callback(decodeResult(requestId, result))
            })
        } catch (t: Throwable) {
            callback(BridgeResult(requestId, ResultStatus.ERROR, if (t is SecurityException) ErrorCode.DENIED else ErrorCode.INTERNAL, t.javaClass.simpleName))
        }
        return BridgeCall(requestId) { runCatching { s.bridgeCancel(requestId) }.getOrDefault(false) }
    }

    /** Blocking convenience over [submit]. Times out locally (and cancels remotely) after [timeoutMs]. */
    fun execute(
        operation: String,
        args: Map<String, Any?> = emptyMap(),
        backend: BackendRequirement = BackendRequirement.ANY,
        remoteTaskId: String? = null,
        timeoutMs: Long = 60_000,
    ): BridgeResult {
        val latch = CountDownLatch(1)
        val ref = AtomicReference<BridgeResult>()
        val call = submit(operation, args, backend, remoteTaskId) { ref.set(it); latch.countDown() }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            call.cancel()
            return BridgeResult(call.requestId, ResultStatus.ERROR, ErrorCode.TIMEOUT, "no reply within ${timeoutMs}ms")
        }
        return ref.get()
    }

    internal fun decodeResult(fallbackId: String, b: Bundle?): BridgeResult {
        if (b == null) return BridgeResult(fallbackId, ResultStatus.ERROR, ErrorCode.INTERNAL, "empty reply")
        val status = runCatching { ResultStatus.valueOf(b.getString(P.K_STATUS) ?: "") }.getOrDefault(ResultStatus.ERROR)
        val code = b.getString(P.K_ERROR_CODE)?.let { c -> ErrorCode.values().firstOrNull { it.name == c } }
        return BridgeResult(
            requestId = b.getString(P.K_REQUEST_ID) ?: fallbackId, status = status, errorCode = code,
            message = b.getString(P.K_MESSAGE), data = BundleCodec.toMap(b.getBundle(P.K_DATA)),
            durationMs = b.getLong(P.K_DURATION_MS), execMode = ExecMode.values().firstOrNull { it.id == b.getString(P.K_EXEC_MODE) },
        )
    }

    // ---- typed helpers -----------------------------------------------------------------------

    fun diagnostics() = execute("diagnostics.read")
    fun getSetting(namespace: String, key: String) = execute("settings.get", mapOf("namespace" to namespace, "key" to key))
    fun putSetting(namespace: String, key: String, value: String) = execute("settings.put", mapOf("namespace" to namespace, "key" to key, "value" to value))
    fun listFiles(path: String, offset: Long = 0, limit: Long = 200) = execute("files.list", mapOf("path" to path, "offset" to offset, "limit" to limit))
    fun statFile(path: String) = execute("files.stat", mapOf("path" to path))
    fun readFile(path: String, offset: Long, length: Long) = execute("files.read", mapOf("path" to path, "offset" to offset, "length" to length))
    fun writeFile(path: String, offset: Long, data: ByteArray, truncate: Boolean = false) =
        execute("files.write", mapOf("path" to path, "offset" to offset, "data" to data, "truncate" to truncate))
    fun listPackages(filter: String = "all", user: Long = 0) = execute("packages.list", mapOf("filter" to filter, "user" to user))
    fun packageInfo(pkg: String) = execute("packages.info", mapOf("package" to pkg))
    fun setPackageEnabled(pkg: String, enabled: Boolean, user: Long = 0) = execute("packages.setEnabled", mapOf("package" to pkg, "enabled" to enabled, "user" to user))
    fun shell(command: String, timeoutMs: Long = 30_000) = execute("shell.exec", mapOf("command" to command, "timeout_ms" to timeoutMs), timeoutMs = timeoutMs + 10_000)

    /** True if this SDK build and the connected service agree on the wire contract. */
    fun isCompatible(info: BridgeInfo): Boolean = info.bridgeApiVersion == BridgeIdentity.BRIDGE_API_VERSION
}
