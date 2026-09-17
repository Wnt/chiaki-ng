// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import com.metallic.chiaki.lib.StreamStatsEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkQualityClassifierTest
{
	private val base = StreamStatsEvent(
		intervalMillis = 1000, rttMicros = 0, streamFrames = 60, decoderFrames = 60,
		decodeMeanMicros = 0, decodeP95Micros = 0, decoderInputFramesDropped = 0,
		presenterFramesDropped = 0, missedVsyncs = 0, videoFramesLost = 0,
		reorderQueueTimeouts = 0, videoPacketJitterMicros = 0, takionPacketsReceived = 1000,
		takionPacketsLost = 0, feedbackPackets = 0, dejitterBufferNanos = 0,
		cadenceDepthNanos = 0, cadenceTargetNanos = 0, cadenceErrP50Nanos = 0,
		cadenceErrP99Nanos = 0, decodeEwmaNanos = 0, cadenceWindowDrops = 0,
		vsyncPeriodNanos = 0, presenterQueueDepth = 0, audioLatencyMicros = 0,
		audioXruns = 0, audioUnderruns = 0, connectionQualityValid = true,
		targetBitrateBps = 15_000_000, measuredThroughputBps = 15_000_000,
		probeRttMicros = 5_000
	)
	private val ethernet = NetworkLinkSample(NetworkLinkType.OTHER)

	@Test
	fun noTransportSampleIsUnknown()
	{
		val idle = base.copy(connectionQualityValid = false, takionPacketsReceived = 0)
		assertEquals(NetworkQualityLevel.UNKNOWN, NetworkQualityClassifier().update(idle, ethernet).level)
	}

	@Test
	fun fiveSampleFastWindowFollowsRttAndUsesNamedBoundaries()
	{
		val classifier = NetworkQualityClassifier()
		repeat(4) { classifier.update(base, ethernet) }
		assertEquals(NetworkQualityLevel.GOOD,
			classifier.update(base.copy(probeRttMicros = 40_000), ethernet).level)
		var result = NetworkQualitySnapshot.UNKNOWN
		repeat(4) { result = classifier.update(base.copy(probeRttMicros = 40_000), ethernet) }
		assertEquals(NetworkQualityLevel.POOR, result.level)
		assertEquals(40.0, result.fastRttMillis, 0.001)
	}

	@Test
	fun recoveryNeedsThreeSamplesBelowExitBoundary()
	{
		val classifier = NetworkQualityClassifier()
		assertEquals(NetworkQualityLevel.CONSTRAINED,
			classifier.update(base.copy(probeRttMicros = 20_000), ethernet).level)
		assertEquals(NetworkQualityLevel.CONSTRAINED, classifier.update(base, ethernet).level)
		assertEquals(NetworkQualityLevel.CONSTRAINED, classifier.update(base, ethernet).level)
		assertEquals(NetworkQualityLevel.GOOD, classifier.update(base, ethernet).level)
	}

	@Test
	fun jitterAndLossEachDriveQualityAtTheirNamedThresholds()
	{
		assertEquals(NetworkQualityLevel.CONSTRAINED, NetworkQualityClassifier().update(
			base.copy(videoPacketJitterMicros = 4_000), ethernet).level)
		assertEquals(NetworkQualityLevel.POOR, NetworkQualityClassifier().update(
			base.copy(takionPacketsReceived = 970, takionPacketsLost = 30), ethernet).level)
	}

	@Test
	fun consoleRttFieldNeverDrivesTheVerdict()
	{
		// PLE-343: a phone 3 ms from its console with the console reporting ~100 in its
		// unverified rtt field must read GOOD, and the chip must show the measured 3 ms.
		val lan = base.copy(probeRttMicros = 3_200, consoleRttMicros = 98_000, consoleRttRaw = 98.0)
		val result = NetworkQualityClassifier().update(lan, ethernet)
		assertEquals(NetworkQualityLevel.GOOD, result.level)
		assertEquals(3.2, result.fastRttMillis, 0.001)
	}

	@Test
	fun startupPingIsTheFallbackAndSenkushaFailureIsNoRtt()
	{
		val startupOnly = base.copy(probeRttMicros = 0, rttMicros = 45_000, consoleRttMicros = 5_000)
		assertEquals(NetworkQualityLevel.POOR, NetworkQualityClassifier().update(startupOnly, ethernet).level)
		val noMeasurement = base.copy(probeRttMicros = 0, rttMicros = 0, consoleRttMicros = 98_000)
		val result = NetworkQualityClassifier().update(noMeasurement, ethernet)
		assertEquals(NetworkQualityLevel.GOOD, result.level)
		assertEquals(0.0, result.fastRttMillis, 0.001)
	}

	@Test
	fun oneRetransmitSizedOutlierDoesNotReachPoor()
	{
		// PLE-343, measured: on the 5g profile one probe sample read 228.8 ms (a heartbeat
		// retransmitted after 200 ms) while the phone's own ping read 24.8 ms. The five-sample
		// mean it used to feed was 65 ms -- Poor for the next 190 s of a link that was fine.
		// Karn's algorithm drops that sample in the lib; the median is the second line of
		// defence for any outlier that is a genuine round trip.
		val classifier = NetworkQualityClassifier()
		val link = base.copy(probeRttMicros = 25_000)
		repeat(4) { classifier.update(link, ethernet) }
		val spike = classifier.update(base.copy(probeRttMicros = 228_800), ethernet)
		assertEquals(NetworkQualityLevel.CONSTRAINED, spike.level)
		assertEquals(25.0, spike.fastRttMillis, 0.001)
	}

	@Test
	fun measuredImpairmentProfilesMapToTheirVerdicts()
	{
		// PLE-343's calibration run: one stream, the impairment profile stepped underneath it,
		// the phone's own ping to the PS5 as ground truth. Median per 60 s phase, ping vs probe:
		//   clean 5.6/5.5   5g 26.6/25.1   wifi-slow 42.8/43.4   4g 62.0/63.6 ms
		// Each level is entered from UNKNOWN so this reads the thresholds, not the hysteresis.
		fun steadyLevel(rttMillis: Double, lossPercent: Double): NetworkQualityLevel
		{
			val classifier = NetworkQualityClassifier()
			val lost = (10_000 * lossPercent / 100.0).toLong()
			var level = NetworkQualityLevel.UNKNOWN
			repeat(NetworkQualityThresholds.SLOW_WINDOW_SECONDS)
			{
				level = classifier.update(base.copy(
					probeRttMicros = (rttMillis * 1000).toLong(),
					takionPacketsReceived = 10_000 - lost, takionPacketsLost = lost), ethernet).level
			}
			return level
		}
		assertEquals(NetworkQualityLevel.GOOD, steadyLevel(5.5, 0.0))
		assertEquals(NetworkQualityLevel.CONSTRAINED, steadyLevel(25.1, 0.5))
		assertEquals(NetworkQualityLevel.POOR, steadyLevel(43.4, 0.0))
		assertEquals(NetworkQualityLevel.POOR, steadyLevel(63.6, 1.6))
	}

	@Test
	fun causePrefersWeakWifiThenLanForClientTransportLoss()
	{
		val lossy = base.copy(takionPacketsReceived = 950, takionPacketsLost = 50,
			congestionMeasuredLoss = 0.05)
		val weakWifi = NetworkLinkSample(NetworkLinkType.WIFI, rssiDbm = -72, linkSpeedMbps = 100)
		assertEquals(NetworkQualityCause.WIFI_LINK,
			NetworkQualityClassifier().update(lossy, weakWifi).cause)
		assertEquals(NetworkQualityCause.LAN,
			NetworkQualityClassifier().update(lossy, ethernet).cause)
	}

	@Test
	fun elevatedRttIsAPathSignatureEvenWithServerLoss()
	{
		// PLE-355: an elevated RTT is our own measured round trip, so it moving is a path
		// fault regardless of what the console's loss counter says -- exactly the case a
		// rate-capped link produces. Server-reported loss no longer overrides a dirty RTT.
		val consoleLimited = base.copy(probeRttMicros = 45_000, serverLoss = 2)
		val result = NetworkQualityClassifier().update(consoleLimited, ethernet)
		assertEquals(NetworkQualityLevel.POOR, result.level)
		assertEquals(NetworkQualityCause.LAN, result.cause)
	}

	@Test
	fun consoleEvidenceWinsOnlyWhenLocalTransportHasTrulyRecovered()
	{
		// The slow window keeps the level POOR under recovery hysteresis after RTT/jitter/
		// loss have already gone clean in the fast window. That is the one case left where
		// server-reported loss can be believed as the console's own fault: nothing in our
		// own measurement (RTT included) is still moving, so a live report from the console
		// is not otherwise explained.
		val classifier = NetworkQualityClassifier()
		val bad = base.copy(probeRttMicros = 45_000)
		repeat(NetworkQualityThresholds.SLOW_WINDOW_SECONDS) { classifier.update(bad, ethernet) }
		// Enough clean samples to flush the 5-sample fast window; the 30-sample slow window
		// is still mostly the earlier bad samples, so its median keeps recovery from firing.
		var result = NetworkQualitySnapshot.UNKNOWN
		repeat(NetworkQualityThresholds.FAST_WINDOW_SECONDS) {
			result = classifier.update(base.copy(serverLoss = 2), ethernet)
		}
		assertEquals(NetworkQualityLevel.POOR, result.level)
		assertEquals(NetworkQualityCause.CONSOLE, result.cause)
	}

	@Test
	fun ple343CapturesRateCappedPathsAreNeverBlamedOnTheConsole()
	{
		// PLE-343's impairment-rig captures (build/captures/ple343-verify), medians per 60 s
		// phase with the first 10 s of each dropped while the shaper step settles:
		//   phase      | probe RTT | jitter | congestion loss | measured/target throughput
		//   01_clean   |    5.2 ms |  2.14  |            0.0% | 6.59M/9.71M (0.68) -- GOOD
		//   02_5g      |   25.8 ms |  3.11  |            0.0% | 6.56M/9.71M (0.68)
		//   03_4g      |   63.0 ms |  2.72  |            0.8% | 5.17M/8.50M (0.61)
		//   04_wifi-slow| 40.7 ms  |  2.89  |            0.0% | 5.27M/8.18M (0.65)
		//   05_clean   |    5.5 ms |  2.53  |            0.0% | 5.23M/7.83M (0.67)
		// The ratio sits at 0.61-0.68 in every phase including the two with zero impairment,
		// so it carries no information -- exactly PLE-355's finding -- and 4g/wifi-slow's
		// rate caps show up as elevated RTT, our own measurement, which is what correctly
		// keeps them off "console" now.
		fun phaseCause(rttMillis: Double, jitterMillis: Double, lossPercent: Double,
			targetBps: Long, measuredBps: Long): NetworkQualityCause
		{
			val classifier = NetworkQualityClassifier()
			val lost = (10_000 * lossPercent / 100.0).toLong()
			var result = NetworkQualitySnapshot.UNKNOWN
			repeat(NetworkQualityThresholds.SLOW_WINDOW_SECONDS)
			{
				result = classifier.update(base.copy(
					probeRttMicros = (rttMillis * 1000).toLong(),
					videoPacketJitterMicros = (jitterMillis * 1000).toLong(),
					takionPacketsReceived = 10_000 - lost, takionPacketsLost = lost,
					targetBitrateBps = targetBps, measuredThroughputBps = measuredBps), ethernet)
			}
			return result.cause
		}
		assertEquals(NetworkQualityCause.NONE, phaseCause(5.2, 2.14, 0.0, 9_708_000, 6_588_352))
		assertEquals(NetworkQualityCause.LAN, phaseCause(25.8, 3.11, 0.0, 9_708_000, 6_559_984))
		assertEquals(NetworkQualityCause.LAN, phaseCause(63.0, 2.72, 0.8, 8_500_000, 5_166_518))
		assertEquals(NetworkQualityCause.LAN, phaseCause(40.7, 2.89, 0.0, 8_175_000, 5_274_452))
		assertEquals(NetworkQualityCause.NONE, phaseCause(5.5, 2.53, 0.0, 7_825_000, 5_230_172))
	}
}
