// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import com.metallic.chiaki.common.Preferences

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
