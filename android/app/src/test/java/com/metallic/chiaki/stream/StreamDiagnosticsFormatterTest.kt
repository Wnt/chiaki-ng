// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import com.metallic.chiaki.lib.StreamStatsEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamDiagnosticsFormatterTest
{
	private val ui = StreamDiagnosticsUiState(
		display = StreamDiagnosticsDisplay(1920, 1080, 59.94f, 7),
		viewMode = "fit",
		flags = listOf("lowlat", "pts"),
		presenterMode = "balanced"
	)

	@Test
	fun zeroStatsPreviewContainsEverySection()
	{
		val text = StreamDiagnosticsFormatter.format(null, ui)
		assertTrue(text.contains("stream 0.0 fps | decoder 0.0 fps"))
		assertTrue(text.contains("decode 0.00 ms mean | 0.00 ms p95"))
		assertTrue(text.contains("Takion 0.0 pkt/s | loss 0.00% | feedback 0.0 pkt/s"))
		assertTrue(text.contains("network UNKNOWN (none) | 0.00/0.00 Mbps actual/target"))
		assertTrue(text.contains("audio 0.00 ms | xruns 0 | underruns 0"))
		assertTrue(text.contains("vsync 0.000 ms | miss 0"))
		assertTrue(text.contains("display 1920x1080@59.94 Hz mode 7 | view=fit"))
		assertTrue(text.endsWith("flags lowlat pts | presenter=balanced"))
	}

	@Test
	fun ratesAndLossUseNativeWindowDuration()
	{
		val stats = StreamStatsEvent(
			intervalMillis = 2000,
			rttMicros = 4_250,
			streamFrames = 120,
			decoderFrames = 118,
			decodeMeanMicros = 8125,
			decodeP95Micros = 10750,
			decoderInputFramesDropped = 2,
			presenterFramesDropped = 3,
			missedVsyncs = 4,
			videoFramesLost = 5,
			reorderQueueTimeouts = 6,
			videoPacketJitterMicros = 2_750,
			takionPacketsReceived = 1800,
			takionPacketsLost = 200,
			feedbackPackets = 240,
			dejitterBufferNanos = 8_000_000,
			cadenceDepthNanos = 6_000_000,
			cadenceTargetNanos = 5_000_000,
			cadenceErrP50Nanos = 1_000_000,
			cadenceErrP99Nanos = 3_000_000,
			decodeEwmaNanos = 8_500_000,
			cadenceWindowDrops = 9,
			vsyncPeriodNanos = 8_333_333,
			presenterQueueDepth = 1,
			audioLatencyMicros = 12_500,
			audioXruns = 7,
			audioUnderruns = 8,
			cadenceHalfRateDetected = true
		)
		val text = StreamDiagnosticsFormatter.format(stats, ui)
		assertTrue(text.contains("stream 60.0 fps | decoder 59.0 fps"))
		assertTrue(text.contains("decode 8.13 ms mean | 10.75 ms p95 | q 1"))
		assertTrue(text.contains("drop-in 2 | late 3 | lost 5 | reorder 6"))
		assertTrue(text.contains("network UNKNOWN (none) | 0.00/0.00 Mbps actual/target"))
		assertTrue(text.contains("loss 0.00/0.00% measured/reported | RTT 4.25 ms startup | jitter 2.75 ms"))
		assertTrue(text.contains("Takion 900.0 pkt/s | loss 10.00% | feedback 120.0 pkt/s"))
		assertTrue(text.contains("audio 12.50 ms | xruns 7 | underruns 8"))
		assertTrue(text.contains("vsync 8.333 ms | miss 4 | DJB 8.0 ms"))
		assertTrue(text.contains("stage0 D 6.0 target 5.0 | err p50 1.0 p99 3.0 ms | decode-ewma 8.5 | drops 9 | source 30-in-60"))
		assertEquals(11, text.lines().size)
	}

	@Test
	fun networkBlockUsesLiveTelemetryAndCause()
	{
		val stats = StreamStatsEvent(
			intervalMillis = 1000, rttMicros = 9_000, streamFrames = 0, decoderFrames = 0,
			decodeMeanMicros = 0, decodeP95Micros = 0, decoderInputFramesDropped = 0,
			presenterFramesDropped = 0, missedVsyncs = 0, videoFramesLost = 0,
			reorderQueueTimeouts = 0, videoPacketJitterMicros = 6_000,
			takionPacketsReceived = 0, takionPacketsLost = 0, feedbackPackets = 0,
			dejitterBufferNanos = 0, cadenceDepthNanos = 0, cadenceTargetNanos = 0,
			cadenceErrP50Nanos = 0, cadenceErrP99Nanos = 0, decodeEwmaNanos = 0,
			cadenceWindowDrops = 0, vsyncPeriodNanos = 0, presenterQueueDepth = 0,
			audioLatencyMicros = 0, audioXruns = 0, audioUnderruns = 0,
			connectionQualityValid = true, targetBitrateBps = 15_000_000,
			measuredThroughputBps = 12_500_000, liveRttMicros = 23_500,
			serverLoss = 2, congestionMeasuredLoss = 0.0375,
			congestionReportedLoss = 0.01
		)
		val quality = NetworkQualitySnapshot(NetworkQualityLevel.POOR, NetworkQualityCause.WIFI_LINK)
		val text = StreamDiagnosticsFormatter.format(stats, ui, quality)
		assertTrue(text.contains("network POOR (Wi-Fi link) | 12.50/15.00 Mbps actual/target"))
		assertTrue(text.contains("loss 3.75/1.00% measured/reported | RTT 23.50 ms live | jitter 6.00 ms"))
	}

	@Test
	fun performanceFlagsOnlyNameLiveLegs()
	{
		assertEquals(emptyList<String>(), StreamActivity.performanceModeDiagnosticFlags(false, false, false))
		assertEquals(listOf("perf-oprate"), StreamActivity.performanceModeDiagnosticFlags(true, false, false))
		assertEquals(
			listOf("perf-oprate", "perf-sustained", "perf-adpf"),
			StreamActivity.performanceModeDiagnosticFlags(true, true, true)
		)
	}
}
