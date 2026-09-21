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

	// PLE-484: decoderInputFramesDropped and presenterFramesDropped are session-cumulative
	// totals from the native decoder/presenter, not per-interval counts. Re-adding the raw
	// value every stats event would make droppedFrames quadratic in session length instead
	// of the real frame count.
	@Test fun decoderAndPresenterDropsCountOnlyTheirGrowth()
	{
		val accumulator = StreamSummaryAccumulator()
		accumulator.connected(1_000)
		accumulator.add(stats(inputDrops = 1, presenterDrops = 0), NetworkLinkSample(NetworkLinkType.OTHER))
		accumulator.add(stats(inputDrops = 1, presenterDrops = 2), NetworkLinkSample(NetworkLinkType.OTHER))
		accumulator.add(stats(inputDrops = 3, presenterDrops = 2), NetworkLinkSample(NetworkLinkType.OTHER))
		accumulator.add(stats(inputDrops = 3, presenterDrops = 2), NetworkLinkSample(NetworkLinkType.OTHER))
		accumulator.ended(5_000)

		// real totals: inputDrops grew 0 -> 1 -> 1 -> 3 -> 3 = 3; presenterDrops grew
		// 0 -> 0 -> 2 -> 2 -> 2 = 2. Total = 5, not the running-sum re-add of 1+3+3+2+2+2=13.
		assertEquals(5, accumulator.build(5_500)!!.droppedFrames)
	}

	// PLE-484: a reconnect can restart the native counters at 0 without an intervening
	// connected() call (the stats stream and the connection callback are not ordered against
	// each other); the backwards jump must contribute 0 growth, not a negative.
	@Test fun decoderAndPresenterDropsRestartMidSessionContributeZero()
	{
		val accumulator = StreamSummaryAccumulator()
		accumulator.connected(1_000)
		accumulator.add(stats(inputDrops = 10, presenterDrops = 5), NetworkLinkSample(NetworkLinkType.OTHER))
		// counters restart at 0 (e.g. decoder/presenter re-init on reconnect) without a
		// fresh connected(): the raw values go backwards.
		accumulator.add(stats(inputDrops = 0, presenterDrops = 0), NetworkLinkSample(NetworkLinkType.OTHER))
		accumulator.add(stats(inputDrops = 2, presenterDrops = 1), NetworkLinkSample(NetworkLinkType.OTHER))
		accumulator.ended(4_000)

		// real total: 10+5 from the first event, 0 from the backwards jump, 2+1 from the
		// growth after restart = 18. The naive running sum would be 10+5+0+0+2+1 = 18 too,
		// but a naive *difference* without coerceAtLeast(0L) would go negative on the jump
		// (0-10=-10, 0-5=-5) and undercount to -2.
		assertEquals(18, accumulator.build(4_500)!!.droppedFrames)
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
