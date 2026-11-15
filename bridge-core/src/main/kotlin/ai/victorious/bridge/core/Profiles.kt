package ai.victorious.bridge.core

import org.json.JSONArray
import org.json.JSONObject

data class ProfileEntry(val namespace: String, val key: String, val value: String)

data class SettingsProfile(
    val name: String,
    val description: String = "",
    val entries: List<ProfileEntry>,
    val formatVersion: Int = FORMAT_VERSION,
) {
    companion object {
        const val FORMAT_VERSION = 1
        const val MAX_ENTRIES = 64

        /**
         * Parses an imported profile. It accepts settings entries only: anything else in the file
         * (keys, tokens, grants...) is ignored, never applied and never re-exported.
         */
        fun parse(json: String): SettingsProfile {
            require(json.length <= 64 * 1024) { "profile too large" }
            val o = JSONObject(json)
            require(o.optInt("format", 0) == FORMAT_VERSION) { "unsupported profile format" }
            val arr = o.getJSONArray("entries")
            require(arr.length() <= MAX_ENTRIES) { "too many entries" }
            val entries = (0 until arr.length()).map {
                val e = arr.getJSONObject(it)
                ProfileEntry(e.getString("namespace"), e.getString("key"), e.getString("value"))
            }
            return SettingsProfile(o.getString("name").take(64), o.optString("description", "").take(200), entries)
        }
    }

    /** Export contains the name, description and settings values only. No keys, no authorizations. */
    fun toJson(): String = JSONObject().apply {
        put("format", formatVersion); put("name", name); put("description", description)
        put("entries", JSONArray().also { a -> entries.forEach { a.put(JSONObject().put("namespace", it.namespace).put("key", it.key).put("value", it.value)) } })
    }.toString(2)
}

interface SettingsBackend {
    /** Current value, or null if unset. Throws [BridgeException] on denial/failure. */
    fun get(namespace: String, key: String): String?
    fun put(namespace: String, key: String, value: String)
}

enum class PreviewStatus { WILL_CHANGE, UNCHANGED, UNSUPPORTED, INVALID_VALUE }

data class PreviewItem(val entry: ProfileEntry, val status: PreviewStatus, val current: String?)

data class ProfilePreview(val items: List<PreviewItem>, val requiredCapabilities: Set<Capability>)

enum class ApplyStatus { APPLIED, UNCHANGED, UNSUPPORTED, INVALID_VALUE, FAILED }

data class AppliedEntry(val entry: ProfileEntry, val previous: String?, val status: ApplyStatus, val error: String?)

data class AppliedProfile(val id: String, val name: String, val wallTimeMs: Long, val entries: List<AppliedEntry>) {
    val partial: Boolean get() = entries.any { it.status == ApplyStatus.FAILED } && entries.any { it.status == ApplyStatus.APPLIED }

    fun toJson(): String = JSONObject().apply {
        put("id", id); put("name", name); put("t", wallTimeMs)
        put("entries", JSONArray().also { a ->
            entries.forEach {
                a.put(JSONObject().put("ns", it.entry.namespace).put("key", it.entry.key).put("value", it.entry.value)
                    .put("previous", it.previous ?: JSONObject.NULL).put("status", it.status.name).put("error", it.error ?: JSONObject.NULL))
            }
        })
    }.toString()

    companion object {
        fun parse(json: String): AppliedProfile {
            val o = JSONObject(json)
            val arr = o.getJSONArray("entries")
            return AppliedProfile(o.getString("id"), o.getString("name"), o.getLong("t"), (0 until arr.length()).map {
                val e = arr.getJSONObject(it)
                AppliedEntry(ProfileEntry(e.getString("ns"), e.getString("key"), e.getString("value")),
                    if (e.isNull("previous")) null else e.getString("previous"), ApplyStatus.valueOf(e.getString("status")),
                    if (e.isNull("error")) null else e.getString("error"))
            })
        }
    }
}

enum class UndoStatus { RESTORED, CONFLICT, NOTHING_TO_UNDO, NO_PREVIOUS_VALUE, FAILED }

data class UndoEntry(val entry: ProfileEntry, val status: UndoStatus, val current: String?, val error: String?)

class ProfileEngine(
    private val backend: SettingsBackend,
    private val held: Set<Capability>,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    /** Reads current values and classifies each entry; changes nothing. */
    fun preview(p: SettingsProfile): ProfilePreview {
        val items = p.entries.map { e ->
            val spec = SettingsPolicy.find(e.namespace, e.key)
            when {
                spec == null || !spec.writable -> PreviewItem(e, PreviewStatus.UNSUPPORTED, null)
                SettingsPolicy.checkWrite(e.namespace, e.key, e.value) != null -> PreviewItem(e, PreviewStatus.INVALID_VALUE, null)
                else -> {
                    val cur = runCatching { backend.get(e.namespace, e.key) }.getOrNull()
                    PreviewItem(e, if (cur == e.value) PreviewStatus.UNCHANGED else PreviewStatus.WILL_CHANGE, cur)
                }
            }
        }
        val caps = HashSet<Capability>()
        if (items.any { it.status == PreviewStatus.WILL_CHANGE }) { caps += Capability.SETTINGS_WRITE; caps += Capability.SETTINGS_READ }
        return ProfilePreview(items, caps)
    }

    /** Applies supported entries one by one, capturing each previous value *before* writing it. */
    fun apply(p: SettingsProfile): AppliedProfile {
        val results = ArrayList<AppliedEntry>()
        for (e in p.entries) {
            val spec = SettingsPolicy.find(e.namespace, e.key)
            if (spec == null || !spec.writable) { results += AppliedEntry(e, null, ApplyStatus.UNSUPPORTED, null); continue }
            if (SettingsPolicy.checkWrite(e.namespace, e.key, e.value) != null) { results += AppliedEntry(e, null, ApplyStatus.INVALID_VALUE, null); continue }
            if (Capability.SETTINGS_WRITE !in held || Capability.SETTINGS_READ !in held) {
                results += AppliedEntry(e, null, ApplyStatus.FAILED, ErrorCode.DENIED.name); continue
            }
            try {
                val previous = backend.get(e.namespace, e.key)
                if (previous == e.value) { results += AppliedEntry(e, previous, ApplyStatus.UNCHANGED, null); continue }
                backend.put(e.namespace, e.key, e.value)
                results += AppliedEntry(e, previous, ApplyStatus.APPLIED, null)
            } catch (x: BridgeException) {
                results += AppliedEntry(e, null, ApplyStatus.FAILED, x.code.name)
            } catch (x: Exception) {
                results += AppliedEntry(e, null, ApplyStatus.FAILED, ErrorCode.INTERNAL.name)
            }
        }
        return AppliedProfile(newId(), p.name, wallClock(), results)
    }

    /**
     * Restores captured values. An entry whose current value is no longer what we applied was changed by
     * someone else since: it is reported as a CONFLICT and left alone unless [force] is set.
     */
    fun undo(a: AppliedProfile, force: Boolean = false): List<UndoEntry> = a.entries.map { r ->
        if (r.status != ApplyStatus.APPLIED) return@map UndoEntry(r.entry, UndoStatus.NOTHING_TO_UNDO, null, null)
        try {
            val current = backend.get(r.entry.namespace, r.entry.key)
            when {
                r.previous == null -> UndoEntry(r.entry, UndoStatus.NO_PREVIOUS_VALUE, current, null)
                current != r.entry.value && !force -> UndoEntry(r.entry, UndoStatus.CONFLICT, current, null)
                else -> { backend.put(r.entry.namespace, r.entry.key, r.previous); UndoEntry(r.entry, UndoStatus.RESTORED, current, null) }
            }
        } catch (x: BridgeException) {
            UndoEntry(r.entry, UndoStatus.FAILED, null, x.code.name)
        }
    }
}
