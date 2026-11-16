package moe.shizuku.manager.bridge

import ai.victorious.bridge.core.ActivityEvent
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.BridgeTextScreenBinding
import androidx.lifecycle.lifecycleScope
import java.util.Date

/** Bounded local history of authorization changes, service lifecycle and controlled operations. Nothing leaves the device. */
class ActivityHistoryActivity : AppBarActivity() {
    private lateinit var binding: BridgeTextScreenBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = BridgeTextScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.activity_title)
        binding.note.visibility = android.view.View.VISIBLE
        binding.note.setText(R.string.activity_note)
        actionButton(binding.actions, R.string.activity_export) { export() }
        actionButton(binding.actions, R.string.activity_retention) { retention() }
        actionButton(binding.actions, R.string.activity_clear) {
            MaterialAlertDialogBuilder(this).setMessage(R.string.activity_clear)
                .setPositiveButton(android.R.string.ok) { _, _ -> lifecycleScope.launch { withContext(Dispatchers.IO) { BridgeAdmin.clearActivity() }; load() } }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        BridgeNav.attach(this, R.id.nav_activity)
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() {
        lifecycleScope.launch {
            val events = withContext(Dispatchers.IO) { BridgeAdmin.activity() }
            val body: TextView = binding.body
            body.text = if (events.isEmpty()) getString(R.string.activity_empty) else events.asReversed().joinToString("\n\n") { fmt(it) }
        }
    }

    private fun fmt(e: ActivityEvent): String {
        val t = DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(e.wallTimeMs))
        val who = e.callerPackage ?: "uid ${e.callerUid}"
        return buildString {
            append(t).append("  ").append(e.type.name.lowercase()).append(" - ").append(e.outcome.name.lowercase()).append('\n')
            append(who)
            e.capability?.let { append("  ·  ").append(it) }
            e.durationMs?.let { append("  ·  ").append(it).append(" ms") }
            e.errorCategory?.let { append("  ·  ").append(it) }
            e.requestId?.let { append("\nrequest ").append(it) }
            e.remoteTaskId?.let { append("  task ").append(it) }
            append('\n').append(e.detail)
        }
    }

    private fun export() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { BridgeAdmin.exportActivity() }
            if (text == null) { Toast.makeText(this@ActivityHistoryActivity, R.string.bridge_service_not_running, Toast.LENGTH_SHORT).show(); return@launch }
            // Share sheet only; the user decides where the redacted text goes. No automatic upload exists.
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), getString(R.string.activity_export)))
        }
    }

    private fun retention() {
        val labels = arrayOf("100 entries, 7 days", "500 entries, 30 days (default)", "2000 entries, 90 days", "Keep nothing (0 entries)")
        val values = arrayOf(100 to 7L, 500 to 30L, 2000 to 90L, 0 to 1L)
        MaterialAlertDialogBuilder(this).setTitle(R.string.activity_retention).setItems(labels) { _, i ->
            lifecycleScope.launch { withContext(Dispatchers.IO) { BridgeAdmin.setRetention(values[i].first, values[i].second * 24 * 3600 * 1000) }; load() }
        }.show()
    }
}
