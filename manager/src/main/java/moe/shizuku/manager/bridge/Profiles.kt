package moe.shizuku.manager.bridge

import ai.victorious.bridge.core.AppliedProfile
import ai.victorious.bridge.core.ApplyStatus
import ai.victorious.bridge.core.BridgeException
import ai.victorious.bridge.core.Capability
import ai.victorious.bridge.core.ErrorCode
import ai.victorious.bridge.core.PreviewStatus
import ai.victorious.bridge.core.ProfileEngine
import ai.victorious.bridge.core.SettingsBackend
import ai.victorious.bridge.core.SettingsProfile
import ai.victorious.bridge.core.UndoStatus
import ai.victorious.bridge.sdk.VictoriousBridge
import android.content.Context
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings

/** Settings backend that goes through the controlled API, so allow-lists, grants and backend policy all apply. */
class BridgeSettingsBackend : SettingsBackend {
    override fun get(namespace: String, key: String): String? {
        val r = VictoriousBridge.getSetting(namespace, key)
        if (!r.ok) throw BridgeException(r.errorCode ?: ErrorCode.INTERNAL, r.message ?: "")
        return r.data["value"] as? String
    }

    override fun put(namespace: String, key: String, value: String) {
        val r = VictoriousBridge.putSetting(namespace, key, value)
        if (!r.ok) throw BridgeException(r.errorCode ?: ErrorCode.INTERNAL, r.message ?: "")
    }
}

/** Import -> preview -> apply -> undo, with the applied record (previous values) kept locally. */
object ProfileFlow {
    private const val K_LAST = "last_applied_profile"
    private val held = setOf(Capability.SETTINGS_READ, Capability.SETTINGS_WRITE)

    const val SAMPLE = """{"format":1,"name":"Calm screen","description":"Shorter animations and a 60 second timeout","entries":[
{"namespace":"global","key":"window_animation_scale","value":"0.5"},
{"namespace":"global","key":"transition_animation_scale","value":"0.5"},
{"namespace":"system","key":"screen_off_timeout","value":"60000"}]}"""

    fun import(context: Context, uri: Uri) {
        val text = try { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } } catch (_: Throwable) { null }
        val profile = try { SettingsProfile.parse(text ?: "") } catch (e: Exception) {
            MaterialAlertDialogBuilder(context).setMessage("Not a valid profile: " + (e.message ?: e.javaClass.simpleName)).setPositiveButton(android.R.string.ok, null).show(); return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val preview = withContext(Dispatchers.IO) { ProfileEngine(BridgeSettingsBackend(), held).preview(profile) }
            val lines = preview.items.joinToString("\n") {
                val tag = when (it.status) { PreviewStatus.WILL_CHANGE -> "change"; PreviewStatus.UNCHANGED -> "already set"; PreviewStatus.UNSUPPORTED -> "unsupported, skipped"; PreviewStatus.INVALID_VALUE -> "invalid value, skipped" }
                "${it.entry.namespace}/${it.entry.key}: ${it.current ?: "(unset)"} -> ${it.entry.value}  [$tag]"
            }
            val need = preview.requiredCapabilities.joinToString { it.id }.ifEmpty { "none" }
            MaterialAlertDialogBuilder(context).setTitle(profile.name)
                .setMessage("${profile.description}\n\nRequired capabilities: $need\n\n$lines")
                .setPositiveButton("Apply") { _, _ -> apply(context, profile) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun apply(context: Context, profile: SettingsProfile) {
        CoroutineScope(Dispatchers.Main).launch {
            val applied = withContext(Dispatchers.IO) { ProfileEngine(BridgeSettingsBackend(), held).apply(profile) }
            ShizukuSettings.getPreferences().edit().putString(K_LAST, applied.toJson()).apply()
            val ok = applied.entries.count { it.status == ApplyStatus.APPLIED }
            val failed = applied.entries.filter { it.status == ApplyStatus.FAILED }
            val skipped = applied.entries.count { it.status == ApplyStatus.UNSUPPORTED || it.status == ApplyStatus.INVALID_VALUE }
            MaterialAlertDialogBuilder(context).setTitle(if (applied.partial || failed.isNotEmpty()) "Partly applied" else "Applied")
                .setMessage("Applied: $ok\nSkipped: $skipped\nFailed: ${failed.size}" + failed.joinToString("") { "\n - ${it.entry.key}: ${it.error}" } + "\n\nPrevious values were saved; use Undo to restore them.")
                .setPositiveButton(android.R.string.ok, null).show()
        }
    }

    fun undoLast(context: Context) {
        val a = ShizukuSettings.getPreferences().getString(K_LAST, null)?.let { runCatching { AppliedProfile.parse(it) }.getOrNull() }
        if (a == null) { MaterialAlertDialogBuilder(context).setMessage("Nothing to undo.").setPositiveButton(android.R.string.ok, null).show(); return }
        CoroutineScope(Dispatchers.Main).launch {
            val res = withContext(Dispatchers.IO) { ProfileEngine(BridgeSettingsBackend(), held).undo(a) }
            val conflicts = res.filter { it.status == UndoStatus.CONFLICT }
            val msg = res.joinToString("\n") { "${it.entry.key}: ${it.status.name.lowercase().replace('_', ' ')}" }
            val b = MaterialAlertDialogBuilder(context).setTitle("Undo").setMessage(msg + if (conflicts.isNotEmpty()) "\n\nSome values were changed after the profile was applied and were left alone." else "")
                .setPositiveButton(android.R.string.ok, null)
            if (conflicts.isNotEmpty()) b.setNeutralButton("Restore anyway") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch { ProfileEngine(BridgeSettingsBackend(), held).undo(a, force = true) }
            } else ShizukuSettings.getPreferences().edit().remove(K_LAST).apply()
            b.show()
        }
    }
}
