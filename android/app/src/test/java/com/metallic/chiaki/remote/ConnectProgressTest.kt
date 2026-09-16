// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PLE-337/PLE-340. The first run's phases come straight from the measured capture
 * `build/captures/ple337-firstrun-20260916T230330Z-logcat.txt` (3.6 s + 9.8 s + ~0 s + 30.3 s +
 * 0.05 s ≈ 43.9 s), which is why the bar is calibrated to [CONNECT_BAR_DURATION_MS] = 45 s.
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
	fun `the bar starts empty and fills linearly towards 45 s`()
	{
		assertEquals(0f, connectProgress(ConnectPhase.WAKING_CONSOLE, 0).barFraction, 1e-6f)
		assertEquals(
			0.2f,
			connectProgress(ConnectPhase.OPENING_ROUTE, 9_000).barFraction,
			1e-6f
		)
		assertEquals(
			1f,
			connectProgress(ConnectPhase.CONSOLE_NOT_READY, CONNECT_BAR_DURATION_MS).barFraction,
			1e-6f
		)
	}

	@Test
	fun `a negative clock reads as zero, never negative progress`()
	{
		val progress = connectProgress(ConnectPhase.WAKING_CONSOLE, -5_000)
		assertEquals(0f, progress.barFraction, 1e-6f)
		assertFalse(progress.overtime)
	}

	@Test
	fun `the bar never claims more than full and switches to overtime at 45 s, not before`()
	{
		assertFalse(connectProgress(ConnectPhase.CONSOLE_NOT_READY, CONNECT_BAR_DURATION_MS - 1).overtime)
		assertEquals(
			1f,
			connectProgress(ConnectPhase.CONSOLE_NOT_READY, CONNECT_BAR_DURATION_MS - 1).barFraction,
			0.001f
		)
		val atLimit = connectProgress(ConnectPhase.CONSOLE_NOT_READY, CONNECT_BAR_DURATION_MS)
		assertTrue(atLimit.overtime)
		assertEquals(1f, atLimit.barFraction, 1e-6f)
		// well past 45 s: still full, still overtime - it never claims more than 100%
		val wayPast = connectProgress(ConnectPhase.CONSOLE_NOT_READY, CONNECT_BAR_DURATION_MS * 4)
		assertTrue(wayPast.overtime)
		assertEquals(1f, wayPast.barFraction, 1e-6f)
	}

	@Test
	fun `the bar is monotonic across an entire connect, phase changes included`()
	{
		// A session clock that only ever advances, walking through every named phase in order -
		// exactly what setConnectPhase / trackPsnProgress feed connectProgress with. The bar must
		// never go backwards and a phase finishing early must not lurch it past where a smooth
		// fill would already be.
		val timeline = listOf(
			ConnectPhase.REACHING_NETWORK to 0L,
			ConnectPhase.REACHING_NETWORK to 1_200L,
			ConnectPhase.WAKING_CONSOLE to 3_600L,
			ConnectPhase.OPENING_ROUTE to 5_000L,
			ConnectPhase.OPENING_ROUTE to 13_400L,
			ConnectPhase.LINKING to 13_450L,
			ConnectPhase.STARTING_STREAM to 13_500L,
			ConnectPhase.CONSOLE_NOT_READY to 20_000L,
			ConnectPhase.CONSOLE_NOT_READY to 43_800L,
			ConnectPhase.CONSOLE_NOT_READY to 60_000L
		)
		var previousFraction = 0f
		timeline.forEach { (phase, sessionElapsedMs) ->
			val progress = connectProgress(phase, sessionElapsedMs)
			assertTrue(
				"fraction must not go backwards at $sessionElapsedMs ms",
				progress.barFraction >= previousFraction
			)
			previousFraction = progress.barFraction
		}
		assertEquals(1f, previousFraction, 1e-6f)
	}
}
