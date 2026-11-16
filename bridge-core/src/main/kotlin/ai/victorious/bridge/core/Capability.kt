package ai.victorious.bridge.core

/** How dangerous a capability is, used for UI ordering and approval wording. */
enum class Risk { LOW, MEDIUM, HIGH, CRITICAL }

/**
 * Typed capabilities exposed by the controlled API. Each is checked by the privileged service for
 * every request; none of them implies another.
 */
enum class Capability(val id: String, val risk: Risk, val legacy: Boolean = false) {
    DIAGNOSTICS_READ("diagnostics.read", Risk.LOW),
    SETTINGS_READ("settings.read", Risk.LOW),
    SETTINGS_WRITE("settings.write", Risk.MEDIUM),
    FILES_READ("files.read", Risk.MEDIUM),
    FILES_WRITE("files.write", Risk.HIGH),
    PACKAGES_READ("packages.read", Risk.LOW),
    PACKAGES_MANAGE("packages.manage", Risk.HIGH),

    /** Arbitrary command execution. Separately authorised; never implied by any other capability. */
    SHELL_EXEC("shell.exec", Risk.CRITICAL),

    /**
     * Upstream-style unrestricted access: raw Binder forwarding, arbitrary UserServices, newProcess.
     * Clearly separate from the scoped capabilities and never granted as a fallback.
     */
    LEGACY_FULL("legacy.full", Risk.CRITICAL, legacy = true);

    companion object {
        private val byId = values().associateBy { it.id }
        fun fromId(id: String?): Capability? = id?.let { byId[it] }
        val scoped: List<Capability> = values().filter { !it.legacy }
    }
}

/** What a user can choose when approving an app. Each value has a precise meaning. */
enum class GrantMode(val id: String) {
    /** Valid until the privileged service process ends (restart, crash, reboot) or the grant is revoked. */
    SESSION("session"),

    /** Valid for [GrantStore.TIMED_DURATION_MS] of monotonic time, never beyond the service process. */
    TIMED_15_MIN("timed_15m"),

    /** Persisted across service restarts and reboots until the user revokes it or the caller identity changes. */
    UNTIL_REVOKED("until_revoked"),

    /**
     * No standing access. Every controlled operation is individually confirmed in the manager and the
     * approval is consumed by exactly that operation.
     */
    PER_OPERATION("per_operation");

    companion object {
        fun fromId(id: String?): GrantMode? = values().firstOrNull { it.id == id }
    }
}
