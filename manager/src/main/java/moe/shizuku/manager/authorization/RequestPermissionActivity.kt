package moe.shizuku.manager.authorization

import ai.victorious.bridge.core.BinderWait
import ai.victorious.bridge.core.BinderWaitResult
import ai.victorious.bridge.core.BridgeProtocol as P
import ai.victorious.bridge.core.Capability
import ai.victorious.bridge.core.DelayScheduler
import ai.victorious.bridge.core.GrantMode
import android.app.Dialog
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.bridge.BridgeAdmin
import moe.shizuku.manager.databinding.BridgeAccessDialogBinding
import rikka.html.text.HtmlCompat
import rikka.shizuku.Shizuku

/**
 * Asks the owner to approve an app. Started only by the privileged service (the activity requires a permission
 * held by shell/system, not by ordinary apps).
 *
 * Victorious rewrite: the old version blocked the main thread in onCreate on CountDownLatch.await() and ignored its
 * result, so a timeout was reported as success. This version never blocks; it waits for the Binder with
 * [BinderWait] and fails closed on timeout, Binder death, malformed input or activity teardown.
 */
class RequestPermissionActivity : AppActivity() {

    private var dialog: Dialog? = null
    private var wait: BinderWait? = null
    private var answered = false

    private val main = Handler(Looper.getMainLooper())

    private var uid = -1
    private var pid = -1
    private var requestCode = -1
    private var ai: ApplicationInfo? = null
    private var requestedCaps: List<Capability> = emptyList()
    private var requestedRoots: List<String> = emptyList()
    private var legacy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        answered = savedInstanceState?.getBoolean(STATE_ANSWERED) == true
        if (answered) { finish(); return }

        uid = intent.getIntExtra(P.EXTRA_UID, -1)
        pid = intent.getIntExtra(P.EXTRA_PID, -1)
        requestCode = intent.getIntExtra(P.EXTRA_REQUEST_CODE, -1)
        @Suppress("DEPRECATION")
        ai = intent.getParcelableExtra(P.EXTRA_APPLICATION_INFO)
        val capIds = intent.getStringArrayExtra(P.EXTRA_REQUESTED_CAPS)
        legacy = capIds == null
        requestedCaps = if (legacy) listOf(Capability.LEGACY_FULL) else capIds!!.mapNotNull { Capability.fromId(it) }.filter { !it.legacy }
        requestedRoots = intent.getStringArrayExtra(P.EXTRA_REQUESTED_ROOTS)?.toList().orEmpty()

        // Fail closed on anything inconsistent: the app info must belong to the uid the service says is asking.
        val info = ai
        if (uid == -1 || pid == -1 || info == null || info.uid != uid || requestedCaps.isEmpty()) {
            finish(); return
        }

        if (Shizuku.pingBinder()) showDialog() else startBinderWait()
    }

    private fun startBinderWait() {
        val received = Shizuku.OnBinderReceivedListener { wait?.binderReceived() }
        val dead = Shizuku.OnBinderDeadListener { wait?.binderDied() }
        val scheduler = DelayScheduler { delayMs, task ->
            val r = Runnable { task() }
            main.postDelayed(r, delayMs)
            val cancel: () -> Unit = { main.removeCallbacks(r) }
            cancel
        }
        wait = BinderWait(
            timeoutMs = 5_000, scheduler = scheduler,
            cleanup = { Shizuku.removeBinderReceivedListener(received); Shizuku.removeBinderDeadListener(dead) },
        ) { result ->
            main.post {
                when (result) {
                    BinderWaitResult.RECEIVED -> if (!isFinishing && !isDestroyed) showDialog()
                    BinderWaitResult.TIMED_OUT, BinderWaitResult.DIED -> {
                        Toast.makeText(applicationContext, R.string.bridge_service_not_running, Toast.LENGTH_LONG).show()
                        finish()
                    }
                    BinderWaitResult.CANCELLED -> Unit
                }
            }
        }
        Shizuku.addBinderReceivedListenerSticky(received, main)
        Shizuku.addBinderDeadListener(dead, main)
        wait!!.start()
    }

    private fun showDialog() {
        val info = ai ?: return finish()
        val label = try { info.loadLabel(packageManager).toString() } catch (_: Exception) { info.packageName }
        val b = BridgeAccessDialogBinding.inflate(LayoutInflater.from(this))
        b.title.text = HtmlCompat.fromHtml(getString(R.string.bridge_access_title, android.text.Html.escapeHtml(label)))
        b.summary.setText(if (legacy) R.string.bridge_access_summary_legacy else R.string.bridge_access_summary_scoped)
        b.legacyWarning.visibility = if (legacy) android.view.View.VISIBLE else android.view.View.GONE
        b.durPerOperation.visibility = if (legacy) android.view.View.GONE else android.view.View.VISIBLE

        val checks = ArrayList<Pair<Capability, CheckBox>>()
        if (!legacy) {
            requestedCaps.sortedByDescending { it.risk.ordinal }.forEach { cap ->
                val cb = CheckBox(this).apply {
                    text = capabilityLabel(cap); isChecked = true; minimumHeight = (48 * resources.displayMetrics.density).toInt()
                    contentDescription = capabilityLabel(cap)
                }
                checks += cap to cb
                b.capsContainer.addView(cb)
            }
        }
        if (requestedCaps.any { it == Capability.FILES_READ || it == Capability.FILES_WRITE }) {
            b.roots.visibility = android.view.View.VISIBLE
            b.roots.text = if (requestedRoots.isEmpty()) getString(R.string.bridge_files_no_roots) else getString(R.string.bridge_files_roots, requestedRoots.joinToString(", "))
        }
        val explain = { id: Int -> b.durationExplain.setText(when (id) {
            R.id.dur_15m -> R.string.bridge_duration_explain_15m
            R.id.dur_until_revoked -> R.string.bridge_duration_explain_until_revoked
            R.id.dur_per_operation -> R.string.bridge_duration_explain_per_operation
            else -> R.string.bridge_duration_explain_session
        }) }
        explain(b.durations.checkedRadioButtonId)
        b.durations.setOnCheckedChangeListener { _, id -> explain(id) }

        dialog = MaterialAlertDialogBuilder(this)
            .setView(b.root)
            .setCancelable(false)
            .setPositiveButton(R.string.bridge_allow) { _, _ ->
                val mode = when (b.durations.checkedRadioButtonId) {
                    R.id.dur_15m -> GrantMode.TIMED_15_MIN
                    R.id.dur_until_revoked -> GrantMode.UNTIL_REVOKED
                    R.id.dur_per_operation -> GrantMode.PER_OPERATION
                    else -> GrantMode.SESSION
                }
                val caps = if (legacy) setOf(Capability.LEGACY_FULL) else checks.filter { it.second.isChecked }.map { it.first }.toSet()
                respond { if (caps.isEmpty()) BridgeAdmin.denyAccess(uid, pid, requestCode) else BridgeAdmin.applyAccess(uid, pid, requestCode, mode, caps, requestedRoots) }
            }
            .setNegativeButton(R.string.bridge_deny) { _, _ -> respond { BridgeAdmin.denyAccess(uid, pid, requestCode) } }
            .setOnDismissListener { if (!answered) { respond { BridgeAdmin.denyAccess(uid, pid, requestCode) } } }
            .create().also { it.setCanceledOnTouchOutside(false); it.show() }
    }

    private fun respond(call: () -> Boolean) {
        if (answered) return
        answered = true
        // Binder call off the main thread; the activity finishes immediately and never waits on the service.
        CoroutineScope(Dispatchers.IO).launch { runCatching(call) }
        main.post { finish() }
    }

    private fun capabilityLabel(c: Capability): String = when (c) {
        Capability.DIAGNOSTICS_READ -> "Read device diagnostics"
        Capability.SETTINGS_READ -> "Read supported settings"
        Capability.SETTINGS_WRITE -> "Change approved settings"
        Capability.FILES_READ -> "Read files in approved folders"
        Capability.FILES_WRITE -> "Write files in approved folders"
        Capability.PACKAGES_READ -> "Inspect installed apps"
        Capability.PACKAGES_MANAGE -> "Enable, disable or force-stop apps"
        Capability.SHELL_EXEC -> "Run shell commands (unrestricted)"
        Capability.LEGACY_FULL -> "Legacy full access"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ANSWERED, answered)
    }

    override fun onDestroy() {
        // Every path releases what it registered: the Binder listeners, the pending timeout and the window.
        wait?.cancel()
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val STATE_ANSWERED = "answered"
    }
}
