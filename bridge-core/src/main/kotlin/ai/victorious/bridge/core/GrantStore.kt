package ai.victorious.bridge.core

data class Grant(
    val uid: Int,
    val packages: Set<String>,
    val signerDigests: Set<String>,
    val capabilities: Set<Capability>,
    val mode: GrantMode,
    /** Canonical file roots approved for FILES_* capabilities. Empty means no file access even if the capability is held. */
    val fileRoots: List<String>,
    /** Monotonic creation time (this service process only). */
    val createdElapsedMs: Long,
    /** Monotonic expiry, or null for modes without a deadline. */
    val expiresElapsedMs: Long?,
    /** Wall-clock creation time, informational only; never used for access decisions. */
    val createdWallMs: Long,
)

enum class DenyReason {
    NO_GRANT, EXPIRED, IDENTITY_MISMATCH, IDENTITY_UNRESOLVED, CAPABILITY_NOT_GRANTED, LEGACY_NOT_GRANTED,
}

sealed class Decision {
    data class Allowed(val grant: Grant) : Decision()

    /** A PER_OPERATION grant: the caller may *ask*, but this specific operation needs a fresh confirmation. */
    data class NeedsApproval(val grant: Grant) : Decision()
    data class Denied(val reason: DenyReason) : Decision()
}

/** Grants that must survive a service restart (UNTIL_REVOKED only). Monotonic fields are deliberately not persisted. */
data class PersistedGrant(
    val uid: Int,
    val packages: Set<String>,
    val signerDigests: Set<String>,
    val capabilities: Set<Capability>,
    val fileRoots: List<String>,
    val createdWallMs: Long,
)

interface GrantPersistence {
    fun load(): List<PersistedGrant>
    fun save(grants: List<PersistedGrant>)
}

/** Why a grant ended; surfaced to the activity log and to revoke listeners. */
enum class GrantEndReason { REVOKED, EXPIRED, IDENTITY_MISMATCH, REPLACED }

/**
 * Authoritative authorization state of the privileged service.
 *
 * Invariants (all covered by tests):
 *  * Temporary grants (SESSION / TIMED_15_MIN) live only in this object's memory, so they end when
 *    the service process ends. No file or setting can resurrect them after a restart or reboot.
 *  * Expiry uses [MonotonicClock]; changing the wall clock cannot extend or shorten a grant.
 *  * Every [check] re-resolves the caller identity (uid -> packages + signer digests). A reinstall
 *    with a different key, or a recycled uid owned by a different package, fails closed and ends the grant.
 *  * Scoped grants never imply [Capability.LEGACY_FULL].
 */
class GrantStore(
    private val clock: MonotonicClock,
    private val resolver: IdentityResolver,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val persistence: GrantPersistence? = null,
    private val onGrantEnded: (uid: Int, reason: GrantEndReason) -> Unit = { _, _ -> },
) {
    companion object {
        const val TIMED_DURATION_MS: Long = 15L * 60L * 1000L
    }

    private val lock = Any()
    private val grants = LinkedHashMap<Int, Grant>()

    init {
        persistence?.load()?.forEach { p ->
            grants[p.uid] = Grant(
                uid = p.uid, packages = p.packages, signerDigests = p.signerDigests,
                capabilities = p.capabilities, mode = GrantMode.UNTIL_REVOKED, fileRoots = p.fileRoots,
                createdElapsedMs = clock.elapsedMs(), expiresElapsedMs = null, createdWallMs = p.createdWallMs,
            )
        }
    }

    /**
     * Creates or replaces the grant for the caller. Identity is re-resolved here rather than trusted from
     * the argument, so a stale [CallerIdentity] cannot be granted.
     */
    fun grant(uid: Int, capabilities: Set<Capability>, mode: GrantMode, fileRoots: List<String> = emptyList()): Grant {
        require(capabilities.isNotEmpty()) { "no capabilities" }
        require(!(Capability.LEGACY_FULL in capabilities && mode == GrantMode.PER_OPERATION)) {
            "legacy full access cannot be per-operation"
        }
        val identity = resolver.resolve(uid) ?: throw IllegalStateException("uid $uid has no installed package")
        val now = clock.elapsedMs()
        val g = Grant(
            uid = uid,
            packages = identity.packages,
            signerDigests = identity.signerDigests,
            capabilities = capabilities.toSet(),
            mode = mode,
            fileRoots = fileRoots.toList(),
            createdElapsedMs = now,
            expiresElapsedMs = if (mode == GrantMode.TIMED_15_MIN) now + TIMED_DURATION_MS else null,
            createdWallMs = wallClock(),
        )
        val replaced: Boolean
        synchronized(lock) {
            replaced = grants.put(uid, g) != null
            persistLocked()
        }
        if (replaced) onGrantEnded(uid, GrantEndReason.REPLACED)
        return g
    }

    fun check(uid: Int, capability: Capability): Decision {
        var ended: GrantEndReason? = null
        val result: Decision
        synchronized(lock) {
            val g = grants[uid]
            if (g == null) {
                result = Decision.Denied(DenyReason.NO_GRANT)
            } else {
                val deadline = g.expiresElapsedMs
                val identity = resolver.resolve(uid)
                if (deadline != null && clock.elapsedMs() >= deadline) {
                    grants.remove(uid); persistLocked(); ended = GrantEndReason.EXPIRED
                    result = Decision.Denied(DenyReason.EXPIRED)
                } else if (identity == null) {
                    grants.remove(uid); persistLocked(); ended = GrantEndReason.IDENTITY_MISMATCH
                    result = Decision.Denied(DenyReason.IDENTITY_UNRESOLVED)
                } else if (!identityMatches(g, identity)) {
                    grants.remove(uid); persistLocked(); ended = GrantEndReason.IDENTITY_MISMATCH
                    result = Decision.Denied(DenyReason.IDENTITY_MISMATCH)
                } else if (capability !in g.capabilities) {
                    result = Decision.Denied(
                        if (capability == Capability.LEGACY_FULL) DenyReason.LEGACY_NOT_GRANTED
                        else DenyReason.CAPABILITY_NOT_GRANTED,
                    )
                } else if (g.mode == GrantMode.PER_OPERATION) {
                    result = Decision.NeedsApproval(g)
                } else {
                    result = Decision.Allowed(g)
                }
            }
        }
        ended?.let { onGrantEnded(uid, it) }
        return result
    }

    /**
     * Ends every grant whose monotonic deadline has passed and notifies listeners. Without this a timed grant
     * would only be noticed lazily on the next [check], leaving anything it started running past its deadline.
     */
    fun sweepExpired(): List<Int> {
        val expired: List<Int>
        synchronized(lock) {
            val now = clock.elapsedMs()
            expired = grants.values.filter { it.expiresElapsedMs != null && now >= it.expiresElapsedMs }.map { it.uid }
            expired.forEach { grants.remove(it) }
            if (expired.isNotEmpty()) persistLocked()
        }
        expired.forEach { onGrantEnded(it, GrantEndReason.EXPIRED) }
        return expired
    }

    /** True only if the caller currently holds unexpired, identity-verified LEGACY_FULL access. */
    fun isLegacyFullActive(uid: Int): Boolean = check(uid, Capability.LEGACY_FULL) is Decision.Allowed

    fun revoke(uid: Int): Boolean {
        val removed: Boolean
        synchronized(lock) {
            removed = grants.remove(uid) != null
            if (removed) persistLocked()
        }
        if (removed) onGrantEnded(uid, GrantEndReason.REVOKED)
        return removed
    }

    fun revokeAll(): List<Int> {
        val uids: List<Int>
        synchronized(lock) {
            uids = grants.keys.toList()
            grants.clear()
            persistLocked()
        }
        uids.forEach { onGrantEnded(it, GrantEndReason.REVOKED) }
        return uids
    }

    /** Snapshot for the UI; expired grants are filtered (and cleaned up lazily on the next [check]). */
    fun list(): List<Grant> = synchronized(lock) {
        val now = clock.elapsedMs()
        grants.values.filter { it.expiresElapsedMs == null || now < it.expiresElapsedMs }
    }

    fun find(uid: Int): Grant? = list().firstOrNull { it.uid == uid }

    /** Milliseconds of monotonic validity left, or null for no deadline. */
    fun remainingMs(grant: Grant): Long? = grant.expiresElapsedMs?.let { maxOf(0L, it - clock.elapsedMs()) }

    private fun identityMatches(g: Grant, now: CallerIdentity): Boolean {
        // Every package the grant was issued to must still be present in the uid with an unchanged signer set.
        // Extra packages joining a shared uid are allowed (same uid == same trust domain) but do not widen the grant.
        if (now.packages.none { it in g.packages }) return false
        if (g.signerDigests.isEmpty() || now.signerDigests.isEmpty()) return false
        return now.signerDigests == g.signerDigests
    }

    private fun persistLocked() {
        val p = persistence ?: return
        p.save(
            grants.values.filter { it.mode == GrantMode.UNTIL_REVOKED }.map {
                PersistedGrant(it.uid, it.packages, it.signerDigests, it.capabilities, it.fileRoots, it.createdWallMs)
            },
        )
    }
}
