// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import com.metallic.chiaki.common.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayRefreshRatePolicyTest
{
	@Test
	fun configuredModeIsUnchangedWhenTimestampFeaturesAreDisabled()
	{
		Preferences.DisplayRefreshRateMode.values().forEach { configuredMode ->
			assertEquals(
				configuredMode,
				effectiveDisplayRefreshRateMode(configuredMode, false, false)
			)
		}
	}

	@Test
	fun realVideoTimestampsPinHighestRefreshRate()
	{
		assertEquals(
			Preferences.DisplayRefreshRateMode.HIGHEST,
			effectiveDisplayRefreshRateMode(
				Preferences.DisplayRefreshRateMode.SYSTEM_DEFAULT,
				true,
				false
			)
		)
	}

	@Test
	fun videoPacingPinsHighestRefreshRate()
	{
		assertEquals(
			Preferences.DisplayRefreshRateMode.HIGHEST,
			effectiveDisplayRefreshRateMode(
				Preferences.DisplayRefreshRateMode.MATCH_STREAM,
				false,
				true
			)
		)
	}

	@Test
	fun matchStreamObservesPostStartupPanelChangeOnce()
	{
		assertTrue(presenterDisplayTimingUpdatesEnabled(
			Preferences.DisplayRefreshRateMode.MATCH_STREAM, false))
		assertTrue(displayTimingChanged(120.0, 1_000_000L, 60.0, 1_000_000L))
		assertFalse(displayTimingChanged(60.0, 1_000_000L, 60.0, 1_000_000L))
	}
}
