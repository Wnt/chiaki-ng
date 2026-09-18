// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import com.metallic.chiaki.common.Preferences
import kotlin.math.abs

// Real 60 fps buffer timestamps make SurfaceFlinger prefer a 60 Hz mode on the S22 Ultra.
// Keep the timestamp experiments at the highest compatible panel rate without requiring PLE-7.
internal fun effectiveDisplayRefreshRateMode(
	configuredMode: Preferences.DisplayRefreshRateMode,
	realVideoTimestamps: Boolean,
	videoPacingEnabled: Boolean
): Preferences.DisplayRefreshRateMode =
	if(realVideoTimestamps || videoPacingEnabled)
		Preferences.DisplayRefreshRateMode.HIGHEST
	else
		configuredMode

internal fun presenterDisplayTimingUpdatesEnabled(
	configuredMode: Preferences.DisplayRefreshRateMode,
	videoPacingEnabled: Boolean
): Boolean = videoPacingEnabled || configuredMode == Preferences.DisplayRefreshRateMode.MATCH_STREAM

internal fun displayTimingChanged(
	oldRefreshHz: Double,
	oldAppVsyncOffsetNanos: Long,
	newRefreshHz: Double,
	newAppVsyncOffsetNanos: Long
): Boolean = oldRefreshHz != newRefreshHz || oldAppVsyncOffsetNanos != newAppVsyncOffsetNanos

/** The fields of an android.view.Display.Mode the refresh-rate policy decides on. */
internal data class DisplayModeCandidate(
	val modeId: Int,
	val width: Int,
	val height: Int,
	val refreshRate: Float
)

/**
 * The mode to request for [mode], chosen among the modes of the display the stream window is
 * on, at that display's current resolution. Null when nothing fits, which leaves the window's
 * preference untouched.
 */
internal fun selectDisplayMode(
	mode: Preferences.DisplayRefreshRateMode,
	streamFrameRate: Float,
	currentMode: DisplayModeCandidate,
	supportedModes: List<DisplayModeCandidate>
): DisplayModeCandidate?
{
	val modesAtCurrentResolution = supportedModes.filter {
		it.width == currentMode.width && it.height == currentMode.height
	}
	return when(mode)
	{
		Preferences.DisplayRefreshRateMode.MATCH_STREAM -> modesAtCurrentResolution
			.filter { abs(it.refreshRate - streamFrameRate) < 0.5f }
			.minByOrNull { abs(it.refreshRate - streamFrameRate) }
		Preferences.DisplayRefreshRateMode.HIGHEST -> modesAtCurrentResolution.maxByOrNull { it.refreshRate }
		Preferences.DisplayRefreshRateMode.SYSTEM_DEFAULT -> null
	}
}

/**
 * The display the stream is shown on (PLE-384). In Samsung DeX the stream window sits on the
 * external display while the phone panel stays on as a touchpad, so the answer must come from
 * the window: the activity's own display (API 30+), else the display the view is attached to,
 * and only then the context's default display.
 */
internal fun <D> streamDisplayOf(activityDisplay: D?, viewDisplay: D?, defaultDisplay: D): D =
	activityDisplay ?: viewDisplay ?: defaultDisplay
