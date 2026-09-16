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
			HandoffDecision.Retry(1_000L, 1),
			policy.decide(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED, retriesMade = 0, elapsedMs = 0)
		)
	}

	@Test
	fun `delays back off in the declared order`()
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
			HandoffDecision.Retry(1_000L, 1),
			policy.decide(QUIT_REASON_SESSION_REQUEST_RP_IN_USE, retriesMade = 0, elapsedMs = 0)
		)
		assertEquals(
			HandoffDecision.Report,
			policy.decide(QUIT_REASON_SESSION_REQUEST_RP_IN_USE, retriesMade = 4, elapsedMs = 11_000)
		)
		// the same point in the same handoff, but refused rather than in use: still worth waiting
		assertTrue(
			policy.decide(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED, retriesMade = 4, elapsedMs = 11_000)
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
		assertEquals(HandoffDecision.Retry(1_000L, 1), handoff.onQuit(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED))
		assertTrue(handoff.handoffInProgress)
		now += 1_000
		assertEquals(HandoffDecision.Retry(2_000L, 2), handoff.onQuit(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED))
		now += 2_000
		assertEquals(HandoffDecision.Retry(3_000L, 3), handoff.onQuit(QUIT_REASON_SESSION_REQUEST_CONNECTION_REFUSED))
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
			if(decisions > 20)
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
