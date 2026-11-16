package ai.victorious.bridge.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Cooperative cancellation shared between the dispatcher, handlers and the process runner. */
class CancelToken {
    private val cancelled = AtomicBoolean(false)
    private val hooks = ArrayList<() -> Unit>()

    @Volatile
    var reason: ErrorCode = ErrorCode.CANCELLED
        private set

    val isCancelled: Boolean get() = cancelled.get()

    fun cancel(reason: ErrorCode = ErrorCode.CANCELLED) {
        if (!cancelled.compareAndSet(false, true)) return
        this.reason = reason
        val toRun = synchronized(hooks) { hooks.toList().also { hooks.clear() } }
        toRun.forEach { runCatching(it) }
    }

    /** Runs [hook] immediately if already cancelled, otherwise when cancelled. Returns a handle that removes it. */
    fun onCancel(hook: () -> Unit): () -> Unit {
        if (isCancelled) {
            runCatching(hook)
            return {}
        }
        synchronized(hooks) { hooks.add(hook) }
        if (isCancelled) {
            val toRun = synchronized(hooks) { hooks.toList().also { hooks.clear() } }
            toRun.forEach { runCatching(it) }
        }
        return { synchronized(hooks) { hooks.remove(hook) } }
    }
}

class ProcessOutcome(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val startFailure: String?,
)

/**
 * Runs a command without a shell (argv form), with a deadline, a hard output cap and cancellation.
 * `Process.destroyForcibly` is API 26+; on older Androids we fall back to `destroy()`.
 * Descendants of the child are only killed when the whole session can be signalled (see [killGroup]);
 * a command that daemonises itself can outlive cancellation, which docs/ARCHITECTURE.md states plainly.
 */
class ProcessRunner(
    private val maxOutputBytes: Int = 256 * 1024,
    private val killGroup: Boolean = false,
) {
    fun run(
        argv: List<String>,
        timeoutMs: Long,
        cancel: CancelToken,
        env: Map<String, String> = emptyMap(),
        dir: File? = null,
    ): ProcessOutcome {
        if (cancel.isCancelled) return ProcessOutcome(null, "", "", false, false, false, true, null)
        val command = if (killGroup && File("/system/bin/setsid").exists()) listOf("/system/bin/setsid") + argv else argv
        val pb = ProcessBuilder(command)
        if (env.isNotEmpty()) pb.environment().putAll(env)
        if (dir != null) pb.directory(dir)
        val p = try {
            pb.start()
        } catch (e: Exception) {
            return ProcessOutcome(null, "", "", false, false, false, false, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
        runCatching { p.outputStream.close() }
        val out = Capture(p.inputStream, maxOutputBytes)
        val err = Capture(p.errorStream, maxOutputBytes)
        val to = Thread(out, "bridge-proc-out").apply { isDaemon = true; start() }
        val te = Thread(err, "bridge-proc-err").apply { isDaemon = true; start() }
        val unhook = cancel.onCancel { destroy(p) }
        var timedOut = false
        try {
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                timedOut = true
                destroy(p)
                p.waitFor(2, TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            destroy(p)
            Thread.currentThread().interrupt()
        } finally {
            unhook()
        }
        to.join(1000); te.join(1000)
        val exit = runCatching { p.exitValue() }.getOrNull()
        return ProcessOutcome(
            exitCode = exit, stdout = out.text(), stderr = err.text(),
            stdoutTruncated = out.truncated, stderrTruncated = err.truncated,
            timedOut = timedOut, cancelled = cancel.isCancelled, startFailure = null,
        )
    }

    private fun destroy(p: Process) {
        if (killGroup) {
            pidOf(p)?.let { pid ->
                runCatching {
                    ProcessBuilder("kill", "-KILL", "--", "-$pid").redirectErrorStream(true).start().waitFor(2, TimeUnit.SECONDS)
                }
            }
        }
        try {
            Process::class.java.getMethod("destroyForcibly").invoke(p)
        } catch (_: Throwable) {
            p.destroy()
        }
    }

    private fun pidOf(p: Process): Long? = try {
        Process::class.java.getMethod("pid").invoke(p) as Long
    } catch (_: Throwable) {
        null
    }

    private class Capture(private val stream: InputStream, private val max: Int) : Runnable {
        private val buf = ByteArrayOutputStream()

        @Volatile
        var truncated = false

        override fun run() {
            val chunk = ByteArray(8192)
            try {
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    synchronized(buf) {
                        val room = max - buf.size()
                        if (room > 0) buf.write(chunk, 0, minOf(room, n))
                        if (n > room) truncated = true
                    }
                }
            } catch (_: Exception) {
            }
        }

        fun text(): String = synchronized(buf) { buf.toString(Charsets.UTF_8) }
    }
}
