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

	/** One second as the classifier reads it: `packet_jitter_ms` and the transport loss the
	 * captures report, so a row of a capture table can be pasted in as-is. */
	private fun sample(jitterMillis: Double, lossPercent: Double): StreamStatsEvent
	{
		val lost = Math.round(10_000 * lossPercent / 100.0)
		return base.copy(
			videoPacketJitterMicros = Math.round(jitterMillis * 1000),
			takionPacketsReceived = 10_000 - lost, takionPacketsLost = lost)
	}

	// PLE-352: the chip reads this same classifier's fastRttMillis (StreamActivity feeds
	// both from one stats event, in the same call), so this is also the chip's guarantee.
	// A PLE-352 device capture (docs/verification/PLE-352/) found the chip tracking the
	// phone's own ping through clean/5g/4g/wifi-slow while console_rtt_raw sat in its usual
	// 0-207 sawtooth (median 83-126 across the same phases) -- this pins that down as a
	// property of the code, not just of the one run that was captured.
	@Test
	fun consoleRttRawNeverMovesTheFastMedian()
	{
		val classifier = NetworkQualityClassifier()
		repeat(NetworkQualityThresholds.FAST_WINDOW_SECONDS)
		{
			classifier.update(base.copy(probeRttMicros = 5_000, consoleRttRaw = 187.6), ethernet)
		}
		val result = classifier.update(base.copy(probeRttMicros = 5_000, consoleRttRaw = 0.4), ethernet)
		assertEquals(NetworkQualityLevel.GOOD, result.level)
		assertEquals(5.0, result.fastRttMillis, 0.001)
	}

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
			repeat(NetworkQualityThresholds.FAST_WINDOW_SECONDS)
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
		// PLE-357 removed the second, 30-sample window recovery used to be judged on, so this no
		// longer lingers at POOR -- it lands on POOR's graceful step-down, CONSTRAINED, exactly
		// at the fast window's fill point. RTT/jitter/loss are clean in that same fast window,
		// so nothing in our own measurement explains the remaining degradation; a live
		// server-reported loss is the one thing left that does, hence CONSOLE.
		val classifier = NetworkQualityClassifier()
		val bad = base.copy(probeRttMicros = 45_000)
		repeat(NetworkQualityThresholds.FAST_WINDOW_SECONDS) { classifier.update(bad, ethernet) }
		var result = NetworkQualitySnapshot.UNKNOWN
		repeat(NetworkQualityThresholds.FAST_WINDOW_SECONDS) {
			result = classifier.update(base.copy(serverLoss = 2), ethernet)
		}
		assertEquals(NetworkQualityLevel.CONSTRAINED, result.level)
		assertEquals(NetworkQualityCause.CONSOLE, result.cause)
	}

	@Test
	fun recoveryReturnsToGoodInEightSecondsNotThirty()
	{
		// PLE-357: recovery used to be judged on a second, 30-sample slow median, so a POOR
		// verdict lingered until a majority of that 30 s buffer was clean again -- measured on
		// the rig at ~25 s. Recovery is now judged on the same 5-sample fast median entering
		// uses: the window itself needs 3 clean samples out of 5 to move (worst case), then 3
		// more consecutive clean readings (RECOVERY_SAMPLES) confirm it -- a deterministic 8 s
		// from the step to GOOD, landing on CONSTRAINED first as the same graceful step-down
		// POOR->GOOD always took.
		val classifier = NetworkQualityClassifier()
		val bad = base.copy(probeRttMicros = 60_000)
		repeat(NetworkQualityThresholds.FAST_WINDOW_SECONDS) { classifier.update(bad, ethernet) }
		var result = NetworkQualitySnapshot.UNKNOWN
		repeat(7) { result = classifier.update(base, ethernet) }
		assertEquals(NetworkQualityLevel.CONSTRAINED, result.level)
		result = classifier.update(base, ethernet)
		assertEquals(NetworkQualityLevel.GOOD, result.level)
	}

	@Test
	fun worseningToPoorStillTakesOnlyThreeSamples()
	{
		// PLE-357 touches only recovery. Getting worse must stay exactly as fast as before:
		// the fast window's median crosses into POOR as soon as bad samples are the majority
		// of the last five, same as fiveSampleFastWindowFollowsRttAndUsesNamedBoundaries.
		val classifier = NetworkQualityClassifier()
		repeat(10) { classifier.update(base, ethernet) }
		val bad = base.copy(probeRttMicros = 60_000)
		assertEquals(NetworkQualityLevel.GOOD, classifier.update(bad, ethernet).level)
		assertEquals(NetworkQualityLevel.GOOD, classifier.update(bad, ethernet).level)
		assertEquals(NetworkQualityLevel.POOR, classifier.update(bad, ethernet).level)
	}

	@Test
	fun rttBlipShapedSpikesStillDoNotFlipTheLevel()
	{
		// PLE-357's adversarial case, kept because PLE-366 deliberately did not give RTT a tail
		// arm: across ple356 + ple357 the ~1 Hz probe never once landed inside a 200 ms blip
		// (blip phases' probe max 11.74 ms, *below* the clean phases' 12.07 ms), so there is no
		// measured rate to cut on and a single high probe sample stays what PLE-343 called it --
		// a retransmit, not a stall. The fast median resists it, on a long run.
		val classifier = NetworkQualityClassifier()
		for(second in 1..90)
		{
			val sample = if(second % 20 == 0) base.copy(probeRttMicros = 200_000) else base
			assertEquals(NetworkQualityLevel.GOOD, classifier.update(sample, ethernet).level)
		}
	}

	@Test
	fun tailArmLeavesGoodOnASingleStalledSecondAndReturnsEightSecondsLater()
	{
		// PLE-366, the defect: the median over five one-second samples cannot see a stall
		// narrower than half its window, and blip-200ms is one bad second in twenty. Measured
		// on ple356's blip phase the worst second read 9.87 ms of jitter against a 2.52 ms
		// clean ceiling, and the median over the window still read 1.63 ms -- GOOD, straight
		// through a hitch the player felt.
		val classifier = NetworkQualityClassifier()
		repeat(10) { classifier.update(base, ethernet) }
		val stall = base.copy(videoPacketJitterMicros = 9_870)
		val stalled = classifier.update(stall, ethernet)
		assertEquals(NetworkQualityLevel.CONSTRAINED, stalled.level)
		// the median is untouched by the one bad sample -- the tail arm is what moved the badge
		assertEquals(0.0, stalled.fastJitterMillis, 0.001)
		assertEquals(NetworkQualityThresholds.TAIL_RATE_CUT, stalled.tailJitterRate, 0.001)
		// it holds while the outlier is in the 5-sample window, then RECOVERY_SAMPLES confirm
		repeat(NetworkQualityThresholds.FAST_WINDOW_SECONDS - 1 +
			NetworkQualityThresholds.RECOVERY_SAMPLES - 1)
		{
			assertEquals(NetworkQualityLevel.CONSTRAINED, classifier.update(base, ethernet).level)
		}
		assertEquals(NetworkQualityLevel.GOOD, classifier.update(base, ethernet).level)
	}

	@Test
	fun tailArmCatchesEveryMeasuredBlipEventAndNoCleanSecond()
	{
		// PLE-366's derivation, replayed. build/captures/ple356 + ple357, the post-PLE-356
		// estimator: blip-200ms fires six times across the two captures, 20 s apart, one
		// second wide each. Clean, across all six clean phases, is 335 samples whose jitter
		// maxes at 3.71 ms and whose loss is exactly 0 in every one.
		val blipEvents = listOf(
			3.71 to 1.76, 4.85 to 12.02, 9.87 to 14.53,
			89.77 to 16.94, 1.95 to 1.73, 2.03 to 1.94)
		for((jitter, loss) in blipEvents)
		{
			val classifier = NetworkQualityClassifier()
			repeat(10) { classifier.update(base, ethernet) }
			assertEquals("blip event $jitter ms / $loss % must leave GOOD",
				NetworkQualityLevel.CONSTRAINED,
				classifier.update(sample(jitter, loss), ethernet).level)
		}
		// The worst clean second in the corpus, repeated: jitter 3.71 ms (the corpus max, equal
		// to one of the blip events -- which is why jitter alone cannot carry the arm) and no
		// loss. It must not move the badge even sustained.
		val clean = NetworkQualityClassifier()
		var level = NetworkQualityLevel.UNKNOWN
		repeat(30) { level = clean.update(sample(3.71, 0.0), ethernet).level }
		assertEquals(NetworkQualityLevel.GOOD, level)
	}

	@Test
	fun tailArmNeverOverridesAWorseMedianVerdict()
	{
		// The worse of the two arms decides, so the single-tier tail arm must not pull a POOR
		// median down to CONSTRAINED: a sustained bad path is still POOR, and stays POOR while
		// the tail keeps firing on top of it.
		val classifier = NetworkQualityClassifier()
		val bad = sample(12.0, 5.0)
		var level = NetworkQualityLevel.UNKNOWN
		repeat(NetworkQualityThresholds.FAST_WINDOW_SECONDS) { level = classifier.update(bad, ethernet).level }
		assertEquals(NetworkQualityLevel.POOR, level)
	}

	@Test
	fun aRecurringStallDoesNotPinTheBadgeAtPoorForever()
	{
		// The one asymmetry in the recovery gate: the tail arm holds the badge off GOOD, but it
		// must not hold it at POOR. A path that had one genuinely bad spell and then only
		// hitches every 20 s has to settle at CONSTRAINED, not stay POOR indefinitely -- that
		// would be PLE-357's stuck badge with a new cause.
		val classifier = NetworkQualityClassifier()
		repeat(NetworkQualityThresholds.FAST_WINDOW_SECONDS) {
			classifier.update(base.copy(probeRttMicros = 60_000), ethernet)
		}
		var level = NetworkQualityLevel.UNKNOWN
		for(second in 1..60)
			level = classifier.update(
				if(second % 20 == 0) sample(9.87, 14.53) else base, ethernet).level
		assertEquals(NetworkQualityLevel.CONSTRAINED, level)
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
			repeat(NetworkQualityThresholds.FAST_WINDOW_SECONDS)
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

	@Test
	fun warmUpBeforeTheJitterEstimatorFillsReadsUnknownNotConstrained()
	{
		// PLE-403: build/captures/ple366's device run shows every session opening at
		// CONSTRAINED for 7 s -- jitter 6.75 ms at t=0 decaying to 1.78 ms by t=5 -- on a clean
		// LAN. The native estimator's first frame-boundary sample lands in its EWMA unsmoothed
		// (the gain term is zero against a zero accumulator), so a one-off startup delay reads
		// back indistinguishable from real jitter and both the median and the PLE-366 tail arm
		// take it at face value. `videoPacketJitterFilled` is false for exactly that window, so
		// suppressing on it (not a wall-clock delay) is what stops the false CONSTRAINED without
		// touching how a genuine bad second is judged once the estimator has real history.
		val classifier = NetworkQualityClassifier()
		repeat(10)
		{
			val warmingUp = sample(6.75, 0.0).copy(videoPacketJitterFilled = false)
			assertEquals(NetworkQualityLevel.UNKNOWN, classifier.update(warmingUp, ethernet).level)
		}
	}

	@Test
	fun aBadSecondImmediatelyAfterTheEstimatorFillsStillClassifiesAtOnce()
	{
		// Suppression must cost nothing once the estimator has real history: no invalid sample
		// was ever pushed into the window (the early return happens before the classifier
		// appends anything), so the first filled sample is also the window's first sample and
		// alone decides the median -- exactly the "a single bad second must not have to wait"
		// guarantee NetworkQuality.kt:174 already promises, undelayed by the warm-up suppression.
		val classifier = NetworkQualityClassifier()
		repeat(10)
		{
			assertEquals(NetworkQualityLevel.UNKNOWN,
				classifier.update(base.copy(videoPacketJitterFilled = false), ethernet).level)
		}
		val stall = sample(9.87, 0.0)
		val stalled = classifier.update(stall, ethernet)
		assertEquals(NetworkQualityLevel.CONSTRAINED, stalled.level)
		assertEquals(9.87, stalled.fastJitterMillis, 0.001)
	}

	// ---- PLE-464: the stall arm -------------------------------------------------------
	//
	// `takionPacketsLost` cannot see a total outage (it is only raised when a packet
	// arrives), so these feed the gap field the native layer now reports and assert that a
	// blackout is no longer GOOD while the profiles that are merely slow still are. The
	// numbers are the worst per-profile gaps measured in build/captures/ple404,
	// ple404b, ple423-blip and ple423-impair.

	/** One second in which the console's socket was silent for `stallMillis`, with every
	 * other input reading exactly as it does on a clean LAN -- which is what PLE-404
	 * measured during the outages: jitter under 3.12 ms and loss a structural 0. */
	private fun stalled(stallMillis: Long) = base.copy(takionMaxReceiveGapMillis = stallMillis)

	@Test
	fun aTotalBlackoutIsNotGood()
	{
		val classifier = NetworkQualityClassifier()
		repeat(NetworkQualityThresholds.FAST_WINDOW_SECONDS) { classifier.update(base, ethernet) }
		// roam-3000ms, worst gap measured in ple404b.
		val result = classifier.update(stalled(2706), ethernet)
		assertEquals(NetworkQualityLevel.POOR, result.level)
		assertEquals(2706.0, result.stallMillis, 0.001)
	}

	@Test
	fun aSecondWithNothingAtAllIsStillClassified()
	{
		// The window in which the outage is total: no packets counted either way, and the
		// console's quality payload did not arrive either. Before PLE-464 this returned
		// UNKNOWN and the one sample that carries the fault was discarded.
		val blackout = base.copy(connectionQualityValid = false, takionPacketsReceived = 0,
			takionPacketsLost = 0, takionMaxReceiveGapMillis = 1200)
		assertEquals(NetworkQualityLevel.POOR,
			NetworkQualityClassifier().update(blackout, ethernet).level)
	}

	@Test
	fun theShorterRoamOutageAlsoReachesPoor()
	{
		val classifier = NetworkQualityClassifier()
		repeat(NetworkQualityThresholds.FAST_WINDOW_SECONDS) { classifier.update(base, ethernet) }
		// roam-1200ms: the outage is 1.2 s wide on the wire.
		assertEquals(NetworkQualityLevel.POOR, classifier.update(stalled(1200), ethernet).level)
	}

	@Test
	fun theProfilesThatAreMerelySlowStayGood()
	{
		// clean 19 ms, 4g 29 ms, wifi-slow 31 ms -- the worst gap each profile produced
		// across six captured phases. None of them may move this arm.
		for(gap in longArrayOf(19, 29, 31))
		{
			val classifier = NetworkQualityClassifier()
			repeat(NetworkQualityThresholds.FAST_WINDOW_SECONDS) { classifier.update(base, ethernet) }
			assertEquals("gap $gap ms", NetworkQualityLevel.GOOD,
				classifier.update(stalled(gap), ethernet).level)
		}
	}

	@Test
	fun aTwoHundredMillisecondHitchDoesNotReachTheStallArm()
	{
		// blip-200ms's worst gap, 199 ms: the tail arm's business, not this one. The badge
		// still reports it -- via jitter and loss, unchanged -- but the stall arm, whose
		// cut is 2.5x higher, must not be what fires, or the cut is inside a distribution.
		val classifier = NetworkQualityClassifier()
		repeat(NetworkQualityThresholds.FAST_WINDOW_SECONDS) { classifier.update(base, ethernet) }
		assertEquals(NetworkQualityLevel.GOOD, classifier.update(stalled(199), ethernet).level)
	}

	@Test
	fun theStallArmRecoversInTheSameEightSecondsAsTheTailArm()
	{
		val classifier = NetworkQualityClassifier()
		repeat(NetworkQualityThresholds.FAST_WINDOW_SECONDS) { classifier.update(base, ethernet) }
		assertEquals(NetworkQualityLevel.POOR, classifier.update(stalled(3000), ethernet).level)
		// The bad sample has to age out of the 5 s window first (4 more clean samples),
		// then RECOVERY_SAMPLES confirm the step down to CONSTRAINED -- POOR never steps
		// straight to GOOD (PLE-357) -- and 3 more confirm the step to GOOD: 4+3+3 = 10 s,
		// deterministic, the same arithmetic PLE-357 and PLE-366 measured.
		var level = NetworkQualityLevel.POOR
		var seconds = 0
		while(level != NetworkQualityLevel.GOOD && seconds < 30)
		{
			level = classifier.update(base, ethernet).level
			seconds++
		}
		assertEquals(NetworkQualityLevel.GOOD, level)
		assertEquals(10, seconds)
	}

	@Test
	fun theSilenceFieldIsReadWhenTheWindowMaxIsAbsent()
	{
		// PLE-423's instantaneous reading, which is all an older native layer reports. It
		// truncates an outage that ends between polls, so it is the fallback, not the input.
		val classifier = NetworkQualityClassifier()
		repeat(NetworkQualityThresholds.FAST_WINDOW_SECONDS) { classifier.update(base, ethernet) }
		assertEquals(NetworkQualityLevel.POOR,
			classifier.update(base.copy(takionSilenceMillis = 2570), ethernet).level)
	}

	@Test
	fun theStallArmLeavesTheOtherTwoArmsAlone()
	{
		// A clean window with no gap reported at all reads exactly as it did before this
		// arm existed: the existing cuts are untouched (PLE-411).
		val classifier = NetworkQualityClassifier()
		var result = NetworkQualitySnapshot.UNKNOWN
		repeat(NetworkQualityThresholds.FAST_WINDOW_SECONDS) { result = classifier.update(base, ethernet) }
		assertEquals(NetworkQualityLevel.GOOD, result.level)
		assertEquals(0.0, result.stallMillis, 0.001)
		assertEquals(0.0, result.tailJitterRate, 0.001)
		assertEquals(0.0, result.tailLossRate, 0.001)
	}
}
