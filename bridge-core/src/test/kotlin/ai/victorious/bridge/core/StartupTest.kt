package ai.victorious.bridge.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupTest {
    class MemStore : StartupStore {
        var rec: StartupRecord? = null
        val history = ArrayList<StartupRecord>()
        override fun load() = rec
        override fun save(record: StartupRecord) { rec = record; history += record }
    }

    class FakeLauncher(val script: List<LaunchOutcome>, override val method: StartMethod = StartMethod.WIRELESS_ADB) : StartupLauncher {
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun launch(attempt: Int): LaunchOutcome {
            calls++
            gate?.await()
            return script[minOf(calls - 1, script.lastIndex)]
        }
    }

    class FakeProbe(var readyAfterLaunch: Boolean = true, var alreadyReady: Boolean = false) : ReadinessProbe {
        var calls = 0
        override suspend fun awaitReady(timeoutMs: Long): Boolean { calls++; return if (timeoutMs == 0L) alreadyReady else readyAfterLaunch }
    }

    private fun orch(l: StartupLauncher?, p: ReadinessProbe, s: MemStore, boot: String? = "boot-1", pol: RetryPolicy = RetryPolicy(),
                     onSuccess: suspend () -> Unit = {}) =
        StartupOrchestrator({ l }, p, s, pol, { boot }, { 42L }, onSuccess)

    @Test fun successRequiresReadinessNotJustASuccessfulShellInvocation() = runTest {
        val s = MemStore(); val l = FakeLauncher(listOf(LaunchOutcome.Launched)); val p = FakeProbe(readyAfterLaunch = false)
        val r = orch(l, p, s, pol = RetryPolicy(maxAttempts = 2, baseDelayMs = 10)).run("boot")
        assertEquals(StartupFailure.SERVICE_NOT_READY, (r as StartupResult.Failure).reason)
        assertEquals(2, l.calls)
        assertEquals(StartupStatus.FAILED, s.rec!!.status)
    }

    @Test fun readyAfterLaunchIsSuccessAndRunsSuccessHook() = runTest {
        val s = MemStore(); var hook = 0
        val r = orch(FakeLauncher(listOf(LaunchOutcome.Launched)), FakeProbe(), s, onSuccess = { hook++ }).run("boot")
        assertEquals(StartupResult.Success(StartMethod.WIRELESS_ADB, 1, false), r)
        assertEquals(1, hook)
        assertEquals(StartupStatus.SUCCEEDED, s.rec!!.status)
    }

    @Test fun alreadyRunningServiceIsNotRelaunched() = runTest {
        val l = FakeLauncher(listOf(LaunchOutcome.Launched))
        val r = orch(l, FakeProbe(alreadyReady = true), MemStore()).run("boot")
        assertTrue((r as StartupResult.Success).alreadyRunning)
        assertEquals(0, l.calls)
    }

    @Test fun nonRetryableFailureStopsImmediatelyWithItsReason() = runTest {
        val l = FakeLauncher(listOf(LaunchOutcome.Failed(StartupFailure.ROOT_DENIED, "su: denied", retryable = false)), StartMethod.ROOT)
        val s = MemStore()
        val r = orch(l, FakeProbe(), s).run("boot") as StartupResult.Failure
        assertEquals(StartupFailure.ROOT_DENIED, r.reason); assertEquals(1, l.calls)
        assertEquals(StartMethod.ROOT, s.rec!!.method)
    }

    @Test fun retriesAreBoundedAndBackOffExponentially() = runTest {
        val l = FakeLauncher(listOf(LaunchOutcome.Failed(StartupFailure.DISCOVERY_TIMEOUT, "", retryable = true)))
        val o = orch(l, FakeProbe(), MemStore(), pol = RetryPolicy(maxAttempts = 4, baseDelayMs = 1000, maxDelayMs = 3000))
        val t0 = currentTime
        val r = o.run("boot") as StartupResult.Failure
        assertEquals(4, l.calls); assertEquals(4, r.attempts)
        assertEquals("1000 + 2000 + 3000 (capped)", 6000L, currentTime - t0)
    }

    @Test fun retryThenSucceed() = runTest {
        val l = FakeLauncher(listOf(LaunchOutcome.Failed(StartupFailure.PORT_UNAVAILABLE, "", true), LaunchOutcome.Launched))
        val r = orch(l, FakeProbe(), MemStore(), pol = RetryPolicy(baseDelayMs = 5)).run("boot")
        assertEquals(2, (r as StartupResult.Success).attempts)
    }

    @Test fun overlappingTriggersNeverRunTwoLaunches() = runTest {
        val l = FakeLauncher(listOf(LaunchOutcome.Launched)); l.gate = CompletableDeferred()
        val o = orch(l, FakeProbe(), MemStore())
        val first = async { o.run("locked_boot") }
        runCurrent()
        assertTrue(o.isRunning)
        val second = o.run("boot")      // BOOT_COMPLETED arrives while LOCKED_BOOT start is mid-flight
        assertTrue(second is StartupResult.Skipped)
        l.gate!!.complete(Unit)
        assertTrue(first.await() is StartupResult.Success)
        assertEquals("exactly one launch", 1, l.calls)
        assertFalse(o.isRunning)
    }

    @Test fun duplicateBootBroadcastAfterSuccessDoesNotRestartAKilledService() = runTest {
        val s = MemStore(); val l = FakeLauncher(listOf(LaunchOutcome.Launched))
        orch(l, FakeProbe(), s).run("locked_boot")
        val again = orch(l, FakeProbe(), s).run("boot")         // new receiver instance, same boot, service gone
        assertTrue(again is StartupResult.Skipped)
        assertEquals(1, l.calls)
        val manual = orch(l, FakeProbe(), s).run(StartupOrchestrator.MANUAL)   // user-initiated retry is honoured
        assertTrue(manual is StartupResult.Success)
        assertEquals(2, l.calls)
    }

    @Test fun newBootStartsAgain() = runTest {
        val s = MemStore(); val l = FakeLauncher(listOf(LaunchOutcome.Launched))
        orch(l, FakeProbe(), s, boot = "boot-1").run("boot")
        assertTrue(orch(l, FakeProbe(), s, boot = "boot-2").run("boot") is StartupResult.Success)
        assertEquals(2, l.calls)
    }

    @Test fun cancellationIsRecordedAsInterruptedAndReleasesTheLock() = runTest {
        val s = MemStore(); val l = FakeLauncher(listOf(LaunchOutcome.Launched)); l.gate = CompletableDeferred()
        val o = orch(l, FakeProbe(), s)
        val job = launch { o.run("boot") }
        runCurrent(); job.cancelAndJoin()
        assertEquals(StartupStatus.INTERRUPTED, s.rec!!.status)
        assertFalse("lock released so a later start can run", o.isRunning)
        l.gate = null
        assertTrue(o.run(StartupOrchestrator.MANUAL) is StartupResult.Success)
    }

    @Test fun missingLauncherReportsNotConfigured() = runTest {
        val r = orch(null, FakeProbe(), MemStore()).run("boot") as StartupResult.Failure
        assertEquals(StartupFailure.NOT_CONFIGURED, r.reason)
    }

    @Test fun launcherExceptionBecomesStarterFailedAndSecretsAreRedactedInTheRecord() = runTest {
        val bad = object : StartupLauncher {
            override val method = StartMethod.ROOT
            override suspend fun launch(attempt: Int): LaunchOutcome = throw IllegalStateException("token=abc")
        }
        val s = MemStore()
        val r = orch(bad, FakeProbe(), s).run("boot") as StartupResult.Failure
        assertEquals(StartupFailure.STARTER_FAILED, r.reason)
        assertFalse(s.rec!!.detail.contains("abc"))
    }

    @Test fun everyFailureReasonHasAnActionableRecovery() {
        StartupFailure.values().forEach { assertTrue(it.name, it.recovery.length > 20) }
    }
}
