// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class NetworkQualityChipPresenterTest
{
	private val poor = NetworkQualitySnapshot(
		level = NetworkQualityLevel.POOR,
		cause = NetworkQualityCause.WIFI_LINK,
		fastRttMillis = 42.4,
		fastJitterMillis = 12.5,
		fastLossPercent = 3.2
	)

	@Test
	fun collapsedChipContainsStateButNoNumbers()
	{
		val content = NetworkQualityChipPresenter.content(poor, expanded = false)
		assertEquals(NetworkQualityLevel.POOR, content.level)
		assertNull(content.metrics)
	}

	@Test
	fun tapExposesTheCurrentMeasurements()
	{
		val metrics = NetworkQualityChipPresenter.content(poor, expanded = true).metrics
		assertNotNull(metrics)
		assertEquals(42.4, metrics!!.rttMillis, 0.0)
		assertEquals(12.5, metrics.jitterMillis, 0.0)
		assertEquals(3.2, metrics.lossPercent, 0.0)
	}

	@Test
	fun unknownQualityNeverInventsMeasurements()
	{
		val content = NetworkQualityChipPresenter.content(NetworkQualitySnapshot.UNKNOWN, expanded = true)
		assertEquals(NetworkQualityLevel.UNKNOWN, content.level)
		assertNull(content.metrics)
	}
}
