package ai.victorious.bridge.core

import org.junit.Assert.*
import org.junit.Test

class ActivityLogTest {
    private fun ev(t: Long, detail: String = "d", id: String = "r$t") = ActivityEvent(
        t, EventType.OPERATION, 10123, "com.example", "files.read", id, "task-9", 12, Outcome.OK, null, detail,
    )

    class MemStore : ActivityStore {
        var lines: List<String> = emptyList()
        override fun load() = lines
        override fun save(lines: List<String>) { this.lines = lines }
    }

    @Test fun redactsOnAppendAndBoundsLength() {
        val log = ActivityLog(wallClock = { 100L })
        log.append(ev(100, "token=abc password=xyz " + "x".repeat(1000)))
        val d = log.snapshot().single().detail
        assertFalse(d.contains("abc")); assertFalse(d.contains("xyz")); assertTrue(d.length <= 240)
    }

    @Test fun enforcesMaxEntriesAndAge() {
        var now = 10_000L
        val log = ActivityLog(retention = RetentionPolicy(maxEntries = 3, maxAgeMs = 5_000), wallClock = { now })
        for (i in 1..5) log.append(ev(now + i))
        assertEquals(3, log.snapshot().size)
        now += 100_000
        log.append(ev(now))
        assertEquals("old events must age out", 1, log.snapshot().size)
    }

    @Test fun retentionCanBeTightenedAndCleared() {
        val log = ActivityLog(wallClock = { 100L })
        for (i in 1..10) log.append(ev(100))
        log.setRetention(RetentionPolicy(maxEntries = 2))
        assertEquals(2, log.snapshot().size)
        log.clear()
        assertTrue(log.snapshot().isEmpty())
    }

    @Test fun persistsAndReloadsRoundTrip() {
        val store = MemStore()
        val log = ActivityLog(store, wallClock = { 100L })
        log.append(ev(100, "hello"))
        log.flushIfDirty()
        val again = ActivityLog(store, wallClock = { 100L })
        assertEquals("task-9", again.snapshot().single().remoteTaskId)
        assertEquals("hello", again.snapshot().single().detail)
    }

    @Test fun exportIsRedactedAndStatesVisibilityLimits() {
        val log = ActivityLog(wallClock = { 100L })
        log.append(ev(100, "secret=topsecret"))
        val out = log.exportRedacted(listOf("device token=zzz"))
        assertFalse(out.contains("topsecret")); assertFalse(out.contains("zzz"))
        assertTrue(out.contains("LEGACY_FULL clients"))
        assertTrue(out.contains("never recorded"))
    }
}
