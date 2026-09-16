// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

class Preferences(context: Context)
{
	enum class Resolution(val value: String, @StringRes val title: Int, val preset: VideoResolutionPreset)
	{
		RES_360P("360p", R.string.preferences_resolution_title_360p, VideoResolutionPreset.RES_360P),
		RES_540P("540p", R.string.preferences_resolution_title_540p, VideoResolutionPreset.RES_540P),
		RES_720P("720p", R.string.preferences_resolution_title_720p, VideoResolutionPreset.RES_720P),
		RES_1080P("1080p", R.string.preferences_resolution_title_1080p, VideoResolutionPreset.RES_1080P),
	}

	enum class FPS(val value: String, @StringRes val title: Int, val preset: VideoFPSPreset)
	{
		FPS_30("30", R.string.preferences_fps_title_30, VideoFPSPreset.FPS_30),
		FPS_60("60", R.string.preferences_fps_title_60, VideoFPSPreset.FPS_60)
	}

	enum class DisplayRefreshRateMode(val value: String, @StringRes val title: Int)
	{
		SYSTEM_DEFAULT("system_default", R.string.preferences_display_refresh_rate_title_system_default),
		MATCH_STREAM("match_stream", R.string.preferences_display_refresh_rate_title_match_stream),
		HIGHEST("highest", R.string.preferences_display_refresh_rate_title_highest)
	}

	enum class Codec(val value: String, @StringRes val title: Int, val codec: com.metallic.chiaki.lib.Codec)
	{
		CODEC_H264("h264", R.string.preferences_codec_title_h264, com.metallic.chiaki.lib.Codec.CODEC_H264),
		CODEC_H265("h265", R.string.preferences_codec_title_h265, com.metallic.chiaki.lib.Codec.CODEC_H265)
	}

	companion object
	{
		val resolutionDefault = Resolution.RES_720P
		val resolutionAll = Resolution.values()
		val fpsDefault = FPS.FPS_60
		val fpsAll = FPS.values()
		val displayRefreshRateModeDefault = DisplayRefreshRateMode.SYSTEM_DEFAULT
		val displayRefreshRateModeAll = DisplayRefreshRateMode.values()
		val codecDefault = Codec.CODEC_H265
		val codecAll = Codec.values()
		const val packetLossMaxPercentDefault = 5
	}

	internal val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
	private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		when(key)
		{
			resolutionKey -> _bitrateAutoFlow.value = bitrateAuto
		}
	}.also { sharedPreferences.registerOnSharedPreferenceChangeListener(it) }

	private val resources = context.resources

	val discoveryEnabledKey get() = resources.getString(R.string.preferences_discovery_enabled_key)
	var discoveryEnabled
		get() = sharedPreferences.getBoolean(discoveryEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(discoveryEnabledKey, value).apply() }

	val psnSignInEnabledKey get() = resources.getString(R.string.preferences_psn_sign_in_enabled_key)
	var psnSignInEnabled
		get() = sharedPreferences.getBoolean(psnSignInEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(psnSignInEnabledKey, value).apply() }

	private val psnAccountIdKey get() = resources.getString(R.string.preferences_psn_account_id_key)
	var psnAccountId: String?
		get() = sharedPreferences.getString(psnAccountIdKey, null)
		set(value) { sharedPreferences.edit().putString(psnAccountIdKey, value).apply() }

	val onScreenControlsEnabledKey get() = resources.getString(R.string.preferences_on_screen_controls_enabled_key)
	var onScreenControlsEnabled
		get() = sharedPreferences.getBoolean(onScreenControlsEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(onScreenControlsEnabledKey, value).apply() }


	val rumbleEnabledKey get() = resources.getString(R.string.preferences_rumble_enabled_key)
	var rumbleEnabled
		get() = sharedPreferences.getBoolean(rumbleEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(rumbleEnabledKey, value).apply() }

	val motionEnabledKey get() = resources.getString(R.string.preferences_motion_enabled_key)
	var motionEnabled
		get() = sharedPreferences.getBoolean(motionEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(motionEnabledKey, value).apply() }

	val buttonHapticEnabledKey get() = resources.getString(R.string.preferences_button_haptic_enabled_key)
	var buttonHapticEnabled
		get() = sharedPreferences.getBoolean(buttonHapticEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(buttonHapticEnabledKey, value).apply() }

	val logVerboseKey get() = resources.getString(R.string.preferences_log_verbose_key)
	var logVerbose
		get() = sharedPreferences.getBoolean(logVerboseKey, false)
		set(value) { sharedPreferences.edit().putBoolean(logVerboseKey, value).apply() }

	val swapCrossMoonKey get() = resources.getString(R.string.preferences_swap_cross_moon_key)
	var swapCrossMoon
		get() = sharedPreferences.getBoolean(swapCrossMoonKey, false)
		set(value) { sharedPreferences.edit().putBoolean(swapCrossMoonKey, value).apply() }

	val debandingEnabledKey get() = resources.getString(R.string.preferences_debanding_key)
	var debandingEnabled
		get() = sharedPreferences.getBoolean(debandingEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(debandingEnabledKey, value).apply() }

	// Default false: keeps today's RENDERMODE_CONTINUOUSLY behavior unless a user opts in,
	// so the change can be A/B tested rather than silently altering the deband render cadence.
	val debandRenderWhenDirtyEnabledKey get() = resources.getString(R.string.preferences_debanding_render_when_dirty_key)
	var debandRenderWhenDirtyEnabled
		get() = sharedPreferences.getBoolean(debandRenderWhenDirtyEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(debandRenderWhenDirtyEnabledKey, value).apply() }

	val realVideoTimestampsKey get() = resources.getString(R.string.preferences_real_video_timestamps_key)
	var realVideoTimestamps
		get() = sharedPreferences.getBoolean(realVideoTimestampsKey, false)
		set(value) { sharedPreferences.edit().putBoolean(realVideoTimestampsKey, value).apply() }

	val decoderLowLatencyEnabledKey get() = resources.getString(R.string.preferences_decoder_low_latency_enabled_key)
	var decoderLowLatencyEnabled
		get() = sharedPreferences.getBoolean(decoderLowLatencyEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(decoderLowLatencyEnabledKey, value).apply() }

	val feedbackReducedIntervalEnabledKey get() = resources.getString(R.string.preferences_feedback_reduced_interval_enabled_key)
	var feedbackReducedIntervalEnabled
		get() = sharedPreferences.getBoolean(feedbackReducedIntervalEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(feedbackReducedIntervalEnabledKey, value).apply() }

	val decoderInputThreadEnabledKey get() = resources.getString(R.string.preferences_decoder_input_thread_enabled_key)
	var decoderInputThreadEnabled
		get() = sharedPreferences.getBoolean(decoderInputThreadEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(decoderInputThreadEnabledKey, value).apply() }

	val threadPriorityBoostEnabledKey get() = resources.getString(R.string.preferences_thread_priority_boost_enabled_key)
	var threadPriorityBoostEnabled
		get() = sharedPreferences.getBoolean(threadPriorityBoostEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(threadPriorityBoostEnabledKey, value).apply() }

	val takionVideoPacketReorderingDisabledKey get() = resources.getString(R.string.preferences_takion_video_packet_reordering_disabled_key)
	var takionVideoPacketReorderingDisabled
		get() = sharedPreferences.getBoolean(takionVideoPacketReorderingDisabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(takionVideoPacketReorderingDisabledKey, value).apply() }

	val decoderLateFrameRecoveryEnabledKey get() = resources.getString(R.string.preferences_decoder_late_frame_recovery_enabled_key)
	var decoderLateFrameRecoveryEnabled
		get() = sharedPreferences.getBoolean(decoderLateFrameRecoveryEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(decoderLateFrameRecoveryEnabledKey, value).apply() }

	val controllerInputCoalescingEnabledKey get() = resources.getString(R.string.preferences_controller_input_coalescing_enabled_key)
	var controllerInputCoalescingEnabled
		get() = sharedPreferences.getBoolean(controllerInputCoalescingEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(controllerInputCoalescingEnabledKey, value).apply() }

	val touchscreenTouchpadEnabledKey get() = "preferences_touchscreen_touchpad_enabled"
	var touchscreenTouchpadEnabled
		get() = sharedPreferences.getBoolean(touchscreenTouchpadEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(touchscreenTouchpadEnabledKey, value).apply() }

	val streamWindowOptimizationsEnabledKey get() = "preferences_stream_window_optimizations_enabled"
	var streamWindowOptimizationsEnabled
		get() = sharedPreferences.getBoolean(streamWindowOptimizationsEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(streamWindowOptimizationsEnabledKey, value).apply() }

	// PLE-12: coalesce touch-controls redraws to once per vsync via postInvalidateOnAnimation,
	// and request unbuffered input dispatch, instead of invalidating on every touch sample.
	// Default false preserves today's per-sample invalidate() behaviour.
	val coalesceTouchRedrawEnabledKey get() = "preferences_coalesce_touch_redraw_enabled"
	var coalesceTouchRedrawEnabled
		get() = sharedPreferences.getBoolean(coalesceTouchRedrawEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(coalesceTouchRedrawEnabledKey, value).apply() }

	// Mapping Keys
	fun getMappingKey(buttonName: String) = "mapping_$buttonName"
	
	private fun getMapping(buttonName: String, default: Int): Int {
		return sharedPreferences.getString(getMappingKey(buttonName), default.toString())?.toIntOrNull() ?: default
	}
	
	private fun setMapping(buttonName: String, value: Int) {
		sharedPreferences.edit().putString(getMappingKey(buttonName), value.toString()).apply()
	}

	var mappingCross: Int 
		get() = getMapping("cross", 96)
		set(value) = setMapping("cross", value)
		
	var mappingCircle: Int
		get() = getMapping("circle", 97)
		set(value) = setMapping("circle", value)
		
	var mappingSquare: Int
		get() = getMapping("square", 99)
		set(value) = setMapping("square", value)
		
	var mappingTriangle: Int
		get() = getMapping("triangle", 100)
		set(value) = setMapping("triangle", value)
		
	var mappingL1: Int
		get() = getMapping("l1", 102)
		set(value) = setMapping("l1", value)
		
	var mappingR1: Int
		get() = getMapping("r1", 103)
		set(value) = setMapping("r1", value)
		
	var mappingL2: Int
		get() = getMapping("l2", 104)
		set(value) = setMapping("l2", value)
		
	var mappingR2: Int
		get() = getMapping("r2", 105)
		set(value) = setMapping("r2", value)
		
	var mappingL3: Int
		get() = getMapping("l3", 106)
		set(value) = setMapping("l3", value)
		
	var mappingR3: Int
		get() = getMapping("r3", 107)
		set(value) = setMapping("r3", value)
		
	var mappingOptions: Int
		get() = getMapping("options", 108)
		set(value) = setMapping("options", value)
		
	var mappingShare: Int
		get() = getMapping("share", 109)
		set(value) = setMapping("share", value)
		
	var mappingPs: Int
		get() = getMapping("ps", 110)
		set(value) = setMapping("ps", value)

	val sharpnessIntensityKey get() = "preferences_sharpness_intensity"
	var sharpnessIntensity: Float
		get() = sharedPreferences.getInt(sharpnessIntensityKey, 0).toFloat() / 100f
		set(value) { sharedPreferences.edit().putInt(sharpnessIntensityKey, (value * 100f).toInt()).apply() }


	val resolutionKey get() = resources.getString(R.string.preferences_resolution_key)
	var resolution
		get() = sharedPreferences.getString(resolutionKey, resolutionDefault.value)?.let { value ->
			Resolution.values().firstOrNull { it.value == value }
		} ?: resolutionDefault
		set(value) { sharedPreferences.edit().putString(resolutionKey, value.value).apply() }

	val fpsKey get() = resources.getString(R.string.preferences_fps_key)
	var fps
		get() = sharedPreferences.getString(fpsKey, fpsDefault.value)?.let { value ->
			FPS.values().firstOrNull { it.value == value }
		}  ?: fpsDefault
		set(value) { sharedPreferences.edit().putString(fpsKey, value.value).apply() }

	val displayRefreshRateModeKey get() = resources.getString(R.string.preferences_display_refresh_rate_key)
	var displayRefreshRateMode
		get() = sharedPreferences.getString(displayRefreshRateModeKey, displayRefreshRateModeDefault.value)?.let { value ->
			DisplayRefreshRateMode.values().firstOrNull { it.value == value }
		} ?: displayRefreshRateModeDefault
		set(value) { sharedPreferences.edit().putString(displayRefreshRateModeKey, value.value).apply() }

	fun validateBitrate(bitrate: Int) = max(2000, min(100000, bitrate))
	val bitrateKey get() = resources.getString(R.string.preferences_bitrate_key)
	var bitrate
		get() = sharedPreferences.getInt(bitrateKey, 0).let { if(it == 0) null else validateBitrate(it) }
		set(value) { sharedPreferences.edit().putInt(bitrateKey, if(value != null) validateBitrate(value) else 0).apply() }
	val bitrateAuto get() = videoProfileDefaultBitrate.bitrate
	private val _bitrateAutoFlow by lazy { MutableStateFlow(bitrateAuto) }
	val bitrateAutoFlow: StateFlow<Int> get() = _bitrateAutoFlow.asStateFlow()

	fun validatePacketLossMaxPercent(percent: Int) = max(0, min(100, percent))
	val packetLossMaxPercentKey get() = resources.getString(R.string.preferences_packet_loss_max_percent_key)
	var packetLossMaxPercent
		get() = validatePacketLossMaxPercent(sharedPreferences.getInt(packetLossMaxPercentKey, packetLossMaxPercentDefault))
		set(value) { sharedPreferences.edit().putInt(packetLossMaxPercentKey, validatePacketLossMaxPercent(value)).apply() }
	val packetLossMax get() = packetLossMaxPercent / 100.0

	val codecKey get() = resources.getString(R.string.preferences_codec_key)
	var codec
		get() = sharedPreferences.getString(codecKey, codecDefault.value)?.let { value ->
			Codec.values().firstOrNull { it.value == value }
		}  ?: codecDefault
		set(value) { sharedPreferences.edit().putString(codecKey, value.value).apply() }

	private val videoProfileDefaultBitrate get() = ConnectVideoProfile.preset(resolution.preset, fps.preset, codec.codec)
	val videoProfile get() = videoProfileDefaultBitrate.let {
		val bitrate = bitrate
		if(bitrate == null)
			it
		else
			it.copy(bitrate = bitrate)
	}
}
