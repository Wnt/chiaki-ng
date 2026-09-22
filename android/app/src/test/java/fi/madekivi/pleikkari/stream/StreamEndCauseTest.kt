// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.stream

import fi.madekivi.pleikkari.stream.ConsoleProbeState.NO_RESPONSE
import fi.madekivi.pleikkari.stream.ConsoleProbeState.READY
import fi.madekivi.pleikkari.stream.ConsoleProbeState.STANDBY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StreamEndCauseTest
{
	private val shutdown = StreamEndReason(
		StreamEndCauseClassifier.QUIT_REASON_STREAM_CONNECTION_REMOTE_SHUTDOWN,
		"Server shutting down", "192.168.1.164", true)

	@Test fun consoleStillAwakeAfterShutdownIsTakeover()
	{
		assertEquals(StreamEndCause.CONSOLE_TAKEOVER, StreamEndCauseClassifier.classify(shutdown, listOf(READY, READY, READY)))
		assertEquals(StreamEndCause.CONSOLE_TAKEOVER, StreamEndCauseClassifier.classify(shutdown, listOf(READY, NO_RESPONSE, READY)))
	}

	@Test fun consoleGoingToStandbyIsRestNotTakeover()
	{
		assertEquals(StreamEndCause.CONSOLE_REST, StreamEndCauseClassifier.classify(shutdown, listOf(READY, READY, STANDBY)))
	}

	@Test fun silentOrSingleAnswerIsNotTakeover()
	{
		assertEquals(StreamEndCause.CONSOLE_UNREACHABLE, StreamEndCauseClassifier.classify(shutdown, listOf(NO_RESPONSE, NO_RESPONSE, NO_RESPONSE)))
		assertEquals(StreamEndCause.UNCLASSIFIED, StreamEndCauseClassifier.classify(shutdown, listOf(NO_RESPONSE, NO_RESPONSE, READY)))
		assertEquals(StreamEndCause.UNCLASSIFIED, StreamEndCauseClassifier.classify(shutdown, listOf(READY, READY, NO_RESPONSE)))
	}

	@Test fun otherQuitReasonsAreNeverProbedOrClassified()
	{
		// 1 = STOPPED (user quit), 10 = STREAM_CONNECTION_UNKNOWN (network/timeout), 11 = REMOTE_DISCONNECTED
		for(value in listOf(1, 10, 11))
		{
			val reason = shutdown.copy(quitReason = value, remoteReason = null)
			assertFalse(StreamEndCauseClassifier.needsProbe(reason))
			assertEquals(StreamEndCause.UNCLASSIFIED, StreamEndCauseClassifier.classify(reason, listOf(READY, READY, READY)))
		}
	}

	@Test fun logLineIsStable()
	{
		assertEquals(
			"Stream end cause: cause=console_takeover quit_reason=12 remote_reason=\"Server shutting down\" console_probes=ready,no_response,ready",
			StreamEndCauseClassifier.logLine(StreamEndCause.CONSOLE_TAKEOVER, shutdown, listOf(READY, NO_RESPONSE, READY)))
	}

	@Test fun parsesDiscoveryStatusLines()
	{
		assertEquals(READY, StreamEndCauseClassifier.parseDiscoveryResponse("HTTP/1.1 200 Ok\nhost-id:C0151B3DD8BC\n"))
		assertEquals(STANDBY, StreamEndCauseClassifier.parseDiscoveryResponse("HTTP/1.1 620 Server Standby\nhost-id:C0151B3DD8BC\n"))
		assertEquals(NO_RESPONSE, StreamEndCauseClassifier.parseDiscoveryResponse("garbage"))
	}
}
