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
	fun fiveSampleFastWindowSmoothsRttAndUsesNamedBoundaries()
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
	fun consoleEvidenceWinsWhenClientLossAndJitterAreClean()
	{
		val consoleLimited = base.copy(probeRttMicros = 45_000, serverLoss = 2)
		val result = NetworkQualityClassifier().update(consoleLimited, ethernet)
		assertEquals(NetworkQualityLevel.POOR, result.level)
		assertEquals(NetworkQualityCause.CONSOLE, result.cause)
	}
}
