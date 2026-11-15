package ai.victorious.bridge.core

import org.junit.Assert.*
import org.junit.Test

class GrantStoreTest {
    private val clock = FakeClock()
    private val pm = FakeResolver().apply { install(10123, "com.example.app"); install(10124, "com.example.other") }
    private val ended = ArrayList<Pair<Int, GrantEndReason>>()
    private val persist = MemoryPersistence()
    private fun store(p: GrantPersistence? = persist) =
        GrantStore(clock, pm, wallClock = { 1_700_000_000_000L }, persistence = p, onGrantEnded = { u, r -> ended += u to r })

    @Test fun noGrantIsDenied() {
        assertEquals(Decision.Denied(DenyReason.NO_GRANT), store().check(10123, Capability.FILES_READ))
    }

    @Test fun scopedGrantAllowsOnlyListedCapabilities() {
        val s = store()
        s.grant(10123, setOf(Capability.FILES_READ), GrantMode.SESSION)
        assertTrue(s.check(10123, Capability.FILES_READ) is Decision.Allowed)
        assertEquals(Decision.Denied(DenyReason.CAPABILITY_NOT_GRANTED), s.check(10123, Capability.FILES_WRITE))
        assertEquals(Decision.Denied(DenyReason.CAPABILITY_NOT_GRANTED), s.check(10123, Capability.SHELL_EXEC))
    }

    @Test fun scopedGrantNeverImpliesLegacyFull() {
        val s = store()
        s.grant(10123, Capability.scoped.toSet(), GrantMode.UNTIL_REVOKED)
        assertFalse("every scoped capability together must still not be legacy access", s.isLegacyFullActive(10123))
        assertEquals(Decision.Denied(DenyReason.LEGACY_NOT_GRANTED), s.check(10123, Capability.LEGACY_FULL))
    }

    @Test fun grantIsIsolatedPerUid() {
        val s = store()
        s.grant(10123, setOf(Capability.DIAGNOSTICS_READ), GrantMode.SESSION)
        assertEquals(Decision.Denied(DenyReason.NO_GRANT), s.check(10124, Capability.DIAGNOSTICS_READ))
    }

    @Test fun timedGrantExpiresAtExecutionTime() {
        val s = store()
        s.grant(10123, setOf(Capability.SETTINGS_READ), GrantMode.TIMED_15_MIN)
        clock.advance(GrantStore.TIMED_DURATION_MS - 1)
        assertTrue(s.check(10123, Capability.SETTINGS_READ) is Decision.Allowed)
        clock.advance(1)
        assertEquals(Decision.Denied(DenyReason.EXPIRED), s.check(10123, Capability.SETTINGS_READ))
        // and it is gone, not merely hidden
        assertEquals(Decision.Denied(DenyReason.NO_GRANT), s.check(10123, Capability.SETTINGS_READ))
        assertTrue(ended.contains(10123 to GrantEndReason.EXPIRED))
    }

    @Test fun wallClockChangesDoNotAffectExpiry() {
        var wall = 1_000_000L
        val s = GrantStore(clock, pm, wallClock = { wall })
        val g = s.grant(10123, setOf(Capability.SETTINGS_READ), GrantMode.TIMED_15_MIN)
        wall -= 10L * 24 * 3600 * 1000      // user rolls the clock back ten days
        clock.advance(GrantStore.TIMED_DURATION_MS)
        assertEquals(Decision.Denied(DenyReason.EXPIRED), s.check(10123, Capability.SETTINGS_READ))
        assertEquals(1_000_000L, g.createdWallMs)
    }

    @Test fun temporaryGrantsAreNotPersistedAndDieWithTheServiceProcess() {
        val s = store()
        s.grant(10123, setOf(Capability.FILES_READ), GrantMode.SESSION)
        s.grant(10124, setOf(Capability.FILES_READ), GrantMode.TIMED_15_MIN)
        assertTrue("nothing temporary may be written to disk", persist.saved.isEmpty())
        val restarted = store()      // simulates service restart: new process, same persistence
        assertEquals(Decision.Denied(DenyReason.NO_GRANT), restarted.check(10123, Capability.FILES_READ))
        assertEquals(Decision.Denied(DenyReason.NO_GRANT), restarted.check(10124, Capability.FILES_READ))
    }

    @Test fun untilRevokedSurvivesRestartButStillVerifiesIdentity() {
        store().grant(10123, setOf(Capability.FILES_READ), GrantMode.UNTIL_REVOKED, listOf("/sdcard/Download"))
        val restarted = store()
        assertTrue(restarted.check(10123, Capability.FILES_READ) is Decision.Allowed)
        assertEquals(listOf("/sdcard/Download"), restarted.find(10123)!!.fileRoots)
    }

    @Test fun reinstallWithDifferentSigningKeyFailsClosedAndEndsGrant() {
        val s = store()
        s.grant(10123, setOf(Capability.FILES_WRITE), GrantMode.UNTIL_REVOKED)
        pm.install(10123, "com.example.app", signer = "bb22")   // same package, different key
        assertEquals(Decision.Denied(DenyReason.IDENTITY_MISMATCH), s.check(10123, Capability.FILES_WRITE))
        assertTrue(persist.saved.isEmpty())
        pm.install(10123, "com.example.app", signer = "aa11")   // original key returns: grant must NOT come back
        assertEquals(Decision.Denied(DenyReason.NO_GRANT), s.check(10123, Capability.FILES_WRITE))
    }

    @Test fun recycledUidOwnedByDifferentPackageIsDenied() {
        val s = store()
        s.grant(10123, setOf(Capability.SHELL_EXEC), GrantMode.UNTIL_REVOKED)
        pm.uninstall(10123)
        pm.install(10123, "com.attacker.app", signer = "aa11")  // even with a coincidentally equal signer
        assertEquals(Decision.Denied(DenyReason.IDENTITY_MISMATCH), s.check(10123, Capability.SHELL_EXEC))
    }

    @Test fun uninstalledCallerIsDenied() {
        val s = store()
        s.grant(10123, setOf(Capability.FILES_READ), GrantMode.SESSION)
        pm.uninstall(10123)
        assertEquals(Decision.Denied(DenyReason.IDENTITY_UNRESOLVED), s.check(10123, Capability.FILES_READ))
    }

    @Test fun differentAndroidUsersAreDifferentUids() {
        pm.install(1010123, "com.example.app")   // same package in user 10
        val s = store()
        s.grant(10123, setOf(Capability.FILES_READ), GrantMode.SESSION)
        assertEquals(Decision.Denied(DenyReason.NO_GRANT), s.check(1010123, Capability.FILES_READ))
        assertEquals(10, pm.resolve(1010123)!!.userId)
    }

    @Test fun sharedUidGrantCoversTheUidAndRecordsEveryPackage() {
        pm.table[10200] = CallerIdentity(10200, setOf("com.suite.a", "com.suite.b"), setOf("cc33"))
        val s = store()
        val g = s.grant(10200, setOf(Capability.DIAGNOSTICS_READ), GrantMode.SESSION)
        assertEquals(setOf("com.suite.a", "com.suite.b"), g.packages)
        // Honest limitation: the grant is per-uid, so package isolation inside one shared uid is not provided.
        assertTrue(s.check(10200, Capability.DIAGNOSTICS_READ) is Decision.Allowed)
    }

    @Test fun perOperationGrantNeverAllowsWithoutApproval() {
        val s = store()
        s.grant(10123, setOf(Capability.FILES_READ), GrantMode.PER_OPERATION)
        assertTrue(s.check(10123, Capability.FILES_READ) is Decision.NeedsApproval)
    }

    @Test(expected = IllegalArgumentException::class) fun legacyFullCannotBePerOperation() {
        store().grant(10123, setOf(Capability.LEGACY_FULL), GrantMode.PER_OPERATION)
    }

    @Test fun revokeEndsGrantNotifiesAndPersists() {
        val s = store()
        s.grant(10123, setOf(Capability.FILES_READ), GrantMode.UNTIL_REVOKED)
        assertTrue(s.revoke(10123))
        assertEquals(Decision.Denied(DenyReason.NO_GRANT), s.check(10123, Capability.FILES_READ))
        assertTrue(persist.saved.isEmpty())
        assertTrue(ended.contains(10123 to GrantEndReason.REVOKED))
        assertFalse(s.revoke(10123))
    }

    @Test fun revokeAllClearsEverything() {
        val s = store()
        s.grant(10123, setOf(Capability.FILES_READ), GrantMode.SESSION)
        s.grant(10124, setOf(Capability.FILES_READ), GrantMode.UNTIL_REVOKED)
        assertEquals(setOf(10123, 10124), s.revokeAll().toSet())
        assertTrue(s.list().isEmpty())
        assertTrue(persist.saved.isEmpty())
    }

    @Test fun regrantReplacesAndNotifies() {
        val s = store()
        s.grant(10123, setOf(Capability.FILES_READ), GrantMode.SESSION)
        s.grant(10123, setOf(Capability.FILES_WRITE), GrantMode.SESSION)
        assertEquals(Decision.Denied(DenyReason.CAPABILITY_NOT_GRANTED), s.check(10123, Capability.FILES_READ))
        assertTrue(ended.contains(10123 to GrantEndReason.REPLACED))
    }

    @Test(expected = IllegalStateException::class) fun cannotGrantUnknownUid() {
        store().grant(99999, setOf(Capability.FILES_READ), GrantMode.SESSION)
    }
}
