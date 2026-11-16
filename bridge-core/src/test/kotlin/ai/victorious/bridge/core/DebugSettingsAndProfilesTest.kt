package ai.victorious.bridge.core

import org.junit.Assert.*
import org.junit.Test

class DebugSettingsAndProfilesTest {
    class FakeAccessor(val values: MutableMap<String, Long?> = HashMap(), var failPut: Boolean = false) : DebugSettingsAccessor {
        val writes = ArrayList<Pair<String, Long>>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: Long): Boolean { if (failPut) return false; writes += key to value; values[key] = value; return true }
    }
    class MemLedger : LedgerStore {
        var entries: List<LedgerEntry> = emptyList()
        override fun load() = entries
        override fun save(entries: List<LedgerEntry>) { this.entries = entries }
    }

    // ---- boot debugging settings -------------------------------------------------------------

    @Test fun nothingIsChangedUnlessOptedIn() {
        val a = FakeAccessor(mutableMapOf("adb_wifi_enabled" to 0L, "adb_enabled" to 0L, "adb_allowed_connection_time" to 604800000L))
        val reports = DebugSettingsController(a, MemLedger()).prepareForStart(BootSettingsPolicy())
        assertTrue(a.writes.isEmpty())
        assertTrue(reports.all { it is SettingChangeReport.NotAllowed })
    }

    @Test fun eachSettingHasItsOwnSeparateSwitch() {
        val a = FakeAccessor(mutableMapOf("adb_wifi_enabled" to 0L, "adb_enabled" to 0L, "adb_allowed_connection_time" to 100L))
        DebugSettingsController(a, MemLedger()).prepareForStart(BootSettingsPolicy(changeWirelessDebugging = true))
        assertEquals(listOf("adb_wifi_enabled" to 1L), a.writes)
    }

    @Test fun capturesPreviousValueAndRestoresIt() {
        val a = FakeAccessor(mutableMapOf("adb_wifi_enabled" to 0L)); val l = MemLedger()
        val c = DebugSettingsController(a, l)
        c.prepareForStart(BootSettingsPolicy(changeWirelessDebugging = true))
        assertEquals(1L, a.values["adb_wifi_enabled"])
        assertEquals(listOf(LedgerEntry(DebugSetting.WIRELESS_DEBUGGING, 0L, 1L)), l.entries)
        val rep = c.restore().single() as RestoreReport.Restored
        assertEquals(0L, rep.value); assertEquals(0L, a.values["adb_wifi_enabled"]); assertTrue(l.entries.isEmpty())
    }

    @Test fun doesNotOverwriteALaterUserChangeOnRestore() {
        val a = FakeAccessor(mutableMapOf("adb_allowed_connection_time" to 604800000L)); val l = MemLedger()
        val c = DebugSettingsController(a, l)
        c.prepareForStart(BootSettingsPolicy(disableAuthTimeout = true))
        a.values["adb_allowed_connection_time"] = 12345L            // user changed it after boot
        val rep = c.restore().single()
        assertTrue(rep is RestoreReport.KeptUserChange)
        assertEquals(12345L, a.values["adb_allowed_connection_time"])
    }

    @Test fun settingAlreadyAtDesiredValueIsNotTouchedOrLedgered() {
        val a = FakeAccessor(mutableMapOf("adb_wifi_enabled" to 1L)); val l = MemLedger()
        val rep = DebugSettingsController(a, l).prepareForStart(BootSettingsPolicy(changeWirelessDebugging = true))
        assertTrue(rep.first() is SettingChangeReport.AlreadySet)
        assertTrue(a.writes.isEmpty()); assertTrue(l.entries.isEmpty())
    }

    @Test fun repeatedStartsKeepTheOriginalPreviousValue() {
        val a = FakeAccessor(mutableMapOf("adb_wifi_enabled" to 0L)); val l = MemLedger()
        val c = DebugSettingsController(a, l)
        c.prepareForStart(BootSettingsPolicy(changeWirelessDebugging = true))
        a.values["adb_wifi_enabled"] = 0L                             // turned off again, never restored by us
        c.prepareForStart(BootSettingsPolicy(changeWirelessDebugging = true))
        assertEquals(0L, l.entries.single().previous)
    }

    @Test fun failedWriteIsReportedAndNotLedgered() {
        val a = FakeAccessor(mutableMapOf("adb_wifi_enabled" to 0L), failPut = true); val l = MemLedger()
        val rep = DebugSettingsController(a, l).prepareForStart(BootSettingsPolicy(changeWirelessDebugging = true))
        assertTrue(rep.first() is SettingChangeReport.Failed); assertTrue(l.entries.isEmpty())
    }

    // ---- profiles ----------------------------------------------------------------------------

    class FakeBackend(val values: MutableMap<String, String?> = HashMap(), val failOn: Set<String> = emptySet()) : SettingsBackend {
        override fun get(namespace: String, key: String) = values["$namespace/$key"]
        override fun put(namespace: String, key: String, value: String) {
            if ("$namespace/$key" in failOn) throw BridgeException(ErrorCode.EXEC_FAILED, "x")
            values["$namespace/$key"] = value
        }
    }

    private val full = setOf(Capability.SETTINGS_READ, Capability.SETTINGS_WRITE)
    private val profile = SettingsProfile("Focus", "calm", listOf(
        ProfileEntry("system", "screen_brightness", "40"),
        ProfileEntry("global", "window_animation_scale", "0.5"),
        ProfileEntry("global", "adb_enabled", "0"),                // not writable -> unsupported
        ProfileEntry("system", "font_scale", "9.9"),               // invalid value
    ))

    @Test fun previewShowsChangesCapabilitiesAndUnsupportedEntriesWithoutWriting() {
        val b = FakeBackend(mutableMapOf("system/screen_brightness" to "200", "global/window_animation_scale" to "0.5"))
        val p = ProfileEngine(b, full).preview(profile)
        assertEquals(listOf(PreviewStatus.WILL_CHANGE, PreviewStatus.UNCHANGED, PreviewStatus.UNSUPPORTED, PreviewStatus.INVALID_VALUE), p.items.map { it.status })
        assertEquals(setOf(Capability.SETTINGS_READ, Capability.SETTINGS_WRITE), p.requiredCapabilities)
        assertEquals("200", b.values["system/screen_brightness"])
    }

    @Test fun applyCapturesPreviousValuesAndReportsPartialFailureAccurately() {
        val b = FakeBackend(mutableMapOf("system/screen_brightness" to "200", "global/window_animation_scale" to "1.0"),
            failOn = setOf("global/window_animation_scale"))
        val a = ProfileEngine(b, full, newId = { "id1" }).apply(profile)
        assertEquals(listOf(ApplyStatus.APPLIED, ApplyStatus.FAILED, ApplyStatus.UNSUPPORTED, ApplyStatus.INVALID_VALUE), a.entries.map { it.status })
        assertEquals("200", a.entries[0].previous)
        assertTrue(a.partial)
        assertEquals("40", b.values["system/screen_brightness"]); assertEquals("1.0", b.values["global/window_animation_scale"])
    }

    @Test fun applyWithoutCapabilityChangesNothing() {
        val b = FakeBackend(mutableMapOf("system/screen_brightness" to "200"))
        val a = ProfileEngine(b, setOf(Capability.SETTINGS_READ)).apply(profile)
        assertEquals("200", b.values["system/screen_brightness"])
        assertTrue(a.entries.filter { it.status == ApplyStatus.FAILED }.all { it.error == "DENIED" })
    }

    @Test fun undoRestoresCapturedValues() {
        val b = FakeBackend(mutableMapOf("system/screen_brightness" to "200"))
        val e = ProfileEngine(b, full)
        val a = e.apply(SettingsProfile("p", entries = listOf(ProfileEntry("system", "screen_brightness", "40"))))
        assertEquals(UndoStatus.RESTORED, e.undo(a).single().status)
        assertEquals("200", b.values["system/screen_brightness"])
    }

    @Test fun undoDetectsConflictingChangeAndLeavesItUnlessForced() {
        val b = FakeBackend(mutableMapOf("system/screen_brightness" to "200"))
        val e = ProfileEngine(b, full)
        val a = e.apply(SettingsProfile("p", entries = listOf(ProfileEntry("system", "screen_brightness", "40"))))
        b.values["system/screen_brightness"] = "77"                  // user moved the slider afterwards
        val u = e.undo(a).single()
        assertEquals(UndoStatus.CONFLICT, u.status); assertEquals("77", u.current)
        assertEquals("77", b.values["system/screen_brightness"])
        assertEquals(UndoStatus.RESTORED, e.undo(a, force = true).single().status)
        assertEquals("200", b.values["system/screen_brightness"])
    }

    @Test fun unsetPreviousValueIsReportedNotGuessed() {
        val b = FakeBackend(); val e = ProfileEngine(b, full)
        val a = e.apply(SettingsProfile("p", entries = listOf(ProfileEntry("system", "screen_brightness", "40"))))
        assertEquals(UndoStatus.NO_PREVIOUS_VALUE, e.undo(a).single().status)
    }

    @Test fun exportCarriesOnlySettingsAndRoundTrips() {
        val json = profile.toJson()
        assertEquals(profile, SettingsProfile.parse(json))
        listOf("token", "grant", "uid", "secret", "private", "adb_key", "pairing").forEach { assertFalse(it, json.lowercase().contains("\"$it")) }
    }

    @Test fun importIgnoresSmuggledAuthorizationAndKeyFields() {
        val smuggled = """{"format":1,"name":"x","grants":[{"uid":1,"caps":["legacy.full"]}],"adb_private_key":"AAAA",
            "entries":[{"namespace":"system","key":"screen_brightness","value":"9","extra":"y"}]}"""
        val p = SettingsProfile.parse(smuggled)
        assertEquals(1, p.entries.size)
        assertFalse(p.toJson().contains("AAAA")); assertFalse(p.toJson().contains("legacy"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownFormatVersion() { SettingsProfile.parse("""{"format":99,"name":"x","entries":[]}""") }
    @Test(expected = IllegalArgumentException::class) fun rejectsOversizedProfiles() { SettingsProfile.parse(" ".repeat(70_000)) }

    @Test fun appliedRecordRoundTripsForUndoAcrossRestarts() {
        val b = FakeBackend(mutableMapOf("system/screen_brightness" to "200"))
        val a = ProfileEngine(b, full, wallClock = { 5 }, newId = { "z" }).apply(SettingsProfile("p", entries = listOf(ProfileEntry("system", "screen_brightness", "40"))))
        assertEquals(a, AppliedProfile.parse(a.toJson()))
    }
}
