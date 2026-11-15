package ai.victorious.bridge.core

/** Android debugging settings that wireless-debugging boot start may need to change. All changes are opt-in. */
enum class DebugSetting(val key: String, val desired: Int, val label: String) {
    WIRELESS_DEBUGGING("adb_wifi_enabled", 1, "Wireless debugging"),
    USB_DEBUGGING("adb_enabled", 1, "USB debugging"),
    ADB_AUTH_TIMEOUT("adb_allowed_connection_time", 0, "ADB authorization timeout (0 = never expire)"),
}

/** Which settings the user allowed boot start to change. Every one defaults to false. */
data class BootSettingsPolicy(
    val changeWirelessDebugging: Boolean = false,
    val changeUsbDebugging: Boolean = false,
    val disableAuthTimeout: Boolean = false,
    /** After the service is verified running, put back what *we* changed, if the user has not changed it since. */
    val restoreAfterStart: Boolean = false,
) {
    fun allows(s: DebugSetting): Boolean = when (s) {
        DebugSetting.WIRELESS_DEBUGGING -> changeWirelessDebugging
        DebugSetting.USB_DEBUGGING -> changeUsbDebugging
        DebugSetting.ADB_AUTH_TIMEOUT -> disableAuthTimeout
    }
}

interface DebugSettingsAccessor {
    fun get(key: String): Long?
    fun put(key: String, value: Long): Boolean
}

data class LedgerEntry(val setting: DebugSetting, val previous: Long?, val applied: Long)

interface LedgerStore {
    fun load(): List<LedgerEntry>
    fun save(entries: List<LedgerEntry>)
}

sealed class SettingChangeReport {
    abstract val setting: DebugSetting
    data class Changed(override val setting: DebugSetting, val previous: Long?, val now: Long) : SettingChangeReport()
    data class AlreadySet(override val setting: DebugSetting) : SettingChangeReport()
    data class NotAllowed(override val setting: DebugSetting) : SettingChangeReport()
    data class Failed(override val setting: DebugSetting) : SettingChangeReport()
}

sealed class RestoreReport {
    abstract val setting: DebugSetting
    data class Restored(override val setting: DebugSetting, val value: Long) : RestoreReport()

    /** The user (or another app) changed it after we did; we leave their value alone. */
    data class KeptUserChange(override val setting: DebugSetting, val current: Long?) : RestoreReport()
    data class Failed(override val setting: DebugSetting) : RestoreReport()
}

/**
 * Applies only the opted-in settings, remembers the value each had *before* the first change, and can
 * undo exactly those changes without clobbering anything the user changed in the meantime.
 */
class DebugSettingsController(
    private val accessor: DebugSettingsAccessor,
    private val ledger: LedgerStore,
) {
    fun prepareForStart(policy: BootSettingsPolicy): List<SettingChangeReport> {
        val entries = ledger.load().associateBy { it.setting }.toMutableMap()
        val reports = ArrayList<SettingChangeReport>()
        for (s in DebugSetting.values()) {
            if (!policy.allows(s)) { reports += SettingChangeReport.NotAllowed(s); continue }
            val current = accessor.get(s.key)
            if (current == s.desired.toLong()) { reports += SettingChangeReport.AlreadySet(s); continue }
            if (!accessor.put(s.key, s.desired.toLong())) { reports += SettingChangeReport.Failed(s); continue }
            // Keep the *original* capture if an earlier start changed it and it was never restored.
            val keptPrevious = entries[s]?.takeIf { it.applied == current }?.previous ?: current
            entries[s] = LedgerEntry(s, keptPrevious, s.desired.toLong())
            reports += SettingChangeReport.Changed(s, keptPrevious, s.desired.toLong())
        }
        ledger.save(entries.values.toList())
        return reports
    }

    fun pending(): List<LedgerEntry> = ledger.load()

    fun restore(): List<RestoreReport> {
        val remaining = ArrayList<LedgerEntry>()
        val reports = ArrayList<RestoreReport>()
        for (e in ledger.load()) {
            val current = accessor.get(e.setting.key)
            when {
                current != e.applied -> reports += RestoreReport.KeptUserChange(e.setting, current)
                e.previous == null -> reports += RestoreReport.KeptUserChange(e.setting, current) // nothing known to restore to
                accessor.put(e.setting.key, e.previous) -> reports += RestoreReport.Restored(e.setting, e.previous)
                else -> { reports += RestoreReport.Failed(e.setting); remaining += e }
            }
        }
        ledger.save(remaining)
        return reports
    }
}
