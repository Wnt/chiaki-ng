// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.stream

import android.app.Application
import android.content.Context
import androidx.lifecycle.*
import fi.madekivi.pleikkari.common.LogManager
import fi.madekivi.pleikkari.session.StreamSession
import fi.madekivi.pleikkari.common.Preferences
import fi.madekivi.pleikkari.lib.*
import fi.madekivi.pleikkari.session.StreamInput
import fi.madekivi.pleikkari.remote.AndroidPsnRemoteClient
import fi.madekivi.pleikkari.remote.AndroidPsnRemoteNativeBridge
import fi.madekivi.pleikkari.remote.PsnDevice
import fi.madekivi.pleikkari.remote.PsnRemoteController
import fi.madekivi.pleikkari.remote.ConnectPhase
import fi.madekivi.pleikkari.remote.connectPhaseOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

class StreamViewModel(
	val application: Application,
	val connectInfo: ConnectInfo,
	private val psnDevice: PsnDevice? = null,
	diagnosticsPreview: Boolean = false,
	/** This stream follows a successful registration: the console may still be settling (PLE-335). */
	val justLinked: Boolean = false
): ViewModel()
{
	val preferences = Preferences(application)
	val logManager = LogManager(application)

	val input = StreamInput(application, preferences)
	val session = StreamSession(application, connectInfo, logManager, preferences.logVerbose, preferences.realVideoTimestamps,
		preferences.decoderInputThreadEnabled, input,
		externallyManaged = psnDevice != null || diagnosticsPreview,
		justLinked = justLinked)

	private val remoteController: PsnRemoteController? = psnDevice?.let {
		val bridge = AndroidPsnRemoteNativeBridge(
			connectInfo,
			logManager.createNewFile().file.absolutePath,
			preferences.logVerbose,
			useFrameIndexVideoTimestamps(preferences.realVideoTimestamps,
				connectInfo.videoPresenterConfig.pacingEnabled),
			preferences.decoderInputThreadEnabled,
			onSessionCreated = session::attachRemoteSession,
			onSessionClosed = session::detachRemoteSession,
			onSessionEvent = session::remoteEvent,
			context = application
		)
		AndroidPsnRemoteClient(application).controller(bridge)
	}
	private var remoteJob: Job? = null
	private var remoteActive = false

	/** The PSN control-plane phase while a remote connect runs in this screen; null otherwise. */
	val remoteConnectPhase: LiveData<ConnectPhase?> =
		remoteController?.state?.map(::connectPhaseOf)?.asLiveData() ?: MutableLiveData(null)

	private val onScreenControlsPolicy = OnScreenControlsPolicy(preferences.onScreenControlsEnabled)
	private var _onScreenControlsEnabled = MutableLiveData<Boolean>(onScreenControlsPolicy.visible)
	val onScreenControlsEnabled: LiveData<Boolean> get() = _onScreenControlsEnabled


	override fun onCleared()
	{
		super.onCleared()
		remoteActive = false
		remoteJob?.cancel()
		remoteController?.close()
		session.shutdown()
	}

	fun resume()
	{
		val device = psnDevice
		val controller = remoteController
		if(device == null || controller == null)
		{
			session.resume()
			return
		}
		if(remoteActive)
			return
		remoteActive = true
		val previous = remoteJob
		remoteJob = viewModelScope.launch {
			previous?.cancelAndJoin()
			controller.disconnect()
			try
			{
				controller.connect(device)
			}
			catch(cancelled: CancellationException)
			{
				throw cancelled
			}
			catch(error: Throwable)
			{
				session.remoteConnectionFailed(error)
			}
		}
	}

	/** PLE-428: a Reconnect after a quit gets a fresh settling-retry budget before it starts. */
	fun reconnect()
	{
		session.armForReconnect()
		pause()
		resume()
	}

	fun pause()
	{
		if(psnDevice == null)
		{
			session.pause()
			return
		}
		if(!remoteActive)
			return
		remoteActive = false
		val controller = remoteController ?: return
		val previous = remoteJob
		remoteJob = viewModelScope.launch {
			previous?.cancelAndJoin()
			controller.disconnect()
			session.detachRemoteSession()
		}
	}

	fun setOnScreenControlsEnabled(enabled: Boolean)
	{
		if(onScreenControlsPolicy.toggle(enabled))
			preferences.onScreenControlsEnabled = enabled
		publishOnScreenControls()
	}

	fun setTouchscreenAvailable(available: Boolean)
	{
		onScreenControlsPolicy.touchscreenChanged(available)
		publishOnScreenControls()
	}

	private fun publishOnScreenControls()
	{
		val visible = onScreenControlsPolicy.visible
		if(_onScreenControlsEnabled.value != visible)
			_onScreenControlsEnabled.value = visible
	}

}
