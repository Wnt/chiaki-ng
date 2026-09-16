// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.app.Activity
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.text.InputType
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.exportAndShareAllSettings
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.common.importSettingsFromUri

class DataStore(val preferences: Preferences): PreferenceDataStore()
{
	override fun getBoolean(key: String?, defValue: Boolean) = when(key)
	{
		preferences.logVerboseKey -> preferences.logVerbose
		preferences.swapCrossMoonKey -> preferences.swapCrossMoon
		preferences.rumbleEnabledKey -> preferences.rumbleEnabled
		preferences.motionEnabledKey -> preferences.motionEnabled
		preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled
		preferences.debandingEnabledKey -> preferences.debandingEnabled
		preferences.debandRenderWhenDirtyEnabledKey -> preferences.debandRenderWhenDirtyEnabled
		preferences.realVideoTimestampsKey -> preferences.realVideoTimestamps
		preferences.decoderLowLatencyEnabledKey -> preferences.decoderLowLatencyEnabled
		preferences.decoderOperatingRateAutoKey -> preferences.decoderOperatingRateAuto
		preferences.decoderRealtimePriorityKey -> preferences.decoderRealtimePriority
		preferences.feedbackReducedIntervalEnabledKey -> preferences.feedbackReducedIntervalEnabled
		preferences.feedbackStatsLogEnabledKey -> preferences.feedbackStatsLogEnabled
		preferences.decoderInputThreadEnabledKey -> preferences.decoderInputThreadEnabled
		preferences.threadPriorityBoostEnabledKey -> preferences.threadPriorityBoostEnabled
		preferences.takionVideoPacketReorderingDisabledKey -> preferences.takionVideoPacketReorderingDisabled
		preferences.decoderLateFrameRecoveryEnabledKey -> preferences.decoderLateFrameRecoveryEnabled
		preferences.videoPacingEnabledKey -> preferences.videoPacingEnabled
		preferences.controllerInputCoalescingEnabledKey -> preferences.controllerInputCoalescingEnabled
		preferences.gamepadUnbufferedDispatchEnabledKey -> preferences.gamepadUnbufferedDispatchEnabled
		preferences.gamepadTriggerFallbackEnabledKey -> preferences.gamepadTriggerFallbackEnabled
		preferences.touchscreenTouchpadEnabledKey -> preferences.touchscreenTouchpadEnabled
		preferences.psnSignInEnabledKey -> preferences.psnSignInEnabled
		else -> defValue
	}

	override fun putBoolean(key: String?, value: Boolean)
	{
		when(key)
		{
			preferences.logVerboseKey -> preferences.logVerbose = value
			preferences.swapCrossMoonKey -> preferences.swapCrossMoon = value
			preferences.rumbleEnabledKey -> preferences.rumbleEnabled = value
			preferences.motionEnabledKey -> preferences.motionEnabled = value
			preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled = value
			preferences.debandingEnabledKey -> preferences.debandingEnabled = value
			preferences.debandRenderWhenDirtyEnabledKey -> preferences.debandRenderWhenDirtyEnabled = value
			preferences.realVideoTimestampsKey -> preferences.realVideoTimestamps = value
			preferences.decoderLowLatencyEnabledKey -> preferences.decoderLowLatencyEnabled = value
			preferences.decoderOperatingRateAutoKey -> preferences.decoderOperatingRateAuto = value
			preferences.decoderRealtimePriorityKey -> preferences.decoderRealtimePriority = value
			preferences.feedbackReducedIntervalEnabledKey -> preferences.feedbackReducedIntervalEnabled = value
			preferences.feedbackStatsLogEnabledKey -> preferences.feedbackStatsLogEnabled = value
			preferences.decoderInputThreadEnabledKey -> preferences.decoderInputThreadEnabled = value
			preferences.threadPriorityBoostEnabledKey -> preferences.threadPriorityBoostEnabled = value
			preferences.takionVideoPacketReorderingDisabledKey -> preferences.takionVideoPacketReorderingDisabled = value
			preferences.decoderLateFrameRecoveryEnabledKey -> preferences.decoderLateFrameRecoveryEnabled = value
			preferences.videoPacingEnabledKey -> preferences.videoPacingEnabled = value
			preferences.controllerInputCoalescingEnabledKey -> preferences.controllerInputCoalescingEnabled = value
			preferences.gamepadUnbufferedDispatchEnabledKey -> preferences.gamepadUnbufferedDispatchEnabled = value
			preferences.gamepadTriggerFallbackEnabledKey -> preferences.gamepadTriggerFallbackEnabled = value
			preferences.touchscreenTouchpadEnabledKey -> preferences.touchscreenTouchpadEnabled = value
			preferences.psnSignInEnabledKey -> preferences.psnSignInEnabled = value
		}
	}

	override fun getInt(key: String?, defValue: Int) = defValue

	override fun putInt(key: String?, value: Int) {}

	override fun getString(key: String, defValue: String?) = when
	{
		key == preferences.resolutionKey -> preferences.resolution.value
		key == preferences.fpsKey -> preferences.fps.value
		key == preferences.displayRefreshRateModeKey -> preferences.displayRefreshRateMode.value
		key == preferences.videoPacingModeKey -> preferences.videoPacingMode.value
		key == preferences.bitrateKey -> preferences.bitrate?.toString() ?: ""
		key == preferences.packetLossMaxPercentKey -> preferences.packetLossMaxPercent.toString()
		key == preferences.decoderOperatingRateKey -> preferences.decoderOperatingRate.toString()
		key == preferences.videoTimestampRateHzKey -> preferences.videoTimestampRateHz.toString()
		key == preferences.audioBufferBurstsKey -> preferences.audioBufferBursts.toString()
		key == preferences.audioFifoMsKey -> preferences.audioFifoMs.toString()
		key == preferences.codecKey -> preferences.codec.value
		key.startsWith("mapping_") -> preferences.sharedPreferences.getString(key, defValue)
		else -> defValue
	}

	override fun putString(key: String, value: String?)
	{
		when
		{
			key == preferences.resolutionKey ->
			{
				val resolution = Preferences.Resolution.values().firstOrNull { it.value == value } ?: return
				preferences.resolution = resolution
			}
			key == preferences.fpsKey ->
			{
				val fps = Preferences.FPS.values().firstOrNull { it.value == value } ?: return
				preferences.fps = fps
			}
			key == preferences.displayRefreshRateModeKey ->
			{
				val mode = Preferences.DisplayRefreshRateMode.values().firstOrNull { it.value == value } ?: return
				preferences.displayRefreshRateMode = mode
			}
			key == preferences.videoPacingModeKey ->
			{
				val mode = Preferences.VideoPacingMode.values().firstOrNull { it.value == value } ?: return
				preferences.videoPacingMode = mode
			}
			key == preferences.bitrateKey -> preferences.bitrate = value?.toIntOrNull()
			key == preferences.packetLossMaxPercentKey ->
			{
				value?.toIntOrNull()?.let { preferences.packetLossMaxPercent = it }
			}
			key == preferences.decoderOperatingRateKey ->
			{
				value?.toIntOrNull()?.let { preferences.decoderOperatingRate = it }
			}
			key == preferences.videoTimestampRateHzKey ->
			{
				value?.toIntOrNull()?.let { preferences.videoTimestampRateHz = it }
			}
			key == preferences.audioBufferBurstsKey ->
			{
				value?.toIntOrNull()?.let { preferences.audioBufferBursts = it }
			}
			key == preferences.audioFifoMsKey ->
			{
				value?.toIntOrNull()?.let { preferences.audioFifoMs = it }
			}
			key == preferences.codecKey ->
			{
				val codec = Preferences.Codec.values().firstOrNull { it.value == value } ?: return
				preferences.codec = codec
			}
			key.startsWith("mapping_") -> 
			{
				preferences.sharedPreferences.edit().putString(key, value).apply()
			}
		}
	}
}

class SettingsFragment: PreferenceFragmentCompat(), TitleFragment
{
	companion object
	{
		private const val PICK_SETTINGS_JSON_REQUEST = 1
	}

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
	{
		val context = context ?: return

		val viewModel = ViewModelProvider(this, viewModelFactory { SettingsViewModel(getDatabase(context), Preferences(context)) })
			.get(SettingsViewModel::class.java)

		val preferences = viewModel.preferences
		preferenceManager.preferenceDataStore = DataStore(preferences)
		setPreferencesFromResource(R.xml.preferences, rootKey)

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_resolution_key))?.let {
			it.entryValues = Preferences.resolutionAll.map { res -> res.value }.toTypedArray()
			it.entries = Preferences.resolutionAll.map { res -> getString(res.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_fps_key))?.let {
			it.entryValues = Preferences.fpsAll.map { fps -> fps.value }.toTypedArray()
			it.entries = Preferences.fpsAll.map { fps -> getString(fps.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_display_refresh_rate_key))?.let {
			it.entryValues = Preferences.displayRefreshRateModeAll.map { mode -> mode.value }.toTypedArray()
			it.entries = Preferences.displayRefreshRateModeAll.map { mode -> getString(mode.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_video_pacing_mode_key))?.let {
			it.entryValues = Preferences.videoPacingModeAll.map { mode -> mode.value }.toTypedArray()
			it.entries = Preferences.videoPacingModeAll.map { mode -> getString(mode.title) }.toTypedArray()
		}

		val bitratePreference = preferenceScreen.findPreference<EditTextPreference>(getString(R.string.preferences_bitrate_key))
		val bitrateSummaryProvider = Preference.SummaryProvider<EditTextPreference> {
			preferences.bitrate?.toString() ?: getString(R.string.preferences_bitrate_auto, preferences.bitrateAuto)
		}
		bitratePreference?.let {
			it.summaryProvider = bitrateSummaryProvider
			it.setOnBindEditTextListener { editText ->
				editText.hint = getString(R.string.preferences_bitrate_auto, preferences.bitrateAuto)
				editText.inputType = InputType.TYPE_CLASS_NUMBER
				editText.setText(preferences.bitrate?.toString() ?: "")
			}
		}
		viewModel.bitrateAuto.observe(this, Observer {
			bitratePreference?.summaryProvider = bitrateSummaryProvider
		})

		val packetLossMaxPercentPreference = preferenceScreen.findPreference<EditTextPreference>(getString(R.string.preferences_packet_loss_max_percent_key))
		packetLossMaxPercentPreference?.let {
			it.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
				getString(R.string.preferences_packet_loss_max_percent_value, preferences.packetLossMaxPercent)
			}
			it.setOnBindEditTextListener { editText ->
				editText.inputType = InputType.TYPE_CLASS_NUMBER
				editText.setText(preferences.packetLossMaxPercent.toString())
			}
		}

		preferenceScreen.findPreference<EditTextPreference>(getString(R.string.preferences_decoder_operating_rate_key))?.let {
			it.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
				getString(R.string.preferences_decoder_operating_rate_value, preferences.decoderOperatingRate)
			}
			it.setOnBindEditTextListener { editText ->
				editText.inputType = InputType.TYPE_CLASS_NUMBER
				editText.setText(preferences.decoderOperatingRate.toString())
			}
		}

		preferenceScreen.findPreference<EditTextPreference>(getString(R.string.preferences_video_timestamp_rate_hz_key))?.let {
			it.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
				getString(R.string.preferences_video_timestamp_rate_hz_value, preferences.videoTimestampRateHz)
			}
			it.setOnBindEditTextListener { editText ->
				editText.inputType = InputType.TYPE_CLASS_NUMBER
				editText.setText(preferences.videoTimestampRateHz.toString())
			}
		}

		val audioBufferBurstsPreference = preferenceScreen.findPreference<EditTextPreference>(getString(R.string.preferences_audio_buffer_bursts_key))
		audioBufferBurstsPreference?.let {
			it.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
				if(preferences.audioBufferBursts == 0) getString(R.string.preferences_audio_buffer_bursts_default)
				else getString(R.string.preferences_audio_buffer_bursts_value, preferences.audioBufferBursts)
			}
			it.setOnBindEditTextListener { editText ->
				editText.inputType = InputType.TYPE_CLASS_NUMBER
				editText.setText(preferences.audioBufferBursts.toString())
			}
		}

		val audioFifoMsPreference = preferenceScreen.findPreference<EditTextPreference>(getString(R.string.preferences_audio_fifo_ms_key))
		audioFifoMsPreference?.let {
			it.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
				getString(R.string.preferences_audio_fifo_ms_value, preferences.audioFifoMs)
			}
			it.setOnBindEditTextListener { editText ->
				editText.inputType = InputType.TYPE_CLASS_NUMBER
				editText.setText(preferences.audioFifoMs.toString())
			}
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_codec_key))?.let {
			it.entryValues = Preferences.codecAll.map { codec -> codec.value }.toTypedArray()
			it.entries = Preferences.codecAll.map { codec -> getString(codec.title) }.toTypedArray()
		}

		val registeredHostsPreference = preferenceScreen.findPreference<Preference>("registered_hosts")
		viewModel.registeredHostsCount.observe(this, Observer {
			registeredHostsPreference?.summary = getString(R.string.preferences_registered_hosts_summary, it)
		})

		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_export_settings_key))?.setOnPreferenceClickListener { exportSettings(); true }
		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_import_settings_key))?.setOnPreferenceClickListener { importSettings(); true }
	}

	override fun getTitle(resources: Resources): String = resources.getString(R.string.title_settings)

	private fun exportSettings()
	{
		val activity = activity ?: return
		exportAndShareAllSettings(activity, lifecycleScope)
	}

	private fun importSettings()
	{
		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
			addCategory(Intent.CATEGORY_OPENABLE)
			type = "application/json"
		}
		startActivityForResult(intent, PICK_SETTINGS_JSON_REQUEST)
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
	{
		if(requestCode == PICK_SETTINGS_JSON_REQUEST && resultCode == Activity.RESULT_OK)
		{
			val activity = activity ?: return
			data?.data?.also {
				importSettingsFromUri(activity, it, lifecycleScope)
			}
		}
	}
}
