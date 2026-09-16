// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.remote

import android.content.Context
import androidx.annotation.StringRes
import com.metallic.chiaki.R

/**
 * What the user is told while a console is being connected (PLE-337).
 *
 * Starting a PS5 over PSN takes tens of seconds on a first run, and the measured breakdown in
 * `build/captures/ple335-firstrun-20260916T224733Z-logcat.txt` is mostly other people's waiting:
 * 6 s for the console's own OFFER, 3.8 s of hole punching, then up to 45 s of asking a
 * freshly-linked console whether it will accept a stream yet. A single spinner over all of that
 * reads as a hang, so each of those waits gets a name and a clock.
 *
 * The phases are ordered and never go backwards: [PsnRemoteState] walks 1..4 while Home holds the
 * screen, and the stream screen picks up at 5 and 6.
 */
enum class ConnectPhase(@StringRes val labelRes: Int, val expectedMs: Long)
{
	/** Resolving the push server and opening the PSN session. */
	REACHING_NETWORK(R.string.connect_phase_reaching_network, 8_000L),

	/** The console has been asked to join; measured at ~6 s, which is the console's own pace. */
	WAKING_CONSOLE(R.string.connect_phase_waking_console, 12_000L),

	/** Signalling and hole punching the control socket; measured at ~3.8 s. */
	OPENING_ROUTE(R.string.connect_phase_opening_route, 10_000L),

	/** Registering this phone with the console — instant in the capture, but it can stall. */
	LINKING(R.string.connect_phase_linking, 6_000L),

	/** The stream itself is being asked for. */
	STARTING_STREAM(R.string.connect_phase_starting_stream, 10_000L),

	/**
	 * The console refused the stream and we are polling it. Deliberately last: on a first run it
	 * follows [STARTING_STREAM], and it is the honest end of the line — nothing is advancing here
	 * but the clock, and the text says so rather than pretending otherwise.
	 */
	CONSOLE_NOT_READY(R.string.connect_phase_console_not_ready, 20_000L);

	val step: Int get() = ordinal + 1

	companion object
	{
		val TOTAL_STEPS = values().size
	}
}

/**
 * One rendering of [ConnectPhase]: which phase, how long it has been running, and whether it has
 * outstayed what the capture says it should take.
 */
data class ConnectProgress(
	val phase: ConnectPhase,
	val elapsedSeconds: Int,
	val slow: Boolean,
	/** False for an ordinary stream, which has no numbered first-run ladder behind it. */
	val showStep: Boolean
)

/** The phase a PSN control-plane state belongs to, or null when nothing is being connected. */
fun connectPhaseOf(state: PsnRemoteState): ConnectPhase? = when(state)
{
	PsnRemoteState.RefreshingToken,
	PsnRemoteState.ResolvingPushServer,
	PsnRemoteState.OpeningWebSocket,
	PsnRemoteState.CreatingSession,
	PsnRemoteState.ClientJoined -> ConnectPhase.REACHING_NETWORK

	PsnRemoteState.StartingConsole,
	PsnRemoteState.ConsoleJoined -> ConnectPhase.WAKING_CONSOLE

	PsnRemoteState.ControlSignaling,
	PsnRemoteState.ControlProbing,
	is PsnRemoteState.ControlPunched,
	PsnRemoteState.AwaitingDataSocket,
	PsnRemoteState.DataSignaling,
	PsnRemoteState.DataProbing,
	is PsnRemoteState.DataPunched -> ConnectPhase.OPENING_ROUTE

	PsnRemoteState.NativeStarting,
	is PsnRemoteState.Registered -> ConnectPhase.LINKING

	PsnRemoteState.Streaming -> ConnectPhase.STARTING_STREAM

	else -> null
}

fun connectProgress(phase: ConnectPhase, phaseElapsedMs: Long, showStep: Boolean = true) = ConnectProgress(
	phase = phase,
	elapsedSeconds = (phaseElapsedMs.coerceAtLeast(0L) / 1000L).toInt(),
	slow = phaseElapsedMs > phase.expectedMs,
	showStep = showStep
)

/**
 * The second line under the phase name: a step count and a clock while things run to time, and a
 * plain admission once they do not. Nothing here ever claims progress that is not happening.
 */
fun ConnectProgress.detailText(context: Context): String = when
{
	slow -> context.getString(R.string.connect_detail_slow, elapsedSeconds)
	showStep -> context.getString(R.string.connect_detail_step, phase.step, ConnectPhase.TOTAL_STEPS, elapsedSeconds)
	else -> context.getString(R.string.connect_detail_elapsed, elapsedSeconds)
}
