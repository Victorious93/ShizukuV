package ai.victorious.bridge.core

import java.util.concurrent.atomic.AtomicBoolean

enum class BinderWaitResult { RECEIVED, TIMED_OUT, DIED, CANCELLED }

/** Schedules a one-shot task and returns a handle that cancels it. Android code wraps a Handler; tests use a fake. */
fun interface DelayScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

/**
 * Lifecycle-safe "wait for the privileged service Binder" state machine, replacing the old
 * `CountDownLatch.await()` on the main thread (which also ignored the timeout result).
 *
 * It never blocks. It reaches exactly one terminal state, no matter how events interleave
 * (arrival racing the timeout, death during the wait, the Activity being destroyed). On that
 * terminal transition [cleanup] runs exactly once, so listeners are always unregistered and the
 * timeout is always cancelled. Callers fail closed on every result except [BinderWaitResult.RECEIVED].
 */
class BinderWait(
    private val timeoutMs: Long,
    private val scheduler: DelayScheduler,
    private val cleanup: () -> Unit,
    private val onResult: (BinderWaitResult) -> Unit,
) {
    private val done = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    @Volatile
    private var cancelTimeout: (() -> Unit)? = null

    /** Registers the timeout. Call after registering listeners; a sticky listener may already have fired [binderReceived]. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (done.get()) return
        cancelTimeout = scheduler.schedule(timeoutMs) { finish(BinderWaitResult.TIMED_OUT) }
        if (done.get()) cancelTimeout?.invoke()
    }

    fun binderReceived() = finish(BinderWaitResult.RECEIVED)
    fun binderDied() = finish(BinderWaitResult.DIED)
    fun cancel() = finish(BinderWaitResult.CANCELLED)
    val isFinished: Boolean get() = done.get()

    private fun finish(r: BinderWaitResult) {
        if (!done.compareAndSet(false, true)) return
        cancelTimeout?.invoke()
        runCatching(cleanup)
        onResult(r)
    }
}
