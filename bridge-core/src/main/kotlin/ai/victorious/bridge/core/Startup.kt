package ai.victorious.bridge.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

enum class StartMethod { ROOT, WIRELESS_ADB }

/** Concrete, user-actionable reasons a start can fail. */
enum class StartupFailure(val recovery: String) {
    NOT_CONFIGURED("Open Victorious Bridge and start the service once so it knows which method to use."),
    UNSUPPORTED_ANDROID("Starting without a computer needs Android 11+ wireless debugging; use root or a computer (adb) instead."),
    MISSING_AUTHORIZATION("Grant the app the WRITE_SECURE_SETTINGS permission (run the adb command shown in Settings once, or use root)."),
    WIRELESS_DEBUGGING_OFF("Turn on Wireless debugging in Developer options, or allow boot start to turn it on in Settings."),
    PAIRING_FAILURE("Pair again: Developer options > Wireless debugging > Pair device with pairing code, then enter the code in the app."),
    DISCOVERY_TIMEOUT("The wireless-debugging port was not found. Make sure the phone is on Wi-Fi and Wireless debugging is on, then retry."),
    PORT_UNAVAILABLE("The wireless-debugging port refused the connection. Toggle Wireless debugging off and on, then retry."),
    ROOT_DENIED("Root access was denied. Allow Victorious Bridge in your root manager, or switch to wireless debugging."),
    STARTER_FAILED("The starter ran but reported an error; see Diagnostics for its output."),
    SERVICE_NOT_READY("The starter ran but the service never answered. Check Diagnostics; some ROMs kill background processes."),
    OEM_RESTRICTION("This ROM restricts background start-up. Exempt the app from battery optimisation / enable auto-start, then retry."),
    CANCELLED("Start was cancelled before it finished; use Start in the app to try again."),
    INTERRUPTED("A previous start was interrupted (process killed). Retry from the app."),
}

sealed class LaunchOutcome {
    object Launched : LaunchOutcome()
    data class Failed(val reason: StartupFailure, val detail: String = "", val retryable: Boolean = false) : LaunchOutcome()
}

interface StartupLauncher {
    val method: StartMethod
    suspend fun launch(attempt: Int): LaunchOutcome
}

fun interface ReadinessProbe {
    /** True once the privileged service answers (Binder received and pingBinder() true). Must observe cancellation. */
    suspend fun awaitReady(timeoutMs: Long): Boolean
}

enum class StartupStatus { RUNNING, SUCCEEDED, FAILED, INTERRUPTED }

data class StartupRecord(
    val bootId: String?,
    val trigger: String,
    val method: StartMethod?,
    val status: StartupStatus,
    val failure: StartupFailure?,
    val detail: String,
    val attempts: Int,
    val wallTimeMs: Long,
)

interface StartupStore {
    fun load(): StartupRecord?
    fun save(record: StartupRecord)
}

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 8_000,
    val readyTimeoutMs: Long = 6_000,
)

sealed class StartupResult {
    data class Success(val method: StartMethod?, val attempts: Int, val alreadyRunning: Boolean) : StartupResult()
    data class Failure(val reason: StartupFailure, val detail: String, val attempts: Int) : StartupResult()

    /** Another start is in flight, or this boot already succeeded; nothing was launched. */
    data class Skipped(val why: String) : StartupResult()
}

/**
 * Idempotent, cancellable start-up with bounded retries.
 *
 * Guarantees (each has a test):
 *  * single flight: overlapping triggers (LOCKED_BOOT_COMPLETED + BOOT_COMPLETED, a manual retry) never
 *    run two launches, so no duplicate service processes;
 *  * a launch only counts as success after [ReadinessProbe] confirms the service answers;
 *  * retries are bounded, back off exponentially and stop immediately on cancellation;
 *  * the last outcome is always persisted - including "interrupted" if the process is cancelled mid-way -
 *    so the UI can show the last failure and the next trigger knows what happened.
 */
class StartupOrchestrator(
    private val launcher: StartupLauncher?,
    private val probe: ReadinessProbe,
    private val store: StartupStore,
    private val policy: RetryPolicy = RetryPolicy(),
    private val bootId: () -> String?,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val onSuccess: suspend () -> Unit = {},
) {
    private val flight = Mutex()

    /** True while a start is in flight. A persisted RUNNING record while this is false means the process died mid-start. */
    val isRunning: Boolean get() = flight.isLocked

    suspend fun run(trigger: String): StartupResult {
        if (!flight.tryLock()) return StartupResult.Skipped("another start is already in progress")
        var attempts = 0
        var finished = false
        val boot = bootId()
        try {
            val prev = store.load()
            if (probe.awaitReady(0)) {
                record(boot, trigger, launcher?.method, StartupStatus.SUCCEEDED, null, "service already running", 0)
                finished = true
                return StartupResult.Success(launcher?.method, 0, alreadyRunning = true)
            }
            if (prev != null && prev.bootId != null && prev.bootId == boot && prev.status == StartupStatus.SUCCEEDED) {
                // This boot already started the service once and it is gone now (killed by the user or system):
                // do not silently restart it behind the user's back on a duplicate boot broadcast.
                if (trigger != MANUAL) {
                    finished = true
                    return StartupResult.Skipped("already started during this boot")
                }
            }
            val l = launcher ?: return fail(boot, trigger, null, StartupFailure.NOT_CONFIGURED, "", 0).also { finished = true }
            record(boot, trigger, l.method, StartupStatus.RUNNING, null, "", 0)
            var last: LaunchOutcome.Failed? = null
            while (attempts < policy.maxAttempts) {
                attempts++
                when (val out = l.launch(attempts)) {
                    LaunchOutcome.Launched -> {
                        if (probe.awaitReady(policy.readyTimeoutMs)) {
                            record(boot, trigger, l.method, StartupStatus.SUCCEEDED, null, "", attempts)
                            finished = true
                            onSuccess()
                            return StartupResult.Success(l.method, attempts, alreadyRunning = false)
                        }
                        last = LaunchOutcome.Failed(StartupFailure.SERVICE_NOT_READY, "no Binder after launch", retryable = true)
                    }
                    is LaunchOutcome.Failed -> last = out
                }
                val f = last!!
                if (!f.retryable || attempts >= policy.maxAttempts) break
                delay(minOf(policy.maxDelayMs, policy.baseDelayMs shl (attempts - 1).coerceAtMost(10)))
            }
            val f = last!!
            finished = true
            return fail(boot, trigger, l.method, f.reason, f.detail, attempts)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                record(boot, trigger, launcher?.method, StartupStatus.INTERRUPTED, StartupFailure.CANCELLED, "cancelled", attempts)
            }
            finished = true
            throw e
        } catch (t: Throwable) {
            finished = true
            return fail(boot, trigger, launcher?.method, StartupFailure.STARTER_FAILED, t.javaClass.simpleName, attempts)
        } finally {
            if (!finished) withContext(NonCancellable) {
                record(boot, trigger, launcher?.method, StartupStatus.INTERRUPTED, StartupFailure.INTERRUPTED, "", attempts)
            }
            flight.unlock()
        }
    }

    private fun fail(boot: String?, trigger: String, m: StartMethod?, r: StartupFailure, detail: String, attempts: Int): StartupResult.Failure {
        record(boot, trigger, m, StartupStatus.FAILED, r, Redactor.redact(detail).take(200), attempts)
        return StartupResult.Failure(r, detail, attempts)
    }

    private fun record(boot: String?, trigger: String, m: StartMethod?, s: StartupStatus, f: StartupFailure?, d: String, attempts: Int) {
        runCatching { store.save(StartupRecord(boot, trigger, m, s, f, d, attempts, wallClock())) }
    }

    companion object {
        const val MANUAL = "manual"
    }
}
