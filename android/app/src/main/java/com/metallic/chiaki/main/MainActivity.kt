// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.BuildConfig
import com.metallic.chiaki.R
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.applySystemBarInsets
import com.metallic.chiaki.common.ext.enableAppEdgeToEdge
import com.metallic.chiaki.common.ext.putRevealExtra
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityMainBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.manualconsole.EditManualConsoleActivity
import com.metallic.chiaki.regist.RegistActivity
import com.metallic.chiaki.remote.AndroidPsnRemoteClient
import com.metallic.chiaki.settings.SettingsActivity
import com.metallic.chiaki.stream.StreamActivity
import com.metallic.chiaki.stream.StreamSummary
import com.metallic.chiaki.stream.StreamSummaryFormatter
import com.metallic.chiaki.stream.StreamSummaryQuality

class MainActivity : AppCompatActivity()
{
	private lateinit var viewModel: MainViewModel
	private lateinit var binding: ActivityMainBinding
	private lateinit var consoleAdapter: DisplayHostRecyclerViewAdapter
	private var discoveryMenuItem: MenuItem? = null
	private var localHosts: List<DisplayHost> = emptyList()
	private var psnConsoles: List<PsnConsole> = emptyList()

	private val streamLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		val summary = result.data?.let {
			IntentCompat.getParcelableExtra(it, StreamActivity.EXTRA_STREAM_SUMMARY, StreamSummary::class.java)
		}
		if(summary != null)
			showStreamSummary(summary)
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		enableAppEdgeToEdge()
		if(BuildConfig.DEBUG && intent.getBooleanExtra(StreamActivity.EXTRA_DIAGNOSTICS_PREVIEW, false))
		{
			startActivity(Intent(this, StreamActivity::class.java).apply {
				putExtra(StreamActivity.EXTRA_DIAGNOSTICS_PREVIEW, true)
			})
			finish()
			return
		}
		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.root.applySystemBarInsets(top = false)
		binding.appBarLayout.applySystemBarInsets(left = false, right = false, bottom = false)
		setSupportActionBar(binding.toolbar)

		binding.addConsoleButton.setOnClickListener { showAddConsoleMenu() }
		binding.summaryDismissButton.setOnClickListener {
			binding.streamSummaryCard.visibility = View.GONE
		}
		binding.summaryDuration.labelTextView.setText(R.string.stream_summary_duration)
		binding.summaryLatency.labelTextView.setText(R.string.stream_summary_latency)
		binding.summaryDrops.labelTextView.setText(R.string.stream_summary_drops)
		binding.summaryQuality.labelTextView.setText(R.string.stream_summary_quality)

		viewModel = ViewModelProvider(this, viewModelFactory {
			MainViewModel(
				getDatabase(this),
				Preferences(this),
				LogManager(this),
				AndroidPsnRemoteClient(this)
			)
		})[MainViewModel::class.java]

		consoleAdapter = DisplayHostRecyclerViewAdapter(
			this::playConsole,
			this::wakeConsole,
			this::editConsole,
			this::deleteConsole
		)
		binding.hostsRecyclerView.adapter = consoleAdapter
		binding.hostsRecyclerView.layoutManager = LinearLayoutManager(this)
		viewModel.displayHosts.observe(this) {
			localHosts = it
			updateConsoleList()
		}
		viewModel.discoveryActive.observe(this) { active ->
			discoveryMenuItem?.let { updateDiscoveryMenuItem(it, active) }
		}
		viewModel.psnConsoles.observe(this) {
			psnConsoles = it
			updateConsoleList()
		}
		viewModel.psnListState.observe(this, this::updatePsnListState)
		viewModel.psnAction.observe(this) { consoleAdapter.action = it }
		viewModel.psnMessage.observe(this) { message ->
			if(message != null)
			{
				Toast.makeText(this, message, Toast.LENGTH_LONG).show()
				viewModel.clearPsnMessage()
			}
		}
	}

	private fun updateConsoleList()
	{
		val atTop = binding.hostsRecyclerView.computeVerticalScrollOffset() == 0
		consoleAdapter.consoles = mergeHomeConsoles(localHosts, psnConsoles)
		if(atTop)
			binding.hostsRecyclerView.scrollToPosition(0)
		binding.emptyInfoLayout.visibility =
			if(consoleAdapter.itemCount == 0) View.VISIBLE else View.GONE
	}

	private fun updatePsnListState(state: PsnConsoleListState?)
	{
		binding.psnProgressLayout.visibility =
			if(state == PsnConsoleListState.Loading) View.VISIBLE else View.GONE
		val error = (state as? PsnConsoleListState.Error)?.message
		binding.psnConsolesInfoTextView.text = error
		binding.psnConsolesInfoTextView.visibility = if(error == null) View.GONE else View.VISIBLE
	}

	private fun showStreamSummary(summary: StreamSummary)
	{
		binding.summaryDuration.valueTextView.text = StreamSummaryFormatter.duration(summary.durationMillis)
		binding.summaryLatency.valueTextView.text = StreamSummaryFormatter.latency(summary.averageLatencyMillis)
		binding.summaryDrops.valueTextView.text = summary.droppedFrames.toString()
		binding.summaryQuality.valueTextView.setText(when(summary.quality)
		{
			StreamSummaryQuality.GOOD -> R.string.network_quality_good
			StreamSummaryQuality.FAIR -> R.string.network_quality_fair
			StreamSummaryQuality.POOR -> R.string.network_quality_poor
			StreamSummaryQuality.UNKNOWN -> R.string.network_quality_unknown
		})
		binding.streamSummaryCard.visibility = View.VISIBLE
	}

	private fun showAddConsoleMenu()
	{
		PopupMenu(this, binding.addConsoleButton).also { menu ->
			menu.menuInflater.inflate(R.menu.add_console, menu.menu)
			menu.setOnMenuItemClickListener {
				when(it.itemId)
				{
					R.id.action_register -> showRegistration()
					R.id.action_add_manual -> addManualConsole()
					else -> return@setOnMenuItemClickListener false
				}
				true
			}
			menu.show()
		}
	}

	override fun onStart()
	{
		super.onStart()
		viewModel.setPsnEnabled(Preferences(this).psnRemotePlayEnabled)
		viewModel.discoveryManager.resume()
	}

	override fun onStop()
	{
		super.onStop()
		viewModel.discoveryManager.pause()
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean
	{
		menuInflater.inflate(R.menu.main, menu)
		discoveryMenuItem = menu.findItem(R.id.action_discover).also {
			updateDiscoveryMenuItem(it, viewModel.discoveryActive.value ?: false)
		}
		return true
	}

	private fun updateDiscoveryMenuItem(item: MenuItem, active: Boolean)
	{
		item.isChecked = active
		item.setIcon(if(active) R.drawable.ic_discover_on else R.drawable.ic_discover_off)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when(item.itemId)
	{
		R.id.action_discover ->
		{
			viewModel.discoveryManager.active = !(viewModel.discoveryActive.value ?: false)
			true
		}
		R.id.action_settings ->
		{
			startActivity(Intent(this, SettingsActivity::class.java))
			true
		}
		else -> super.onOptionsItemSelected(item)
	}

	private fun addManualConsole()
	{
		Intent(this, EditManualConsoleActivity::class.java).also {
			it.putRevealExtra(binding.addConsoleButton, binding.rootLayout)
			startActivity(it, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
		}
	}

	private fun showRegistration()
	{
		Intent(this, RegistActivity::class.java).also {
			it.putRevealExtra(binding.addConsoleButton, binding.rootLayout)
			startActivity(it, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
		}
	}

	private fun playConsole(console: HomeConsole)
	{
		val psn = console.psnConsole
		when
		{
			console.status == HomeConsoleStatus.REMOTE && psn?.registeredHost != null ->
				connectPsnConsole(psn)
			console.status == HomeConsoleStatus.REGISTRATION_REQUIRED && psn != null ->
				viewModel.registerPsnConsole(psn)
			console.displayHost != null -> playLocalConsole(console.displayHost)
			psn?.registeredHost != null -> connectPsnConsole(psn)
		}
	}

	private fun wakeConsole(console: HomeConsole)
	{
		console.displayHost?.let {
			wakeupHost(it)
			return
		}
		console.psnConsole?.let(viewModel::wakePsnConsole)
	}

	private fun playLocalConsole(host: DisplayHost)
	{
		val registeredHost = host.registeredHost
		if(registeredHost == null)
		{
			Intent(this, RegistActivity::class.java).let {
				it.putExtra(RegistActivity.EXTRA_HOST, host.host)
				it.putExtra(RegistActivity.EXTRA_BROADCAST, false)
				if(Preferences(this).psnSignInEnabled && host is DiscoveredDisplayHost)
					it.putExtra(RegistActivity.EXTRA_CONSOLE_IS_PS5, host.isPS5)
				if(host is ManualDisplayHost)
					it.putExtra(RegistActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, host.manualHost.id)
				startActivity(it)
			}
			return
		}
		val preferences = Preferences(this)
		val connectInfo = ConnectInfo(
			ps5 = host.isPS5,
			host = host.host,
			registKey = registeredHost.rpRegistKey,
			morning = registeredHost.rpKey,
			videoProfile = preferences.videoProfile,
			decoderLowLatencyEnabled = preferences.decoderLowLatencyEnabled,
			threadPriorityBoostEnabled = preferences.threadPriorityBoostEnabled,
			decoderLateFrameRecoveryEnabled = preferences.decoderLateFrameRecoveryEnabled,
			packetLossMax = preferences.packetLossMax,
			adaptiveLossReport = preferences.adaptiveLossReport,
			takionVideoPacketReorderingDisabled = preferences.takionVideoPacketReorderingDisabled,
			feedbackStateMinIntervalMs = if(preferences.feedbackReducedIntervalEnabled) 4 else 0,
			feedbackStatsLogIntervalMs = preferences.feedbackStatsLogIntervalMs,
			audioBufferBursts = preferences.audioBufferBursts,
			audioFifoMs = preferences.audioFifoMs,
			performanceModeEnabled = preferences.performanceModeEnabled,
			decoderOperatingRate = preferences.decoderOperatingRate,
			decoderOperatingRateDefault = preferences.decoderOperatingRateDefault,
			decoderOperatingRateAuto = preferences.decoderOperatingRateAuto,
			decoderRealtimePriority = preferences.decoderRealtimePriority,
			videoTimestampRateHz = preferences.videoTimestampRateHz,
			streamDiagnosticsEnabled = true,
			videoPresenterConfig = preferences.videoPresenterConfig
		)
		streamLauncher.launch(Intent(this, StreamActivity::class.java).apply {
			putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo)
		})
	}

	private fun wakeupHost(host: DisplayHost)
	{
		val registeredHost = host.registeredHost ?: return
		viewModel.discoveryManager.sendWakeup(
			host.host,
			registeredHost.rpRegistKey,
			registeredHost.target.isPS5
		)
	}

	private fun connectPsnConsole(console: PsnConsole)
	{
		val registered = console.registeredHost ?: return
		streamLauncher.launch(Intent(this, StreamActivity::class.java).apply {
			putExtra(StreamActivity.EXTRA_CONNECT_INFO, viewModel.connectInfo(registered))
			putExtra(StreamActivity.EXTRA_PSN_DEVICE, console.device)
		})
	}

	private fun editConsole(console: HomeConsole)
	{
		val host = console.displayHost as? ManualDisplayHost ?: return
		startActivity(Intent(this, EditManualConsoleActivity::class.java).apply {
			putExtra(EditManualConsoleActivity.EXTRA_MANUAL_HOST_ID, host.manualHost.id)
		})
	}

	private fun deleteConsole(console: HomeConsole)
	{
		val host = console.displayHost as? ManualDisplayHost ?: return
		MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.alert_message_delete_manual_host, host.manualHost.host))
			.setPositiveButton(R.string.action_delete) { _, _ ->
				viewModel.deleteManualHost(host.manualHost)
			}
			.setNegativeButton(R.string.action_keep) { _, _ -> }
			.show()
	}
}
