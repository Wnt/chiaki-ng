// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.metallic.chiaki.R
import com.metallic.chiaki.databinding.ItemPsnConsoleBinding

class PsnConsoleRecyclerViewAdapter(
	private val register: (PsnConsole) -> Unit,
	private val connect: (PsnConsole) -> Unit,
	private val wake: (PsnConsole) -> Unit
) : RecyclerView.Adapter<PsnConsoleRecyclerViewAdapter.ViewHolder>()
{
	var consoles: List<PsnConsole> = emptyList()
		set(value)
		{
			field = value
			notifyDataSetChanged()
		}

	var action: PsnConsoleActionState? = null
		set(value)
		{
			field = value
			notifyDataSetChanged()
		}

	class ViewHolder(val binding: ItemPsnConsoleBinding) : RecyclerView.ViewHolder(binding.root)

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
		ItemPsnConsoleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
	)

	override fun getItemCount() = consoles.size

	override fun onBindViewHolder(holder: ViewHolder, position: Int)
	{
		val console = consoles[position]
		val registered = console.registeredHost != null
		val busy = action != null
		val thisBusy = action?.duid == console.device.duid
		holder.binding.apply {
			nameTextView.text = console.device.name
			statusTextView.setText(if(registered) R.string.psn_console_registered else R.string.psn_console_not_registered)
			registerButton.isVisible = !registered
			connectButton.isVisible = registered
			wakeButton.isVisible = registered
			actionProgressBar.isVisible = thisBusy
			registerButton.isEnabled = !busy
			connectButton.isEnabled = !busy
			wakeButton.isEnabled = !busy
			registerButton.setOnClickListener { register(console) }
			connectButton.setOnClickListener { connect(console) }
			wakeButton.setOnClickListener { wake(console) }
		}
	}
}
