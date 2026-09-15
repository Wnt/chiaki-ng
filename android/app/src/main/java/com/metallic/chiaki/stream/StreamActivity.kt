// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.*
import android.util.Log
import android.view.*
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityStreamBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.session.*
import com.metallic.chiaki.touchcontrols.DefaultTouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchControlsFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.abs
import kotlin.math.min

private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()

class StreamActivity : AppCompatActivity()
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		private const val HIDE_UI_TIMEOUT_MS = 2000L
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding
	private lateinit var insetsController: WindowInsetsControllerCompat
	private var originalPreferredDisplayModeId: Int? = null

	private val uiVisibilityHandler = Handler(Looper.getMainLooper())

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		val connectInfo = IntentCompat.getParcelableExtra(intent, EXTRA_CONNECT_INFO, ConnectInfo::class.java)
		if(connectInfo == null)
		{
			finish()
			return
		}

		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, connectInfo)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)

		WindowCompat.setDecorFitsSystemWindows(window, false)
		configureDisplayRefreshRate(Preferences(this).displayRefreshRateMode, connectInfo.videoProfile.maxFPS.toFloat())
		insetsController = WindowCompat.getInsetsController(window, window.decorView)
		insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

		ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
			val systemBars = insets.isVisible(WindowInsetsCompat.Type.systemBars())
			if(systemBars)
				showOverlay()
			else
				hideOverlay()
			insets
		}

		viewModel.onScreenControlsEnabled.observe(this, Observer {
			if(binding.onScreenControlsSwitch.isChecked != it)
				binding.onScreenControlsSwitch.isChecked = it
		})
		binding.onScreenControlsSwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setOnScreenControlsEnabled(isChecked)
			showOverlay()
		}


		binding.displayModeToggle.addOnButtonCheckedListener { _, _, _ ->
			adjustStreamViewAspect()
			showOverlay()
		}

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

	private var controlsJob: Job? = null
	private var debandRenderer: DebandRenderer? = null
	private val streamTouchpadState = MutableStateFlow(com.metallic.chiaki.lib.ControllerState())

	private fun setupVideoOutput()
	{
		val prefs = Preferences(this)
		viewModel.session.detachSurface()

		if(prefs.debandingEnabled)
		{
			// Decode into a SurfaceTexture consumed by the deband/RCAS GL renderer
			binding.surfaceView.visibility = View.GONE
			binding.debandSurfaceView.visibility = View.VISIBLE

			debandRenderer = DebandRenderer { surface ->
				viewModel.session.attachToSurface(surface)
			}

			binding.debandSurfaceView.setEGLContextClientVersion(3)
			binding.debandSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 0, 0)
			binding.debandSurfaceView.holder.setFormat(PixelFormat.RGBA_8888)
			binding.debandSurfaceView.setRenderer(debandRenderer)
			debandRenderer?.sharpness = prefs.sharpnessIntensity
			binding.debandSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
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

	@Suppress("DEPRECATION")
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
		}
	}

	override fun onResume()
	{
		super.onResume()
		hideSystemUI()
		if (Preferences(this).debandingEnabled) {
			binding.debandSurfaceView.onResume()
		}
		viewModel.session.resume()
	}

	override fun onPause()
	{
		super.onPause()
		if (Preferences(this).debandingEnabled) {
			binding.debandSurfaceView.onPause()
		}
		viewModel.session.pause()
	}

	override fun onConfigurationChanged(newConfig: Configuration)
	{
		super.onConfigurationChanged(newConfig)
		viewModel.input.refreshDisplayRotation()
	}

	override fun onDestroy()
	{
		restoreDisplayRefreshRate()
		super.onDestroy()
		controlsJob?.cancel()
		debandRenderer?.let { renderer ->
			// GL teardown must happen on the GL thread, while the context is current
			binding.debandSurfaceView.queueEvent { renderer.releaseGl() }
			renderer.release()
		}
		debandRenderer = null
	}

	private fun reconnect()
	{
		viewModel.session.shutdown()
		viewModel.session.resume()
	}

	private val hideSystemUIRunnable = Runnable { hideSystemUI() }

	private fun showOverlay()
	{
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
		binding.progressBar.visibility = if(state == StreamStateConnecting) View.VISIBLE else View.GONE

		when(state)
		{
			is StreamStateQuit ->
			{
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

	private fun adjustTextureViewAspect(textureView: TextureView)
	{
		val trans = TextureViewTransform(viewModel.session.connectInfo.videoProfile, textureView)
		val resolution = trans.resolutionFor(TransformMode.fromButton(binding.displayModeToggle.checkedButtonId))
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
		binding.aspectRatioLayout.mode = TransformMode.fromButton(binding.displayModeToggle.checkedButtonId)
	}

	private fun adjustStreamViewAspect() = adjustSurfaceViewAspect()

	override fun dispatchKeyEvent(event: KeyEvent) = viewModel.input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
	override fun onGenericMotionEvent(event: MotionEvent) = viewModel.input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)
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
