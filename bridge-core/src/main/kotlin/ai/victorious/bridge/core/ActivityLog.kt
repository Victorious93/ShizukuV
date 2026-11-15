package ai.victorious.bridge.core

import org.json.JSONArray
import org.json.JSONObject

enum class EventType { AUTHORIZATION, LIFECYCLE, OPERATION }
enum class Outcome { OK, ERROR, CANCELLED, INFO }

data class ActivityEvent(
    val wallTimeMs: Long,
    val type: EventType,
    val callerUid: Int,
    val callerPackage: String?,
    val capability: String?,
    val requestId: String?,
    /** Correlation id of a remote task (e.g. a DroidCommand task). Informational; NEVER used for authorization. */
    val remoteTaskId: String?,
    val durationMs: Long?,
    val outcome: Outcome,
    val errorCategory: String?,
    val detail: String,
)

interface ActivityStore {
    fun load(): List<String>
    fun save(lines: List<String>)
}

data class RetentionPolicy(val maxEntries: Int = 500, val maxAgeMs: Long = 30L * 24 * 3600 * 1000) {
    init {
        require(maxEntries in 0..5000) { "maxEntries out of range" }
        require(maxAgeMs >= 0) { "maxAgeMs" }
    }
}

/**
 * Bounded local history. Events are redacted on the way in ([Redactor]); file contents and command
 * arguments are never passed in by callers. Nothing here ever leaves the device on its own.
 */
class ActivityLog(
    private val store: ActivityStore? = null,
    retention: RetentionPolicy = RetentionPolicy(),
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val events = ArrayDeque<ActivityEvent>()
    private var dirty = false

    @Volatile
    var retention: RetentionPolicy = retention
        private set

    init {
        store?.load()?.forEach { line -> runCatching { fromJson(JSONObject(line)) }.getOrNull()?.let { events.addLast(it) } }
        trimLocked()
    }

    fun append(e: ActivityEvent) {
        val safe = e.copy(
            detail = Redactor.redact(e.detail).take(240),
            callerPackage = e.callerPackage?.take(128),
            errorCategory = e.errorCategory?.take(48),
        )
        synchronized(lock) {
            events.addLast(safe)
            trimLocked()
            dirty = true
        }
    }

    fun snapshot(): List<ActivityEvent> = synchronized(lock) { events.toList() }

    fun setRetention(p: RetentionPolicy) {
        synchronized(lock) { retention = p; trimLocked(); dirty = true }
    }

    fun clear() = synchronized(lock) { events.clear(); dirty = true }

    /** Persists if anything changed since the last flush. Call from a debounced worker. */
    fun flushIfDirty() {
        val lines: List<String>
        synchronized(lock) {
            if (!dirty) return
            lines = events.map { toJson(it).toString() }
            dirty = false
        }
        store?.save(lines)
    }

    /**
     * Human-readable redacted export for support. Carries an explicit statement of what the log can't see.
     */
    fun exportRedacted(headerLines: List<String> = emptyList()): String {
        val sb = StringBuilder()
        sb.append("Victorious Bridge activity export (redacted)\n")
        headerLines.forEach { sb.append(Redactor.redact(it)).append('\n') }
        sb.append("Note: actions by LEGACY_FULL clients (raw Binder / UserService / newProcess) are not visible here.\n")
        sb.append("Note: secrets, pairing codes, tokens, file contents and command arguments are never recorded.\n\n")
        for (e in snapshot()) {
            sb.append(e.wallTimeMs).append(' ').append(e.type).append(' ').append(e.outcome)
                .append(" uid=").append(e.callerUid)
                .append(" pkg=").append(e.callerPackage ?: "-")
                .append(" cap=").append(e.capability ?: "-")
                .append(" req=").append(e.requestId ?: "-")
                .append(" task=").append(e.remoteTaskId ?: "-")
                .append(" ms=").append(e.durationMs ?: "-")
                .append(" err=").append(e.errorCategory ?: "-")
                .append(" :: ").append(e.detail).append('\n')
        }
        return sb.toString()
    }

    fun toJsonArray(): JSONArray = JSONArray().also { a -> snapshot().forEach { a.put(toJson(it)) } }

    private fun trimLocked() {
        val cutoff = wallClock() - retention.maxAgeMs
        while (events.isNotEmpty() && (events.size > retention.maxEntries || events.first().wallTimeMs < cutoff)) {
            events.removeFirst()
        }
    }

    companion object {
        fun toJson(e: ActivityEvent): JSONObject = JSONObject().apply {
            put("t", e.wallTimeMs); put("type", e.type.name); put("uid", e.callerUid)
            put("pkg", e.callerPackage ?: JSONObject.NULL); put("cap", e.capability ?: JSONObject.NULL)
            put("req", e.requestId ?: JSONObject.NULL); put("task", e.remoteTaskId ?: JSONObject.NULL)
            put("ms", e.durationMs ?: JSONObject.NULL); put("out", e.outcome.name)
            put("err", e.errorCategory ?: JSONObject.NULL); put("detail", e.detail)
        }

        fun fromJson(o: JSONObject): ActivityEvent = ActivityEvent(
            wallTimeMs = o.getLong("t"), type = EventType.valueOf(o.getString("type")), callerUid = o.getInt("uid"),
            callerPackage = o.optString("pkg").takeIf { !o.isNull("pkg") && it.isNotEmpty() },
            capability = o.optString("cap").takeIf { !o.isNull("cap") && it.isNotEmpty() },
            requestId = o.optString("req").takeIf { !o.isNull("req") && it.isNotEmpty() },
            remoteTaskId = o.optString("task").takeIf { !o.isNull("task") && it.isNotEmpty() },
            durationMs = if (o.isNull("ms")) null else o.getLong("ms"),
            outcome = Outcome.valueOf(o.getString("out")),
            errorCategory = o.optString("err").takeIf { !o.isNull("err") && it.isNotEmpty() },
            detail = o.optString("detail"),
        )
    }
}
