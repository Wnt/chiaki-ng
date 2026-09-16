// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.content.res.ColorStateList
import android.hardware.display.DisplayManager
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.opengl.GLSurfaceView
import android.os.*
import android.util.Log
import android.view.*
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.BuildConfig
import com.metallic.chiaki.remote.ConnectPhase
import com.metallic.chiaki.remote.connectProgress
import com.metallic.chiaki.remote.detailText
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityStreamBinding
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.remote.PsnDevice
import com.metallic.chiaki.session.*
import com.metallic.chiaki.touchcontrols.DefaultTouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchControlsFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object UserQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()

@Suppress("DEPRECATION")
internal fun wifiLockModeForSdk(sdkInt: Int) =
	if(sdkInt >= Build.VERSION_CODES.Q)
		WifiManager.WIFI_MODE_FULL_LOW_LATENCY
	else
		WifiManager.WIFI_MODE_FULL_HIGH_PERF

class StreamActivity : AppCompatActivity()
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		const val EXTRA_PSN_DEVICE = "psn_device"
		/** The console was linked moments ago, so it may still refuse the session (PLE-335). */
		const val EXTRA_JUST_LINKED = "just_linked"
		const val EXTRA_DIAGNOSTICS_PREVIEW = "diagnostics_preview"
		const val EXTRA_STREAM_SUMMARY = "stream_summary"
		private const val HIDE_UI_TIMEOUT_MS = 3500L
		/** How often the connect overlay's second count is redrawn (PLE-337). */
		private const val CONNECT_PROGRESS_TICK_MS = 500L

		internal fun shouldRequestUnbufferedGamepadDispatch(source: Int, sdkInt: Int, enabled: Boolean): Boolean
		{
			if(!enabled || sdkInt < Build.VERSION_CODES.R)
				return false
			return source and InputDevice.SOURCE_CLASS_JOYSTICK == InputDevice.SOURCE_CLASS_JOYSTICK
		}

		internal fun performanceModeDiagnosticFlags(sustainedLive: Boolean, adpfLive: Boolean) =
			buildList {
				if(sustainedLive) add("perf-sustained")
				if(adpfLive) add("perf-adpf")
			}
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding
	private lateinit var insetsController: WindowInsetsControllerCompat
	private var originalPreferredDisplayModeId: Int? = null
	private var performanceModeRequested = false
	private var sustainedPerformanceModeEnabled = false
	private var sustainedPerformanceModeRefusalLogged = false
	private var wifiLock: WifiManager.WifiLock? = null
	private var diagnosticsOverlay: StreamDiagnosticsOverlay? = null
	private var displayManager: DisplayManager? = null
	private var displayListener: DisplayManager.DisplayListener? = null
	private val summaryAccumulator = StreamSummaryAccumulator()
	private var quitEndReason: StreamEndReason? = null
	private var summaryResultSet = false
	private val networkQualityClassifier = NetworkQualityClassifier()
	private var networkQuality = NetworkQualitySnapshot.UNKNOWN
	private var networkQualityDetailsExpanded = false
	private var streamTransformMode = TransformMode.FIT
	private var touchControlsFragment: TouchControlsFragment? = null
	private var lastWindowInsets: WindowInsetsCompat? = null
	private var lastLayoutBoundsLog: String? = null

	private val uiVisibilityHandler = Handler(Looper.getMainLooper())

	// PLE-337: which wait the connect is in, and when it started, so the overlay can count.
	private var connectPhase: ConnectPhase? = null
	private var connectPhaseStartedAt = 0L
	private val connectProgressTick = object: Runnable
	{
		override fun run()
		{
			renderConnectProgress()
			uiVisibilityHandler.postDelayed(this, CONNECT_PROGRESS_TICK_MS)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		val diagnosticsPreview = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DIAGNOSTICS_PREVIEW, false)
		val connectInfo = IntentCompat.getParcelableExtra(intent, EXTRA_CONNECT_INFO, ConnectInfo::class.java)
			?: if(diagnosticsPreview) diagnosticsPreviewConnectInfo() else null
		val psnDevice = IntentCompat.getParcelableExtra(intent, EXTRA_PSN_DEVICE, PsnDevice::class.java)
		val justLinked = intent.getBooleanExtra(EXTRA_JUST_LINKED, false)
		if(connectInfo == null)
		{
			finish()
			return
		}

		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, connectInfo, psnDevice, diagnosticsPreview, justLinked)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)
		prepareWindowTouchLayout()
		performanceModeRequested = connectInfo.performanceModeEnabled

		val preferences = Preferences(this)
		WindowCompat.setDecorFitsSystemWindows(window, false)
		if(preferences.streamWindowOptimizationsEnabled)
			configureWindowOptimizations()
		val displayRefreshRateMode = effectiveDisplayRefreshRateMode(
			preferences.displayRefreshRateMode,
			preferences.realVideoTimestamps,
			preferences.videoPacingEnabled
		)
		configureDisplayRefreshRate(displayRefreshRateMode, connectInfo.videoProfile.maxFPS.toFloat())
		insetsController = WindowCompat.getInsetsController(window, window.decorView)
		insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

		ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
			lastWindowInsets = insets
			applyOverlayInsets(insets)
			val systemBars = insets.isVisible(WindowInsetsCompat.Type.systemBars())
			if(systemBars)
				showOverlay()
			insets
		}
		binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
			applyWindowTouchLayout()
		}

		viewModel.onScreenControlsEnabled.observe(this, Observer {
			if(binding.controllerButton.isChecked != it)
				binding.controllerButton.isChecked = it
		})
		binding.controllerButton.addOnCheckedChangeListener { _, isChecked ->
			viewModel.setOnScreenControlsEnabled(isChecked)
			showOverlay()
		}
		binding.streamMenuButton.setOnClickListener { showDisplayModeMenu() }
		binding.networkQualityChip.setOnClickListener {
			if(networkQuality.level != NetworkQualityLevel.UNKNOWN)
				networkQualityDetailsExpanded = !networkQualityDetailsExpanded
			updateNetworkQualityChip()
			showOverlay()
		}
		binding.quitButton.setOnClickListener { showQuitConfirmation() }
		binding.aspectRatioLayout.setOnClickListener { showOverlay() }

// Setup video output based on debanding preference
		setupVideoOutput()
		
		val prefs = Preferences(this)
		if (prefs.touchscreenTouchpadEnabled) {
			binding.streamTouchpadView.visibility = View.VISIBLE
			binding.streamTouchpadView.controllerState
				.onEach { streamTouchpadState.value = it }
				.launchIn(lifecycleScope)
		}

		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		if(diagnosticsPreview || preferences.streamDiagnosticsOverlayEnabled)
		{
			val overlay = StreamDiagnosticsOverlay(this) {
				diagnosticsUiState(preferences, connectInfo)
			}
			diagnosticsOverlay = overlay
			binding.root.post { overlay.show(binding.root) }
			Log.i("StreamActivity", "Stream diagnostics overlay enabled; redraw interval 1000 ms")
		}
		else
			Log.i("StreamActivity", "Stream diagnostics overlay disabled")
		viewModel.session.streamStats.observe(this) { stats ->
			val link = diagnosticsNetworkLink()
			summaryAccumulator.add(stats, link)
			networkQuality = networkQualityClassifier.update(stats, link)
			updateNetworkQualityChip()
			diagnosticsOverlay?.update(stats)
		}
		updateNetworkQualityChip()
		adjustStreamViewAspect()

		if(Preferences(this).rumbleEnabled)
		{
			val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
			viewModel.session.rumbleState.observe(this, Observer {
				val amplitude = min(255, (it.left.toInt() + it.right.toInt()) / 2)
				vibrator.cancel()
				if(amplitude == 0)
					return@Observer
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
					vibrator.vibrate(VibrationEffect.createOneShot(1000, amplitude))
				else
					vibrator.vibrate(1000)
			})
		}
	}

	private fun diagnosticsPreviewConnectInfo() = ConnectInfo(
		ps5 = true,
		host = "diagnostics-preview",
		registKey = byteArrayOf(),
		morning = byteArrayOf(),
		videoProfile = ConnectVideoProfile(1920, 1080, 60, 15_000, Codec.CODEC_H265),
		decoderLowLatencyEnabled = false,
		threadPriorityBoostEnabled = false,
		decoderLateFrameRecoveryEnabled = false,
		packetLossMax = 0.0,
		takionVideoPacketReorderingDisabled = false,
		streamDiagnosticsEnabled = true
	)

	private var controlsJob: Job? = null
	private var debandRenderer: DebandRenderer? = null
	private var eglRenderer: EglRenderer? = null
	private val streamTouchpadState = MutableStateFlow(com.metallic.chiaki.lib.ControllerState())

	private fun setupVideoOutput()
	{
		val prefs = Preferences(this)
		viewModel.session.detachSurface()

		if(prefs.debandingEnabled)
		{
			if(BuildConfig.CHIAKI_ANDROID_EGL_RENDERER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
				setupEglDebandOutput(prefs)
			else
				setupGlSurfaceViewDebandOutput(prefs)
		}
		else
		{
			// Straight to SurfaceView, no shader stage
			binding.surfaceView.visibility = View.VISIBLE
			binding.debandSurfaceView.visibility = View.GONE
			val frameRate = if(prefs.displayRefreshRateMode == Preferences.DisplayRefreshRateMode.MATCH_STREAM)
				viewModel.connectInfo.videoProfile.maxFPS.toFloat()
			else
				null
			viewModel.session.attachToSurfaceView(binding.surfaceView, frameRate)
		}
	}

	private fun setupEglDebandOutput(prefs: Preferences)
	{
		binding.surfaceView.visibility = View.VISIBLE
		binding.debandSurfaceView.visibility = View.GONE
		binding.surfaceView.holder.setFormat(PixelFormat.RGBA_8888)
		val renderer = EglRenderer(
			binding.surfaceView,
			viewModel.connectInfo.videoProfile.width,
			viewModel.connectInfo.videoProfile.height,
			prefs.sharpnessIntensity,
			onDecoderSurfaceReady = { surface ->
				viewModel.session.attachToSurface(surface, binding.surfaceView.display)
			},
			onDecoderSurfaceDestroyed = { viewModel.session.detachSurface() },
			onUnavailable = { failedRenderer ->
				if(eglRenderer === failedRenderer)
				{
					failedRenderer.release()
					eglRenderer = null
					setupGlSurfaceViewDebandOutput(prefs)
				}
			}
		)
		eglRenderer = renderer
		renderer.start()
	}

	private fun setupGlSurfaceViewDebandOutput(prefs: Preferences)
	{
		val renderWhenDirty = prefs.debandRenderWhenDirtyEnabled
		// Decode into a SurfaceTexture consumed by the established deband/RCAS renderer.
		binding.surfaceView.visibility = View.GONE
		binding.debandSurfaceView.visibility = View.VISIBLE
		debandRenderer = DebandRenderer(
			onSurfaceReady = { surface ->
				viewModel.session.attachToSurface(surface, binding.debandSurfaceView.display)
			},
			onRequestRender = { binding.debandSurfaceView.requestRender() },
			renderWhenDirty = renderWhenDirty
		)
		binding.debandSurfaceView.setEGLContextClientVersion(3)
		binding.debandSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 0, 0)
		binding.debandSurfaceView.holder.setFormat(PixelFormat.RGBA_8888)
		binding.debandSurfaceView.setRenderer(debandRenderer)
		debandRenderer?.sharpness = prefs.sharpnessIntensity
		// Default (flag off) keeps continuous rendering; the flag lets an A/B test measure
		// GPU/power savings from rendering only when the decoder delivers a new frame.
		binding.debandSurfaceView.renderMode = if(renderWhenDirty)
			GLSurfaceView.RENDERMODE_WHEN_DIRTY
		else
			GLSurfaceView.RENDERMODE_CONTINUOUSLY
	}

	@Suppress("DEPRECATION")
	private fun configureWindowOptimizations()
	{
		val attributes = window.attributes
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
			attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
			attributes.preferMinimalPostProcessing = true
		window.attributes = attributes
	}

	private fun configurePerformanceMode(enabled: Boolean)
	{
		if(!enabled)
		{
			if(sustainedPerformanceModeEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
				window.setSustainedPerformanceMode(false)
			sustainedPerformanceModeEnabled = false
			viewModel.session.setSustainedPerformanceModeLive(false)
			return
		}
		if(sustainedPerformanceModeEnabled)
			return
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
			&& getSystemService(PowerManager::class.java).isSustainedPerformanceModeSupported)
		{
			window.setSustainedPerformanceMode(true)
			sustainedPerformanceModeEnabled = true
			viewModel.session.setSustainedPerformanceModeLive(true)
			Log.i("StreamActivity", "Sustained performance mode enabled")
		}
		else
		{
			val reason = if(Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
				"requires Android 7 (API 24)"
			else
				"PowerManager reports unsupported"
			if(!sustainedPerformanceModeRefusalLogged)
			{
				sustainedPerformanceModeRefusalLogged = true
				Log.i("StreamActivity", "Sustained performance mode refused: $reason")
			}
		}
	}

	@Suppress("DEPRECATION")
	private fun configureWifiLock(enabled: Boolean)
	{
		if(!enabled)
		{
			wifiLock?.let { lock ->
				if(lock.isHeld)
					lock.release()
			}
			wifiLock = null
			return
		}
		if(wifiLock?.isHeld == true)
			return

		try
		{
			val mode = wifiLockModeForSdk(Build.VERSION.SDK_INT)
			val lock = getSystemService(WifiManager::class.java)
				.createWifiLock(mode, "$packageName:StreamWifiLowLatency")
			lock.setReferenceCounted(false)
			lock.acquire()
			wifiLock = lock
			Log.i("StreamActivity", "Wi-Fi lock acquired in mode $mode")
		}
		catch(e: RuntimeException)
		{
			Log.e("StreamActivity", "Failed to acquire Wi-Fi lock", e)
		}
	}

	private fun configureDisplayRefreshRate(mode: Preferences.DisplayRefreshRateMode, streamFrameRate: Float)
	{
		if(mode == Preferences.DisplayRefreshRateMode.SYSTEM_DEFAULT)
			return

		val display = windowManager.defaultDisplay
		val currentMode = display.mode
		val modesAtCurrentResolution = display.supportedModes.filter {
			it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight
		}
		val targetMode = when(mode)
		{
			Preferences.DisplayRefreshRateMode.MATCH_STREAM -> modesAtCurrentResolution
				.filter { abs(it.refreshRate - streamFrameRate) < 0.5f }
				.minByOrNull { abs(it.refreshRate - streamFrameRate) }
			Preferences.DisplayRefreshRateMode.HIGHEST -> modesAtCurrentResolution.maxByOrNull { it.refreshRate }
			Preferences.DisplayRefreshRateMode.SYSTEM_DEFAULT -> null
		}
		if(targetMode == null)
		{
			Log.w("StreamActivity", "No display mode for $mode at ${currentMode.physicalWidth}x${currentMode.physicalHeight}")
			return
		}

		val attributes = window.attributes
		originalPreferredDisplayModeId = attributes.preferredDisplayModeId
		attributes.preferredDisplayModeId = targetMode.modeId
		window.attributes = attributes
		Log.i("StreamActivity", "Requested display mode ${targetMode.modeId}: ${targetMode.physicalWidth}x${targetMode.physicalHeight}@${targetMode.refreshRate}")
	}

	private fun restoreDisplayRefreshRate()
	{
		val modeId = originalPreferredDisplayModeId ?: return
		val attributes = window.attributes
		attributes.preferredDisplayModeId = modeId
		window.attributes = attributes
		originalPreferredDisplayModeId = null
	}

	override fun onAttachFragment(fragment: Fragment)
	{
		super.onAttachFragment(fragment)
		if(fragment is TouchControlsFragment)
		{
			controlsJob?.cancel()
			controlsJob = combine(fragment.controllerState, streamTouchpadState) { a, b -> a or b }
				.onEach { viewModel.input.touchControllerState = it }
				.launchIn(lifecycleScope)
			fragment.onScreenControlsEnabled = viewModel.onScreenControlsEnabled
			fragment.overlayRevealRequested = ::showOverlay
			touchControlsFragment = fragment
		}
	}

	override fun onResume()
	{
		super.onResume()
		configurePerformanceMode(performanceModeRequested)
		configureWifiLock(Preferences(this).wifiLowLatencyLockEnabled)
		hideSystemUI()
		if(debandRenderer != null) {
			binding.debandSurfaceView.onResume()
			// In RENDERMODE_WHEN_DIRTY, onResume() alone won't redraw the last frame; force one
			// so the surface isn't left blank until the next decoded frame arrives.
			binding.debandSurfaceView.requestRender()
		}
		viewModel.resume()
		registerDisplayListener()
	}

	override fun onPause()
	{
		unregisterDisplayListener()
		configureWifiLock(false)
		super.onPause()
		configurePerformanceMode(false)
		if(debandRenderer != null) {
			binding.debandSurfaceView.onPause()
		}
		viewModel.pause()
	}

	private fun registerDisplayListener()
	{
		val preferences = Preferences(this)
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.N
				|| !presenterDisplayTimingUpdatesEnabled(
					preferences.displayRefreshRateMode,
					preferences.videoPacingEnabled
				)
				|| displayListener != null)
			return

		val manager = getSystemService(DisplayManager::class.java)
		val listener = object: DisplayManager.DisplayListener
		{
			override fun onDisplayAdded(displayId: Int) = Unit
			override fun onDisplayRemoved(displayId: Int) = Unit
			override fun onDisplayChanged(displayId: Int)
			{
				val streamDisplay = binding.root.display ?: windowManager.defaultDisplay
				if(displayId != streamDisplay.displayId)
					return
				manager.getDisplay(displayId)?.let(::updatePresenterDisplayTiming)
			}
		}
		displayManager = manager
		displayListener = listener
		manager.registerDisplayListener(listener, uiVisibilityHandler)
		val streamDisplay = binding.root.display ?: windowManager.defaultDisplay
		updatePresenterDisplayTiming(manager.getDisplay(streamDisplay.displayId) ?: streamDisplay)
	}

	private fun unregisterDisplayListener()
	{
		displayListener?.let { displayManager?.unregisterDisplayListener(it) }
		displayListener = null
		displayManager = null
	}

	private fun updatePresenterDisplayTiming(display: Display)
	{
		val refreshHz = display.mode.refreshRate.toDouble()
		if(viewModel.session.updateDisplayTiming(refreshHz, display.appVsyncOffsetNanos))
			Log.i("StreamActivity", "Display timing changed: ${"%.2f".format(Locale.US, refreshHz)} Hz")
	}

	override fun onConfigurationChanged(newConfig: Configuration)
	{
		super.onConfigurationChanged(newConfig)
		viewModel.input.refreshDisplayRotation()
		binding.root.post(::applyWindowTouchLayout)
	}

	override fun onDestroy()
	{
		uiVisibilityHandler.removeCallbacks(connectProgressTick)
		diagnosticsOverlay?.destroy()
		diagnosticsOverlay = null
		configureWifiLock(false)
		configurePerformanceMode(false)
		restoreDisplayRefreshRate()
		super.onDestroy()
		controlsJob?.cancel()
		debandRenderer?.let { renderer ->
			// GL teardown must happen on the GL thread, while the context is current
			binding.debandSurfaceView.queueEvent { renderer.releaseGl() }
			renderer.release()
		}
		debandRenderer = null
		eglRenderer?.release()
		eglRenderer = null
	}

	@Suppress("DEPRECATION")
	private fun diagnosticsUiState(preferences: Preferences, connectInfo: ConnectInfo): StreamDiagnosticsUiState
	{
		val display = binding.root.display ?: windowManager.defaultDisplay
		val mode = display.mode
		val effectiveRefreshRateMode = effectiveDisplayRefreshRateMode(
			preferences.displayRefreshRateMode,
			preferences.realVideoTimestamps,
			preferences.videoPacingEnabled
		)
		val flags = buildList {
			if(connectInfo.decoderLowLatencyEnabled) add("lowlat")
			if(preferences.wifiLowLatencyLockEnabled) add("wifi-lowlat")
			if(preferences.realVideoTimestamps) add("pts")
			if(preferences.decoderInputThreadEnabled) add("in-thread")
			if(connectInfo.decoderLateFrameRecoveryEnabled) add("late-drop")
			if(preferences.videoPacingEnabled)
			{
				add("pacing")
				add(if(preferences.videoPacingHighRefreshEnabled) "hi-hz-on" else "hi-hz-gate")
				if(preferences.videoPacingMode != Preferences.VideoPacingMode.LOWEST_LATENCY
					&& !preferences.videoPacingHighRefreshEnabled && mode.refreshRate >= 119.0f)
					add("immediate-release")
			}
			when(effectiveRefreshRateMode)
			{
				Preferences.DisplayRefreshRateMode.MATCH_STREAM -> add("hz-match")
				Preferences.DisplayRefreshRateMode.HIGHEST -> add("hz-max")
				Preferences.DisplayRefreshRateMode.SYSTEM_DEFAULT -> add("hz-system")
			}
			if(preferences.videoPacingEnabled && preferences.videoPacingBoundedAgeEnabled)
				add("age-${preferences.videoPacingMaxFrameAgePeriods}f")
			if(preferences.videoPacingEnabled
				&& preferences.videoRecoveryStrategy == Preferences.VideoRecoveryStrategy.FLUSH)
				add("flush-rec")
			if(connectInfo.takionVideoPacketReorderingDisabled) add("reorder-off")
			if(preferences.feedbackReducedIntervalEnabled) add("fb4ms")
			if(preferences.feedbackStatsLogEnabled) add("fb-log")
			if(connectInfo.threadPriorityBoostEnabled) add("prio")
			addAll(performanceModeDiagnosticFlags(
				sustainedPerformanceModeEnabled,
				viewModel.session.session?.adpfPerformanceModeLive == true
			))
			if(preferences.debandingEnabled) add("deband")
			if(preferences.debandRenderWhenDirtyEnabled) add("dirty")
			if(preferences.streamWindowOptimizationsEnabled) add("window")
			if(preferences.controllerInputCoalescingEnabled) add("input-coal")
			if(preferences.gamepadUnbufferedDispatchEnabled) add("gamepad-unbuf")
		}
		return StreamDiagnosticsUiState(
			display = StreamDiagnosticsDisplay(
				width = mode.physicalWidth,
				height = mode.physicalHeight,
				refreshRate = mode.refreshRate,
				modeId = mode.modeId
			),
			viewMode = streamTransformMode.name.lowercase(Locale.US),
			flags = flags,
			presenterMode = if(preferences.videoPacingEnabled) preferences.videoPacingMode.value else null,
			networkLink = diagnosticsNetworkLink()
		)
	}

	@Suppress("DEPRECATION")
	private fun diagnosticsNetworkLink(): NetworkLinkSample
	{
		val connectivity = getSystemService(ConnectivityManager::class.java)
		val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
		if(capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true)
			return NetworkLinkSample(
				if(capabilities == null) NetworkLinkType.UNKNOWN else NetworkLinkType.OTHER
			)
		return runCatching {
			val info = getSystemService(WifiManager::class.java).connectionInfo
			NetworkLinkSample(
				type = NetworkLinkType.WIFI,
				rssiDbm = info.rssi.takeIf { it in -126..-1 },
				linkSpeedMbps = info.linkSpeed.takeIf { it > 0 }
			)
		}.getOrDefault(NetworkLinkSample(NetworkLinkType.WIFI))
	}

	private fun reconnect()
	{
		viewModel.pause()
		viewModel.resume()
	}

	private val hideSystemUIRunnable = Runnable {
		hideOverlay()
		hideSystemUI()
	}

	private fun applyOverlayInsets(insets: WindowInsetsCompat)
	{
		val safe = insets.getInsetsIgnoringVisibility(
			WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
		)
		val gestures = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
		val baseMargin = (12 * resources.displayMetrics.density).toInt()
		val controlsTop = portraitControlsTop()
		binding.streamControlDock.updateLayoutParams<ViewGroup.MarginLayoutParams> {
			topMargin = baseMargin + if(controlsTop > 0) controlsTop else maxOf(safe.top, gestures.top)
			marginEnd = baseMargin + maxOf(safe.right, gestures.right)
		}
	}

	private fun prepareWindowTouchLayout()
	{
		if(binding.streamTouchpadView.parent === binding.root)
			return
		(binding.streamTouchpadView.parent as ViewGroup).removeView(binding.streamTouchpadView)
		val videoIndex = binding.root.indexOfChild(binding.aspectRatioLayout)
		binding.root.addView(
			binding.streamTouchpadView,
			videoIndex + 1,
			FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT
			)
		)
	}

	private fun portraitControlsTop(): Int
	{
		if(binding.root.width <= 0 || binding.root.height <= binding.root.width)
			return 0
		val ratio = binding.aspectRatioLayout.aspectRatio
		return if(ratio > 0f) (binding.root.width / ratio).toInt().coerceAtMost(binding.root.height) else 0
	}

	private fun applyWindowTouchLayout()
	{
		if(binding.root.width <= 0)
			return
		val controlsTop = portraitControlsTop()
		val portrait = controlsTop > 0
		val videoGravity = if(portrait) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
		val videoParams = binding.aspectRatioLayout.layoutParams as FrameLayout.LayoutParams
		if(videoParams.gravity != videoGravity)
		{
			videoParams.gravity = videoGravity
			binding.aspectRatioLayout.layoutParams = videoParams
		}
		updateWindowTouchRegion(binding.streamTouchpadView, controlsTop)
		findViewById<View>(R.id.controlsFragment)?.let { updateWindowTouchRegion(it, controlsTop) }
		touchControlsFragment?.controlsBelowVideo = portrait
		lastWindowInsets?.let(::applyOverlayInsets)
		binding.root.post(::logWindowTouchBounds)
	}

	private fun updateWindowTouchRegion(view: View, top: Int)
	{
		val params = view.layoutParams as FrameLayout.LayoutParams
		if(params.width == ViewGroup.LayoutParams.MATCH_PARENT
			&& params.height == ViewGroup.LayoutParams.MATCH_PARENT
			&& params.topMargin == top
			&& params.bottomMargin == 0
			&& params.gravity == Gravity.TOP)
			return
		params.width = ViewGroup.LayoutParams.MATCH_PARENT
		params.height = ViewGroup.LayoutParams.MATCH_PARENT
		params.topMargin = top
		params.bottomMargin = 0
		params.gravity = Gravity.TOP
		view.layoutParams = params
	}

	private fun logWindowTouchBounds()
	{
		fun View.boundsString(): String
		{
			val bounds = Rect()
			getGlobalVisibleRect(bounds)
			return "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"
		}
		val controls = findViewById<View>(R.id.controlsFragment) ?: return
		val message = "PLE-244 bounds orientation=${if(binding.root.height > binding.root.width) "portrait" else "landscape"} " +
			"window=${binding.root.boundsString()} video=${binding.aspectRatioLayout.boundsString()} " +
			"controls=${controls.boundsString()}"
		if(message != lastLayoutBoundsLog)
		{
			lastLayoutBoundsLog = message
			Log.i("StreamActivity", message)
		}
	}

	internal fun showOverlay()
	{
		binding.overlay.animate().setListener(null)
		binding.overlay.animate().cancel()
		binding.overlay.isVisible = true
		binding.overlay.animate()
			.alpha(1.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.alpha = 1.0f
				}
			})
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
	}

	private fun hideOverlay()
	{
		if(!binding.overlay.isVisible)
			return
		networkQualityDetailsExpanded = false
		updateNetworkQualityChip()
		binding.overlay.animate().setListener(null)
		binding.overlay.animate().cancel()
		binding.overlay.animate()
			.alpha(0.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.isGone = true
				}
			})
	}

	private fun showDisplayModeMenu()
	{
		PopupMenu(this, binding.streamMenuButton).also { menu ->
			menu.inflate(R.menu.stream_display_mode)
			menu.menu.findItem(when(streamTransformMode)
			{
				TransformMode.FIT -> R.id.display_mode_normal_button
				TransformMode.ZOOM -> R.id.display_mode_zoom_button
				TransformMode.STRETCH -> R.id.display_mode_stretch_button
			}).isChecked = true
			menu.setOnMenuItemClickListener { item ->
				streamTransformMode = TransformMode.fromButton(item.itemId)
				item.isChecked = true
				adjustStreamViewAspect()
				showOverlay()
				true
			}
			menu.show()
		}
		showOverlay()
	}

	private fun updateNetworkQualityChip()
	{
		val content = NetworkQualityChipPresenter.content(networkQuality, networkQualityDetailsExpanded)
		val label = getString(when(content.level)
		{
			NetworkQualityLevel.GOOD -> R.string.network_quality_good
			NetworkQualityLevel.CONSTRAINED -> R.string.network_quality_fair
			NetworkQualityLevel.POOR -> R.string.network_quality_poor
			NetworkQualityLevel.UNKNOWN -> R.string.network_quality_unknown
		})
		val color = when(content.level)
		{
			NetworkQualityLevel.GOOD -> R.color.stream_quality_good
			NetworkQualityLevel.CONSTRAINED -> R.color.stream_quality_fair
			NetworkQualityLevel.POOR -> R.color.stream_quality_poor
			NetworkQualityLevel.UNKNOWN -> R.color.stream_quality_unknown
		}
		binding.networkQualityChip.chipBackgroundColor = ColorStateList.valueOf(
			ContextCompat.getColor(this, color)
		)
		val foreground = if(content.level == NetworkQualityLevel.CONSTRAINED)
			ContextCompat.getColor(this, android.R.color.black)
		else ContextCompat.getColor(this, R.color.stream_text)
		binding.networkQualityChip.setTextColor(foreground)
		binding.networkQualityChip.chipIconTint = ColorStateList.valueOf(foreground)
		binding.networkQualityChip.text = content.metrics?.let { metrics ->
			getString(
				R.string.stream_quality_details,
				metrics.rttMillis,
				metrics.jitterMillis,
				metrics.lossPercent
			)
		} ?: label
		binding.networkQualityChip.contentDescription = if(content.metrics == null)
			label else "$label, ${binding.networkQualityChip.text}"
	}

	private fun showQuitConfirmation()
	{
		if(dialogContents == UserQuitDialog)
			return
		dialog?.dismiss()
		val confirmation = MaterialAlertDialogBuilder(this)
			.setTitle(R.string.stream_quit_title)
			.setNegativeButton(android.R.string.cancel) { _, _ -> dialog = null }
			.setPositiveButton(R.string.action_quit_session) { _, _ ->
				dialog = null
				finish()
			}
			.setOnCancelListener { dialog = null }
			.create()
		dialogContents = UserQuitDialog
		dialog = confirmation
		confirmation.show()
	}

	/**
	 * PLE-337: the connect overlay. [phase] changing restarts the clock; the same phase repeating -
	 * which is exactly what a handoff retry does - leaves it running, so the count keeps rising
	 * across every poll of the console instead of resetting to zero each time.
	 */
	private fun setConnectPhase(phase: ConnectPhase?)
	{
		if(phase != connectPhase)
		{
			connectPhase = phase
			connectPhaseStartedAt = SystemClock.elapsedRealtime()
		}
		uiVisibilityHandler.removeCallbacks(connectProgressTick)
		renderConnectProgress()
		if(phase != null)
			uiVisibilityHandler.postDelayed(connectProgressTick, CONNECT_PROGRESS_TICK_MS)
	}

	private fun renderConnectProgress()
	{
		val phase = connectPhase
		if(phase == null)
		{
			binding.connectingStatusText.visibility = View.GONE
			binding.connectingDetailText.visibility = View.GONE
			return
		}
		val progress = connectProgress(phase, SystemClock.elapsedRealtime() - connectPhaseStartedAt,
			showStep = viewModel.justLinked)
		binding.connectingStatusText.visibility = View.VISIBLE
		binding.connectingStatusText.setText(phase.labelRes)
		binding.connectingDetailText.visibility = View.VISIBLE
		binding.connectingDetailText.text = progress.detailText(this)
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		if(hasFocus)
			hideSystemUI()
	}

	private fun hideSystemUI()
	{
		insetsController.hide(WindowInsetsCompat.Type.systemBars())
	}

	private var dialogContents: DialogContents? = null
	private var dialog: AlertDialog? = null
		set(value)
		{
			field = value
			if(value == null)
				dialogContents = null
		}

	private fun stateChanged(state: StreamState)
	{
		val connecting = state == StreamStateConnecting || state == StreamStateLinkedStarting
		binding.progressBar.visibility = if(connecting) View.VISIBLE else View.GONE
		// PLE-335/PLE-337: a console that has just been linked needs a moment before it accepts the
		// stream. Name that wait and keep a clock on it, rather than leaving a bare spinner or - as
		// before - raising "Session has quit".
		setConnectPhase(when(state)
		{
			StreamStateLinkedStarting -> ConnectPhase.CONSOLE_NOT_READY
			StreamStateConnecting -> ConnectPhase.STARTING_STREAM
			else -> null
		})
		if(state == StreamStateConnected)
			summaryAccumulator.connected(SystemClock.elapsedRealtime())

		when(state)
		{
			is StreamStateQuit ->
			{
				summaryAccumulator.ended(SystemClock.elapsedRealtime())
				quitEndReason = StreamEndReason(state.reason.value, state.reasonString,
					viewModel.session.connectInfo.host, viewModel.session.connectInfo.ps5)
				if(dialogContents != StreamQuitDialog)
				{
					if(state.reason.isError)
					{
						dialog?.dismiss()
						val reasonStr = state.reasonString
						val dialog = MaterialAlertDialogBuilder(this)
							.setMessage(getString(R.string.alert_message_session_quit, state.reason.toString())
									+ (if(reasonStr != null) "\n$reasonStr" else ""))
							.setPositiveButton(R.string.action_reconnect) { _, _ ->
								dialog = null
								reconnect()
							}
							.setOnCancelListener {
								dialog = null
								finish()
							}
							.setNegativeButton(R.string.action_quit_session) { _, _ ->
								dialog = null
								finish()
							}
							.create()
						dialogContents = StreamQuitDialog
						dialog.show()
					}
					else
						finish()
				}
			}

			is StreamStateCreateError ->
			{
				if(dialogContents != CreateErrorDialog)
				{
					dialog?.dismiss()
					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(getString(R.string.alert_message_session_create_error, state.error.errorCode.toString()))
						.setOnDismissListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ -> }
						.create()
					dialogContents = CreateErrorDialog
					dialog.show()
				}
			}

			is StreamStateRemoteError ->
			{
				if(dialogContents != CreateErrorDialog)
				{
					dialog?.dismiss()
					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(getString(R.string.alert_message_psn_remote_error, state.message))
						.setOnDismissListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ -> }
						.create()
					dialogContents = CreateErrorDialog
					dialog.show()
				}
			}

			is StreamStateLoginPinRequest ->
			{
				if(dialogContents != PinRequestDialog)
				{
					dialog?.dismiss()

					val view = layoutInflater.inflate(R.layout.dialog_login_pin, null)
					val pinEditText = view.findViewById<EditText>(R.id.pinEditText)

					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(
							if(state.pinIncorrect)
								R.string.alert_message_login_pin_request_incorrect
							else
								R.string.alert_message_login_pin_request)
						.setView(view)
						.setPositiveButton(R.string.action_login_pin_connect) { _, _ ->
							dialog = null
							viewModel.session.setLoginPin(pinEditText.text.toString())
						}
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
						.create()
					dialogContents = PinRequestDialog
					dialog.show()
				}
			}
			else ->{}
		}
	}

	override fun finish()
	{
		if(!summaryResultSet)
		{
			summaryAccumulator.build(SystemClock.elapsedRealtime())?.let { summary ->
				setResult(RESULT_OK, Intent().putExtra(EXTRA_STREAM_SUMMARY, summary.copy(endReason = quitEndReason)))
				summaryResultSet = true
			}
		}
		super.finish()
	}

	private fun adjustTextureViewAspect(textureView: TextureView)
	{
		val trans = TextureViewTransform(viewModel.session.connectInfo.videoProfile, textureView)
		val resolution = trans.resolutionFor(streamTransformMode)
		Matrix().also {
			textureView.getTransform(it)
			it.setScale(resolution.width / trans.viewWidth, resolution.height / trans.viewHeight)
			it.postTranslate((trans.viewWidth - resolution.width) * 0.5f, (trans.viewHeight - resolution.height) * 0.5f)
			textureView.setTransform(it)
		}
	}

	private fun adjustSurfaceViewAspect()
	{
		val videoProfile = viewModel.session.connectInfo.videoProfile
		binding.aspectRatioLayout.aspectRatio = videoProfile.width.toFloat() / videoProfile.height.toFloat()
		binding.aspectRatioLayout.mode = streamTransformMode
	}

	private fun adjustStreamViewAspect() = adjustSurfaceViewAspect()

	override fun dispatchKeyEvent(event: KeyEvent) = viewModel.input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

	override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean
	{
		requestUnbufferedGamepadDispatchIfEnabled(event)
		return super.dispatchGenericMotionEvent(event)
	}

	override fun onGenericMotionEvent(event: MotionEvent) = viewModel.input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)

	// PLE-91: on the buffered path Android holds a joystick axis change for up to one input-batch
	// interval (8-16 ms) before dispatchGenericMotionEvent sees it; requesting unbuffered dispatch
	// removes that wait. The MotionEvent overload of requestUnbufferedDispatch is documented for
	// touch events only (it has existed since API 21), so joystick/gamepad sources need the
	// source-class overload, which the platform added only in API 30 (confirmed against
	// android-35's api-versions.xml, not the API 26/31 levels quoted in earlier notes).
	private fun requestUnbufferedGamepadDispatchIfEnabled(event: MotionEvent)
	{
		val shouldRequest = shouldRequestUnbufferedGamepadDispatch(
			source = event.source,
			sdkInt = Build.VERSION.SDK_INT,
			enabled = Preferences(this).gamepadUnbufferedDispatchEnabled
		)
		if(shouldRequest)
			window.decorView.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK)
	}
}

enum class TransformMode
{
	FIT,
	STRETCH,
	ZOOM;

	companion object
	{
		fun fromButton(displayModeButtonId: Int)
			= when (displayModeButtonId)
			{
				R.id.display_mode_stretch_button -> STRETCH
				R.id.display_mode_zoom_button -> ZOOM
				else -> FIT
			}
	}
}

class TextureViewTransform(private val videoProfile: ConnectVideoProfile, private val textureView: TextureView)
{
	private val contentWidth : Float get() = videoProfile.width.toFloat()
	private val contentHeight : Float get() = videoProfile.height.toFloat()
	val viewWidth : Float get() = textureView.width.toFloat()
	val viewHeight : Float get() = textureView.height.toFloat()
	private val contentAspect : Float get() =  contentHeight / contentWidth

	fun resolutionFor(mode: TransformMode): Resolution
		= when(mode)
		{
			TransformMode.STRETCH -> strechedResolution
			TransformMode.ZOOM -> zoomedResolution
			TransformMode.FIT -> normalResolution
		}

	private val strechedResolution get() = Resolution(viewWidth, viewHeight)

	private val zoomedResolution get() =
		if(viewHeight > viewWidth * contentAspect)
		{
			val zoomFactor = viewHeight / contentHeight
			Resolution(contentWidth * zoomFactor, viewHeight)
		}
		else
		{
			val zoomFactor = viewWidth / contentWidth
			Resolution(viewWidth, contentHeight * zoomFactor)
		}

	private val normalResolution get() =
		if(viewHeight > viewWidth * contentAspect)
			Resolution(viewWidth, viewWidth * contentAspect)
		else
			Resolution(viewHeight / contentAspect, viewHeight)
}


data class Resolution(val width: Float, val height: Float)
