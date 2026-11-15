package ai.victorious.bridge.core

/** Stable, machine-readable error categories returned to clients and recorded in the activity log. */
enum class ErrorCode {
    NOT_ATTACHED,
    DENIED,
    EXPIRED,
    REVOKED,
    IDENTITY_MISMATCH,
    APPROVAL_DENIED,
    APPROVAL_TIMEOUT,
    UNSUPPORTED_CAPABILITY,
    UNSUPPORTED_OPERATION,
    INVALID_ARGUMENT,
    PATH_OUT_OF_SCOPE,
    NOT_FOUND,
    BACKEND_POLICY,
    CANCELLED,
    TIMEOUT,
    IO_ERROR,
    EXEC_FAILED,
    TOO_LARGE,
    OVERLOADED,
    INTERNAL,
}

enum class ResultStatus { OK, ERROR, CANCELLED }

/** What the privileged service is running as. Ordering = increasing privilege. */
enum class ExecMode(val id: String) {
    SHELL("adb"), ROOT("root");

    companion object {
        fun ofUid(uid: Int): ExecMode? = when (uid) {
            0 -> ROOT
            2000 -> SHELL
            else -> null
        }
    }
}

/** Per-request backend requirement. A mismatch fails the request; it never selects a different backend. */
enum class BackendRequirement(val id: String) {
    ANY("any"), SHELL_ONLY("adb"), ROOT_ONLY("root");

    fun accepts(mode: ExecMode): Boolean = when (this) {
        ANY -> true
        SHELL_ONLY -> mode == ExecMode.SHELL
        ROOT_ONLY -> mode == ExecMode.ROOT
    }

    companion object {
        fun fromId(id: String?): BackendRequirement = values().firstOrNull { it.id == id } ?: ANY
    }
}
