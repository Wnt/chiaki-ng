// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_LINKWATCHDOG_H
#define CHIAKI_LINKWATCHDOG_H

#include "common.h"

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * PLE-423: how long an active stream may go without a single inbound packet from
 * the console before it is declared dead.
 *
 * Neither `ctrl.c` nor `takion.c` has a local idle timeout: without this, a
 * symmetric network loss leaves the session sending a heartbeat into a dead
 * socket forever (observed past four minutes on device, PLE-393) with a frozen
 * frame on screen and no error dialog.
 *
 * The figure is set by measurement, not by taste. A healthy stream puts
 * something on the socket roughly every 16 ms (video at 60 fps) and, even with
 * video stalled, the console still acks the 1 Hz heartbeat, so the *floor* on
 * inbound traffic is one packet per second. The worst inbound gaps the
 * impairment rig's deliberate profiles produce are measured per run and
 * reported in `docs/verification/PLE-423/`; this constant is an order of
 * magnitude above them, because a false quit mid-game is worse than the hang
 * it replaces.
 */
#define CHIAKI_LINK_WATCHDOG_TIMEOUT_MS 10000

/**
 * Liveness deadline for one stream, in truncated monotonic milliseconds.
 *
 * Milliseconds rather than the microseconds used elsewhere, and 32-bit rather
 * than 64-bit, on purpose: the timestamp is written by the Takion receive
 * thread and read by the StreamConnection thread without a lock, and a 32-bit
 * aligned load is indivisible on every ABI this project builds (including
 * 32-bit ARM, where a `uint64_t` load is not). All arithmetic below is
 * unsigned, so the 49.7-day wrap of the truncated clock is handled by the
 * subtraction itself and never produces a false expiry.
 */
typedef struct chiaki_link_watchdog_t
{
	/** Silence, in ms, that counts as the console being unreachable. */
	uint32_t timeout_ms;
	/** When the watchdog was armed; the reference until the first packet lands. */
	uint32_t armed_ms;
	/** Longest silence observed so far, for the report at teardown. */
	uint32_t max_silence_ms;
	/** Set once the deadline has passed, so the quit is raised exactly once. */
	bool expired;
} ChiakiLinkWatchdog;

/** Arm the watchdog at `now_ms`, with `timeout_ms` of silence as the deadline. */
CHIAKI_EXPORT void chiaki_link_watchdog_init(ChiakiLinkWatchdog *watchdog, uint32_t now_ms, uint32_t timeout_ms);

/**
 * Silence, in ms, as of `now_ms`, given the last inbound packet at `last_receive_ms`.
 *
 * `last_receive_ms` of 0 means nothing has arrived yet and the arming instant is
 * used instead, so a stream that never receives anything still times out. A
 * `last_receive_ms` in the future relative to `now_ms` — the two clocks are read
 * at different instants on different threads — reads as no silence at all rather
 * than as a near-wrap enormity.
 */
CHIAKI_EXPORT uint32_t chiaki_link_watchdog_silence_ms(const ChiakiLinkWatchdog *watchdog, uint32_t last_receive_ms, uint32_t now_ms);

/**
 * Feed the watchdog the current time and the last inbound timestamp.
 *
 * Returns true on the single poll where the deadline is first crossed; false
 * on every poll before it and on every poll after it, so the caller raises one
 * quit rather than one per second. Updates `max_silence_ms`.
 */
CHIAKI_EXPORT bool chiaki_link_watchdog_check(ChiakiLinkWatchdog *watchdog, uint32_t last_receive_ms, uint32_t now_ms);

#ifdef __cplusplus
}
#endif

#endif // CHIAKI_LINKWATCHDOG_H
