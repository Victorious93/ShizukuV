package moe.shizuku.manager.bridge

import ai.victorious.bridge.core.ActivityEvent
import ai.victorious.bridge.core.ActivityLog
import ai.victorious.bridge.core.BackendRequirement
import ai.victorious.bridge.core.BridgeProtocol as P
import ai.victorious.bridge.core.Capability
import ai.victorious.bridge.core.GrantMode
import android.os.Bundle
import moe.shizuku.server.IShizukuService
import org.json.JSONArray
import rikka.shizuku.Shizuku

data class GrantInfo(
    val uid: Int, val packages: List<String>, val capabilities: Set<Capability>, val mode: GrantMode?,
    val fileRoots: List<String>, val remainingMs: Long?,
)

data class ActiveOpInfo(val uid: Int, val packageName: String?, val requestId: String, val operation: String, val state: String, val startedWallMs: Long)

/** Manager-side client for the service's manager-only administration surface (`bridgeManage`). All calls block; use off the main thread. */
object BridgeAdmin {
    private fun service(): IShizukuService? {
        val b = Shizuku.getBinder() ?: return null
        if (!b.pingBinder()) return null
        return IShizukuService.Stub.asInterface(b)
    }

    private fun call(cmd: String, fill: Bundle.() -> Unit = {}): Bundle? = try {
        service()?.bridgeManage(Bundle().apply { putString(P.M_COMMAND, cmd); fill() })
    } catch (_: Throwable) {
        null
    }

    fun grants(): List<GrantInfo> = call(P.CMD_LIST_GRANTS)?.getParcelableArrayList<Bundle>(P.M_LIST).orEmpty().map { b ->
        GrantInfo(
            b.getInt(P.M_UID), b.getStringArray("packages")?.toList().orEmpty(),
            b.getStringArray(P.M_CAPS)?.mapNotNull { Capability.fromId(it) }?.toSet().orEmpty(), GrantMode.fromId(b.getString(P.M_MODE)),
            b.getStringArray(P.M_ROOTS)?.toList().orEmpty(), if (b.containsKey(P.K_GRANT_REMAINING_MS)) b.getLong(P.K_GRANT_REMAINING_MS) else null,
        )
    }

    fun revoke(uid: Int): Boolean = call(P.CMD_REVOKE) { putInt(P.M_UID, uid) }?.getBoolean(P.M_OK) == true
    fun revokeAll(): Int = call(P.CMD_REVOKE_ALL)?.getInt("count") ?: 0

    fun activeOperations(): List<ActiveOpInfo> = call(P.CMD_ACTIVE_OPS)?.getParcelableArrayList<Bundle>(P.M_LIST).orEmpty().map {
        ActiveOpInfo(it.getInt(P.M_UID), it.getString("package"), it.getString(P.K_REQUEST_ID) ?: "", it.getString(P.K_OPERATION) ?: "", it.getString("state") ?: "", it.getLong("started_wall_ms"))
    }

    fun cancelOperation(uid: Int, requestId: String): Boolean = call(P.CMD_CANCEL_OP) { putInt(P.M_UID, uid); putString(P.K_REQUEST_ID, requestId) }?.getBoolean(P.M_OK) == true

    fun activity(): List<ActivityEvent> = runCatching {
        val a = JSONArray(call(P.CMD_ACTIVITY)?.getString(P.M_TEXT) ?: "[]")
        (0 until a.length()).map { ActivityLog.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun exportActivity(): String? = call(P.CMD_ACTIVITY_EXPORT)?.getString(P.M_TEXT)
    fun clearActivity(): Boolean = call(P.CMD_ACTIVITY_CLEAR)?.getBoolean(P.M_OK) == true
    fun setRetention(maxEntries: Int, maxAgeMs: Long): Boolean =
        call(P.CMD_RETENTION) { putInt(P.M_MAX_ENTRIES, maxEntries); putLong(P.M_MAX_AGE_MS, maxAgeMs) }?.getBoolean(P.M_OK) == true

    fun setBackendPolicy(p: BackendRequirement): Boolean = call(P.CMD_SET_POLICY) { putString(P.M_BACKEND, p.id) }?.getBoolean(P.M_OK) == true
    fun status(): Bundle? = call(P.CMD_STATUS)

    fun applyAccess(uid: Int, pid: Int, requestCode: Int, mode: GrantMode, caps: Set<Capability>, roots: List<String>): Boolean =
        call(P.CMD_APPLY_ACCESS) {
            putInt(P.M_UID, uid); putInt(P.M_PID, pid); putInt(P.M_REQUEST_CODE, requestCode); putString(P.M_MODE, mode.id)
            putStringArray(P.M_CAPS, caps.map { it.id }.toTypedArray()); putStringArray(P.M_ROOTS, roots.toTypedArray())
        }?.getBoolean(P.M_OK) == true

    fun denyAccess(uid: Int, pid: Int, requestCode: Int): Boolean =
        call(P.CMD_DENY_ACCESS) { putInt(P.M_UID, uid); putInt(P.M_PID, pid); putInt(P.M_REQUEST_CODE, requestCode) }?.getBoolean(P.M_OK) == true

    fun answerOperation(requestId: String, approved: Boolean): Boolean =
        call(P.CMD_APPROVAL_RESULT) { putString(P.K_REQUEST_ID, requestId); putBoolean(P.M_APPROVED, approved) }?.getBoolean(P.M_OK) == true
}
