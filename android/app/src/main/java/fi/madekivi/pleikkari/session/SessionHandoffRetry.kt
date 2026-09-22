// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.session

/**
 * The console needs a moment to itself after it has just been linked (PLE-335).
 *
 * PIN-free PSN linking registers the console over the hole-punched RUDP control socket and then
 * tears that session down; the app immediately asks the same console for a local session on TCP
 * 9295. Captures show the console refusing that request 350-390 ms after
 * `Console auto registered successfully` — `ple326/run5-logcat.txt` has the registration at
 * 01:01:22.275 and `Session request connect failed: Connection Refused` at 01:01:22.659, while the
 * very same request succeeded later in the same evening (`run7-logcat.txt` reaches `Ctrl connected`
 * and live video). Nothing is wrong with the link or the network: the console's 9295 listener is
 * simply not up yet.
 *
 * So a refusal in the seconds after a successful link is not a failure to report, it is a wait.
 * This policy bounds that wait. It is deliberately pure — no Android types, no clock of its own —
 * so `SessionHandoffRetryTest` can drive every branch on the JVM.
 *
 * Only two quit reasons are treated as "the console is still settling":
 *  - [QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED] — the listener is not up yet;
 *  - [QUIT_REASON_SESSION_REQUEST_RP_IN_USE] — the console still holds the session we just closed
 *    (`Reported Application Reason: 0x80108b10`). That one gets a much shorter budget, because it
 *    is also what a console genuinely occupied by somebody else reports, and that user deserves the
 *    real message rather than a minute of spinner.
 *
 * Every other reason, and every failure once the stream has connected, is reported as before.
 */
sealed interface HandoffDecision
{
	/** Wait [delayMs] and start the session again; [attempt] counts retries, so the first is 1. */
	data class Retry(val delayMs: Long, val attempt: Int): HandoffDecision

	/** Out of budget, or not a settling failure: show the user the real outcome. */
	data object Report: HandoffDecision
}

class SessionHandoffRetryPolicy(
	/** One entry per retry, in order; the list length is the attempt bound. */
	private val delaysMs: List<Long> = DEFAULT_DELAYS_MS,
	private val refusedBudgetMs: Long = REFUSED_BUDGET_MS,
	private val inUseBudgetMs: Long = IN_USE_BUDGET_MS
)
{
	init
	{
		require(delaysMs.isNotEmpty()) { "a retry policy needs at least one delay" }
		require(delaysMs.all { it > 0 }) { "retry delays must be positive" }
	}

	/**
	 * @param quitReason the native `ChiakiQuitReason` value of the session that just quit
	 * @param retriesMade how many retries this handoff has already spent
	 * @param elapsedMs milliseconds since the first refusal of this handoff
	 */
	fun decide(quitReason: Int, retriesMade: Int, elapsedMs: Long): HandoffDecision
	{
		val budgetMs = when(quitReason)
		{
			QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED -> refusedBudgetMs
			QUIT_REASON_SESSION_REQUEST_RP_IN_USE -> inUseBudgetMs
			else -> return HandoffDecision.Report
		}
		if(retriesMade < 0 || retriesMade >= delaysMs.size)
			return HandoffDecision.Report
		val delayMs = delaysMs[retriesMade]
		if(elapsedMs + delayMs > budgetMs)
			return HandoffDecision.Report
		return HandoffDecision.Retry(delayMs, retriesMade + 1)
	}

	companion object
	{
		// lib/include/chiaki/session.h ChiakiQuitReason, in declaration order.
		const val QUIT_REASON_STOPPED = 1
		const val QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED = 3
		const val QUIT_REASON_SESSION_REQUEST_RP_IN_USE = 4

		const val REFUSED_BUDGET_MS = 45_000L
		const val IN_USE_BUDGET_MS = 15_000L

		/** The first probe, fired soon after the refusal: the console is often ready within a second. */
		const val FIRST_DELAY_MS = 500L

		/** Every probe after the first. Short, and it stays short — see [DEFAULT_DELAYS_MS]. */
		const val STEADY_DELAY_MS = 1_200L

		/**
		 * A steady ladder: [firstMs], then [steadyMs] over and over, for as long as [budgetMs] allows.
		 *
		 * PLE-337 replaced PLE-335's 1/2/3/5/8/8/8/8 backoff. A refused TCP connect to the console's
		 * port 9295 costs 4-5 ms — every failed attempt in
		 * `build/captures/ple335-firstrun-20260916T224733Z-logcat.txt` took that long — so backing off
		 * buys nothing and costs the user real seconds: in that capture the console became ready
		 * somewhere inside the final 8 s gap, and up to 8 s of a 35.2 s wait was our own backoff.
		 * Polling at a roughly constant interval bounds that overshoot to one interval instead.
		 */
		fun steadyDelays(firstMs: Long, steadyMs: Long, budgetMs: Long): List<Long>
		{
			require(firstMs > 0 && steadyMs > 0) { "retry delays must be positive" }
			val delays = mutableListOf<Long>()
			var elapsedMs = 0L
			var nextMs = firstMs
			while(elapsedMs + nextMs <= budgetMs)
			{
				delays += nextMs
				elapsedMs += nextMs
				nextMs = steadyMs
			}
			return delays
		}

		/** 500 ms, then 1.2 s every time, filling the 45 s refusal budget. */
		val DEFAULT_DELAYS_MS: List<Long> = steadyDelays(FIRST_DELAY_MS, STEADY_DELAY_MS, REFUSED_BUDGET_MS)
	}
}

/**
 * The stateful half: one instance per stream attempt, owned by `StreamSession`.
 *
 * [armed] is set only when this stream was started straight off a successful registration, so an
 * ordinary "console is busy" on a console the user has had for weeks still reports immediately.
 * [onConnected] disarms it for good: once frames have flowed, a later quit is a real end of stream.
 */
class SessionHandoffRetry(
	private val policy: SessionHandoffRetryPolicy = SessionHandoffRetryPolicy(),
	private val clock: () -> Long
)
{
	var armed: Boolean = false
		private set
	private var retriesMade = 0
	private var firstFailureAt: Long? = null

	/** True once a refusal has been absorbed, i.e. the user is being told "linked, starting". */
	val handoffInProgress: Boolean get() = armed && retriesMade > 0

	fun arm()
	{
		armed = true
	}

	/**
	 * A Reconnect tap after a quit (PLE-428): the app's own previous session may not have finished
	 * tearing down, and the console can refuse the new request with the same `rp_in_use` it uses for
	 * a console genuinely held by someone else. Reusing [arm] here would work most of the time, but
	 * if this instance had already spent a budget on an earlier, unrelated handoff and never saw
	 * [onConnected] (its own retries were exhausted, or the last quit was some other error), its
	 * leftover [retriesMade]/[firstFailureAt] would eat into this new attempt's budget. A Reconnect
	 * is a fresh attempt, so it gets a fresh budget.
	 */
	fun armForReconnect()
	{
		armed = true
		retriesMade = 0
		firstFailureAt = null
	}

	fun onQuit(quitReason: Int): HandoffDecision
	{
		if(!armed)
			return HandoffDecision.Report
		/*
		 * PLE-428: Reconnect tears down the previous session with `StreamSession.pause()` before
		 * arming and starting the new one. If that previous session was still genuinely live (a real
		 * error quit had not actually stopped its native side yet, or the devtools injection never
		 * stops it at all), stopping it now raises its own [QUIT_REASON_STOPPED] through this same
		 * callback, racing the new attempt. A stop is never itself the failure this handoff waits
		 * out or gives up on — the real "user left" case is handled independently, by cancelling the
		 * scheduled retry Handler callback in `StreamSession.shutdown()` — so it must leave `armed`
		 * alone rather than spend it on a reason [SessionHandoffRetryPolicy.decide] was never asked
		 * about.
		 */
		if(quitReason == SessionHandoffRetryPolicy.QUIT_REASON_STOPPED)
			return HandoffDecision.Report
		val now = clock()
		val since = firstFailureAt ?: now
		val decision = policy.decide(quitReason, retriesMade, now - since)
		if(decision is HandoffDecision.Retry)
		{
			firstFailureAt = since
			retriesMade++
		}
		else
			armed = false
		return decision
	}

	/** The stream is up: this handoff is over and must never swallow a later quit. */
	fun onConnected()
	{
		armed = false
		retriesMade = 0
		firstFailureAt = null
	}
}
