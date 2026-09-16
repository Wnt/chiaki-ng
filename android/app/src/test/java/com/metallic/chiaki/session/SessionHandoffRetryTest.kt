// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import com.metallic.chiaki.session.SessionHandoffRetryPolicy.Companion.QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED
import com.metallic.chiaki.session.SessionHandoffRetryPolicy.Companion.QUIT_REASON_SESSION_REQUEST_RP_IN_USE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val QUIT_REASON_STOPPED = 1
private const val QUIT_REASON_SESSION_REQUEST_UNKNOWN = 2
private const val QUIT_REASON_CTRL_CONNECTION_REFUSED = 9

class SessionHandoffRetryPolicyTest
{
	private val policy = SessionHandoffRetryPolicy()

	@Test
	fun `the first refusal after a link is waited out, not reported`()
	{
		assertEquals(
			HandoffDecision.Retry(SessionHandoffRetryPolicy.FIRST_DELAY_MS, 1),
			policy.decide(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED, retriesMade = 0, elapsedMs = 0)
		)
	}

	@Test
	fun `delays follow the declared ladder`()
	{
		val delays = (0 until SessionHandoffRetryPolicy.DEFAULT_DELAYS_MS.size).map { retries ->
			val decision = policy.decide(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED, retries, elapsedMs = 0)
			(decision as HandoffDecision.Retry).delayMs
		}
		assertEquals(SessionHandoffRetryPolicy.DEFAULT_DELAYS_MS, delays)
	}

	@Test
	fun `the attempt count is bounded`()
	{
		val beyond = SessionHandoffRetryPolicy.DEFAULT_DELAYS_MS.size
		assertEquals(
			HandoffDecision.Report,
			policy.decide(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED, retriesMade = beyond, elapsedMs = 0)
		)
	}

	@Test
	fun `the wait is bounded in wall clock too`()
	{
		assertEquals(
			HandoffDecision.Report,
			policy.decide(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED, retriesMade = 1, elapsedMs = 44_500)
		)
	}

	@Test
	fun `a console still holding the old session is retried on a shorter budget`()
	{
		assertEquals(
			HandoffDecision.Retry(SessionHandoffRetryPolicy.FIRST_DELAY_MS, 1),
			policy.decide(QUIT_REASON_SESSION_REQUEST_RP_IN_USE, retriesMade = 0, elapsedMs = 0)
		)
		assertEquals(
			HandoffDecision.Report,
			policy.decide(QUIT_REASON_SESSION_REQUEST_RP_IN_USE, retriesMade = 4, elapsedMs = 14_500)
		)
		// the same point in the same handoff, but refused rather than in use: still worth waiting
		assertTrue(
			policy.decide(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED, retriesMade = 4, elapsedMs = 14_500)
				is HandoffDecision.Retry
		)
	}

	@Test
	fun `every other quit reason is reported at once`()
	{
		listOf(
			QUIT_REASON_STOPPED,
			QUIT_REASON_SESSION_REQUEST_UNKNOWN,
			QUIT_REASON_CTRL_CONNECTION_REFUSED,
			0
		).forEach { reason ->
			assertEquals(
				"quit reason $reason must not be retried",
				HandoffDecision.Report,
				policy.decide(reason, retriesMade = 0, elapsedMs = 0)
			)
		}
	}

	@Test
	fun `the whole ladder fits inside the budget it declares`()
	{
		var elapsed = 0L
		var retries = 0
		while(true)
		{
			val decision = policy.decide(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED, retries, elapsed)
			if(decision !is HandoffDecision.Retry)
				break
			elapsed += decision.delayMs
			retries++
		}
		assertEquals(SessionHandoffRetryPolicy.DEFAULT_DELAYS_MS.size, retries)
		assertTrue("the ladder must stay inside the budget", elapsed <= SessionHandoffRetryPolicy.REFUSED_BUDGET_MS)
	}

	// PLE-337: a refused connect costs 4-5 ms, so the interval must stay short for the whole budget.

	@Test
	fun `the interval never grows beyond the steady one`()
	{
		val ladder = SessionHandoffRetryPolicy.DEFAULT_DELAYS_MS
		assertEquals(SessionHandoffRetryPolicy.FIRST_DELAY_MS, ladder.first())
		ladder.drop(1).forEach { delay ->
			assertEquals(SessionHandoffRetryPolicy.STEADY_DELAY_MS, delay)
		}
		assertTrue("the ladder must never back off", ladder.max() <= SessionHandoffRetryPolicy.STEADY_DELAY_MS)
	}

	@Test
	fun `the last second of the budget is still polled`()
	{
		// The console can become ready at any moment; the old 8 s tail could miss it by 8 s.
		var elapsed = 0L
		var retries = 0
		var longestGapMs = 0L
		while(true)
		{
			val decision = policy.decide(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED, retries, elapsed)
			if(decision !is HandoffDecision.Retry)
				break
			longestGapMs = maxOf(longestGapMs, decision.delayMs)
			elapsed += decision.delayMs
			retries++
		}
		assertTrue("no gap may exceed 2 s", longestGapMs <= 2_000L)
		assertTrue("the ladder must reach within one gap of the ceiling",
			SessionHandoffRetryPolicy.REFUSED_BUDGET_MS - elapsed <= longestGapMs)
		assertTrue("a short interval means many more chances than the old eight", retries >= 30)
	}

	@Test
	fun `a busy console is polled just as tightly on its shorter budget`()
	{
		var elapsed = 0L
		var retries = 0
		while(true)
		{
			val decision = policy.decide(QUIT_REASON_SESSION_REQUEST_RP_IN_USE, retries, elapsed)
			if(decision !is HandoffDecision.Retry)
				break
			elapsed += decision.delayMs
			retries++
		}
		assertTrue("the in-use wait must stay inside its own budget",
			elapsed <= SessionHandoffRetryPolicy.IN_USE_BUDGET_MS)
		assertTrue("and must use most of it", elapsed >= SessionHandoffRetryPolicy.IN_USE_BUDGET_MS - 2_000L)
	}

	@Test
	fun `a steady ladder is built from the interval and the budget`()
	{
		assertEquals(
			listOf(500L, 1_200L, 1_200L),
			SessionHandoffRetryPolicy.steadyDelays(500L, 1_200L, 3_000L)
		)
		assertEquals(emptyList<Long>(), SessionHandoffRetryPolicy.steadyDelays(500L, 1_200L, 400L))
	}
}

class SessionHandoffRetryTest
{
	private var now = 0L
	private fun retry() = SessionHandoffRetry(clock = { now })

	@Test
	fun `a stream that did not follow a link never retries`()
	{
		val handoff = retry()
		assertFalse(handoff.armed)
		assertEquals(HandoffDecision.Report, handoff.onQuit(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED))
	}

	@Test
	fun `a freshly linked console is waited for, and elapsed time accumulates`()
	{
		val handoff = retry()
		handoff.arm()
		assertFalse(handoff.handoffInProgress)
		val first = SessionHandoffRetryPolicy.FIRST_DELAY_MS
		val steady = SessionHandoffRetryPolicy.STEADY_DELAY_MS
		assertEquals(HandoffDecision.Retry(first, 1), handoff.onQuit(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED))
		assertTrue(handoff.handoffInProgress)
		now += first
		assertEquals(HandoffDecision.Retry(steady, 2), handoff.onQuit(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED))
		now += steady
		assertEquals(HandoffDecision.Retry(steady, 3), handoff.onQuit(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED))
	}

	@Test
	fun `the handoff gives up and reports once the budget is spent`()
	{
		val handoff = retry()
		handoff.arm()
		var decisions = 0
		while(handoff.onQuit(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED) is HandoffDecision.Retry)
		{
			decisions++
			now += SessionHandoffRetryPolicy.DEFAULT_DELAYS_MS[decisions - 1]
			if(decisions > SessionHandoffRetryPolicy.DEFAULT_DELAYS_MS.size)
				break
		}
		assertEquals(SessionHandoffRetryPolicy.DEFAULT_DELAYS_MS.size, decisions)
		assertFalse("a spent handoff must report the next failure too", handoff.armed)
		assertEquals(HandoffDecision.Report, handoff.onQuit(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED))
	}

	@Test
	fun `a genuine failure during the handoff is reported immediately`()
	{
		val handoff = retry()
		handoff.arm()
		assertEquals(HandoffDecision.Report, handoff.onQuit(QUIT_REASON_SESSION_REQUEST_UNKNOWN))
		assertFalse(handoff.armed)
	}

	@Test
	fun `once connected, a later quit is a real end of stream`()
	{
		val handoff = retry()
		handoff.arm()
		handoff.onQuit(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED)
		handoff.onConnected()
		assertFalse(handoff.armed)
		assertFalse(handoff.handoffInProgress)
		assertEquals(HandoffDecision.Report, handoff.onQuit(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED))
	}
}
