package ai.victorious.bridge.core

import java.util.concurrent.atomic.AtomicLong

class FakeClock(start: Long = 1_000) : MonotonicClock {
    private val t = AtomicLong(start)
    override fun elapsedMs(): Long = t.get()
    fun advance(ms: Long) { t.addAndGet(ms) }
}

/** Mutable fake package manager. */
class FakeResolver : IdentityResolver {
    val table = HashMap<Int, CallerIdentity>()
    fun install(uid: Int, pkg: String, signer: String = "aa11") {
        table[uid] = CallerIdentity(uid, setOf(pkg), setOf(signer))
    }
    fun uninstall(uid: Int) { table.remove(uid) }
    override fun resolve(uid: Int): CallerIdentity? = table[uid]
}

class MemoryPersistence : GrantPersistence {
    var saved: List<PersistedGrant> = emptyList()
    override fun load(): List<PersistedGrant> = saved
    override fun save(grants: List<PersistedGrant>) { saved = grants }
}
