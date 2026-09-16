// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.updatePadding
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
import com.metallic.chiaki.regist.PsnLoginActivity
import com.metallic.chiaki.regist.RegistActivity
import com.metallic.chiaki.remote.AndroidPsnRemoteClient
import com.metallic.chiaki.remote.PsnDevice
import com.metallic.chiaki.settings.SettingsActivity
import com.metallic.chiaki.stream.StreamActivity
import com.metallic.chiaki.stream.StreamSummary
import com.metallic.chiaki.stream.StreamSummaryFormatter
import com.metallic.chiaki.stream.StreamSummaryQuality

class MainActivity : AppCompatActivity()
{
	companion object
	{
		const val EXTRA_ONBOARDING_PREVIEW = "onboarding_preview"
		private const val PREVIEW_WELCOME = "welcome"
		private const val PREVIEW_CONSOLES = "consoles"
	}

	private lateinit var viewModel: MainViewModel
	private lateinit var binding: ActivityMainBinding
	private lateinit var consoleAdapter: DisplayHostRecyclerViewAdapter
	private lateinit var preferences: Preferences
	private var discoveryMenuItem: MenuItem? = null
	private var localHosts: List<DisplayHost> = emptyList()
	private var psnConsoles: List<PsnConsole> = emptyList()
	private var configuredConsoleCount = -1
	private var pendingRegistrationHost: DisplayHost? = null
	private var pendingAutoPlayAddress: String? = null
	private var previewState: String? = null

	private val streamLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		val summary = result.data?.let {
			IntentCompat.getParcelableExtra(it, StreamActivity.EXTRA_STREAM_SUMMARY, StreamSummary::class.java)
		}
		if(summary != null)
			showStreamSummary(summary)
	}

	private val psnLoginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		if(result.resultCode != Activity.RESULT_OK)
			return@registerForActivityResult
		preferences.psnSignInEnabled = true
		preferences.psnRemotePlayEnabled = true
		viewModel.setPsnEnabled(true)
		pendingRegistrationHost?.also { host ->
			pendingRegistrationHost = null
			showGuidedRegistration(host.host, host.name, host.isPS5)
		}
		updateHomeState()
	}

	private val registrationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		if(result.resultCode == Activity.RESULT_OK)
		{
			pendingAutoPlayAddress = result.data?.getStringExtra(RegistActivity.EXTRA_REGISTERED_HOST)
			maybePlayRegisteredHost(localHosts)
		}
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
		binding.onboardingLayout.applySystemBarInsets(left = false, right = false, bottom = false)
		preferences = Preferences(this)
		previewState = intent.getStringExtra(EXTRA_ONBOARDING_PREVIEW)
			?.takeIf { BuildConfig.DEBUG && it in setOf(PREVIEW_WELCOME, PREVIEW_CONSOLES) }
		setSupportActionBar(binding.toolbar)
		setupQualityPresetChooser()

		binding.addConsoleButton.setOnClickListener { showAddConsoleMenu() }
		if(BuildConfig.CHIAKI_HOME_NON_OVERLAPPING_ACTIONS)
		{
			binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
				updateConsoleActionInsets()
			}
		}
		binding.onboardingSignInButton.setOnClickListener { startPsnSignIn() }
		binding.onboardingAddAddressButton.setOnClickListener {
			addManualConsole(binding.onboardingAddAddressButton)
		}
		binding.retryPsnListButton.setOnClickListener { viewModel.loadPsnConsoles() }
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
				preferences,
				LogManager(this),
				AndroidPsnRemoteClient(this)
			)
		})[MainViewModel::class.java]
		if(previewState == PREVIEW_CONSOLES)
			showPreviewConsoles()

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
			maybePlayRegisteredHost(it)
		}
		viewModel.configuredConsoleCount.observe(this) { count ->
			configuredConsoleCount = count
			updateHomeState()
		}
		viewModel.discoveryActive.observe(this) { active ->
			discoveryMenuItem?.let { updateDiscoveryMenuItem(it, active) }
		}
		viewModel.psnConsoles.observe(this) {
			psnConsoles = it
			updateConsoleList()
		}
		viewModel.psnListState.observe(this) {
			updatePsnListState(it)
			updateHomeState()
		}
		viewModel.psnAction.observe(this) { consoleAdapter.action = it }
		viewModel.psnError.observe(this, this::showPsnActionError)
		viewModel.psnPlayRequest.observe(this) { request ->
			request ?: return@observe
			connectPsnConsole(request.console)
			viewModel.clearPsnPlayRequest()
		}
		updateHomeState()
	}

	private fun updateConsoleActionInsets()
	{
		val container = binding.consoleListContainer
		val action = binding.addConsoleButton
		if(container.width == 0 || container.height == 0 || action.width == 0 || action.height == 0)
			return
		val location = IntArray(2)
		val actionLocation = IntArray(2)
		container.getLocationInWindow(location)
		action.getLocationInWindow(actionLocation)
		val insets = HomeActionLayout.contentInsets(
			contentRight = location[0] + container.width,
			contentBottom = location[1] + container.height,
			actionLeft = actionLocation[0],
			actionTop = actionLocation[1],
			clearance = resources.getDimensionPixelSize(R.dimen.home_action_clearance),
			landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
		)
		if(container.paddingRight != insets.end || container.paddingBottom != insets.bottom)
			container.updatePadding(right = insets.end, bottom = insets.bottom)
	}

	private fun currentHomeState(): OnboardingHomeState = when(previewState)
	{
		PREVIEW_WELCOME -> OnboardingHomeState.WELCOME
		PREVIEW_CONSOLES -> OnboardingHomeState.ACCOUNT_CONSOLES
		else -> onboardingHomeState(configuredConsoleCount, preferences.psnRemotePlayEnabled)
	}

	private fun updateHomeState()
	{
		if(previewState == null && configuredConsoleCount < 0)
			return
		val state = currentHomeState()
		val welcome = state == OnboardingHomeState.WELCOME
		val accountConsoles = state == OnboardingHomeState.ACCOUNT_CONSOLES
		binding.onboardingLayout.visibility = if(welcome) View.VISIBLE else View.GONE
		binding.mainContentLayout.visibility = if(welcome) View.GONE else View.VISIBLE
		binding.appBarLayout.visibility = if(welcome) View.GONE else View.VISIBLE
		binding.addConsoleButton.visibility = if(welcome) View.GONE else View.VISIBLE
		if(accountConsoles)
		{
			binding.addConsoleButton.setText(R.string.action_add_by_address)
			binding.addConsoleButton.setOnClickListener { addManualConsole(binding.addConsoleButton) }
		}
		else if(!welcome)
		{
			binding.addConsoleButton.setText(R.string.action_add_console)
			binding.addConsoleButton.setOnClickListener { showAddConsoleMenu() }
		}
		updateConsoleList()
	}

	private fun setupQualityPresetChooser()
	{
		val preferences = Preferences(this)
		val checkedButton = when(preferences.streamQualityPreset)
		{
			Preferences.StreamQualityPreset.BALANCED -> R.id.qualityPresetBalancedButton
			Preferences.StreamQualityPreset.LOW_LATENCY -> R.id.qualityPresetLowLatencyButton
			Preferences.StreamQualityPreset.DATA_SAVER -> R.id.qualityPresetDataSaverButton
		}
		binding.qualityPresetToggleGroup.check(checkedButton)
		binding.qualityPresetBalancedButton.setOnClickListener {
			preferences.applyStreamQualityPreset(Preferences.StreamQualityPreset.BALANCED)
		}
		binding.qualityPresetLowLatencyButton.setOnClickListener {
			preferences.applyStreamQualityPreset(Preferences.StreamQualityPreset.LOW_LATENCY)
		}
		binding.qualityPresetDataSaverButton.setOnClickListener {
			preferences.applyStreamQualityPreset(Preferences.StreamQualityPreset.DATA_SAVER)
		}
	}

	private fun updateConsoleList()
	{
		if(!::consoleAdapter.isInitialized)
			return
		val atTop = binding.hostsRecyclerView.computeVerticalScrollOffset() == 0
		val hosts = if(currentHomeState() == OnboardingHomeState.ACCOUNT_CONSOLES) emptyList() else localHosts
		consoleAdapter.consoles = mergeHomeConsoles(hosts, psnConsoles)
		if(atTop)
			binding.hostsRecyclerView.scrollToPosition(0)
		val listUnavailable = viewModel.psnListState.value is PsnConsoleListState.Error ||
			viewModel.psnListState.value == PsnConsoleListState.Loading
		binding.emptyInfoLayout.visibility =
			if(consoleAdapter.itemCount == 0 && !listUnavailable && currentHomeState() != OnboardingHomeState.WELCOME)
				View.VISIBLE else View.GONE
	}

	private fun updatePsnListState(state: PsnConsoleListState?)
	{
		binding.psnProgressLayout.visibility =
			if(state == PsnConsoleListState.Loading) View.VISIBLE else View.GONE
		val error = (state as? PsnConsoleListState.Error)?.message
		binding.psnListErrorLayout.visibility = if(error == null) View.GONE else View.VISIBLE
		binding.psnConsolesInfoTextView.text = error
		updateConsoleList()
	}

	private fun showPsnActionError(error: PsnActionError?)
	{
		binding.psnActionErrorLayout.visibility = if(error == null) View.GONE else View.VISIBLE
		binding.psnActionErrorTextView.text = error?.let { getString(R.string.psn_play_failed, it.message) }
		if(error != null)
		{
			binding.retryPsnActionButton.setText(
				if(error.recovery == PsnErrorRecovery.SIGN_IN) R.string.action_psn_sign_in else R.string.action_retry
			)
			binding.retryPsnActionButton.setOnClickListener {
				viewModel.clearPsnError()
				if(error.recovery == PsnErrorRecovery.SIGN_IN) startPsnSignIn()
				else viewModel.retryLastPsnAction()
			}
		}
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
					R.id.action_add_manual -> addManualConsole(binding.addConsoleButton)
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
		if(previewState == null)
			viewModel.setPsnEnabled(preferences.psnRemotePlayEnabled)
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

	private fun addManualConsole(source: View)
	{
		Intent(this, EditManualConsoleActivity::class.java).also {
			it.putRevealExtra(source, binding.rootLayout)
			startActivity(it, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
		}
	}

	private fun showRegistration()
	{
		if(preferences.psnAccountId.isNullOrBlank())
		{
			startPsnSignIn()
			return
		}
		preferences.psnSignInEnabled = true
		preferences.psnRemotePlayEnabled = true
		viewModel.setPsnEnabled(true)
		updateHomeState()
	}

	private fun playConsole(console: HomeConsole)
	{
		val psn = console.psnConsole
		when
		{
			console.status == HomeConsoleStatus.REGISTRATION_REQUIRED && psn != null -> playPsnConsole(psn)
			console.displayHost != null -> playLocalConsole(console.displayHost)
			psn != null -> playPsnConsole(psn)
		}
	}

	private fun wakeConsole(console: HomeConsole)
	{
		console.displayHost?.let(::wakeupHost)
	}

	private fun playLocalConsole(host: DisplayHost)
	{
		val registeredHost = host.registeredHost
		if(registeredHost == null)
		{
			if(host is DiscoveredDisplayHost)
			{
				if(preferences.psnAccountId.isNullOrBlank())
				{
					pendingRegistrationHost = host
					startPsnSignIn()
				}
				else
					showGuidedRegistration(host.host, host.name, host.isPS5)
			}
			else
				startLegacyRegistration(host)
			return
		}
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

	private fun startPsnSignIn()
	{
		if(previewState != null)
		{
			previewState = PREVIEW_CONSOLES
			showPreviewConsoles()
			updateHomeState()
			return
		}
		psnLoginLauncher.launch(Intent(this, PsnLoginActivity::class.java))
	}

	private fun showPreviewConsoles()
	{
		viewModel.showPsnPreview(listOf(PsnDevice("preview-console", "Living Room PS5")))
	}

	private fun showGuidedRegistration(host: String, name: String?, isPS5: Boolean)
	{
		registrationLauncher.launch(Intent(this, RegistActivity::class.java).apply {
			putExtra(RegistActivity.EXTRA_HOST, host)
			putExtra(RegistActivity.EXTRA_BROADCAST, false)
			putExtra(RegistActivity.EXTRA_CONSOLE_IS_PS5, isPS5)
			putExtra(RegistActivity.EXTRA_CONSOLE_NAME, name)
			putExtra(RegistActivity.EXTRA_GUIDED, true)
			if(previewState != null)
				putExtra(RegistActivity.EXTRA_PREVIEW, true)
		})
	}

	private fun startLegacyRegistration(host: DisplayHost)
	{
		startActivity(Intent(this, RegistActivity::class.java).apply {
			putExtra(RegistActivity.EXTRA_HOST, host.host)
			putExtra(RegistActivity.EXTRA_BROADCAST, false)
			if(host is ManualDisplayHost)
				putExtra(RegistActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, host.manualHost.id)
		})
	}

	private fun playPsnConsole(console: PsnConsole)
	{
		if(previewState != null)
		{
			showGuidedRegistration("192.0.2.1", console.device.name, true)
			return
		}
		viewModel.playPsnConsole(console)
	}

	private fun maybePlayRegisteredHost(hosts: List<DisplayHost>)
	{
		val address = pendingAutoPlayAddress ?: return
		val host = hosts.firstOrNull { it.host == address && it.registeredHost != null } ?: return
		pendingAutoPlayAddress = null
		playLocalConsole(host)
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
