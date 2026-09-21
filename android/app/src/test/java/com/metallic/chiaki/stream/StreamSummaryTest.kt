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

	// PLE-474: videoFramesLost is a running total; a blackout's frames count once, not
	// once per stats event that follows it.
	@Test fun videoFramesLostCountsOnlyItsGrowth()
	{
		val accumulator = StreamSummaryAccumulator()
		accumulator.connected(1_000)
		accumulator.add(stats(lostFrames = 0), NetworkLinkSample(NetworkLinkType.OTHER))
		accumulator.add(stats(lostFrames = 182), NetworkLinkSample(NetworkLinkType.OTHER))
		accumulator.add(stats(lostFrames = 190), NetworkLinkSample(NetworkLinkType.OTHER))
		accumulator.add(stats(lostFrames = 190), NetworkLinkSample(NetworkLinkType.OTHER))
		accumulator.add(stats(lostFrames = 190), NetworkLinkSample(NetworkLinkType.OTHER))
		accumulator.ended(6_000)

		assertEquals(190, accumulator.build(6_500)!!.droppedFrames)
	}

	// PLE-352: the home card's "Avg latency" is a duration-weighted mean of
	// measuredRttMicros, computed independently of the chip/overlay's classifier -- this
	// pins down in code what the device capture (docs/verification/PLE-352/) found by
	// measurement: it never reads consoleRttRaw, the sawtooth PLE-343 disqualified.
	@Test fun averageLatencyIgnoresConsoleRttRaw()
	{
		val accumulator = StreamSummaryAccumulator()
		accumulator.connected(1_000)
		accumulator.add(
			stats(rttMicros = 10_000).copy(consoleRttRaw = 187.6),
			NetworkLinkSample(NetworkLinkType.OTHER)
		)
		accumulator.add(
			stats(rttMicros = 10_000).copy(consoleRttRaw = 0.4),
			NetworkLinkSample(NetworkLinkType.OTHER)
		)
		accumulator.ended(3_000)

		val result = accumulator.build(3_500)!!
		assertEquals(10.0, result.averageLatencyMillis!!, 0.01)
	}

	@Test fun formatsCloudGamingMetrics()
	{
		assertEquals("2:05", StreamSummaryFormatter.duration(125_000))
		assertEquals("1:02:03", StreamSummaryFormatter.duration(3_723_000))
		assertEquals("17 ms", StreamSummaryFormatter.latency(16.6))
		assertEquals("—", StreamSummaryFormatter.latency(null))
	}
}
