// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.metallic.chiaki.R
import com.metallic.chiaki.databinding.ItemDisplayHostBinding
import com.metallic.chiaki.remote.ConnectProgress
import com.metallic.chiaki.remote.detailText

private class HomeConsoleDiffCallback(
	private val old: List<HomeConsole>,
	private val new: List<HomeConsole>
): DiffUtil.Callback()
{
	override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
		old[oldItemPosition].key == new[newItemPosition].key
	override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
		old[oldItemPosition] == new[newItemPosition]
	override fun getOldListSize() = old.size
	override fun getNewListSize() = new.size
}

class DisplayHostRecyclerViewAdapter(
	private val play: (HomeConsole) -> Unit,
	private val wake: (HomeConsole) -> Unit,
	private val edit: (HomeConsole) -> Unit,
	private val delete: (HomeConsole) -> Unit
): RecyclerView.Adapter<DisplayHostRecyclerViewAdapter.ViewHolder>()
{
	var consoles: List<HomeConsole> = emptyList()
		set(value)
		{
			val diff = DiffUtil.calculateDiff(HomeConsoleDiffCallback(field, value))
			field = value
			diff.dispatchUpdatesTo(this)
		}

	var action: PsnConsoleActionState? = null
		set(value)
		{
			field = value
			notifyDataSetChanged()
		}

	/** Live progress for the console named by [action] (PLE-337); null when nothing is connecting. */
	var progress: ConnectProgress? = null
		set(value)
		{
			if(field == value)
				return
			field = value
			notifyDataSetChanged()
		}

	class ViewHolder(val binding: ItemDisplayHostBinding): RecyclerView.ViewHolder(binding.root)

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
		ItemDisplayHostBinding.inflate(LayoutInflater.from(parent.context), parent, false)
	)

	override fun getItemCount() = consoles.size

	override fun onBindViewHolder(holder: ViewHolder, position: Int)
	{
		val console = consoles[position]
		val context = holder.itemView.context
		val thisBusy = action?.duid?.let { it == console.psnConsole?.device?.duid } == true
		val anyBusy = action != null
		holder.binding.apply {
			nameTextView.text = console.name
			statusTextView.setText(when(console.status)
			{
				HomeConsoleStatus.ON -> R.string.console_status_on
				HomeConsoleStatus.STANDBY -> R.string.console_status_standby
				HomeConsoleStatus.REMOTE -> if(console.psnConsole != null)
					R.string.console_status_remote_psn else R.string.console_status_remote
				HomeConsoleStatus.REGISTRATION_REQUIRED -> R.string.console_status_registration_required
			})
			val consoleProgress = if(thisBusy) progress else null
			detailTextView.text = console.detail
			detailTextView.isVisible = !console.detail.isNullOrBlank() && consoleProgress == null
			actionStatusTextView.isVisible = consoleProgress != null
			actionDetailTextView.isVisible = consoleProgress != null
			if(consoleProgress != null)
			{
				actionStatusTextView.setText(consoleProgress.phase.labelRes)
				actionDetailTextView.text = consoleProgress.detailText(context)
			}
			stateIndicatorImageView.setImageResource(
				if(console.displayHost?.isPS5 != false) R.drawable.ic_console_ps5 else R.drawable.ic_console
			)
			actionProgressBar.isVisible = thisBusy
			playButton.isEnabled = !anyBusy
			playButton.setOnClickListener { play(console) }
			wakeButton.isVisible = console.status == HomeConsoleStatus.STANDBY
			wakeButton.isEnabled = !anyBusy
			wakeButton.setOnClickListener { wake(console) }

			val editable = console.manualDisplayHost != null
			menuButton.isVisible = editable
			if(editable)
				menuButton.setOnClickListener {
					PopupMenu(context, menuButton).also { menu ->
						menu.menuInflater.inflate(R.menu.display_host, menu.menu)
						menu.menu.findItem(R.id.action_wakeup).isVisible = false
						menu.setOnMenuItemClickListener { item ->
							when(item.itemId)
							{
								R.id.action_edit -> edit(console)
								R.id.action_delete -> delete(console)
								else -> return@setOnMenuItemClickListener false
							}
							true
						}
						menu.show()
					}
				}
			else
				menuButton.setOnClickListener(null)
			menuButton.isGone = !editable
		}
	}
}
