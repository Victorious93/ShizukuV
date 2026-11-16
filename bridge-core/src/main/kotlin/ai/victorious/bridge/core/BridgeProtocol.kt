package ai.victorious.bridge.core

/**
 * Bundle keys and manager commands shared by the SDK (client side) and the privileged service.
 * Values travel as Android `Bundle`s; only String, Long/Int, Boolean, ByteArray, String[] and nested
 * Bundles are accepted by the service's codec - anything else is dropped, never interpreted.
 */
object BridgeProtocol {
    private const val ACTION_PREFIX = BridgeIdentity.APPLICATION_ID + ".intent.action"

    // request
    const val K_REQUEST_ID = "request_id"
    const val K_OPERATION = "operation"
    const val K_BACKEND = "backend"
    const val K_REMOTE_TASK_ID = "remote_task_id"
    const val K_ARGS = "args"

    // result
    const val K_STATUS = "status"
    const val K_ERROR_CODE = "error_code"
    const val K_MESSAGE = "message"
    const val K_DATA = "data"
    const val K_DURATION_MS = "duration_ms"
    const val K_EXEC_MODE = "exec_mode"

    // negotiation (request)
    const val K_SDK_VERSION = "sdk_version"

    // negotiation (reply)
    const val K_BRIDGE_API_VERSION = "bridge_api_version"
    const val K_SERVER_VERSION = "server_version"
    const val K_SUPPORTED_OPERATIONS = "supported_operations"
    const val K_SUPPORTED_CAPABILITIES = "supported_capabilities"
    const val K_HELD_CAPABILITIES = "held_capabilities"
    const val K_ACCESS_LEVEL = "access_level"      // none | scoped | legacy
    const val K_GRANT_MODE = "grant_mode"
    const val K_GRANT_REMAINING_MS = "grant_remaining_ms"
    const val K_FILE_ROOTS = "file_roots"
    const val K_SERVICE_POLICY = "service_policy"

    // access-request reply extras (informational; the SDK re-negotiates to learn the truth)
    const val K_REQUEST_CODE = "request_code"

    // manager commands (bridgeManage)
    const val M_COMMAND = "cmd"
    const val CMD_LIST_GRANTS = "grants.list"
    const val CMD_REVOKE = "grants.revoke"            // uid
    const val CMD_REVOKE_ALL = "grants.revoke_all"
    const val CMD_ACTIVE_OPS = "ops.active"
    const val CMD_CANCEL_OP = "ops.cancel"            // uid, request_id
    const val CMD_ACTIVITY = "activity.read"
    const val CMD_ACTIVITY_EXPORT = "activity.export"
    const val CMD_ACTIVITY_CLEAR = "activity.clear"
    const val CMD_RETENTION = "activity.retention"    // max_entries, max_age_ms
    const val CMD_SET_POLICY = "policy.set"           // backend
    const val CMD_STATUS = "status"
    const val CMD_APPROVAL_RESULT = "approval.result" // request_id, approved
    const val CMD_APPLY_ACCESS = "access.apply"       // uid, pid, request_code, mode, caps, roots
    const val CMD_DENY_ACCESS = "access.deny"         // uid, pid, request_code

    const val M_UID = "uid"
    const val M_PID = "pid"
    const val M_REQUEST_CODE = "request_code"
    const val M_MODE = "mode"
    const val M_CAPS = "caps"
    const val M_ROOTS = "roots"
    const val M_APPROVED = "approved"
    const val M_BACKEND = "backend"
    const val M_MAX_ENTRIES = "max_entries"
    const val M_MAX_AGE_MS = "max_age_ms"
    const val M_LIST = "list"
    const val M_TEXT = "text"
    const val M_OK = "ok"

    // intents manager <- service
    const val EXTRA_UID = "uid"
    const val EXTRA_PID = "pid"
    const val EXTRA_REQUEST_CODE = "requestCode"
    const val EXTRA_APPLICATION_INFO = "applicationInfo"
    const val EXTRA_REQUESTED_CAPS = "bridge.caps"
    const val EXTRA_REQUESTED_ROOTS = "bridge.roots"
    const val EXTRA_OPERATION_REQUEST_ID = "bridge.op.request_id"
    const val EXTRA_OPERATION_ID = "bridge.op.id"
    const val EXTRA_OPERATION_SUMMARY = "bridge.op.summary"
    const val ACTION_CONFIRM_OPERATION = "$ACTION_PREFIX.CONFIRM_OPERATION"

}
