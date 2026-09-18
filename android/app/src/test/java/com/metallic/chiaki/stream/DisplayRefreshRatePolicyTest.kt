// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import com.metallic.chiaki.common.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

	// PLE-384: the modes of the S22 Ultra in Samsung DeX, from dumpsys display. Display 0 is the
	// phone panel, display 7 the TV behind the HDMI dongle; their mode ids do not overlap.
	private val panelModes = listOf(
		DisplayModeCandidate(1, 1440, 3088, 120.00001f),
		DisplayModeCandidate(3, 1440, 3088, 60.0f),
		DisplayModeCandidate(8, 1080, 2316, 120.00001f),
		DisplayModeCandidate(9, 1080, 2316, 96.0f),
		DisplayModeCandidate(10, 1080, 2316, 60.0f)
	)
	private val tvModes = listOf(
		DisplayModeCandidate(23, 1920, 1080, 120.00001f),
		DisplayModeCandidate(24, 1920, 1080, 100.0f),
		DisplayModeCandidate(25, 1920, 1080, 60.0f),
		DisplayModeCandidate(26, 1920, 1080, 50.0f),
		DisplayModeCandidate(32, 1280, 720, 60.0f)
	)

	private class FakeDisplay(val displayId: Int, val currentMode: DisplayModeCandidate,
		val supportedModes: List<DisplayModeCandidate>)

	private val panel = FakeDisplay(0, panelModes[4], panelModes)
	private val tv = FakeDisplay(7, tvModes[2], tvModes)

	@Test
	fun streamDisplayIsTheWindowsDisplayNotTheDefaultOne()
	{
		assertSame(tv, streamDisplayOf(tv, panel, panel))
		assertSame(tv, streamDisplayOf(null, tv, panel))
		assertSame(panel, streamDisplayOf<FakeDisplay>(null, null, panel))
	}

	@Test
	fun highestPicksTheFastestModeOfTheStreamDisplayAtItsResolution()
	{
		val display = streamDisplayOf(tv, panel, panel)
		assertEquals(23, selectDisplayMode(Preferences.DisplayRefreshRateMode.HIGHEST, 60f,
			display.currentMode, display.supportedModes)?.modeId)
		assertEquals(8, selectDisplayMode(Preferences.DisplayRefreshRateMode.HIGHEST, 60f,
			panel.currentMode, panel.supportedModes)?.modeId)
	}

	@Test
	fun matchStreamPicksTheStreamRateOnTheStreamDisplay()
	{
		assertEquals(25, selectDisplayMode(Preferences.DisplayRefreshRateMode.MATCH_STREAM, 60f,
			tv.currentMode, tv.supportedModes)?.modeId)
		assertEquals(26, selectDisplayMode(Preferences.DisplayRefreshRateMode.MATCH_STREAM, 50f,
			tv.currentMode, tv.supportedModes)?.modeId)
		assertNull(selectDisplayMode(Preferences.DisplayRefreshRateMode.MATCH_STREAM, 50f,
			panel.currentMode, panel.supportedModes))
	}

	@Test
	fun selectionStaysAtTheCurrentResolution()
	{
		val at720 = tvModes[4]
		assertEquals(32, selectDisplayMode(Preferences.DisplayRefreshRateMode.HIGHEST, 60f,
			at720, tvModes)?.modeId)
	}

	@Test
	fun systemDefaultSelectsNothing()
	{
		assertNull(selectDisplayMode(Preferences.DisplayRefreshRateMode.SYSTEM_DEFAULT, 60f,
			tv.currentMode, tv.supportedModes))
	}
}
