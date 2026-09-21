// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

/**
 * PLE-464/476 gave the quality classifier a receive-gap measurement, but the badge only ever
 * shows a colour change. This surfaces the one fact a player actually wants after watching a
 * freeze: how bad the *last* stall was and how long ago it happened.
 *
 * Not a live figure: [NetworkQualityClassifier]'s window is [NetworkQualityThresholds.FAST_WINDOW_SECONDS]
 * seconds, so by the time someone reacts to a freeze and opens the popup the live value has
 * usually already decayed back to 0 -- showing it would undo the point of this ticket. Not a
 * session-worst either: an early, now-irrelevant stall would keep answering "why did it just
 * freeze" long after it stopped being true. "Last stall, N s ago" answers exactly the question
 * the player is asking when they open this popup.
 */
internal data class StallEvent(val millis: Double, val atElapsedMillis: Long)

/**
 * Records a stall only once it reaches the same magnitude that would move the quality badge off
 * GOOD ([NetworkQualityThresholds.STALL_MS]). PLE-464 measured the worst gap on a clean LAN at
 * 19 ms and on the worst non-outage profile (wifi-slow) at 31 ms; a lower bar would make this
 * row flicker on a healthy link, which is the noise the ticket asks not to show.
 */
internal class StallTracker
{
	private var lastStall: StallEvent? = null

	fun update(stallMillis: Double, nowElapsedMillis: Long)
	{
		if(stallMillis >= NetworkQualityThresholds.STALL_MS)
			lastStall = StallEvent(stallMillis, nowElapsedMillis)
	}

	fun snapshot(): StallEvent? = lastStall
}

internal data class StallDisplay(val stallSeconds: Double, val agoMillis: Long)

internal object StallInfoPresenter
{
	/** Null hides the row: a session with no stall at or above the badge's own threshold has
	 *  nothing to report, and a permanent 0 ms row on a clean LAN would be exactly the noise
	 *  the ticket asks us not to show. */
	fun display(event: StallEvent?, nowElapsedMillis: Long): StallDisplay? = event?.let {
		StallDisplay(
			stallSeconds = it.millis / 1000.0,
			agoMillis = (nowElapsedMillis - it.atElapsedMillis).coerceAtLeast(0L)
		)
	}
}
