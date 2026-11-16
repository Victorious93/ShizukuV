package ai.victorious.bridge.core

/** Android user id for a uid (`uid / 100000`). */
fun userIdOf(uid: Int): Int = uid / 100000

/**
 * Who is calling, as established by the privileged service from kernel-attested uid and package-manager
 * data. A shared uid yields several [packages]; package-level isolation inside one uid is impossible
 * (they share a process permission set), so a grant belongs to the uid and records every package seen.
 */
data class CallerIdentity(
    val uid: Int,
    val packages: Set<String>,
    /** Lower-case hex SHA-256 digests of the signing certificates of every package in [packages]. */
    val signerDigests: Set<String>,
) {
    val userId: Int get() = userIdOf(uid)
}

/** Resolves current identity data for a uid at call time (never cached by the grant layer). */
fun interface IdentityResolver {
    /** @return null if the uid has no installed packages (uninstalled / uid gone). */
    fun resolve(uid: Int): CallerIdentity?
}

/** Monotonic time source. On a device this is `SystemClock.elapsedRealtime()`; it never goes backwards on wall-clock changes. */
fun interface MonotonicClock {
    fun elapsedMs(): Long
}
