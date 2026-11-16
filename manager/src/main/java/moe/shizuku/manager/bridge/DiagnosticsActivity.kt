package moe.shizuku.manager.bridge

import ai.victorious.bridge.core.StartupOrchestrator
import ai.victorious.bridge.core.StartupStatus
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.BridgeTextScreenBinding
import moe.shizuku.manager.startup.BridgeStartup
import rikka.shizuku.Shizuku

/** Service state, execution mode, restrictions, last start result with recovery steps, and active managed operations. */
class DiagnosticsActivity : AppBarActivity() {
    private lateinit var binding: BridgeTextScreenBinding
    private var report = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = BridgeTextScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.diagnostics_title)
        actionButton(binding.actions, R.string.diagnostics_retry_start) {
            BridgeStartup.start(applicationContext, StartupOrchestrator.MANUAL) { runOnUiThread { load() } }
            Toast.makeText(this, R.string.diagnostics_retry_start, Toast.LENGTH_SHORT).show()
        }
        actionButton(binding.actions, R.string.revoke_all_title) { RevokeAll.confirm(this) { load() } }
        actionButton(binding.actions, R.string.diagnostics_copy) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("bridge", report))
        }
        BridgeNav.attach(this, R.id.nav_diagnostics)
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { build() }
            report = text
            binding.body.text = text
        }
    }

    private fun build(): String = buildString {
        append("Victorious Bridge ${BuildConfig.VERSION_NAME}\n")
        append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n\n")
        val running = Shizuku.pingBinder()
        if (!running) append(getString(R.string.status_not_running)).append('\n')
        else {
            val st = BridgeAdmin.status()
            val root = Shizuku.getUid() == 0
            append(getString(if (root) R.string.status_exec_mode_root else R.string.status_exec_mode_adb)).append('\n')
            append("Server version ${Shizuku.getVersion()}.${Shizuku.getServerPatchVersion()}\n")
            append("SELinux: ${st?.getString("selinux_context") ?: "?"}\n")
            append("Backend policy: ${st?.getString("service_policy") ?: "?"}\n")
            append("Authorized apps: ${BridgeAdmin.grants().size}\n")
            append("Capabilities in this mode: files/settings/packages/shell as granted; ")
            append(if (root) "root can reach most of the system, subject to SELinux.\n" else "ADB (shell) cannot read other apps' private data or change protected settings.\n")
            val ops = BridgeAdmin.activeOperations()
            append("\nActive managed operations: ${ops.size}\n")
            ops.forEach { append(" - ${it.packageName ?: it.uid}: ${it.operation} [${it.state}] ${it.requestId}\n") }
        }
        append("\n").append(getString(R.string.diagnostics_last_start)).append(": ")
        val r = BridgeStartup.lastRecord()
        if (r == null) append("none recorded\n") else {
            val interrupted = r.status == StartupStatus.RUNNING && !BridgeStartup.isRunning
            append((if (interrupted) "INTERRUPTED" else r.status.name)).append(" via ${r.method ?: "?"} (${r.trigger}), ${r.attempts} attempt(s)\n")
            val f = r.failure
            if (f != null) { append("Reason: ${f.name}\n"); append(getString(R.string.diagnostics_recovery, f.recovery)).append('\n') }
            if (r.detail.isNotEmpty()) append("Detail: ${r.detail}\n")
        }
    }
}
