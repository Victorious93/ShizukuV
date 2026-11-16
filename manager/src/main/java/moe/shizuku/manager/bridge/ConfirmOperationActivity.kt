package moe.shizuku.manager.bridge

import ai.victorious.bridge.core.BridgeProtocol as P
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity

/** Per-operation confirmation. One approval covers exactly the one request shown. Closing it declines. */
class ConfirmOperationActivity : AppActivity() {
    private var answered = false
    private val main = Handler(Looper.getMainLooper())
    private var dialog: android.app.Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestId = intent.getStringExtra(P.EXTRA_OPERATION_REQUEST_ID)
        val summary = intent.getStringExtra(P.EXTRA_OPERATION_SUMMARY)
        val pkg = intent.getStringExtra("package") ?: "?"
        if (requestId == null || summary == null || savedInstanceState?.getBoolean("answered") == true) { finish(); return }

        fun answer(ok: Boolean) {
            if (answered) return
            answered = true
            CoroutineScope(Dispatchers.IO).launch { runCatching { BridgeAdmin.answerOperation(requestId, ok) } }
            finish()
        }
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bridge_op_title)
            .setMessage(getString(R.string.bridge_op_message, pkg, summary))
            .setCancelable(false)
            .setPositiveButton(R.string.bridge_op_allow_once) { _, _ -> answer(true) }
            .setNegativeButton(R.string.bridge_deny) { _, _ -> answer(false) }
            .create().also { it.show() }
        // The service stops waiting after 30 s; do not leave a stale dialog behind.
        main.postDelayed({ answer(false) }, 30_000)
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putBoolean("answered", answered) }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        dialog?.dismiss(); dialog = null
        super.onDestroy()
    }
}
