// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class StallInfoTest
{
	@Test
	fun cleanSessionHasNothingToReport()
	{
		val tracker = StallTracker()
		tracker.update(19.0, 1_000L) // PLE-464's worst clean-LAN gap
		tracker.update(31.0, 2_000L) // PLE-464's worst non-outage-profile gap
		assertNull(StallInfoPresenter.display(tracker.snapshot(), 3_000L))
	}

	@Test
	fun aStallReachingTheBadgesOwnThresholdIsRecorded()
	{
		val tracker = StallTracker()
		tracker.update(NetworkQualityThresholds.STALL_MS, 10_000L)
		val display = StallInfoPresenter.display(tracker.snapshot(), 12_500L)
		assertNotNull(display)
		assertEquals(NetworkQualityThresholds.STALL_MS / 1000.0, display!!.stallSeconds, 0.0)
		assertEquals(2_500L, display.agoMillis)
	}

	@Test
	fun aLaterStallReplacesAnEarlierOne()
	{
		val tracker = StallTracker()
		tracker.update(1_130.0, 5_000L) // roam-1200ms's measured worst gap
		tracker.update(600.0, 20_000L)
		val display = StallInfoPresenter.display(tracker.snapshot(), 21_000L)
		assertNotNull(display)
		assertEquals(0.6, display!!.stallSeconds, 0.0)
		assertEquals(1_000L, display.agoMillis)
	}

	@Test
	fun neverUpdatedIsNeverShown()
	{
		assertNull(StallInfoPresenter.display(StallTracker().snapshot(), 60_000L))
	}
}
