// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

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
		const val QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED = 3
		const val QUIT_REASON_SESSION_REQUEST_RP_IN_USE = 4

		/**
		 * Eight retries, 43 s of waiting in all: short at first because the console is usually
		 * ready within a second or two, then patient, because the capture that finally succeeded
		 * did so 49 s after the link.
		 */
		val DEFAULT_DELAYS_MS = listOf(1_000L, 2_000L, 3_000L, 5_000L, 8_000L, 8_000L, 8_000L, 8_000L)
		const val REFUSED_BUDGET_MS = 45_000L
		const val IN_USE_BUDGET_MS = 15_000L
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

	fun onQuit(quitReason: Int): HandoffDecision
	{
		if(!armed)
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
