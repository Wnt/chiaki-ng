// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.app.Application
import android.content.Context
import androidx.lifecycle.*
import com.metallic.chiaki.common.LogManager
import com.metallic.chiaki.session.StreamSession
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.*
import com.metallic.chiaki.session.StreamInput
import com.metallic.chiaki.remote.AndroidPsnRemoteClient
import com.metallic.chiaki.remote.AndroidPsnRemoteNativeBridge
import com.metallic.chiaki.remote.PsnDevice
import com.metallic.chiaki.remote.PsnRemoteController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

class StreamViewModel(
	val application: Application,
	val connectInfo: ConnectInfo,
	private val psnDevice: PsnDevice? = null,
	diagnosticsPreview: Boolean = false
): ViewModel()
{
	val preferences = Preferences(application)
	val logManager = LogManager(application)

	val input = StreamInput(application, preferences)
	val session = StreamSession(application, connectInfo, logManager, preferences.logVerbose, preferences.realVideoTimestamps,
		preferences.decoderInputThreadEnabled, input,
		externallyManaged = psnDevice != null || diagnosticsPreview)

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

	private var _onScreenControlsEnabled = MutableLiveData<Boolean>(preferences.onScreenControlsEnabled)
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
		preferences.onScreenControlsEnabled = enabled
		_onScreenControlsEnabled.value = enabled
	}

}
