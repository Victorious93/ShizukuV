package ai.victorious.bridge.core

import org.junit.Assert.*
import org.junit.Test

class BinderWaitTest {
    class ManualScheduler : DelayScheduler {
        var pending: (() -> Unit)? = null
        var cancelled = false
        var delay = -1L
        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit { pending = task; delay = delayMs; return { cancelled = true; pending = null } }
        fun fire() { pending?.invoke() }
    }

    private class Harness(timeout: Long = 5000) {
        val sched = ManualScheduler(); val results = ArrayList<BinderWaitResult>(); var cleanups = 0
        val wait = BinderWait(timeout, sched, { cleanups++ }, { results += it })
    }

    @Test fun timeoutIsReportedAsTimeoutNotSuccess() {
        val h = Harness(); h.wait.start()
        assertEquals(5000, h.sched.delay)
        h.sched.fire()
        assertEquals(listOf(BinderWaitResult.TIMED_OUT), h.results); assertEquals(1, h.cleanups)
    }

    @Test fun arrivalBeatsTimeoutAndCancelsIt() {
        val h = Harness(); h.wait.start(); h.wait.binderReceived()
        assertEquals(listOf(BinderWaitResult.RECEIVED), h.results); assertTrue(h.sched.cancelled)
        h.sched.fire(); assertEquals("late timeout must not produce a second result", 1, h.results.size)
    }

    @Test fun stickyListenerFiringBeforeStartStillWorks() {
        val h = Harness(); h.wait.binderReceived(); h.wait.start()
        assertEquals(listOf(BinderWaitResult.RECEIVED), h.results); assertNull(h.sched.pending)
    }

    @Test fun deathDuringWaitFailsClosed() {
        val h = Harness(); h.wait.start(); h.wait.binderDied()
        assertEquals(listOf(BinderWaitResult.DIED), h.results); assertEquals(1, h.cleanups)
    }

    @Test fun cancelFromActivityDestroyCleansUpOnceAndNeverReportsLater() {
        val h = Harness(); h.wait.start(); h.wait.cancel(); h.wait.binderReceived(); h.wait.binderDied(); h.sched.fire()
        assertEquals(listOf(BinderWaitResult.CANCELLED), h.results); assertEquals(1, h.cleanups)
    }

    @Test fun startIsIdempotent() {
        val h = Harness(); h.wait.start(); val first = h.sched.pending; h.wait.start()
        assertSame(first, h.sched.pending)
    }
}
