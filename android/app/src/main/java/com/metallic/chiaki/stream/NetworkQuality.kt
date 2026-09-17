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
			// PLE-343: only round trips we measured ourselves. The console's rtt field is a
			// sawtooth -- it resets to ~200 and ramps down to ~0 at about 27 units/s, over and
			// over -- and its per-phase median stayed 105-116 while the measured RTT was stepped
			// 5.6 -> 26.6 -> 62.0 -> 42.8 ms under it. It never drives the badge.
			rttMillis = stats.measuredRttMicros / 1000.0,
			// PLE-356: this is a per-frame delay variation now. It used to be an EWMA over
			// every video packet, and ~9 of every 10 video packets share their frame's index,
			// so their sender delta was zero and the sample was an intra-burst gap of tens of
			// microseconds. It read 2.14 -> 3.11 ms while the path's delay variation was
			// stepped 1.8 -> 13.3 ms, never once reaching CONSTRAINED_JITTER_MS. The
			// attenuation was the packets per frame, which is a function of bitrate, so no
			// rescaling could have recovered it -- the estimator had to change.
			jitterMillis = stats.videoPacketJitterMicros / 1000.0,
			lossPercent = max(packetLoss, stats.congestionMeasuredLoss * 100.0)
		)
		samples.addLast(sample)
		while(samples.size > NetworkQualityThresholds.SLOW_WINDOW_SECONDS)
			samples.removeFirst()

		val fast = median(samples.takeLast(NetworkQualityThresholds.FAST_WINDOW_SECONDS))
		val slow = median(samples)
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

		// PLE-355: throughput below 75% of target is not evidence of anything. PLE-343's
		// impairment captures put the measured/target ratio at 0.61-0.68 in every one of
		// five phases, including the two with no impairment at all -- it is encoder
		// headroom, not a symptom, so a rate-capped path (4g/wifi-slow's tc caps) produces
		// the identical ratio a struggling console would. It cannot tell the two apart and
		// is not used here.
		//
		// What does: RTT here is our own measured round trip (PLE-343), so a fault in the
		// path raises it together with loss, while a console/encoder problem leaves RTT and
		// loss flat and only throughput falls. In those same captures RTT alone is what
		// pushed the 5g/4g/wifi-slow phases out of GOOD, so a clean local RTT -- not just
		// clean jitter and loss -- is what has to hold before the evidence can be read as
		// the console's fault rather than the path's.
		val consoleEvidence = stats.connectionQualityValid && stats.serverLoss > 0L
		val localTransportClean = fast.rttMillis < NetworkQualityThresholds.CONSTRAINED_RTT_MS &&
			fast.jitterMillis < NetworkQualityThresholds.CONSTRAINED_JITTER_MS &&
			fast.lossPercent < NetworkQualityThresholds.CONSTRAINED_LOSS_PERCENT
		return if(consoleEvidence && localTransportClean) NetworkQualityCause.CONSOLE else NetworkQualityCause.LAN
	}

	/** Median, not mean. The window is five or thirty one-per-second samples, so a single
	 * outlier moves a mean by a fifth of itself: PLE-343 measured one 228.8 ms RTT sample
	 * lifting a five-sample mean of a 25 ms link to 65 ms, which is Poor. The median of the
	 * same window is 25 ms. A condition that lasts long enough to matter to the viewer moves
	 * the median too, so nothing real is suppressed. */
	private fun median(values: List<NetworkQualitySample>) = NetworkQualitySample(
		rttMillis = middle(values.map { it.rttMillis }),
		jitterMillis = middle(values.map { it.jitterMillis }),
		lossPercent = middle(values.map { it.lossPercent })
	)

	private fun middle(values: List<Double>): Double
	{
		val sorted = values.sorted()
		val mid = sorted.size / 2
		return if(sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
	}

}
