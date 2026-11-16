package ai.victorious.bridge.core

class BridgeException(val code: ErrorCode, message: String) : Exception(message)

/** Typed view over request arguments. Values come from a Binder Bundle and are treated as hostile. */
class Args(private val map: Map<String, Any?>) {
    fun string(name: String, max: Int = 4096, required: Boolean = true): String? {
        val v = map[name]
        if (v == null) {
            if (required) throw BridgeException(ErrorCode.INVALID_ARGUMENT, "missing '$name'")
            return null
        }
        if (v !is String) throw BridgeException(ErrorCode.INVALID_ARGUMENT, "'$name' must be a string")
        if (v.length > max) throw BridgeException(ErrorCode.INVALID_ARGUMENT, "'$name' too long")
        return v
    }

    fun long(name: String, default: Long, min: Long, max: Long): Long {
        val v = map[name] ?: return default
        val n = when (v) {
            is Long -> v
            is Int -> v.toLong()
            else -> throw BridgeException(ErrorCode.INVALID_ARGUMENT, "'$name' must be an integer")
        }
        if (n < min || n > max) throw BridgeException(ErrorCode.INVALID_ARGUMENT, "'$name' out of range")
        return n
    }

    fun bool(name: String, default: Boolean): Boolean {
        val v = map[name] ?: return default
        return v as? Boolean ?: throw BridgeException(ErrorCode.INVALID_ARGUMENT, "'$name' must be a boolean")
    }

    fun bytes(name: String, max: Int): ByteArray {
        val v = map[name] as? ByteArray ?: throw BridgeException(ErrorCode.INVALID_ARGUMENT, "missing '$name'")
        if (v.size > max) throw BridgeException(ErrorCode.TOO_LARGE, "'$name' exceeds $max bytes")
        return v
    }
}

/** Catalogue of controlled operations. Each maps to exactly one capability, so the check cannot be skipped by operation choice. */
enum class Operation(val id: String, val capability: Capability, val summary: String) {
    DIAGNOSTICS("diagnostics.read", Capability.DIAGNOSTICS_READ, "Read device diagnostics"),
    SETTINGS_LIST("settings.list", Capability.SETTINGS_READ, "List supported settings"),
    SETTINGS_GET("settings.get", Capability.SETTINGS_READ, "Read a setting"),
    SETTINGS_PUT("settings.put", Capability.SETTINGS_WRITE, "Change a setting"),
    FILES_STAT("files.stat", Capability.FILES_READ, "Inspect a file"),
    FILES_LIST("files.list", Capability.FILES_READ, "List a folder"),
    FILES_READ("files.read", Capability.FILES_READ, "Read a file"),
    FILES_WRITE("files.write", Capability.FILES_WRITE, "Write a file"),
    FILES_MKDIR("files.mkdir", Capability.FILES_WRITE, "Create a folder"),
    FILES_DELETE("files.delete", Capability.FILES_WRITE, "Delete a file or empty folder"),
    PACKAGES_LIST("packages.list", Capability.PACKAGES_READ, "List apps"),
    PACKAGES_INFO("packages.info", Capability.PACKAGES_READ, "Inspect an app"),
    PACKAGES_SET_ENABLED("packages.setEnabled", Capability.PACKAGES_MANAGE, "Enable or disable an app"),
    PACKAGES_FORCE_STOP("packages.forceStop", Capability.PACKAGES_MANAGE, "Force-stop an app"),
    SHELL_EXEC("shell.exec", Capability.SHELL_EXEC, "Run a shell command");

    companion object {
        private val byId = values().associateBy { it.id }
        fun fromId(id: String?): Operation? = id?.let { byId[it] }
    }
}

data class BridgeRequest(
    val requestId: String,
    val operation: String,
    val args: Map<String, Any?> = emptyMap(),
    val backend: BackendRequirement = BackendRequirement.ANY,
    /** Correlation only (e.g. a DroidCommand task id). Recorded in the log; never consulted for authorization. */
    val remoteTaskId: String? = null,
)

data class BridgeResult(
    val requestId: String,
    val status: ResultStatus,
    val errorCode: ErrorCode? = null,
    val message: String? = null,
    val data: Map<String, Any?> = emptyMap(),
    val durationMs: Long = 0,
    val execMode: ExecMode? = null,
) {
    val ok: Boolean get() = status == ResultStatus.OK
}

class ExecContext(
    val callerUid: Int,
    val grant: Grant,
    val cancel: CancelToken,
    val execMode: ExecMode,
)

interface OperationHandlers {
    /** Executes [op]; throws [BridgeException] for expected failures. Must observe [ExecContext.cancel]. */
    fun execute(ctx: ExecContext, op: Operation, args: Args): Map<String, Any?>
}

enum class ApprovalOutcome { APPROVED, DENIED, TIMEOUT, CANCELLED }

/** Asks the owner (via the manager app) to confirm one specific operation. Blocking; called on a worker thread. */
fun interface ApprovalGate {
    fun requestApproval(uid: Int, packageName: String?, operation: Operation, summary: String, requestId: String, timeoutMs: Long, cancel: CancelToken): ApprovalOutcome
}

fun interface CommandExecutor {
    fun run(argv: List<String>, timeoutMs: Long, cancel: CancelToken): ProcessOutcome
}
