package moe.shizuku.manager.bridge

import android.app.Activity
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R

/** The prominent "revoke everything and stop managed work" control. The dialog states what it does and does not do. */
object RevokeAll {
    fun confirm(activity: Activity, done: () -> Unit = {}) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.revoke_all_title)
            .setMessage(R.string.revoke_all_message)
            .setPositiveButton(R.string.apps_revoke) { _, _ ->
                CoroutineScope(Dispatchers.Main).launch {
                    val n = withContext(Dispatchers.IO) { BridgeAdmin.revokeAll() }
                    Toast.makeText(activity, activity.getString(R.string.revoke_all_done, n), Toast.LENGTH_LONG).show()
                    done()
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}
