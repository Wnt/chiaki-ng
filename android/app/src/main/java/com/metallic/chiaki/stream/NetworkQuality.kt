// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import com.metallic.chiaki.lib.StreamStatsEvent
import kotlin.math.max

internal object NetworkQualityThresholds
{
	// The producer is the diagnostics event's fixed 1 Hz cadence, so samples equal seconds.
	const val FAST_WINDOW_SECONDS = 5
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

	// PLE-366: the tail arm. A median over five one-second samples is by construction blind to
	// a stall that is shorter than half the window, and `blip-200ms` -- 200 ms of 200 ms delay
	// every 20 s, the profile that models a recurring hitch -- is exactly that shape: one bad
	// second in twenty. Measured over build/captures/ple356 + ple357 (443 samples of the
	// post-PLE-356 estimator; ple343/* carry the old packet-gap EWMA and are not comparable),
	// the six blip events are one second wide each and read:
	//   jitter ms | 3.71  4.85  9.87  89.77  1.95  2.03
	//   loss %    | 1.76 12.02 14.53  16.94  1.73  1.94
	//   probe rtt |  3.70  6.73  7.62   7.46  5.88  6.46
	// against 335 clean samples whose jitter maxes at 3.71 ms, whose loss is exactly 0.0000 %
	// in every one, and whose probe RTT maxes at 12.07 ms.
	//
	// So: no RTT tail arm. The probe is itself a ~1 Hz measurement and never once landed inside
	// a 200 ms blip -- the blip phases' probe max, 11.74 ms, is *below* the clean phases' 12.07 ms.
	// There is nothing in the capture to cut on, and a cut chosen without one is the intuition
	// this ticket exists to replace.
	//
	// Jitter alone is not enough either: only three of the six events reach 4.0 ms, and the
	// worst clean sample (3.71 ms) equals one of them. Loss is what separates them cleanly --
	// every event reaches 1.73 %, every clean sample reads 0.
	//
	// Thresholds sit at the same boundaries the median arm calls CONSTRAINED, because both are
	// derived from the same clean-LAN floor; what is new is that *one* sample in the window
	// reaching them is enough. Replayed over the corpus that fires on 0 of 311 clean windows
	// and catches 6 of 6 blip events (22 of 100 blip windows, i.e. the badge is non-good for
	// ~8 s of every 20 s the stall recurs). Margin on clean is the whole cut: 0.00 vs 0.20.
	const val TAIL_RATE_CUT = 0.2
	const val TAIL_JITTER_MS = 4.0
	const val TAIL_LOSS_PERCENT = 1.0

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
	val fastLossPercent: Double = 0.0,
	/** PLE-366: the worse of the two tail arms' rates, for the diagnostic log. The chip does
	 * not show it -- it explains a verdict the medians alone cannot account for. */
	val tailJitterRate: Double = 0.0,
	val tailLossRate: Double = 0.0
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
		// PLE-403: videoPacketJitterMicros is one unsmoothed frame sample, not an average, until
		// the native EWMA reports filled (CHIAKI_TAKION_VIDEO_JITTER_FILL_SAMPLES). A one-off
		// startup delay -- the first frame after connect is commonly late -- then reads back
		// indistinguishable from real jitter, and both the fast median and the tail-rate arm
		// (PLE-366) take a single sample at face value by design, so a startup transient opens
		// the badge at CONSTRAINED every session (measured: 7 s, PLE-403). "Filled" is the right
		// condition because it names exactly the moment the value stops being one raw sample --
		// not a tuned delay, and the same signal a real bad second still has by the time the
		// badge reacts to it (a stall long enough for the estimator to have any samples at all
		// has already filled it, since fill takes a fraction of a second of frames).
		if(!stats.videoPacketJitterFilled)
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
		while(samples.size > NetworkQualityThresholds.FAST_WINDOW_SECONDS)
			samples.removeFirst()

		val fast = median(samples)
		val tailJitterRate = tailRate(samples) { it.jitterMillis >= NetworkQualityThresholds.TAIL_JITTER_MS }
		val tailLossRate = tailRate(samples) { it.lossPercent >= NetworkQualityThresholds.TAIL_LOSS_PERCENT }
		level = nextLevel(fast, tailLevel(tailJitterRate, tailLossRate))
		return NetworkQualitySnapshot(
			level = level,
			cause = cause(level, stats, link, fast),
			fastRttMillis = fast.rttMillis,
			fastJitterMillis = fast.jitterMillis,
			fastLossPercent = fast.lossPercent,
			tailJitterRate = tailJitterRate,
			tailLossRate = tailLossRate
		)
	}

	/** PLE-366: the share of the window at or above a tail threshold. The window can be short
	 * while it fills, so this is a rate over the samples actually held, not over
	 * FAST_WINDOW_SECONDS -- a single bad second must not have to wait 5 s to be reportable. */
	private fun tailRate(values: List<NetworkQualitySample>, over: (NetworkQualitySample) -> Boolean) =
		if(values.isEmpty()) 0.0 else values.count(over) / values.size.toDouble()

	// The cut is one sample in five, so `rate >= cut` is a comparison of 1.0/5.0 against the
	// literal 0.2. Those are the same double today, but the window shortens while it fills and
	// the epsilon costs nothing, so the arm does not hinge on that staying true.
	private fun tailLevel(jitterRate: Double, lossRate: Double): NetworkQualityLevel =
		if(jitterRate >= NetworkQualityThresholds.TAIL_RATE_CUT - 1e-9 ||
			lossRate >= NetworkQualityThresholds.TAIL_RATE_CUT - 1e-9)
			NetworkQualityLevel.CONSTRAINED else NetworkQualityLevel.GOOD

	// PLE-357: there used to be a second, 30-sample "slow" window feeding the same candidate as
	// the 5-sample fast one (`candidate = maxOf(enterLevel(fast), enterLevel(slow))`), so a
	// genuinely bad spell stayed live in that buffer for up to 30 s after the path went clean.
	// A worse verdict from the slow median could also re-fire *after* the fast window had
	// already recovered the badge down a level -- because the slow window is a strict superset
	// of history, its own median lags the fast one by construction, so the "candidate worse than
	// current level" check re-triggered on stale data and flipped the badge back up
	// (POOR -> CONSTRAINED -> POOR) before the slow window emptied. That is the oscillation this
	// card is explicitly here to avoid, so the second window is gone rather than patched: one
	// 5 s median drives both directions, asymmetric only in how many *consecutive* samples each
	// direction needs. Getting worse needs one (`candidate.ordinal > level.ordinal`, unchanged
	// from before this ticket -- worseningToPoorStillTakesOnlyThreeSamples shows the median
	// itself still needs three bad samples of five to move, same math as before). Recovering
	// needs three consecutive samples with the fast median below the exit boundary
	// (RECOVERY_SAMPLES), so a single clean sample can't flip it back and a single dirty one
	// resets the count. Worst case that is 5 s to fill the window with clean samples plus 3 more
	// to confirm: 8 s, deterministic, versus the ~25-30 s the slow window produced --
	// recoveryReturnsToGoodInEightSecondsNotThirty asserts the exact number. The same 5-sample
	// median that already resists one outlier out of five (oneRetransmitSizedOutlierDoesNotReachPoor)
	// resists it on the way down too, so a recurring single-sample blip never round-trips the
	// badge on the RTT arm (rttBlipShapedSpikesStillDoNotFlipTheLevel).
	//
	// PLE-366 adds the second arm this comment used to describe as a feature. The median's
	// blindness to a one-in-five outlier is correct for a retransmit-sized RTT sample and wrong
	// for a stall the player feels, and the two cannot be told apart by the median alone --
	// that is what the tail arm is for. The worse of the two decides (`maxOf` below), so
	// entering is unchanged for everything the median already caught and now also fires on a
	// single bad second. Recovery needs no new code: while the outlier is still in the window
	// the tail arm keeps `candidate` at CONSTRAINED, which lands on the `candidate.ordinal >=
	// level.ordinal` branch and resets recoveryCount, so the badge cannot recover until the
	// outlier has aged out (5 s) and RECOVERY_SAMPLES have confirmed (3 s) -- the same
	// deterministic 8 s PLE-357 measured. One deliberate asymmetry: a tail firing does not
	// block the step down from POOR to CONSTRAINED, only the step to GOOD. Otherwise a stall
	// recurring every 20 s would pin the badge at POOR indefinitely after one bad spell, which
	// is PLE-357's defect wearing a different hat.
	private fun nextLevel(fast: NetworkQualitySample, tail: NetworkQualityLevel): NetworkQualityLevel
	{
		val candidate = maxOf(enterLevel(fast), tail)
		if(level == NetworkQualityLevel.UNKNOWN || candidate.ordinal > level.ordinal)
		{
			recoveryCount = 0
			return candidate
		}
		if(candidate.ordinal >= level.ordinal)
		{
			recoveryCount = 0
			return level
		}
		val canRecover = when(level)
		{
			NetworkQualityLevel.POOR -> fast.rttMillis < NetworkQualityThresholds.POOR_EXIT_RTT_MS &&
				fast.jitterMillis < NetworkQualityThresholds.POOR_EXIT_JITTER_MS &&
				fast.lossPercent < NetworkQualityThresholds.POOR_EXIT_LOSS_PERCENT
			NetworkQualityLevel.CONSTRAINED -> fast.rttMillis < NetworkQualityThresholds.GOOD_RTT_MS &&
				fast.jitterMillis < NetworkQualityThresholds.GOOD_JITTER_MS &&
				fast.lossPercent < NetworkQualityThresholds.GOOD_LOSS_PERCENT
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

	/** Median, not mean. The window is five one-per-second samples, so a single
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
