package ai.victorious.bridge.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DispatcherTest {
    @get:Rule val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val pm = FakeResolver().apply { install(10123, "com.example.app"); install(10124, "com.example.other") }
    private val log = ActivityLog()
    private lateinit var dispatcher: BridgeDispatcher
    private lateinit var grants: GrantStore
    private val pool = Executors.newFixedThreadPool(4)
    private val sched = Executors.newSingleThreadScheduledExecutor()

    /** Programmable system-command fake; records every argv it is asked to run. */
    class RecordingCommands : CommandExecutor {
        val calls = ArrayList<List<String>>()
        @Volatile var block: CountDownLatch? = null
        @Volatile var started: CountDownLatch? = null
        override fun run(argv: List<String>, timeoutMs: Long, cancel: CancelToken): ProcessOutcome {
            synchronized(calls) { calls += argv }
            started?.countDown()
            block?.let { b ->
                val unhook = cancel.onCancel { b.countDown() }
                b.await(10, TimeUnit.SECONDS); unhook()
            }
            if (cancel.isCancelled) return ProcessOutcome(null, "", "", false, false, false, true, null)
            return ProcessOutcome(0, "package:com.a\npackage:com.b\n", "", false, false, false, false, null)
        }
    }

    private val commands = RecordingCommands()
    private var approval = ApprovalOutcome.APPROVED
    private var approvalCalls = 0
    private var policy = BackendRequirement.ANY
    private var mode = ExecMode.SHELL

    @Before fun setUp() {
        grants = GrantStore(clock, pm, onGrantEnded = { uid, why ->
            dispatcher.cancelAllFor(uid, if (why == GrantEndReason.EXPIRED) ErrorCode.EXPIRED else ErrorCode.REVOKED)
        })
        build()
    }

    private fun build(handlers: OperationHandlers? = null) {
        dispatcher = BridgeDispatcher(
            grants, pm, log, mode, { policy },
            handlers ?: DefaultHandlers(SystemInfo { mapOf("sdk" to 34) }, commands,
                HandlerConfig(shellPath = "/bin/sh"), shellCommands = ProcessCommands()),
            ApprovalGate { _, _, _, _, _, _, _ -> approvalCalls++; approval },
            pool, sched, clock,
        )
    }

    class ProcessCommands : CommandExecutor {
        private val runner = ProcessRunner(killGroup = false)
        override fun run(argv: List<String>, timeoutMs: Long, cancel: CancelToken) = runner.run(argv, timeoutMs, cancel)
    }

    @After fun tearDown() { pool.shutdownNow(); sched.shutdownNow() }

    private fun call(uid: Int, op: String, args: Map<String, Any?> = emptyMap(), id: String = "r-${System.nanoTime()}",
                     backend: BackendRequirement = BackendRequirement.ANY): BridgeResult {
        val ref = AtomicReference<BridgeResult>()
        val latch = CountDownLatch(1)
        dispatcher.submit(uid, BridgeRequest(id, op, args, backend)) { ref.set(it); latch.countDown() }
        assertTrue("callback must fire", latch.await(10, TimeUnit.SECONDS))
        return ref.get()
    }

    private fun callAsync(uid: Int, op: String, args: Map<String, Any?> = emptyMap(), id: String): Pair<AtomicReference<BridgeResult>, CountDownLatch> {
        val ref = AtomicReference<BridgeResult>(); val latch = CountDownLatch(1)
        dispatcher.submit(uid, BridgeRequest(id, op, args)) { ref.set(it); latch.countDown() }
        return ref to latch
    }

    @Test fun unauthorizedCallerIsDeniedAndNothingRuns() {
        val r = call(10123, "packages.list")
        assertEquals(ErrorCode.DENIED, r.errorCode)
        assertTrue(commands.calls.isEmpty())
    }

    @Test fun grantedOperationRunsAndReturnsStructuredResult() {
        grants.grant(10123, setOf(Capability.PACKAGES_READ), GrantMode.SESSION)
        val r = call(10123, "packages.list", id = "req-1")
        assertTrue(r.ok); assertEquals("req-1", r.requestId); assertEquals(ExecMode.SHELL, r.execMode)
        assertEquals(listOf("com.a", "com.b"), r.data["packages"])
        assertEquals(listOf("/system/bin/pm", "list", "packages", "--user", "0"), commands.calls.single())
    }

    @Test fun operationNeedsItsOwnCapability_noImplicitEscalation() {
        grants.grant(10123, setOf(Capability.PACKAGES_READ, Capability.SETTINGS_READ, Capability.FILES_READ), GrantMode.SESSION)
        assertEquals(ErrorCode.DENIED, call(10123, "packages.forceStop", mapOf("package" to "com.x.y")).errorCode)
        assertEquals(ErrorCode.DENIED, call(10123, "settings.put", mapOf("namespace" to "system", "key" to "screen_brightness", "value" to "10")).errorCode)
        assertEquals(ErrorCode.DENIED, call(10123, "shell.exec", mapOf("command" to "id")).errorCode)
        assertEquals(ErrorCode.DENIED, call(10123, "files.write", mapOf("path" to "/tmp/x", "data" to ByteArray(1))).errorCode)
        assertTrue(commands.calls.isEmpty())
    }

    @Test fun unknownOperationsAreUnsupportedNotForwarded() {
        grants.grant(10123, Capability.scoped.toSet(), GrantMode.SESSION)
        assertEquals(ErrorCode.UNSUPPORTED_OPERATION, call(10123, "binder.transact").errorCode)
        assertEquals(ErrorCode.UNSUPPORTED_OPERATION, call(10123, "userservice.bind").errorCode)
        assertEquals(ErrorCode.UNSUPPORTED_OPERATION, call(10123, "packages.uninstall", mapOf("package" to "com.x.y")).errorCode)
    }

    @Test fun backendPolicyMismatchFailsAndNeverEscalates() {
        grants.grant(10123, setOf(Capability.PACKAGES_READ), GrantMode.SESSION)
        mode = ExecMode.SHELL; build()
        val r = call(10123, "packages.list", backend = BackendRequirement.ROOT_ONLY)
        assertEquals(ErrorCode.BACKEND_POLICY, r.errorCode)
        assertTrue("must not have run on any backend", commands.calls.isEmpty())
        policy = BackendRequirement.ROOT_ONLY
        assertEquals(ErrorCode.BACKEND_POLICY, call(10123, "packages.list").errorCode)
        policy = BackendRequirement.ANY; mode = ExecMode.ROOT; build()
        assertEquals("adb-only requirement is not satisfied by a root service", ErrorCode.BACKEND_POLICY,
            call(10123, "packages.list", backend = BackendRequirement.SHELL_ONLY).errorCode)
        assertTrue(commands.calls.isEmpty())
    }

    @Test fun revokeWhileRunningCancelsOperationAndDropsItsResult() {
        grants.grant(10123, setOf(Capability.PACKAGES_READ), GrantMode.SESSION)
        commands.block = CountDownLatch(1); commands.started = CountDownLatch(1)
        val (ref, latch) = callAsync(10123, "packages.list", id = "long-1")
        assertTrue(commands.started!!.await(5, TimeUnit.SECONDS))
        assertEquals(OperationState.RUNNING, dispatcher.activeOperations().single().state)
        grants.revoke(10123)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(ResultStatus.CANCELLED, ref.get().status)
        assertEquals(ErrorCode.REVOKED, ref.get().errorCode)
        assertTrue("no data may be handed to a revoked caller", ref.get().data.isEmpty())
        assertTrue(dispatcher.activeOperations().isEmpty())
    }

    @Test fun revokeWhileQueuedPreventsExecution() {
        // One worker, occupied by a blocked operation: the second request is queued behind it.
        pool.shutdownNow(); val one = Executors.newSingleThreadExecutor()
        dispatcher = BridgeDispatcher(grants, pm, log, mode, { policy },
            DefaultHandlers(SystemInfo { emptyMap() }, commands), ApprovalGate { _, _, _, _, _, _, _ -> ApprovalOutcome.APPROVED },
            one, sched, clock)
        grants.grant(10123, setOf(Capability.PACKAGES_READ), GrantMode.SESSION)
        grants.grant(10124, setOf(Capability.PACKAGES_READ), GrantMode.SESSION)
        commands.block = CountDownLatch(1); commands.started = CountDownLatch(1)
        val first = callAsync(10124, "packages.list", id = "first")
        assertTrue(commands.started!!.await(5, TimeUnit.SECONDS))
        commands.block = null
        val queued = callAsync(10123, "packages.list", id = "queued")
        assertEquals(OperationState.QUEUED, dispatcher.activeOperations().first { it.requestId == "queued" }.state)
        grants.revoke(10123)
        grants.revoke(10124)      // releases the blocking command via cancel hook
        assertTrue(queued.second.await(5, TimeUnit.SECONDS))
        assertEquals(ResultStatus.CANCELLED, queued.first.get().status)
        assertEquals("queued work for the revoked caller never executed", 1, commands.calls.size)
        one.shutdownNow()
    }

    @Test fun timedGrantExpiryAfterQueueingIsEnforcedAtExecution() {
        grants.grant(10123, setOf(Capability.PACKAGES_READ), GrantMode.TIMED_15_MIN)
        clock.advance(GrantStore.TIMED_DURATION_MS)
        assertEquals(ErrorCode.EXPIRED, call(10123, "packages.list").errorCode)
        assertTrue(commands.calls.isEmpty())
    }

    @Test fun cancelByCallerStopsOwnOperationButNotOthers() {
        grants.grant(10123, setOf(Capability.PACKAGES_READ), GrantMode.SESSION)
        commands.block = CountDownLatch(1); commands.started = CountDownLatch(1)
        val (ref, latch) = callAsync(10123, "packages.list", id = "mine")
        assertTrue(commands.started!!.await(5, TimeUnit.SECONDS))
        assertFalse("another uid cannot cancel it", dispatcher.cancel(10124, "mine"))
        assertTrue(dispatcher.cancel(10123, "mine"))
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(ErrorCode.CANCELLED, ref.get().errorCode)
    }

    @Test fun perOperationGrantAsksEveryTimeAndHonoursDecline() {
        grants.grant(10123, setOf(Capability.PACKAGES_READ), GrantMode.PER_OPERATION)
        assertTrue(call(10123, "packages.list").ok)
        assertTrue(call(10123, "packages.list").ok)
        assertEquals("one prompt per operation, none reused", 2, approvalCalls)
        approval = ApprovalOutcome.DENIED
        assertEquals(ErrorCode.APPROVAL_DENIED, call(10123, "packages.list").errorCode)
        approval = ApprovalOutcome.TIMEOUT
        assertEquals(ErrorCode.APPROVAL_TIMEOUT, call(10123, "packages.list").errorCode)
        assertEquals("declined/timed-out operations must not run", 2, commands.calls.size)
    }

    @Test fun perOperationApprovalRevalidatesGrantAfterDialog() {
        grants.grant(10123, setOf(Capability.PACKAGES_READ), GrantMode.PER_OPERATION)
        dispatcher = BridgeDispatcher(grants, pm, log, mode, { policy }, DefaultHandlers(SystemInfo { emptyMap() }, commands),
            ApprovalGate { uid, _, _, _, _, _, _ -> grants.revoke(uid); ApprovalOutcome.APPROVED }, pool, sched, clock)
        val r = call(10123, "packages.list")
        assertNotEquals(ResultStatus.OK, r.status)
        assertTrue(commands.calls.isEmpty())
    }

    @Test fun sessionGrantDoesNotAskForApproval() {
        grants.grant(10123, setOf(Capability.PACKAGES_READ), GrantMode.SESSION)
        assertTrue(call(10123, "packages.list").ok)
        assertEquals(0, approvalCalls)
    }

    @Test fun duplicateAndMalformedRequestIdsAreRejected() {
        grants.grant(10123, setOf(Capability.PACKAGES_READ), GrantMode.SESSION)
        commands.block = CountDownLatch(1); commands.started = CountDownLatch(1)
        val a = callAsync(10123, "packages.list", id = "dup")
        assertTrue(commands.started!!.await(5, TimeUnit.SECONDS))
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(10123, "packages.list", id = "dup").errorCode)
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(10123, "packages.list", id = "bad id!").errorCode)
        commands.block!!.countDown(); a.second.await(5, TimeUnit.SECONDS)
    }

    @Test fun backpressureRejectsFloodsWithOverloaded() {
        grants.grant(10123, setOf(Capability.PACKAGES_READ), GrantMode.SESSION)
        commands.block = CountDownLatch(1)
        val results = (1..40).map { i -> callAsync(10123, "packages.list", id = "f$i") }
        val overloaded = results.count { it.second.await(0, TimeUnit.MILLISECONDS) && it.first.get().errorCode == ErrorCode.OVERLOADED }
        assertTrue("per-uid queue limit must engage", overloaded > 0)
        commands.block!!.countDown()
    }

    @Test fun remoteTaskIdIsLoggedButNeverAuthorizes() {
        // Same task id, no grant: still denied. With a grant: logged for correlation.
        val denied = AtomicReference<BridgeResult>(); val l1 = CountDownLatch(1)
        dispatcher.submit(10123, BridgeRequest("t1", "packages.list", remoteTaskId = "dca-task-77")) { denied.set(it); l1.countDown() }
        l1.await(5, TimeUnit.SECONDS)
        assertEquals(ErrorCode.DENIED, denied.get().errorCode)
        grants.grant(10123, setOf(Capability.PACKAGES_READ), GrantMode.SESSION)
        val ok = AtomicReference<BridgeResult>(); val l2 = CountDownLatch(1)
        dispatcher.submit(10123, BridgeRequest("t2", "packages.list", remoteTaskId = "dca-task-77")) { ok.set(it); l2.countDown() }
        l2.await(5, TimeUnit.SECONDS)
        assertTrue(ok.get().ok)
        assertTrue(log.snapshot().any { it.requestId == "t2" && it.remoteTaskId == "dca-task-77" && it.outcome == Outcome.OK })
    }

    @Test fun activityLogRecordsOutcomeCategoryAndDuration_withoutArguments() {
        grants.grant(10123, setOf(Capability.SHELL_EXEC), GrantMode.SESSION)
        val r = call(10123, "shell.exec", mapOf("command" to "echo hunter2-password=abc", "timeout_ms" to 5000L), id = "sh-1")
        assertTrue(r.ok)
        assertEquals("hunter2-password=abc\n", r.data["stdout"])
        val ev = log.snapshot().single { it.requestId == "sh-1" }
        assertEquals("shell.exec", ev.capability)
        assertFalse("command arguments must never be logged", ev.detail.contains("hunter2"))
        assertFalse(ev.detail.contains("abc"))
        assertEquals(Outcome.OK, ev.outcome)
        val denied = call(10124, "shell.exec", mapOf("command" to "id"), id = "sh-2")
        assertEquals("DENIED", log.snapshot().single { it.requestId == "sh-2" }.errorCategory)
        assertEquals(ErrorCode.DENIED, denied.errorCode)
    }

    // ---- scoped-app bypass attempts at the handler layer ------------------------------------

    @Test fun fileCapabilityWithoutApprovedRootsReachesNothing() {
        val f = tmp.newFile("x.txt")
        grants.grant(10123, setOf(Capability.FILES_READ), GrantMode.SESSION)          // no roots
        assertEquals(ErrorCode.PATH_OUT_OF_SCOPE, call(10123, "files.read", mapOf("path" to f.path)).errorCode)
    }

    @Test fun fileTraversalAndSymlinkEscapesAreBlockedEndToEnd() {
        val root = tmp.newFolder("root").canonicalFile; val outside = tmp.newFolder("outside").canonicalFile
        File(outside, "secret").writeText("S3CR3T")
        java.nio.file.Files.createSymbolicLink(File(root, "esc").toPath(), File(outside, "secret").toPath())
        grants.grant(10123, setOf(Capability.FILES_READ, Capability.FILES_WRITE), GrantMode.SESSION, listOf(root.path))
        assertEquals(ErrorCode.PATH_OUT_OF_SCOPE, call(10123, "files.read", mapOf("path" to "${root.path}/../outside/secret")).errorCode)
        assertEquals(ErrorCode.PATH_OUT_OF_SCOPE, call(10123, "files.read", mapOf("path" to "${root.path}/esc")).errorCode)
        assertEquals(ErrorCode.PATH_OUT_OF_SCOPE, call(10123, "files.write", mapOf("path" to "${root.path}/esc", "data" to "x".toByteArray())).errorCode)
        assertEquals("S3CR3T", File(outside, "secret").readText())
    }

    @Test fun boundedFileRoundTripWithChunkingAndLimits() {
        val root = tmp.newFolder("rt").canonicalFile
        grants.grant(10123, setOf(Capability.FILES_READ, Capability.FILES_WRITE), GrantMode.SESSION, listOf(root.path))
        val p = "${root.path}/big.bin"
        val payload = ByteArray(2_500_000) { (it % 251).toByte() }
        var off = 0
        while (off < payload.size) {
            val n = minOf(1_000_000, payload.size - off)
            val r = call(10123, "files.write", mapOf("path" to p, "offset" to off.toLong(), "truncate" to (off == 0), "data" to payload.copyOfRange(off, off + n)))
            assertTrue(r.errorCode.toString(), r.ok); off += n
        }
        val out = java.io.ByteArrayOutputStream(); var pos = 0L
        while (true) {
            val r = call(10123, "files.read", mapOf("path" to p, "offset" to pos, "length" to 1_000_000L))
            assertTrue(r.ok); val d = r.data["data"] as ByteArray; out.write(d); pos += d.size
            if (r.data["eof"] == true) break
        }
        assertArrayEquals(payload, out.toByteArray())
        assertEquals(ErrorCode.TOO_LARGE, call(10123, "files.write", mapOf("path" to p, "data" to ByteArray(1_048_577))).errorCode)
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(10123, "files.read", mapOf("path" to p, "length" to 99_999_999L)).errorCode)
    }

    @Test fun deleteRefusesRootsAndNonEmptyDirectories() {
        val root = tmp.newFolder("d").canonicalFile; File(root, "sub").mkdir(); File(root, "sub/f").writeText("x")
        grants.grant(10123, setOf(Capability.FILES_WRITE), GrantMode.SESSION, listOf(root.path))
        assertEquals(ErrorCode.PATH_OUT_OF_SCOPE, call(10123, "files.delete", mapOf("path" to root.path)).errorCode)
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(10123, "files.delete", mapOf("path" to "${root.path}/sub")).errorCode)
        assertTrue(call(10123, "files.delete", mapOf("path" to "${root.path}/sub/f")).ok)
    }

    @Test fun settingsOutsideAllowListAreUnsupportedAndInjectionIsRejected() {
        grants.grant(10123, setOf(Capability.SETTINGS_READ, Capability.SETTINGS_WRITE), GrantMode.SESSION)
        val bad = listOf(
            mapOf("namespace" to "global", "key" to "adb_enabled", "value" to "0"),                  // read-only key
            mapOf("namespace" to "secure", "key" to "lockscreen.password_type", "value" to "0"),     // not listed
            mapOf("namespace" to "system", "key" to "screen_brightness", "value" to "10; reboot"),   // injection
            mapOf("namespace" to "system", "key" to "screen_brightness", "value" to "999"),
            mapOf("namespace" to "system", "key" to "screen_brightness\nx", "value" to "1"),
        )
        bad.forEach { assertNotEquals(it.toString(), ResultStatus.OK, call(10123, "settings.put", it).status) }
        assertTrue("nothing reached the system", commands.calls.isEmpty())
        assertTrue(call(10123, "settings.put", mapOf("namespace" to "system", "key" to "screen_brightness", "value" to "120")).ok)
        assertEquals(listOf("/system/bin/settings", "put", "system", "screen_brightness", "120"), commands.calls.single())
    }

    @Test fun protectedAndMalformedPackagesAreRefused() {
        grants.grant(10123, setOf(Capability.PACKAGES_MANAGE), GrantMode.SESSION)
        assertEquals(ErrorCode.DENIED, call(10123, "packages.setEnabled", mapOf("package" to "com.android.systemui", "enabled" to false)).errorCode)
        assertEquals(ErrorCode.DENIED, call(10123, "packages.forceStop", mapOf("package" to BridgeIdentity.APPLICATION_ID)).errorCode)
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(10123, "packages.forceStop", mapOf("package" to "x; rm -rf /")).errorCode)
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(10123, "packages.forceStop", mapOf("package" to "-a.b")).errorCode)
        assertTrue(commands.calls.isEmpty())
        assertTrue(call(10123, "packages.setEnabled", mapOf("package" to "com.example.victim", "enabled" to false, "user" to 0L)).ok)
        assertEquals(listOf("/system/bin/pm", "disable-user", "--user", "0", "com.example.victim"), commands.calls.single())
    }

    @Test fun shellCancellationKillsTheProcess() {
        grants.grant(10123, setOf(Capability.SHELL_EXEC), GrantMode.SESSION)
        val (ref, latch) = callAsync(10123, "shell.exec", mapOf("command" to "sleep 30", "timeout_ms" to 60000L), id = "sleepy")
        Thread.sleep(500)
        val t0 = System.nanoTime()
        grants.revoke(10123)
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertEquals(ErrorCode.REVOKED, ref.get().errorCode)
        assertTrue("process must die promptly, not run 30 s", (System.nanoTime() - t0) < 8_000_000_000L)
    }

    @Test fun shellTimeoutAndOutputCapAreEnforced() {
        grants.grant(10123, setOf(Capability.SHELL_EXEC), GrantMode.SESSION)
        assertEquals(ErrorCode.TIMEOUT, call(10123, "shell.exec", mapOf("command" to "sleep 5", "timeout_ms" to 300L)).errorCode)
        val big = call(10123, "shell.exec", mapOf("command" to "head -c 600000 /dev/zero | tr '\\0' a", "timeout_ms" to 20000L))
        assertTrue(big.ok); assertEquals(true, big.data["stdout_truncated"])
        assertEquals(256 * 1024, (big.data["stdout"] as String).length)
    }

    @Test fun diagnosticsIncludeExecModeAndApiVersion() {
        grants.grant(10123, setOf(Capability.DIAGNOSTICS_READ), GrantMode.SESSION)
        val r = call(10123, "diagnostics.read")
        assertEquals("adb", r.data["exec_mode"]); assertEquals(BridgeIdentity.BRIDGE_API_VERSION, r.data["bridge_api_version"])
    }

    @Test fun listingReportsTypeSizeMtimeAndNeverFollowsSymlinks() {
        val root = tmp.newFolder("ls").canonicalFile
        File(root, "a.txt").writeText("hello"); File(root, "dir").mkdir()
        File(root, "out").let { java.nio.file.Files.createSymbolicLink(it.toPath(), tmp.newFolder("elsewhere").toPath()) }
        grants.grant(10123, setOf(Capability.FILES_READ), GrantMode.SESSION, listOf(root.path))
        val r = call(10123, "files.list", mapOf("path" to root.path))
        assertTrue(r.ok)
        @Suppress("UNCHECKED_CAST") val entries = (r.data["entries"] as List<String>).map { it.split('\t', limit = 4) }
        assertEquals(listOf("f", "5"), entries.first { it[3] == "a.txt" }.take(2))
        assertEquals("d", entries.first { it[3] == "dir" }[0])
        val link = entries.first { it[3] == "out" }
        assertEquals(listOf("l", "0", "0"), link.take(3))
        assertTrue(entries.first { it[3] == "a.txt" }[2].toLong() > 0)
    }
}
