// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import com.metallic.chiaki.lib.StreamStatsEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamSummaryTest
{
	private fun stats(
		intervalMillis: Long = 1000,
		rttMicros: Long = 10_000,
		inputDrops: Long = 0,
		presenterDrops: Long = 0,
		lostFrames: Long = 0,
		packetJitterMicros: Long = 1_000,
		packetsReceived: Long = 100,
		packetsLost: Long = 0
	) = StreamStatsEvent(
		intervalMillis, rttMicros, 60, 60, 0, 0, inputDrops, presenterDrops,
		0, lostFrames, 0, packetJitterMicros, packetsReceived, packetsLost, 0,
		0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
	)

	@Test fun aggregatesDurationWeightedLatencyDropsAndQuality()
	{
		val accumulator = StreamSummaryAccumulator()
		accumulator.connected(1_000)
		accumulator.add(stats(rttMicros = 10_000, inputDrops = 1), NetworkLinkSample(NetworkLinkType.OTHER))
		accumulator.add(stats(rttMicros = 20_000, presenterDrops = 2, lostFrames = 3), NetworkLinkSample(NetworkLinkType.OTHER))
		accumulator.ended(3_000)

		val result = accumulator.build(3_500)!!

		assertEquals(2_000, result.durationMillis)
		assertEquals(15.0, result.averageLatencyMillis!!, 0.01)
		assertEquals(6, result.droppedFrames)
		assertEquals(StreamSummaryQuality.GOOD, result.quality)
	}

	@Test fun formatsCloudGamingMetrics()
	{
		assertEquals("2:05", StreamSummaryFormatter.duration(125_000))
		assertEquals("1:02:03", StreamSummaryFormatter.duration(3_723_000))
		assertEquals("17 ms", StreamSummaryFormatter.latency(16.6))
		assertEquals("—", StreamSummaryFormatter.latency(null))
	}
}
