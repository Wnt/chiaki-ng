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
		assertTrue(text.contains("display 1920x1080@59.94 Hz mode 7 | view=fit"))
		assertTrue(text.endsWith("flags lowlat pts | presenter=balanced"))
	}

	@Test
	fun ratesAndLossUseNativeWindowDuration()
	{
		val stats = StreamStatsEvent(
			intervalMillis = 2000,
			streamFrames = 120,
			decoderFrames = 118,
			decodeMeanMicros = 8125,
			decodeP95Micros = 10750,
			decoderInputFramesDropped = 2,
			presenterFramesDropped = 3,
			missedVsyncs = 4,
			videoFramesLost = 5,
			reorderQueueTimeouts = 6,
			takionPacketsReceived = 1800,
			takionPacketsLost = 200,
			feedbackPackets = 240,
			dejitterBufferNanos = 8_000_000,
			presenterQueueDepth = 1
		)
		val text = StreamDiagnosticsFormatter.format(stats, ui)
		assertTrue(text.contains("stream 60.0 fps | decoder 59.0 fps"))
		assertTrue(text.contains("decode 8.13 ms mean | 10.75 ms p95 | q 1"))
		assertTrue(text.contains("drop-in 2 | late 3 | lost 5 | reorder 6"))
		assertTrue(text.contains("Takion 900.0 pkt/s | loss 10.00% | feedback 120.0 pkt/s"))
		assertEquals(7, text.lines().size)
	}
}
