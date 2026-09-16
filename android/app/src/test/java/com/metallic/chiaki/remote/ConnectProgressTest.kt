// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PLE-337. The first run's phases come straight from the measured capture
 * `build/captures/ple335-firstrun-20260916T224733Z-logcat.txt`.
 */
class ConnectProgressTest
{
	@Test
	fun `the whole PSN connect is covered, in order`()
	{
		val ordered = listOf(
			PsnRemoteState.ResolvingPushServer to ConnectPhase.REACHING_NETWORK,
			PsnRemoteState.OpeningWebSocket to ConnectPhase.REACHING_NETWORK,
			PsnRemoteState.CreatingSession to ConnectPhase.REACHING_NETWORK,
			PsnRemoteState.ClientJoined to ConnectPhase.REACHING_NETWORK,
			PsnRemoteState.StartingConsole to ConnectPhase.WAKING_CONSOLE,
			PsnRemoteState.ConsoleJoined to ConnectPhase.WAKING_CONSOLE,
			PsnRemoteState.ControlSignaling to ConnectPhase.OPENING_ROUTE,
			PsnRemoteState.ControlProbing to ConnectPhase.OPENING_ROUTE,
			PsnRemoteState.NativeStarting to ConnectPhase.LINKING,
			PsnRemoteState.Streaming to ConnectPhase.STARTING_STREAM
		)
		ordered.forEach { (state, phase) ->
			assertEquals("$state", phase, connectPhaseOf(state))
		}
		// the steps a user sees must only ever go up
		val steps = ordered.map { it.second.step }
		assertEquals(steps.sorted(), steps)
	}

	@Test
	fun `waiting for the console is the last step, so a handoff retry never walks backwards`()
	{
		assertEquals(ConnectPhase.TOTAL_STEPS, ConnectPhase.CONSOLE_NOT_READY.step)
		assertTrue(ConnectPhase.CONSOLE_NOT_READY.step > ConnectPhase.STARTING_STREAM.step)
	}

	@Test
	fun `states that are not a connect show nothing`()
	{
		listOf(
			PsnRemoteState.Idle,
			PsnRemoteState.ListingDevices,
			PsnRemoteState.Devices(emptyList()),
			PsnRemoteState.Cancelling,
			PsnRemoteState.DeletingSession,
			PsnRemoteState.Woken,
			PsnRemoteState.Failed("nope")
		).forEach { state ->
			assertNull("$state", connectPhaseOf(state))
		}
		assertNotNull(connectPhaseOf(PsnRemoteState.ControlSignaling))
	}

	@Test
	fun `the clock advances a second at a time`()
	{
		assertEquals(0, connectProgress(ConnectPhase.WAKING_CONSOLE, 0).elapsedSeconds)
		assertEquals(0, connectProgress(ConnectPhase.WAKING_CONSOLE, 999).elapsedSeconds)
		assertEquals(1, connectProgress(ConnectPhase.WAKING_CONSOLE, 1_000).elapsedSeconds)
		assertEquals(35, connectProgress(ConnectPhase.CONSOLE_NOT_READY, 35_200).elapsedSeconds)
		// a clock that ran backwards would be worse than none
		assertEquals(0, connectProgress(ConnectPhase.WAKING_CONSOLE, -5_000).elapsedSeconds)
	}

	@Test
	fun `a phase running to time is not called slow`()
	{
		// the capture's own numbers: 6.0 s for the console's OFFER, 3.8 s for the punch
		assertFalse(connectProgress(ConnectPhase.WAKING_CONSOLE, 6_000).slow)
		assertFalse(connectProgress(ConnectPhase.OPENING_ROUTE, 3_800).slow)
		assertFalse(connectProgress(ConnectPhase.CONSOLE_NOT_READY, 19_999).slow)
	}

	@Test
	fun `a phase that overstays stops claiming progress`()
	{
		val progress = connectProgress(ConnectPhase.CONSOLE_NOT_READY, 30_000)
		assertTrue(progress.slow)
		assertEquals(30, progress.elapsedSeconds)
		assertTrue(connectProgress(ConnectPhase.WAKING_CONSOLE, 30_000).slow)
	}

	@Test
	fun `an ordinary stream gets the clock without the first-run step count`()
	{
		assertFalse(connectProgress(ConnectPhase.STARTING_STREAM, 2_000, showStep = false).showStep)
		assertTrue(connectProgress(ConnectPhase.STARTING_STREAM, 2_000).showStep)
	}
}
