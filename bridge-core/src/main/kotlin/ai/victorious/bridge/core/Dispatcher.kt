package ai.victorious.bridge.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class OperationState { QUEUED, WAITING_APPROVAL, RUNNING }

data class ActiveOperation(
    val uid: Int,
    val packageName: String?,
    val requestId: String,
    val operation: String,
    val capability: Capability,
    val state: OperationState,
    val startedWallMs: Long,
)

class DispatcherLimits(
    val maxQueuedPerUid: Int = 16,
    val maxTotalActive: Int = 64,
    val approvalTimeoutMs: Long = 30_000,
)

/**
 * Executes controlled operations for already-attached callers.
 *
 * Order of checks for every request (all repeated at *execution* time, after any queueing):
 *  1. operation exists and is supported;
 *  2. grant is present, unexpired, identity-verified and includes the operation's capability;
 *  3. backend policy: the actual execution mode must satisfy both the service policy and the request's
 *     requirement. A mismatch fails; the dispatcher has no code path that selects another backend;
 *  4. per-operation approval, when the grant is PER_OPERATION;
 *  5. handler runs with a [CancelToken] that revocation, expiry and explicit cancel all trip.
 */
class BridgeDispatcher(
    private val grants: GrantStore,
    private val resolver: IdentityResolver,
    private val log: ActivityLog,
    private val execMode: ExecMode,
    private val servicePolicy: () -> BackendRequirement,
    private val handlers: OperationHandlers,
    private val approvals: ApprovalGate,
    private val executor: ExecutorService,
    private val scheduler: ScheduledExecutorService,
    private val clock: MonotonicClock,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val limits: DispatcherLimits = DispatcherLimits(),
) {
    private class Active(
        val uid: Int,
        val pkg: String?,
        val request: BridgeRequest,
        val op: Operation,
        val token: CancelToken,
        val startedWall: Long,
        @Volatile var state: OperationState,
    ) {
        val finished = AtomicBoolean(false)
    }

    private val active = ConcurrentHashMap<String, Active>()
    private fun key(uid: Int, id: String) = "$uid:$id"
    private val idPattern = Regex("[A-Za-z0-9._:-]{1,64}")

    /**
     * Accepts a request. [callback] is invoked exactly once (on a worker thread) with the result.
     * Early rejections also go through [callback], never by exception, so callers handle one path.
     */
    fun submit(uid: Int, request: BridgeRequest, callback: (BridgeResult) -> Unit) {
        val started = clock.elapsedMs()
        fun early(code: ErrorCode, msg: String) {
            val r = BridgeResult(request.requestId, ResultStatus.ERROR, code, msg, emptyMap(), 0, execMode)
            record(uid, null, null, request, r, 0)
            callback(r)
        }
        if (!idPattern.matches(request.requestId)) return early(ErrorCode.INVALID_ARGUMENT, "bad request id")
        val op = Operation.fromId(request.operation) ?: return early(ErrorCode.UNSUPPORTED_OPERATION, "unknown operation")
        val pkg = resolver.resolve(uid)?.packages?.sorted()?.firstOrNull()
        if (active.size >= limits.maxTotalActive || active.keys.count { it.startsWith("$uid:") } >= limits.maxQueuedPerUid) {
            return early(ErrorCode.OVERLOADED, "too many active requests")
        }
        val a = Active(uid, pkg, request, op, CancelToken(), wallClock(), OperationState.QUEUED)
        if (active.putIfAbsent(key(uid, request.requestId), a) != null) {
            return early(ErrorCode.INVALID_ARGUMENT, "duplicate request id")
        }
        try {
            executor.execute {
                val result = run(a, started)
                finish(a, result, callback)
            }
        } catch (e: RejectedExecutionException) {
            active.remove(key(uid, request.requestId))
            early(ErrorCode.OVERLOADED, "service busy")
        }
    }

    private fun finish(a: Active, result: BridgeResult, callback: (BridgeResult) -> Unit) {
        if (!a.finished.compareAndSet(false, true)) return
        active.remove(key(a.uid, a.request.requestId))
        record(a.uid, a.pkg, a.op, a.request, result, result.durationMs)
        runCatching { callback(result) }
    }

    private fun run(a: Active, startedElapsed: Long): BridgeResult {
        val req = a.request
        fun elapsed() = clock.elapsedMs() - startedElapsed
        fun err(code: ErrorCode, msg: String, status: ResultStatus = ResultStatus.ERROR) =
            BridgeResult(req.requestId, status, code, msg, emptyMap(), elapsed(), execMode)

        if (a.token.isCancelled) return cancelled(a, ::elapsed)

        // Backend policy first: it does not depend on who is asking and must never be bypassed by an approval.
        val policy = servicePolicy()
        if (!policy.accepts(execMode)) return err(ErrorCode.BACKEND_POLICY, "service policy requires ${policy.id}, running as ${execMode.id}")
        if (!req.backend.accepts(execMode)) return err(ErrorCode.BACKEND_POLICY, "request requires ${req.backend.id}, running as ${execMode.id}")

        // Authorization is evaluated *now*, after any time spent queued.
        var decision = grants.check(a.uid, a.op.capability)
        var grant: Grant = when (decision) {
            is Decision.Allowed -> decision.grant
            is Decision.NeedsApproval -> decision.grant
            is Decision.Denied -> return err(denyCode(decision.reason), "not authorized: ${decision.reason}")
        }
        if (decision is Decision.NeedsApproval) {
            a.state = OperationState.WAITING_APPROVAL
            val outcome = approvals.requestApproval(
                a.uid, a.pkg, a.op, summaryOf(a.op, req.args), req.requestId, limits.approvalTimeoutMs, a.token,
            )
            when (outcome) {
                ApprovalOutcome.APPROVED -> Unit
                ApprovalOutcome.DENIED -> return err(ErrorCode.APPROVAL_DENIED, "owner declined this operation")
                ApprovalOutcome.TIMEOUT -> return err(ErrorCode.APPROVAL_TIMEOUT, "no answer from owner")
                ApprovalOutcome.CANCELLED -> return cancelled(a, ::elapsed)
            }
            // Re-validate: the grant may have been revoked while the dialog was open.
            decision = grants.check(a.uid, a.op.capability)
            grant = when (decision) {
                is Decision.Allowed -> decision.grant
                is Decision.NeedsApproval -> decision.grant
                is Decision.Denied -> return err(denyCode(decision.reason), "not authorized: ${decision.reason}")
            }
        }
        if (a.token.isCancelled) return cancelled(a, ::elapsed)

        a.state = OperationState.RUNNING
        // A timed grant that expires mid-operation stops the operation.
        val remaining = grants.remainingMs(grant)
        val watchdog = remaining?.let {
            scheduler.schedule({ a.token.cancel(ErrorCode.EXPIRED) }, it, TimeUnit.MILLISECONDS)
        }
        try {
            val data = handlers.execute(ExecContext(a.uid, grant, a.token, execMode), a.op, Args(req.args))
            // Never hand data to a caller whose access ended while the work ran.
            if (a.token.isCancelled) return cancelled(a, ::elapsed)
            return BridgeResult(req.requestId, ResultStatus.OK, null, null, data, elapsed(), execMode)
        } catch (e: BridgeException) {
            return if (a.token.isCancelled) cancelled(a, ::elapsed) else err(e.code, e.message ?: e.code.name)
        } catch (e: Throwable) {
            return if (a.token.isCancelled) cancelled(a, ::elapsed) else err(ErrorCode.INTERNAL, e.javaClass.simpleName)
        } finally {
            watchdog?.cancel(false)
        }
    }

    private fun cancelled(a: Active, elapsed: () -> Long): BridgeResult {
        val why = a.token.reason
        return BridgeResult(a.request.requestId, ResultStatus.CANCELLED, why, "operation ended: $why", emptyMap(), elapsed(), execMode)
    }

    private fun denyCode(r: DenyReason): ErrorCode = when (r) {
        DenyReason.EXPIRED -> ErrorCode.EXPIRED
        DenyReason.IDENTITY_MISMATCH, DenyReason.IDENTITY_UNRESOLVED -> ErrorCode.IDENTITY_MISMATCH
        else -> ErrorCode.DENIED
    }

    /** Cancels one of the caller's own requests. Returns false if it is unknown or already finished. */
    fun cancel(uid: Int, requestId: String): Boolean {
        val a = active[key(uid, requestId)] ?: return false
        a.token.cancel(ErrorCode.CANCELLED)
        return true
    }

    /** Called when a grant ends: cancels everything queued or running for that uid. */
    fun cancelAllFor(uid: Int, reason: ErrorCode) {
        active.values.filter { it.uid == uid }.forEach { it.token.cancel(reason) }
    }

    fun cancelEverything(reason: ErrorCode) {
        active.values.forEach { it.token.cancel(reason) }
    }

    fun activeOperations(): List<ActiveOperation> = active.values.map {
        ActiveOperation(it.uid, it.pkg, it.request.requestId, it.op.id, it.op.capability, it.state, it.startedWall)
    }.sortedBy { it.startedWallMs }

    /** Manager-initiated cancel of any operation by id (the user pressing "Cancel" in the dashboard). */
    fun cancelByManager(uid: Int, requestId: String): Boolean = cancel(uid, requestId)

    private fun summaryOf(op: Operation, args: Map<String, Any?>): String = when (op) {
        Operation.SHELL_EXEC -> Redactor.summarizeShell(args["command"] as? String ?: "")
        Operation.FILES_READ, Operation.FILES_WRITE, Operation.FILES_LIST, Operation.FILES_STAT,
        Operation.FILES_MKDIR, Operation.FILES_DELETE -> "${op.summary}: ${(args["path"] as? String).orEmpty().take(160)}"
        Operation.SETTINGS_GET, Operation.SETTINGS_PUT ->
            "${op.summary}: ${(args["namespace"] as? String).orEmpty()}/${(args["key"] as? String).orEmpty()}".take(160)
        Operation.PACKAGES_INFO, Operation.PACKAGES_SET_ENABLED, Operation.PACKAGES_FORCE_STOP ->
            "${op.summary}: ${(args["package"] as? String).orEmpty().take(160)}"
        else -> op.summary
    }

    private fun record(uid: Int, pkg: String?, op: Operation?, req: BridgeRequest, r: BridgeResult, dur: Long) {
        // Chunked file transfers would flood the log; record the first chunk and every failure.
        val chunkNoise = r.ok && (op == Operation.FILES_READ || op == Operation.FILES_WRITE) &&
            ((req.args["offset"] as? Number)?.toLong() ?: 0L) != 0L
        if (chunkNoise) return
        log.append(
            ActivityEvent(
                wallTimeMs = wallClock(), type = EventType.OPERATION, callerUid = uid, callerPackage = pkg,
                capability = op?.capability?.id, requestId = req.requestId, remoteTaskId = req.remoteTaskId?.take(64),
                durationMs = dur,
                outcome = when (r.status) { ResultStatus.OK -> Outcome.OK; ResultStatus.CANCELLED -> Outcome.CANCELLED; else -> Outcome.ERROR },
                errorCategory = r.errorCode?.name,
                detail = (op?.let { summaryOf(it, req.args) } ?: req.operation.take(64)),
            ),
        )
    }
}
