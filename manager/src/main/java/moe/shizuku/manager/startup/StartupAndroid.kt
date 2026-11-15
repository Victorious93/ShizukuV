package moe.shizuku.manager.startup

import ai.victorious.bridge.core.*
import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbInvalidPairingCodeException
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.starter.Starter
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

/** Persisted start-up preferences. Every setting-changing option is off until the user turns it on. */
object StartupPrefs {
    private const val K_RECORD = "startup_last_record"
    private const val K_LEDGER = "startup_ledger"
    const val K_CHANGE_WIRELESS = "boot_change_wireless_debugging"
    const val K_CHANGE_USB = "boot_change_usb_debugging"
    const val K_DISABLE_TIMEOUT = "boot_disable_adb_auth_timeout"
    const val K_RESTORE = "boot_restore_after_start"

    private val prefs get() = ShizukuSettings.getPreferences()

    fun policy() = BootSettingsPolicy(
        prefs.getBoolean(K_CHANGE_WIRELESS, false), prefs.getBoolean(K_CHANGE_USB, false),
        prefs.getBoolean(K_DISABLE_TIMEOUT, false), prefs.getBoolean(K_RESTORE, false),
    )

    fun loadRecord(): StartupRecord? = prefs.getString(K_RECORD, null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
    fun saveRecord(r: StartupRecord) = prefs.edit().putString(K_RECORD, toJson(r).toString()).apply()

    fun loadLedger(): List<LedgerEntry> = runCatching {
        val a = JSONArray(prefs.getString(K_LEDGER, "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            LedgerEntry(DebugSetting.valueOf(o.getString("s")), if (o.isNull("p")) null else o.getLong("p"), o.getLong("a"))
        }
    }.getOrDefault(emptyList())

    fun saveLedger(e: List<LedgerEntry>) = prefs.edit().putString(K_LEDGER, JSONArray().also { a ->
        e.forEach { a.put(JSONObject().put("s", it.setting.name).put("p", it.previous ?: JSONObject.NULL).put("a", it.applied)) }
    }.toString()).apply()

    private fun toJson(r: StartupRecord) = JSONObject().put("boot", r.bootId ?: JSONObject.NULL).put("trigger", r.trigger)
        .put("method", r.method?.name ?: JSONObject.NULL).put("status", r.status.name).put("failure", r.failure?.name ?: JSONObject.NULL)
        .put("detail", r.detail).put("attempts", r.attempts).put("t", r.wallTimeMs)

    private fun fromJson(o: JSONObject) = StartupRecord(
        if (o.isNull("boot")) null else o.getString("boot"), o.getString("trigger"),
        if (o.isNull("method")) null else StartMethod.valueOf(o.getString("method")), StartupStatus.valueOf(o.getString("status")),
        if (o.isNull("failure")) null else StartupFailure.valueOf(o.getString("failure")), o.getString("detail"), o.getInt("attempts"), o.getLong("t"),
    )
}

class PrefsStartupStore : StartupStore {
    override fun load() = StartupPrefs.loadRecord()
    override fun save(record: StartupRecord) = StartupPrefs.saveRecord(record)
}

class PrefsLedgerStore : LedgerStore {
    override fun load() = StartupPrefs.loadLedger()
    override fun save(entries: List<LedgerEntry>) = StartupPrefs.saveLedger(entries)
}

class GlobalSettingsAccessor(private val context: Context) : DebugSettingsAccessor {
    override fun get(key: String): Long? = try {
        Settings.Global.getString(context.contentResolver, key)?.toLongOrNull()
    } catch (_: Throwable) {
        null
    }

    override fun put(key: String, value: Long): Boolean = try {
        Settings.Global.putLong(context.contentResolver, key, value)
    } catch (_: SecurityException) {
        false
    } catch (_: Throwable) {
        false
    }
}

/** "Ready" means the service Binder is alive - not merely that a shell command exited with 0. */
class BinderReadinessProbe : ReadinessProbe {
    override suspend fun awaitReady(timeoutMs: Long): Boolean {
        if (Shizuku.pingBinder()) return true
        if (timeoutMs <= 0) return false
        val got = CompletableDeferred<Unit>()
        val listener = Shizuku.OnBinderReceivedListener { got.complete(Unit) }
        Shizuku.addBinderReceivedListenerSticky(listener, Handler(Looper.getMainLooper()))
        try {
            return withTimeoutOrNull(timeoutMs) { got.await() } != null && Shizuku.pingBinder()
        } finally {
            Shizuku.removeBinderReceivedListener(listener)
        }
    }
}

class RootLauncher : StartupLauncher {
    override val method = StartMethod.ROOT

    override suspend fun launch(attempt: Int): LaunchOutcome = withContext(Dispatchers.IO) {
        val root = try {
            Shell.getShell().isRoot
        } catch (_: Throwable) {
            false
        }
        if (!root) {
            Shell.getCachedShell()?.close()
            // At boot the su daemon can be a few seconds late, so this is retried (bounded) before being reported as denied.
            return@withContext LaunchOutcome.Failed(StartupFailure.ROOT_DENIED, "no root shell", retryable = true)
        }
        val r = Shell.cmd(Starter.internalCommand).exec()
        val out = r.out.joinToString("\n")
        if (r.isSuccess && out.contains(BridgeIdentity.STARTER_DONE_MARKER.removePrefix("info: "))) LaunchOutcome.Launched
        else LaunchOutcome.Failed(StartupFailure.STARTER_FAILED, out.takeLast(300), retryable = false)
    }
}

class WirelessAdbLauncher(
    private val context: Context,
    private val controller: DebugSettingsController,
    private val policy: BootSettingsPolicy,
    private val accessor: DebugSettingsAccessor,
) : StartupLauncher {
    override val method = StartMethod.WIRELESS_ADB

    override suspend fun launch(attempt: Int): LaunchOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return LaunchOutcome.Failed(StartupFailure.UNSUPPORTED_ANDROID, "API ${Build.VERSION.SDK_INT}")
        if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            return LaunchOutcome.Failed(StartupFailure.MISSING_AUTHORIZATION, "WRITE_SECURE_SETTINGS not granted")
        }
        // Only the settings the user opted into are touched; each previous value is captured for restore.
        controller.prepareForStart(policy)
        if (accessor.get(DebugSetting.WIRELESS_DEBUGGING.key) != 1L) return LaunchOutcome.Failed(StartupFailure.WIRELESS_DEBUGGING_OFF, "adb_wifi_enabled != 1")

        val port = discoverPort(10_000)
        if (port <= 0) {
            val am = context.getSystemService(ActivityManager::class.java)
            val restricted = Build.VERSION.SDK_INT >= 28 && am?.isBackgroundRestricted == true
            return if (restricted) LaunchOutcome.Failed(StartupFailure.OEM_RESTRICTION, "app is background-restricted", retryable = false)
            else LaunchOutcome.Failed(StartupFailure.DISCOVERY_TIMEOUT, "no _adb-tls-connect service", retryable = true)
        }
        return withContext(Dispatchers.IO) { connectAndStart(port) }
    }

    /** Discovery that always releases the NsdManager listener, whether it finds a port, errors, times out or is cancelled. */
    private suspend fun discoverPort(timeoutMs: Long): Int {
        var mdns: AdbMdns? = null
        try {
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val done = AtomicBoolean(false)
                    val m = AdbMdns(context, AdbMdns.TLS_CONNECT, onError = { _ ->
                        if (done.compareAndSet(false, true)) cont.resume(-1)
                    }, observer = { port ->
                        if (port > 0 && done.compareAndSet(false, true)) cont.resume(port)
                    })
                    mdns = m
                    cont.invokeOnCancellation { m.stop() }
                    m.start()
                }
            } ?: -1
        } finally {
            mdns?.stop()
        }
    }

    private fun connectAndStart(port: Int): LaunchOutcome {
        val out = StringBuilder()
        try {
            val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            AdbClient("127.0.0.1", port, key).use { c ->
                c.connect()
                c.shellCommand(Starter.internalCommand) { out.append(String(it)) }
            }
        } catch (e: ConnectException) {
            return LaunchOutcome.Failed(StartupFailure.PORT_UNAVAILABLE, e.javaClass.simpleName, retryable = true)
        } catch (e: SocketTimeoutException) {
            return LaunchOutcome.Failed(StartupFailure.PORT_UNAVAILABLE, e.javaClass.simpleName, retryable = true)
        } catch (e: SSLException) {
            return LaunchOutcome.Failed(StartupFailure.PAIRING_FAILURE, "TLS handshake rejected (not paired?)", retryable = false)
        } catch (e: AdbKeyException) {
            return LaunchOutcome.Failed(StartupFailure.PAIRING_FAILURE, "adb key unusable", retryable = false)
        } catch (e: AdbInvalidPairingCodeException) {
            return LaunchOutcome.Failed(StartupFailure.PAIRING_FAILURE, "invalid pairing code", retryable = false)
        } catch (e: IOException) {
            return LaunchOutcome.Failed(StartupFailure.PORT_UNAVAILABLE, e.javaClass.simpleName, retryable = true)
        } catch (e: Exception) {
            return LaunchOutcome.Failed(StartupFailure.STARTER_FAILED, e.javaClass.simpleName, retryable = false)
        }
        val text = out.toString()
        return if (text.contains(BridgeIdentity.STARTER_DONE_MARKER.removePrefix("info: "))) LaunchOutcome.Launched
        else LaunchOutcome.Failed(StartupFailure.STARTER_FAILED, text.takeLast(300), retryable = text.contains("fatal").not())
    }
}

/** Process-wide start-up entry point shared by the boot receiver and the UI so they cannot overlap. */
object BridgeStartup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val probe = BinderReadinessProbe()

    @Volatile
    private var appContext: Context? = null

    private val orchestrator: StartupOrchestrator by lazy {
        StartupOrchestrator(
            launcherProvider = ::chooseLauncher, probe = probe, store = PrefsStartupStore(),
            bootId = ::currentBootId, onSuccess = ::afterSuccess,
        )
    }

    val isRunning: Boolean get() = orchestrator.isRunning

    private fun currentBootId(): String? = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()

    private fun chooseLauncher(): StartupLauncher? {
        val ctx = appContext ?: return null
        return when {
            ShizukuSettings.getLastLaunchMode() == LaunchMethod.ROOT -> RootLauncher()
            ShizukuSettings.getLastLaunchMode() == LaunchMethod.ADB && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                GlobalSettingsAccessor(ctx).let { WirelessAdbLauncher(ctx, DebugSettingsController(it, PrefsLedgerStore()), StartupPrefs.policy(), it) }
            else -> null
        }
    }

    private suspend fun afterSuccess() {
        val ctx = appContext ?: return
        val policy = StartupPrefs.policy()
        if (policy.restoreAfterStart) DebugSettingsController(GlobalSettingsAccessor(ctx), PrefsLedgerStore()).restore()
    }

    /** Starts (or skips) in the application scope and returns immediately. [onDone] runs exactly once on every path. */
    fun start(context: Context, trigger: String, onDone: () -> Unit = {}) {
        appContext = context.applicationContext
        scope.launch {
            try {
                orchestrator.run(trigger)
            } finally {
                runCatching(onDone)
            }
        }
    }

    /** Boot broadcasts: the receiver must return fast, so its PendingResult is released by [maxReceiverMs] at the latest. */
    fun startFromBroadcast(context: Context, receiver: BroadcastReceiver, trigger: String, maxReceiverMs: Long = 8_000) {
        val pending = receiver.goAsync()
        val finished = AtomicBoolean(false)
        val finish = { if (finished.compareAndSet(false, true)) runCatching { pending.finish() } }
        val watchdog = Handler(Looper.getMainLooper())
        watchdog.postDelayed({ finish() }, maxReceiverMs)
        start(context, trigger) { watchdog.removeCallbacksAndMessages(null); finish() }
    }

    fun lastRecord(): StartupRecord? = StartupPrefs.loadRecord()
}
