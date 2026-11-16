package ai.victorious.bridge.core

data class SettingSpec(
    val namespace: String,
    val key: String,
    val writable: Boolean,
    val description: String,
    val valuePattern: Regex,
)

/**
 * Allow-list of settings the controlled API may read or change. Anything not listed is unsupported:
 * a client holding `settings.write` still cannot touch, for example, `adb_enabled` or secure-lock keys.
 * Values are validated against a strict pattern before they reach any command line.
 */
object SettingsPolicy {
    private val bool01 = Regex("[01]")
    private val smallInt = Regex("[0-9]{1,6}")
    private val scale = Regex("(?:[0-9]|10)(?:\\.[0-9]{1,2})?")

    val specs: List<SettingSpec> = listOf(
        SettingSpec("global", "window_animation_scale", true, "Window animation scale", scale),
        SettingSpec("global", "transition_animation_scale", true, "Transition animation scale", scale),
        SettingSpec("global", "animator_duration_scale", true, "Animator duration scale", scale),
        SettingSpec("global", "stay_on_while_plugged_in", true, "Stay awake while charging (bitmask 0-7)", Regex("[0-7]")),
        SettingSpec("system", "screen_off_timeout", true, "Screen timeout (ms)", Regex("[0-9]{4,7}")),
        SettingSpec("system", "screen_brightness", true, "Screen brightness (0-255)", Regex("(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})")),
        SettingSpec("system", "screen_brightness_mode", true, "Adaptive brightness (0/1)", bool01),
        SettingSpec("system", "accelerometer_rotation", true, "Auto-rotate (0/1)", bool01),
        SettingSpec("system", "font_scale", true, "Font scale", Regex("(?:[0-1]\\.[0-9]{1,2}|2\\.0{1,2})")),
        // Read-only diagnostics-style settings. Reading is allowed; writing is deliberately not.
        SettingSpec("global", "adb_enabled", false, "USB debugging state", bool01),
        SettingSpec("global", "adb_wifi_enabled", false, "Wireless debugging state", bool01),
        SettingSpec("global", "development_settings_enabled", false, "Developer options state", bool01),
        SettingSpec("global", "airplane_mode_on", false, "Airplane mode state", bool01),
    )

    fun find(namespace: String?, key: String?): SettingSpec? =
        specs.firstOrNull { it.namespace == namespace && it.key == key }

    /** @return null if the write is permitted, else an [ErrorCode] explaining why not. */
    fun checkWrite(namespace: String?, key: String?, value: String?): ErrorCode? {
        val spec = find(namespace, key) ?: return ErrorCode.UNSUPPORTED_OPERATION
        if (!spec.writable) return ErrorCode.UNSUPPORTED_OPERATION
        if (value == null || !spec.valuePattern.matches(value)) return ErrorCode.INVALID_ARGUMENT
        return null
    }
}

object PackagePolicy {
    private val nameRegex = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")

    /** Packages the controlled API will never disable or force-stop, regardless of grant. */
    private val protectedExact = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone", "com.android.shell",
        "com.android.providers.settings", "com.android.providers.telephony", "com.android.server.telecom",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.android.bluetooth",
        "com.android.nfc", "com.android.inputmethod.latin", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.android.permissioncontroller",
        BridgeIdentity.APPLICATION_ID,
    )

    fun isValidName(name: String?): Boolean = name != null && name.length <= 255 && nameRegex.matches(name)

    fun isProtected(name: String): Boolean = name in protectedExact
}
