// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import com.metallic.chiaki.lib.StreamStatsEvent
import kotlin.math.max

internal object NetworkQualityThresholds
{
	// The producer is the diagnostics event's fixed 1 Hz cadence, so samples equal seconds.
	const val FAST_WINDOW_SECONDS = 5
	const val SLOW_WINDOW_SECONDS = 30
	const val RECOVERY_SAMPLES = 3

	const val CONSTRAINED_RTT_MS = 20.0
	const val POOR_RTT_MS = 40.0
	const val CONSTRAINED_JITTER_MS = 4.0
	const val POOR_JITTER_MS = 10.0
	const val CONSTRAINED_LOSS_PERCENT = 1.0
	const val POOR_LOSS_PERCENT = 3.0

	// A lower exit boundary prevents a metric near a boundary from flapping the label.
	const val GOOD_RTT_MS = 15.0
	const val GOOD_JITTER_MS = 3.0
	const val GOOD_LOSS_PERCENT = 0.5
	const val POOR_EXIT_RTT_MS = 30.0
	const val POOR_EXIT_JITTER_MS = 7.0
	const val POOR_EXIT_LOSS_PERCENT = 2.0

	const val WEAK_WIFI_RSSI_DBM = -67
	const val WIFI_TARGET_HEADROOM = 2.0
	const val CONSOLE_THROUGHPUT_RATIO = 0.75
}

internal enum class NetworkQualityLevel { UNKNOWN, GOOD, CONSTRAINED, POOR }
internal enum class NetworkQualityCause { NONE, WIFI_LINK, LAN, CONSOLE }
internal enum class NetworkLinkType { WIFI, OTHER, UNKNOWN }

internal data class NetworkLinkSample(
	val type: NetworkLinkType,
	val rssiDbm: Int? = null,
	val linkSpeedMbps: Int? = null
)

internal data class NetworkQualitySnapshot(
	val level: NetworkQualityLevel,
	val cause: NetworkQualityCause,
	val fastRttMillis: Double = 0.0,
	val fastJitterMillis: Double = 0.0,
	val fastLossPercent: Double = 0.0
)
{
	companion object
	{
		val UNKNOWN = NetworkQualitySnapshot(NetworkQualityLevel.UNKNOWN, NetworkQualityCause.NONE)
	}
}

internal data class NetworkQualityChipMetrics(
	val rttMillis: Double,
	val jitterMillis: Double,
	val lossPercent: Double
)

internal data class NetworkQualityChipContent(
	val level: NetworkQualityLevel,
	val metrics: NetworkQualityChipMetrics?
)

internal object NetworkQualityChipPresenter
{
	fun content(snapshot: NetworkQualitySnapshot, expanded: Boolean) = NetworkQualityChipContent(
		level = snapshot.level,
		metrics = if(expanded && snapshot.level != NetworkQualityLevel.UNKNOWN)
			NetworkQualityChipMetrics(
				rttMillis = snapshot.fastRttMillis,
				jitterMillis = snapshot.fastJitterMillis,
				lossPercent = snapshot.fastLossPercent
			)
		else null
	)
}

private data class NetworkQualitySample(
	val rttMillis: Double,
	val jitterMillis: Double,
	val lossPercent: Double
)

/** Stateful 1 Hz quality classifier. It belongs to the default-off overlay, not the stream path. */
internal class NetworkQualityClassifier
{
	private val samples = ArrayDeque<NetworkQualitySample>()
	private var level = NetworkQualityLevel.UNKNOWN
	private var recoveryCount = 0

	fun update(stats: StreamStatsEvent, link: NetworkLinkSample): NetworkQualitySnapshot
	{
		val packetTotal = stats.takionPacketsReceived + stats.takionPacketsLost
		if(!stats.connectionQualityValid && packetTotal == 0L)
			return NetworkQualitySnapshot.UNKNOWN

		val packetLoss = if(packetTotal > 0L)
			stats.takionPacketsLost * 100.0 / packetTotal else 0.0
		val sample = NetworkQualitySample(
			// PLE-343: only round trips we measured ourselves. The console's rtt field is
			// not a network RTT (193 -> 68 ms decaying on a 3.8 ms LAN) and never drives the badge.
			rttMillis = stats.measuredRttMicros / 1000.0,
			jitterMillis = stats.videoPacketJitterMicros / 1000.0,
			lossPercent = max(packetLoss, stats.congestionMeasuredLoss * 100.0)
		)
		samples.addLast(sample)
		while(samples.size > NetworkQualityThresholds.SLOW_WINDOW_SECONDS)
			samples.removeFirst()

		val fast = average(samples.takeLast(NetworkQualityThresholds.FAST_WINDOW_SECONDS))
		val slow = average(samples)
		val candidate = maxOf(enterLevel(fast), enterLevel(slow))
		level = nextLevel(candidate, slow)
		return NetworkQualitySnapshot(
			level = level,
			cause = cause(level, stats, link, fast),
			fastRttMillis = fast.rttMillis,
			fastJitterMillis = fast.jitterMillis,
			fastLossPercent = fast.lossPercent
		)
	}

	private fun nextLevel(candidate: NetworkQualityLevel, slow: NetworkQualitySample): NetworkQualityLevel
	{
		if(level == NetworkQualityLevel.UNKNOWN || candidate.ordinal > level.ordinal)
		{
			recoveryCount = 0
			return candidate
		}
		if(candidate == level)
		{
			recoveryCount = 0
			return level
		}
		val canRecover = when(level)
		{
			NetworkQualityLevel.POOR -> slow.rttMillis < NetworkQualityThresholds.POOR_EXIT_RTT_MS &&
				slow.jitterMillis < NetworkQualityThresholds.POOR_EXIT_JITTER_MS &&
				slow.lossPercent < NetworkQualityThresholds.POOR_EXIT_LOSS_PERCENT
			NetworkQualityLevel.CONSTRAINED -> slow.rttMillis < NetworkQualityThresholds.GOOD_RTT_MS &&
				slow.jitterMillis < NetworkQualityThresholds.GOOD_JITTER_MS &&
				slow.lossPercent < NetworkQualityThresholds.GOOD_LOSS_PERCENT
			else -> true
		}
		recoveryCount = if(canRecover) recoveryCount + 1 else 0
		if(recoveryCount < NetworkQualityThresholds.RECOVERY_SAMPLES)
			return level
		recoveryCount = 0
		return if(level == NetworkQualityLevel.POOR && candidate == NetworkQualityLevel.GOOD)
			NetworkQualityLevel.CONSTRAINED else candidate
	}

	private fun enterLevel(sample: NetworkQualitySample): NetworkQualityLevel = when
	{
		sample.rttMillis >= NetworkQualityThresholds.POOR_RTT_MS ||
			sample.jitterMillis >= NetworkQualityThresholds.POOR_JITTER_MS ||
			sample.lossPercent >= NetworkQualityThresholds.POOR_LOSS_PERCENT -> NetworkQualityLevel.POOR
		sample.rttMillis >= NetworkQualityThresholds.CONSTRAINED_RTT_MS ||
			sample.jitterMillis >= NetworkQualityThresholds.CONSTRAINED_JITTER_MS ||
			sample.lossPercent >= NetworkQualityThresholds.CONSTRAINED_LOSS_PERCENT -> NetworkQualityLevel.CONSTRAINED
		else -> NetworkQualityLevel.GOOD
	}

	private fun cause(level: NetworkQualityLevel, stats: StreamStatsEvent, link: NetworkLinkSample,
		fast: NetworkQualitySample): NetworkQualityCause
	{
		if(level == NetworkQualityLevel.GOOD || level == NetworkQualityLevel.UNKNOWN)
			return NetworkQualityCause.NONE
		val weakRadio = link.type == NetworkLinkType.WIFI && (
			(link.rssiDbm != null && link.rssiDbm <= NetworkQualityThresholds.WEAK_WIFI_RSSI_DBM) ||
			(link.linkSpeedMbps != null && stats.targetBitrateBps > 0L &&
				link.linkSpeedMbps * 1_000_000.0 < stats.targetBitrateBps * NetworkQualityThresholds.WIFI_TARGET_HEADROOM)
		)
		if(weakRadio)
			return NetworkQualityCause.WIFI_LINK

		val consoleEvidence = stats.connectionQualityValid &&
			(stats.serverLoss > 0L || (stats.targetBitrateBps > 0L &&
				stats.measuredThroughputBps < stats.targetBitrateBps * NetworkQualityThresholds.CONSOLE_THROUGHPUT_RATIO))
		val localTransportClean = fast.jitterMillis < NetworkQualityThresholds.CONSTRAINED_JITTER_MS &&
			fast.lossPercent < NetworkQualityThresholds.CONSTRAINED_LOSS_PERCENT
		return if(consoleEvidence && localTransportClean) NetworkQualityCause.CONSOLE else NetworkQualityCause.LAN
	}

	private fun average(values: List<NetworkQualitySample>) = NetworkQualitySample(
		rttMillis = values.sumOf { it.rttMillis } / values.size,
		jitterMillis = values.sumOf { it.jitterMillis } / values.size,
		lossPercent = values.sumOf { it.lossPercent } / values.size
	)

}
