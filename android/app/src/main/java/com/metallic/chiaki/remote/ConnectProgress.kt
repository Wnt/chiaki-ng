// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.remote

import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.metallic.chiaki.R

/**
 * What the user is told while a console is being connected (PLE-337, revised by PLE-340).
 *
 * Starting a PS5 over PSN takes tens of seconds on a first run, and the measured breakdown in
 * `build/captures/ple337-firstrun-20260916T230330Z-logcat.txt` is mostly other people's waiting:
 * 3.6 s to join the console, 9.8 s of hole punching, then up to 30.3 s of asking a freshly-linked
 * console whether it will accept a stream yet - 43.9 s end to end. A single spinner over all of
 * that reads as a hang, so each wait gets a name, and PLE-340 replaced the second count that used
 * to sit under it with a bar (see [connectProgress] below).
 *
 * The phases are ordered and never go backwards: [PsnRemoteState] walks 1..4 while Home holds the
 * screen, and the stream screen picks up at 5 and 6.
 */
enum class ConnectPhase(@StringRes val labelRes: Int)
{
	/** Resolving the push server and opening the PSN session. */
	REACHING_NETWORK(R.string.connect_phase_reaching_network),

	/** The console has been asked to join; measured at ~3.6 s combined with the phase above. */
	WAKING_CONSOLE(R.string.connect_phase_waking_console),

	/** Signalling and hole punching the control socket; measured at ~9.8 s. */
	OPENING_ROUTE(R.string.connect_phase_opening_route),

	/** Registering this phone with the console — instant in the capture, but it can stall. */
	LINKING(R.string.connect_phase_linking),

	/** The stream itself is being asked for. */
	STARTING_STREAM(R.string.connect_phase_starting_stream),

	/**
	 * The console refused the stream and we are polling it. Deliberately last: on a first run it
	 * follows [STARTING_STREAM], and it is the honest end of the line, measured at 30.3 s.
	 */
	CONSOLE_NOT_READY(R.string.connect_phase_console_not_ready);

	val step: Int get() = ordinal + 1

	companion object
	{
		val TOTAL_STEPS = values().size
	}
}

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

/**
 * PLE-340: the user asked twice for a bar with no digits - "a progress bar that is timed to fill
 * in about 45 seconds. and only if it takes longer than that it should say 'this is taking longer
 * than usual'". 45 s is the measured end-to-end first run above, rounded up slightly.
 *
 * The bar granularity a [ProgressBar] is driven at ([ConnectProgress.barFraction] times this).
 * 1000 rather than the platform default of 100 so a half-second tick still visibly moves it.
 */
const val CONNECT_BAR_DURATION_MS = 45_000L
const val CONNECT_BAR_MAX = 1_000

/**
 * One rendering of [ConnectPhase]: which phase (for the label) and how far the 45 s bar covering
 * the *whole* connect has filled.
 *
 * [barFraction] is linear in wall-clock time since the connect being watched started, not
 * weighted by phase. The six phases vary wildly in length (3.6 s, 9.8 s, up to 30.3 s), and
 * splitting the measured 43.9 s across all six beyond what the capture already gives per named
 * phase would be a second guess stacked on the first. A linear bar is honest about that variance
 * instead of pretending to know it in more detail than was measured, and reusing the same
 * elapsed-since-start value for both the fill and the [overtime] switch means the two can never
 * disagree with each other. It also needs nothing threaded across the Home card and the stream
 * overlay beyond what each already tracks locally: a single clock that starts once, when the
 * first phase of an attempt begins, and never restarts at a phase boundary - which is what makes
 * this one bar for the whole connect rather than the "bar per phase" PLE-337 shipped with.
 */
data class ConnectProgress(
	val phase: ConnectPhase,
	/** 0f..1f; monotonically non-decreasing for as long as [overtime] is false. */
	val barFraction: Float,
	/** Past ~45 s: the bar goes indeterminate and the caller shows "taking longer than usual". */
	val overtime: Boolean
)

/**
 * @param sessionElapsedMs time since the connect being watched started - *not* since [phase]
 * started. The caller starts this clock once, the moment the first phase of an attempt begins,
 * and keeps it running across every later phase change until the attempt ends; restarting it per
 * phase would reintroduce the "bar per phase" this replaces.
 */
fun connectProgress(phase: ConnectPhase, sessionElapsedMs: Long): ConnectProgress
{
	val clamped = sessionElapsedMs.coerceAtLeast(0L)
	return ConnectProgress(
		phase = phase,
		barFraction = (clamped.toFloat() / CONNECT_BAR_DURATION_MS.toFloat()).coerceIn(0f, 1f),
		overtime = clamped >= CONNECT_BAR_DURATION_MS
	)
}

/**
 * Applies [ConnectProgress] to a bar + "taking longer than usual" line. Shared by the console
 * list card (PLE-337/338) and the stream connect overlay so the two surfaces a user might see
 * mid-connect never disagree about what "taking longer" looks like, and neither ever renders a
 * digit.
 */
fun ConnectProgress.applyTo(bar: ProgressBar, overtimeText: TextView)
{
	bar.isVisible = true
	if(overtime)
	{
		bar.isIndeterminate = true
		overtimeText.isVisible = true
	}
	else
	{
		bar.isIndeterminate = false
		bar.max = CONNECT_BAR_MAX
		bar.setProgress((barFraction * CONNECT_BAR_MAX).toInt(), true)
		overtimeText.isVisible = false
	}
}

/** Hides both views [applyTo] would otherwise drive, for when nothing is being connected. */
fun hideConnectBar(bar: ProgressBar, overtimeText: TextView)
{
	bar.isVisible = false
	overtimeText.isVisible = false
}
