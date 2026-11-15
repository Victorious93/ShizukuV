package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.startup.BridgeStartup
import moe.shizuku.manager.utils.UserHandleCompat

/**
 * Victorious: boot start is delegated to [BridgeStartup]. Compared with the previous receiver this one
 *  - never swallows failures: every outcome (including each failure reason) is persisted for the UI;
 *  - is idempotent across LOCKED_BOOT_COMPLETED and BOOT_COMPLETED and across overlapping triggers;
 *  - verifies the service actually answers before calling a start successful;
 *  - releases goAsync() exactly once, on every path, within a bounded time;
 *  - changes debugging settings only for options the user explicitly enabled, and can restore them.
 */
class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val trigger = when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> "locked_boot"
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            else -> return
        }
        if (UserHandleCompat.myUserId() > 0) return
        BridgeStartup.startFromBroadcast(context, this, trigger)
    }
}
