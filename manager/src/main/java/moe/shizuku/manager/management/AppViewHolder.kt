package moe.shizuku.manager.management

import android.content.pm.PackageInfo
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.databinding.AppListItemBinding
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.utils.AppIconCache
import moe.shizuku.manager.utils.ShizukuSystemApis
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.html.text.HtmlCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku

class AppViewHolder(private val binding: AppListItemBinding) : BaseViewHolder<PackageInfo>(binding.root), View.OnClickListener {

    companion object {
        @JvmField
        val CREATOR = Creator<PackageInfo> { inflater: LayoutInflater, parent: ViewGroup? -> AppViewHolder(AppListItemBinding.inflate(inflater, parent, false)) }
    }


    private val icon get() = binding.icon
    private val name get() = binding.title
    private val pkg get() = binding.summary
    private val switchWidget get() = binding.switchWidget
    private val root get() = binding.requiresRoot

    init {
        itemView.filterTouchesWhenObscured = true
        itemView.setOnClickListener(this)
    }

    private inline val packageName get() = data.packageName
    private inline val ai get() = data.applicationInfo!!
    private inline val uid get() = ai.uid

    private var loadIconJob: Job? = null

    override fun onClick(v: View) {
        val context = v.context
        val label = ai.loadLabel(context.packageManager)
        val has = AuthorizationManager.granted(packageName, uid)
        val b = MaterialAlertDialogBuilder(context).setTitle(label)
            .setMessage(packageName + "\n\n" + AuthorizationManager.accessSummary(context, uid) + if (has) "\n\n" + context.getString(R.string.apps_detail_revoke_message) else "\n\n" + context.getString(R.string.bridge_legacy_warning))
            .setNeutralButton(android.R.string.cancel, null)
        if (has) {
            b.setPositiveButton(R.string.apps_revoke) { _, _ ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching { moe.shizuku.manager.bridge.BridgeAdmin.revoke(uid) }
                    AuthorizationManager.refreshGrants()
                    itemView.post { adapter.notifyItemChanged(adapterPosition, Any()) }
                }
            }
        } else {
            // Explicit, labelled legacy grant (until revoked). Scoped grants are requested by the app itself and approved in the prompt.
            b.setPositiveButton(R.string.apps_access_legacy) { _, _ -> grantLegacy(context) }
        }
        b.show()
    }

    private fun grantLegacy(context: android.content.Context) {
        try {
            AuthorizationManager.grant(packageName, uid)
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                AuthorizationManager.refreshGrants()
                itemView.post { adapter.notifyItemChanged(adapterPosition, Any()) }
            }
        } catch (e: SecurityException) {
            val uid = try {
                Shizuku.getUid()
            } catch (ex: Throwable) {
                return
            }
            if (uid != 0) {
                val dialog = MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.app_management_dialog_adb_is_limited_title)
                        .setMessage(context.getString(R.string.app_management_dialog_adb_is_limited_message, Helps.ADB.get()).toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE))
                        .setPositiveButton(android.R.string.ok, null)
                        .create()
                dialog.setOnShowListener {
                    (it as AlertDialog).findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
                }
                try {
                    dialog.show()
                } catch (ignored: Throwable) {
                }
            }
        }
        adapter.notifyItemChanged(adapterPosition, Any())
    }

    override fun onBind() {
        val pm = itemView.context.packageManager
        val userId = UserHandleCompat.getUserId(uid)
        icon.setImageDrawable(ai.loadIcon(pm))
        name.text = if (userId != UserHandleCompat.myUserId()) {
            val userInfo = ShizukuSystemApis.getUserInfo(userId)
            "${ai.loadLabel(pm)} - ${userInfo.name} ($userId)"
        } else {
            ai.loadLabel(pm)
        }
        pkg.text = ai.packageName + "\n" + AuthorizationManager.accessSummary(itemView.context, uid)
        switchWidget.isChecked = AuthorizationManager.granted(packageName, uid)
        switchWidget.contentDescription = AuthorizationManager.accessSummary(itemView.context, uid)
        root.visibility = if (ai.metaData != null && ai.metaData.getBoolean("com.victorious.shizukuv.client.REQUIRES_ROOT")) View.VISIBLE else View.GONE

        loadIconJob = AppIconCache.loadIconBitmapAsync(context, ai, ai.uid / 100000, icon)
    }

    override fun onBind(payloads: List<Any>) {
        switchWidget.isChecked = AuthorizationManager.granted(packageName, uid)
    }

    override fun onRecycle() {
        if (loadIconJob?.isActive == true) {
            loadIconJob?.cancel()
        }
    }
}
