// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.Log
import android.view.*
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.metallic.chiaki.common.LogManager
import com.metallic.chiaki.lib.*

sealed class StreamState
object StreamStateIdle: StreamState()
object StreamStateConnecting: StreamState()
object StreamStateConnected: StreamState()
data class StreamStateCreateError(val error: CreateError): StreamState()
data class StreamStateRemoteError(val message: String): StreamState()
data class StreamStateQuit(val reason: QuitReason, val reasonString: String?): StreamState()
data class StreamStateLoginPinRequest(val pinIncorrect: Boolean): StreamState()

class StreamSession(private val context: Context, val connectInfo: ConnectInfo, val logManager: LogManager, val logVerbose: Boolean, val realVideoTimestamps: Boolean,
		val decoderInputThread: Boolean, val videoPacingEnabled: Boolean, val videoPacingMode: Int,
		val videoPresenterLead: Int,
		val videoPacingBoundedAgeEnabled: Boolean, val videoPacingMaxFrameAgePeriods: Int,
		val input: StreamInput, private val externallyManaged: Boolean = false)
{
	var session: Session? = null
		private set

	private val _state = MutableLiveData<StreamState>(StreamStateIdle)
	val state: LiveData<StreamState> get() = _state
	private val _rumbleState = MutableLiveData<RumbleEvent>(RumbleEvent(0U, 0U))
	val rumbleState: LiveData<RumbleEvent> get() = _rumbleState
	private val _streamStats = MutableLiveData<StreamStatsEvent>()
	val streamStats: LiveData<StreamStatsEvent> get() = _streamStats

	private var surfaceTexture: SurfaceTexture? = null
	private var surface: Surface? = null
	private var surfaceRefreshHz = connectInfo.videoProfile.maxFPS.toDouble()
	private var surfaceVsyncOffsetNanos = 0L
	private val nativePacingMode get() = if(videoPacingEnabled) videoPacingMode else 0
	private val nativeMaxQueueAgePeriods get() =
		if(videoPacingEnabled && videoPacingBoundedAgeEnabled) videoPacingMaxFrameAgePeriods else 0

	init
	{
		input.controllerStateChangedCallback = {
			session?.setControllerState(it)
		}
	}

	fun shutdown()
	{
		session?.stop()
		session?.dispose()
		session = null
		surface = null
		_state.value = StreamStateIdle
		//surfaceTexture?.release()
	}

	fun pause()
	{
		shutdown()
	}

	fun resume()
	{
		if(externallyManaged)
			return
		if(session != null)
			return
		try
		{
			val session = Session(connectInfo, logManager.createNewFile().file.absolutePath, logVerbose,
				realVideoTimestamps || videoPacingEnabled, decoderInputThread, context = context)
			_state.value = StreamStateConnecting
			session.eventCallback = this::eventCallback
			session.start()
			val surface = surface
			if(surface != null)
				session.setSurface(surface, connectInfo.videoProfile.maxFPS, surfaceRefreshHz,
					surfaceVsyncOffsetNanos, nativePacingMode, videoPresenterLead,
					nativeMaxQueueAgePeriods)
			this.session = session
		}
		catch(e: CreateError)
		{
			_state.value = StreamStateCreateError(e)
		}
	}

	fun attachRemoteSession(remoteSession: Session)
	{
		session = remoteSession
		_state.value = StreamStateConnecting
		val currentSurface = surface
		if(currentSurface != null)
			remoteSession.setSurface(currentSurface, connectInfo.videoProfile.maxFPS, surfaceRefreshHz,
				surfaceVsyncOffsetNanos, nativePacingMode, videoPresenterLead,
				nativeMaxQueueAgePeriods)
	}

	fun detachRemoteSession()
	{
		session = null
		_state.value = StreamStateIdle
	}

	fun remoteEvent(event: Event) = eventCallback(event)

	fun remoteConnectionFailed(error: Throwable)
	{
		session = null
		_state.value = StreamStateRemoteError(error.message ?: "PSN remote connection failed")
	}

	private fun eventCallback(event: Event)
	{
		when(event)
		{
			is ConnectedEvent -> _state.postValue(StreamStateConnected)
			is QuitEvent -> _state.postValue(
				StreamStateQuit(
					event.reason,
					event.reasonString
				)
			)
			is LoginPinRequestEvent -> _state.postValue(
				StreamStateLoginPinRequest(
					event.pinIncorrect
				)
			)
			is RumbleEvent -> _rumbleState.postValue(event)
			is RemoteDataSocketNeededEvent -> Unit // handled by the PSN control-plane bridge
			is RegistrationEvent -> Unit // handled by the PSN control-plane bridge
			is StreamStatsEvent -> _streamStats.postValue(event)
		}
	}

	fun attachToSurfaceView(surfaceView: SurfaceView, frameRate: Float?)
	{
		surfaceView.holder.addCallback(object: SurfaceHolder.Callback {
			override fun surfaceCreated(holder: SurfaceHolder)
			{
				val surface = holder.surface
				applyFrameRate(surface, frameRate)
				setSurface(surface, surfaceView.display, frameRate?.toDouble())
			}

			override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }

			override fun surfaceDestroyed(holder: SurfaceHolder)
			{
				clearFrameRate(holder.surface, frameRate)
				this@StreamSession.surface = null
				session?.setSurface(null, connectInfo.videoProfile.maxFPS, surfaceRefreshHz,
					surfaceVsyncOffsetNanos, nativePacingMode, videoPresenterLead,
					nativeMaxQueueAgePeriods)
			}
		})
		
		val surface = surfaceView.holder.surface
		if (surface?.isValid == true) {
			applyFrameRate(surface, frameRate)
			setSurface(surface, surfaceView.display, frameRate?.toDouble())
		}
	}

	private fun applyFrameRate(surface: Surface, frameRate: Float?)
	{
		if(frameRate == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
			return
		try
		{
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
				surface.setFrameRate(
					frameRate,
					Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
					Surface.CHANGE_FRAME_RATE_ALWAYS
				)
			else
				surface.setFrameRate(frameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
		}
		catch(e: RuntimeException)
		{
			Log.w("StreamSession", "Failed to request surface frame rate $frameRate", e)
		}
	}

	private fun clearFrameRate(surface: Surface, frameRate: Float?)
	{
		if(frameRate == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
			return
		try
		{
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
				surface.setFrameRate(
					0f,
					Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
					Surface.CHANGE_FRAME_RATE_ALWAYS
				)
			else
				surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
		}
		catch(e: RuntimeException)
		{
			Log.w("StreamSession", "Failed to clear surface frame rate", e)
		}
	}

	/**
	 * Attach to a custom Surface (e.g., from GLSurfaceView with debanding)
	 */
	fun attachToSurface(surface: Surface, display: Display? = null, refreshHz: Double? = null)
	{
		setSurface(surface, display, refreshHz)
	}

	private fun setSurface(surface: Surface, display: Display?, refreshHz: Double? = null)
	{
		this.surface = surface
		surfaceRefreshHz = refreshHz ?: display?.refreshRate?.toDouble() ?: connectInfo.videoProfile.maxFPS.toDouble()
		surfaceVsyncOffsetNanos = display?.appVsyncOffsetNanos ?: 0L
		session?.setSurface(surface, connectInfo.videoProfile.maxFPS, surfaceRefreshHz,
			surfaceVsyncOffsetNanos, nativePacingMode, videoPresenterLead, nativeMaxQueueAgePeriods)
	}

	fun updateDisplayTiming(refreshHz: Double, appVsyncOffsetNanos: Long)
	{
		if(surfaceRefreshHz == refreshHz && surfaceVsyncOffsetNanos == appVsyncOffsetNanos)
			return
		surfaceRefreshHz = refreshHz
		surfaceVsyncOffsetNanos = appVsyncOffsetNanos
		if(surface != null)
			session?.setTiming(connectInfo.videoProfile.maxFPS, surfaceRefreshHz,
				surfaceVsyncOffsetNanos, nativePacingMode, videoPresenterLead,
				nativeMaxQueueAgePeriods)
	}

	fun detachSurface()
	{
		this.surface = null
		session?.setSurface(null, connectInfo.videoProfile.maxFPS, surfaceRefreshHz,
			surfaceVsyncOffsetNanos, nativePacingMode, videoPresenterLead, nativeMaxQueueAgePeriods)
	}

	fun attachToTextureView(textureView: TextureView)
	{
		textureView.surfaceTextureListener = object: TextureView.SurfaceTextureListener {
			override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int)
			{
				if(surfaceTexture != null)
					return
				surfaceTexture = surface
				setSurface(Surface(surface), textureView.display)
			}

			override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean
			{
				// return false if we want to keep the surface texture
				return surfaceTexture == null
			}

			override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { }
			override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
		}

		val surfaceTexture = surfaceTexture
		if(surfaceTexture != null)
			textureView.setSurfaceTexture(surfaceTexture)
	}

	fun setLoginPin(pin: String)
	{
		session?.setLoginPin(pin)
	}
}
