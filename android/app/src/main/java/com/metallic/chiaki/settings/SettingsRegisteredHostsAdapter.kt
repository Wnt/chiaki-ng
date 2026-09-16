// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.metallic.chiaki.R
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.databinding.ItemRegisteredHostBinding

class SettingsRegisteredHostsAdapter: RecyclerView.Adapter<SettingsRegisteredHostsAdapter.ViewHolder>()
{
	class ViewHolder(val binding: ItemRegisteredHostBinding): RecyclerView.ViewHolder(binding.root)

	var hosts: List<RegisteredHost> = listOf()
		set(value)
		{
			field = value
			notifyDataSetChanged()
		}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int)
		= ViewHolder(ItemRegisteredHostBinding.inflate(LayoutInflater.from(parent.context), parent, false))

	override fun getItemCount() = hosts.size

	override fun onBindViewHolder(holder: ViewHolder, position: Int)
	{
		val host = hosts[position]
		val context = holder.itemView.context
		if(host.target.isPS5)
		{
			holder.binding.nameTextView.text = host.serverNickname
			holder.binding.summaryTextView.text = host.serverMac.toString()
		}
		else
		{
			holder.binding.nameTextView.text = context.getString(R.string.registered_host_ps4_unsupported, host.serverNickname)
			holder.binding.summaryTextView.text = context.getString(R.string.registered_host_ps4_unsupported_summary, host.serverMac.toString())
		}
	}
}