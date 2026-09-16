// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.metallic.chiaki.common.*
import com.metallic.chiaki.discovery.DiscoveryManager
import com.metallic.chiaki.discovery.serverMac
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.remote.AndroidPsnRemoteClient
import com.metallic.chiaki.remote.AndroidPsnRemoteNativeBridge
import com.metallic.chiaki.remote.PsnDevice
import com.metallic.chiaki.remote.PsnRemoteController
import com.metallic.chiaki.remote.PsnRemoteNativeBridge
import com.metallic.chiaki.remote.PsnRemoteState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(
	val database: AppDatabase,
	val preferences: Preferences,
	private val logManager: LogManager,
	private val psnClient: AndroidPsnRemoteClient
): ViewModel()
{
	val discoveryManager = DiscoveryManager().also {
		it.active = preferences.discoveryEnabled
		viewModelScope.launch {
			it.discoveryActive.collect { active ->
				preferences.discoveryEnabled = active
			}
		}
	}

	val displayHosts by lazy {
		combine(
			database.manualHostDao().getAll(),
			database.registeredHostDao().getAll(),
			discoveryManager.discoveredHosts
		) { manualHosts, registeredHosts, discoveredHosts ->
			val macRegisteredHosts = registeredHosts.associateBy { it.serverMac }
			val idRegisteredHosts = registeredHosts.associateBy { it.id }
			discoveredHosts.map {
				DiscoveredDisplayHost(it.serverMac?.let { mac -> macRegisteredHosts[mac] }, it)
			} +
			manualHosts.map {
				ManualDisplayHost(it.registeredHost?.let { id -> idRegisteredHosts[id] }, it)
			}
		}.asLiveData()
	}

	val discoveryActive by lazy {
		discoveryManager.discoveryActive.asLiveData()
	}

	private val psnDevices = MutableStateFlow<List<PsnDevice>>(emptyList())
	private val _psnListState = MutableLiveData<PsnConsoleListState>(PsnConsoleListState.Hidden)
	val psnListState: LiveData<PsnConsoleListState> get() = _psnListState
	private val _psnAction = MutableLiveData<PsnConsoleActionState?>(null)
	val psnAction: LiveData<PsnConsoleActionState?> get() = _psnAction
	private val _psnMessage = MutableLiveData<String?>(null)
	val psnMessage: LiveData<String?> get() = _psnMessage
	private var psnLoadJob: Job? = null
	private var psnActionJob: Job? = null
	private var actionController: PsnRemoteController? = null

	val psnConsoles = combine(psnDevices, database.registeredHostDao().getAll(), ::matchPsnConsoles).asLiveData()

	fun setPsnEnabled(enabled: Boolean)
	{
		if(!enabled)
		{
			psnLoadJob?.cancel()
			psnDevices.value = emptyList()
			_psnListState.value = PsnConsoleListState.Hidden
			return
		}
		if(_psnListState.value == PsnConsoleListState.Hidden)
			loadPsnConsoles()
	}

	fun loadPsnConsoles()
	{
		if(psnLoadJob?.isActive == true)
			return
		psnLoadJob = viewModelScope.launch {
			_psnListState.value = PsnConsoleListState.Loading
			try
			{
				psnDevices.value = psnClient.listDevices()
				_psnListState.value = PsnConsoleListState.Ready
			}
			catch(cancelled: CancellationException)
			{
				throw cancelled
			}
			catch(error: Throwable)
			{
				_psnListState.value = PsnConsoleListState.Error(
					error.message ?: "Unable to list consoles on your PSN account"
				)
			}
		}
	}

	fun connectInfo(host: RegisteredHost?, autoRegister: Boolean = false): ConnectInfo = ConnectInfo(
		ps5 = true,
		host = "",
		registKey = host?.rpRegistKey ?: ByteArray(16),
		morning = host?.rpKey ?: ByteArray(16),
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
		autoRegister = autoRegister,
		performanceModeEnabled = preferences.performanceModeEnabled,
		decoderOperatingRate = preferences.decoderOperatingRate,
		decoderOperatingRateDefault = preferences.decoderOperatingRateDefault,
		decoderOperatingRateAuto = preferences.decoderOperatingRateAuto,
		decoderRealtimePriority = preferences.decoderRealtimePriority,
		videoTimestampRateHz = preferences.videoTimestampRateHz,
		streamDiagnosticsEnabled = preferences.streamDiagnosticsOverlayEnabled,
		videoPresenterConfig = preferences.videoPresenterConfig
	)

	fun registerPsnConsole(console: PsnConsole)
	{
		runPsnAction(console, PsnConsoleAction.REGISTER) {
			val bridge = AndroidPsnRemoteNativeBridge(
				connectInfo(null, autoRegister = true),
				logManager.createNewFile().file.absolutePath,
				preferences.logVerbose,
				preferences.realVideoTimestamps,
				preferences.decoderInputThreadEnabled
			)
			val controller = psnClient.controller(bridge).also { actionController = it }
			controller.connect(console.device)
			val registered = (controller.state.value as? PsnRemoteState.Registered)?.host
				?: error("PSN registration did not return console credentials")
			withContext(Dispatchers.IO) {
				val host = RegisteredHost(registered)
				database.registeredHostDao().deleteByMac(host.serverMac)
				database.registeredHostDao().insert(host)
			}
			"${console.device.name} registered"
		}
	}

	fun wakePsnConsole(console: PsnConsole)
	{
		runPsnAction(console, PsnConsoleAction.WAKE) {
			val bridge = object : PsnRemoteNativeBridge
			{
				override suspend fun start(control: com.metallic.chiaki.remote.PsnPunchedSocket, registration: com.metallic.chiaki.remote.PsnRegistrationMaterial) =
					error("Wake does not start the native session")
				override suspend fun setDataSocket(data: com.metallic.chiaki.remote.PsnPunchedSocket) = Unit
			}
			val controller = psnClient.controller(bridge).also { actionController = it }
			controller.wake(console.device)
			"Wake command sent to ${console.device.name}"
		}
	}

	private fun runPsnAction(
		console: PsnConsole,
		action: PsnConsoleAction,
		block: suspend () -> String
	)
	{
		if(psnActionJob?.isActive == true)
			return
		psnActionJob = viewModelScope.launch {
			_psnAction.value = PsnConsoleActionState(console.device.duid, action)
			try
			{
				_psnMessage.value = block()
			}
			catch(cancelled: CancellationException)
			{
				throw cancelled
			}
			catch(error: Throwable)
			{
				_psnMessage.value = error.message ?: "PSN console action failed"
			}
			finally
			{
				actionController?.close()
				actionController = null
				_psnAction.value = null
			}
		}
	}

	fun clearPsnMessage()
	{
		_psnMessage.value = null
	}

	fun deleteManualHost(manualHost: ManualHost)
	{
		viewModelScope.launch(Dispatchers.IO) {
			try {
				database.manualHostDao().delete(manualHost)
			} catch(_: Exception) {}
		}
	}

	override fun onCleared()
	{
		super.onCleared()
		psnLoadJob?.cancel()
		psnActionJob?.cancel()
		actionController?.close()
		discoveryManager.dispose()
	}
}
