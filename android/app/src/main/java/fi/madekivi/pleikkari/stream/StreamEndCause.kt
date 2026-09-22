// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.stream

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * PLE-262: why a stream ended, when the console (not this client) ended it.
 *
 * A PS5 allows one session per account, so when a local controller takes over
 * the console it tears the Remote Play session down with a Takion DISCONNECT
 * reason "Server shutting down" (lib: CHIAKI_QUIT_REASON_STREAM_CONNECTION_REMOTE_SHUTDOWN).
 * The same reason is sent when the console goes to rest, so the reason alone is
 * ambiguous; the console's discovery answer right after the quit corroborates it:
 * still awake and answering = taken over, standby = rest, silent = unreachable.
 */
@Parcelize
data class StreamEndReason(
	val quitReason: Int,
	val remoteReason: String?,
	val host: String,
	val ps5: Boolean
): Parcelable

enum class ConsoleProbeState { READY, STANDBY, NO_RESPONSE }

enum class StreamEndCause(val token: String)
{
	CONSOLE_TAKEOVER("console_takeover"),
	CONSOLE_REST("console_rest"),
	CONSOLE_UNREACHABLE("console_unreachable"),
	UNCLASSIFIED("unclassified")
}

object StreamEndCauseClassifier
{
	// Ordinals of ChiakiQuitReason in lib/include/chiaki/session.h.
	const val QUIT_REASON_STREAM_CONNECTION_REMOTE_SHUTDOWN = 12

	const val LOG_TAG = "Chiaki"

	/** Only a console-initiated shutdown is worth corroborating; everything else keeps its own reason. */
	fun needsProbe(reason: StreamEndReason) =
		reason.quitReason == QUIT_REASON_STREAM_CONNECTION_REMOTE_SHUTDOWN

	fun classify(reason: StreamEndReason, probes: List<ConsoleProbeState>): StreamEndCause
	{
		if(!needsProbe(reason) || probes.isEmpty())
			return StreamEndCause.UNCLASSIFIED
		return when
		{
			probes.any { it == ConsoleProbeState.STANDBY } -> StreamEndCause.CONSOLE_REST
			probes.all { it == ConsoleProbeState.NO_RESPONSE } -> StreamEndCause.CONSOLE_UNREACHABLE
			// A lost UDP answer is tolerated, but the console must say it is awake more than once
			// and the last answer must still be awake: a console going to rest answers READY for
			// a moment before it switches to standby.
			probes.count { it == ConsoleProbeState.READY } >= 2 && probes.last() == ConsoleProbeState.READY ->
				StreamEndCause.CONSOLE_TAKEOVER
			else -> StreamEndCause.UNCLASSIFIED
		}
	}

	/** One grep-able line for humans and scripts/dev/ab/summarize.py. Keep the format stable. */
	fun logLine(cause: StreamEndCause, reason: StreamEndReason, probes: List<ConsoleProbeState>) =
		"Stream end cause: cause=${cause.token} quit_reason=${reason.quitReason} " +
			"remote_reason=\"${reason.remoteReason ?: ""}\" " +
			"console_probes=${probes.joinToString(",") { it.name.lowercase() }}"

	fun parseDiscoveryResponse(response: String): ConsoleProbeState = when
	{
		response.contains(" 200 ") -> ConsoleProbeState.READY
		response.contains(" 620 ") -> ConsoleProbeState.STANDBY
		else -> ConsoleProbeState.NO_RESPONSE
	}
}

object ConsoleDiscoveryProbe
{
	private const val PS5_PORT = 9302
	private const val PS4_PORT = 987
	private const val PS5_PROTOCOL = "00030010"
	private const val PS4_PROTOCOL = "00020020"

	const val PROBE_COUNT = 3
	const val PROBE_SPACING_MILLIS = 1500L
	private const val TIMEOUT_MILLIS = 1000

	/** Blocking; call off the main thread. Takes about PROBE_COUNT * PROBE_SPACING_MILLIS. */
	fun probeSeries(host: String, ps5: Boolean): List<ConsoleProbeState> =
		(0 until PROBE_COUNT).map { index ->
			if(index > 0)
				Thread.sleep(PROBE_SPACING_MILLIS)
			probeOnce(host, ps5)
		}

	private fun probeOnce(host: String, ps5: Boolean): ConsoleProbeState = try
	{
		DatagramSocket().use { socket ->
			socket.soTimeout = TIMEOUT_MILLIS
			val request = "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:${if(ps5) PS5_PROTOCOL else PS4_PROTOCOL}\n"
				.toByteArray(Charsets.US_ASCII)
			socket.send(DatagramPacket(request, request.size, InetAddress.getByName(host), if(ps5) PS5_PORT else PS4_PORT))
			val buffer = ByteArray(2048)
			val packet = DatagramPacket(buffer, buffer.size)
			socket.receive(packet)
			StreamEndCauseClassifier.parseDiscoveryResponse(String(buffer, 0, packet.length, Charsets.US_ASCII))
		}
	}
	catch(e: SocketTimeoutException) { ConsoleProbeState.NO_RESPONSE }
	catch(e: Exception) { ConsoleProbeState.NO_RESPONSE }
}
