package moe.shizuku.manager.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import moe.shizuku.manager.R
import moe.shizuku.manager.bridge.RevokeAll
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeManageAppsItemBinding
import moe.shizuku.manager.model.ServiceStatus
import rikka.core.content.asActivity
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

/** Prominent control on the Status screen: ends every authorization and stops managed work. */
class RevokeAllViewHolder(private val binding: HomeManageAppsItemBinding, root: View) : BaseViewHolder<ServiceStatus>(root), View.OnClickListener {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeManageAppsItemBinding.inflate(inflater, outer.root, true)
            RevokeAllViewHolder(inner, outer.root)
        }
    }

    init {
        root.setOnClickListener(this)
    }

    override fun onBind() {
        binding.text1.setText(R.string.revoke_all_title)
        binding.text2.setText(R.string.revoke_all_message)
        itemView.contentDescription = itemView.context.getString(R.string.revoke_all_title)
    }

    override fun onClick(v: View) {
        RevokeAll.confirm(v.context.asActivity())
    }
}
