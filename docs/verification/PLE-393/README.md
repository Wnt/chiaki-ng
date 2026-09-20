# PLE-393: Reconnect-after-an-error-quit verification

## Result: inconclusive (BLOCKED) — could not reach the scenario, not a confirmed regression

I could not get the phone into the state the fix actually addresses (StreamActivity
showing the "Session has quit: ..." error dialog with a Reconnect button), so I never
observed the Reconnect button being pressed. Everything below is either real-hardware
evidence for adjacent scenarios that DID work, or an explanation of why the two
substitutes I tried do not stand in for the real one. Per the ticket's own instruction
("if Reconnect still shows no video, report it as BLOCKED... do not attempt a fix"),
I did not touch `StreamSession.kt` or the decoder.

Device: Samsung S22 Ultra (SM-S908B), serial/endpoint `192.168.40.101:5555`, reserved
via `scripts/dev/device.py run ple-393`. App: this worktree's own build (branch
`jonni/ple-393-...`, which contains `1acc5ab0` — confirmed with
`git merge-base --is-ancestor 1acc5ab0... HEAD`), built by `scripts/dev/gate.sh`
(GATE: PASS) and installed with `adb install -r`. PS5-466, 192.168.1.164, confirmed
`ready` with `scripts/dev/ps5-discover.py` before starting.

## 1. What `1acc5ab0` actually changed (read first, per the ticket)

In `StreamSession.kt`:
- `shutdown()` (called by `pause()`, from `StreamActivity.onPause()` when not
  `isChangingConfigurations`, and from the error-dialog's Reconnect button via
  `reconnect() = viewModel.pause(); viewModel.resume()`) used to set `surface = null`
  unconditionally after stopping the native session. The very next `resume()` then
  found `surface == null` and never called `session.setSurface()`, so the newly
  created `Session` decoded into nothing.
- `shutdown()` now leaves the `surface` field alone — ownership stays with the
  `SurfaceView`, whose own holder callback (`surfaceDestroyed`) is the only thing
  that should null it.
- `resume()` and `attachRemoteSession()` now guard with
  `surface?.takeIf { it.isValid }` instead of using the field directly, so a
  genuinely-dead surface still is not reused blindly.
- The `video-decoder.c` half of the same commit (`surface_lost_idr_pending`) is a
  **different** code path: it fires when the same decoder survives a real
  `surfaceDestroyed`/`surfaceCreated` cycle on the same `Session` (a DeX display
  move), not when `shutdown()`/`resume()` tear down and rebuild the whole `Session`.
  It is not exercised by the Reconnect scenario this ticket is about, and no
  `surface_lost_idr_pending` log line is expected or was seen below.

So the actual bug was purely in the Kotlin layer's surface bookkeeping across a
`shutdown()` + `resume()` pair on the same `StreamActivity`/`StreamSession` instance.

## 2. What I could get real hardware evidence for

### Baseline connect (healthy)
Connected to PS5-466, confirmed `Feedback stats:` lines (`video received`/`decoded`
climbing, `per_s takion` ~120) within 10s. Screenshot and logcat exist for the
second run only (see "Evidence paths" below); the first run's baseline looked
identical.

### Clean quit → fresh connect (the ticket's control, scenario B) — WORKS
Left the stream with the hardware Back key (`adb shell input keyevent 4`).
`StreamActivity.dispatchKeyEvent` does not intercept Back for navigation (it forwards
to the controller input mapper first, then falls through to the default Activity
back behaviour), so this reliably runs the same `finish()` the in-app Quit button's
confirm dialog runs. Logcat for this run shows a fully graceful native teardown:
```
Stop JNI Session
Join JNI Session
StreamConnection is disconnecting
StreamConnection sending Disconnect
StreamConnection closed takion
StreamConnection completed successfully
Ctrl stopped
Session has quit
Session quit: reason=stopped remote_reason=""
Shutting down JNI Session
Video Decoder Input Thread exiting
Video Decoder Output Thread exiting
```
Back at the console list (`MainActivity`), tapping PS5-466 again connects a brand
new `StreamActivity`/`StreamSession`/`Surface` and `Feedback stats:` resume within
10s (`B_RESULT.txt`: `OK: Feedback stats present on the fresh connect (control)`).

This confirms connecting to the console still works after a clean stop, but — see
next section — it does **not** exercise the lines `1acc5ab0` changed, because a
fresh connect from the console list builds an entirely new `Surface`/`StreamSession`
object graph. It is a weak sanity check, not evidence for the fix.

## 3. The two substitutes I tried, and why neither confirms the fix

### Attempt 1: total network loss to force a genuine error quit — wedges, never quits
`scripts/net/impairctl.py profile custom --loss 100% --ttl 40s --commit` (the same
CT950/CT240 netem driver PLE-356/PLE-367 use for every A/B round on this phone;
the phone's own Wi-Fi radio/association is never touched, exactly as the tool's
docstring promises) cut the link for 40s mid-stream. Result, read from the source
first and then confirmed on-device:

- `lib/src/ctrl.c` and `lib/src/takion.c` have no local idle-timeout watchdog: a
  `QuitReason` only gets set from an explicitly **received** disconnect/error
  message (`lib/src/session.c:805`, `CHIAKI_ERR_DISCONNECTED` from
  `chiaki_stream_connection_run`) or from a hard local socket error surfacing
  through a blocking call. Under **symmetric** total loss, an explicit disconnect
  from the console can never arrive (both directions are cut), and the only other
  exit is the control socket's own TCP retransmission timeout, which is Linux's
  default multi-minute `tcp_retries2` ceiling — far outside a practical test window.
- On device this matched exactly: `video received`/`decoded` and `per_s takion`
  dropped to 0 within ~10s of applying the loss (confirmed in logcat), and stayed
  at 0 even after I restored `impair clean --commit` ~40s later. About 90s after
  the cut, the socket itself failed (`Takion failed to send raw: Bad file
  descriptor`, `StreamConnection failed to send heartbeat`, once per second) —
  and it **still never raised a `Session has quit` event**. I re-checked the live
  logcat ~4 minutes after the cut (23:29, cut applied at 23:23): still spinning
  the same failed-heartbeat loop, `audio_underruns` past 180000, no quit, no
  Reconnect dialog, and the console's frozen last frame stuck on screen (confirmed
  with a screenshot whose on-screen PS5 clock had stopped advancing).
- I recovered the phone with the hardware Back key (not force-stop — the app-state
  memory note on this fork is that force-stopping mid-stream can wedge the PS5's
  own `AvCap` for ~40 minutes; Back key still runs `StreamSession.shutdown()`'s
  graceful `session.stop()`/`dispose()`, confirmed by the same
  `StreamConnection sending Disconnect` / `Ctrl stopped` / `Session quit:
  reason=stopped` sequence as section 2).

This is a real, reproducible, hardware-confirmed finding (see Follow-ups), but it
is the **wrong** substitute for this ticket: it never produces the error dialog, so
Reconnect is never reachable this way. I do not have the raw logcat file for this
run any more — I deleted the capture directory between runs before copying it out,
which was a process mistake on my part (see Learnings). The timeline above is
reconstructed from the run's own stdout log
(`build/captures/ple393_run.log`) plus log lines I quoted verbatim while
investigating, in the session transcript for this ticket.

### Attempt 2: screen-off/screen-on pause/resume — exercises a different code path than Reconnect
Reasoning going in: `StreamActivity.onPause()` (screen off, not a configuration
change) calls `viewModel.pause()` and `onResume()` calls `viewModel.resume()` —
textually the exact same two calls `reconnect()` makes, on the exact same
`Surface`/`StreamSession` instance, no new `Activity`. On device this did call
`shutdown()` (`Stop JNI Session` / `Session quit: reason=stopped` / `Shutting down
JNI Session`, same sequence as section 2), but by the time the screen came back on
several seconds later, `StreamActivity` had already gone — `topResumedActivity` was
back at `MainActivity`'s console list with a "Last session" summary card, and there
is no `StreamActivity` `onCreate`/"Stream window created" log line anywhere between
the screen-off and the later Scenario B run, meaning it was not even recreated —
its `finish()` ran while the screen was still off.

Reading `StreamActivity.stateChanged()` explains why: the `QuitEvent` from the
native session is delivered to the `StreamState` `LiveData` via `postValue()` from
a **non-main thread** (the session's own worker thread, inside
`chiaki_stream_connection_run`), so it is queued rather than delivered
synchronously. `shutdown()`'s own `_state.value = StreamStateIdle` (a direct,
synchronous `setValue()` from the main thread, inside `onPause()`) does not cancel
that queued delivery. Once `onPause()` returns and the main Looper drains its
queue, the queued `StreamStateQuit(reason=STOPPED, isError=false)` reaches the
observer, and `stateChanged()`'s non-error branch calls `finish()` unconditionally
— this is unrelated to `1acc5ab0` and does not depend on the screen being off; it
would happen after **any** `pause()` that is not immediately followed by something
that changes `dialogContents` to suppress it.

I want to be careful not to over-claim a second bug here: `1acc5ab0`'s own commit
message explicitly frames non-config-change `onPause()` as intentionally ending the
session ("onPause no longer ends the session **when isChangingConfigurations**" —
implying it still does otherwise), and the error-dialog's Reconnect path may avoid
this exact race because `dialogContents` is still `StreamQuitDialog` at the moment
`reconnect()` synchronously calls `pause()` (`dialogContents` is only cleared by the
dialog's own dismiss listener, which Kotlin dispatches after `reconnect()`'s call
stack, not before — I have not traced this precisely enough to be sure). Either
way, this substitute does not put `StreamActivity` through the same lifecycle the
Reconnect button does, so it doesn't confirm or refute the fix, and I don't have
independent evidence it's a bug rather than intended design behind the button. It's
listed as a Follow-up.

## Evidence paths

- Second run (scenario B, and the failed/inconclusive scenario-A substitute):
  `build/captures/ple393/session_logcat.txt` (full `adb logcat -v time` from launch
  through both scenarios), `00_main.png`, `02_streaming_before_A.png`,
  `A_fail_nostream.png` (screen back at the console list after screen-off/on, with
  the "Last session 0:16" summary card), `B_01_back_at_list.png`,
  `B_03_control_streaming.png`, `A_RESULT.txt`, `B_RESULT.txt`.
- First run (network-blackout attempt): `build/captures/ple393_run.log` (script
  stdout/timeline only; the raw session logcat for this run was not preserved —
  see Learnings).
- `docs/verification/PLE-393/capture.sh` — the script that produced the second run;
  it documents both substitutes and why in its header comment.

## Verdict

**Not settled either way.** The fix's own logic (`surface?.takeIf { it.isValid }`
instead of an unconditionally-nulled field) is sound reasoning for the bug the
commit describes, and the adjacent path I *could* exercise on real hardware (clean
quit → fresh connect) works. But I was not able to drive the phone into the actual
"error dialog with a Reconnect button" state within this ticket's time/resource
budget, on this hardware, with the tools available to me, so I never watched
Reconnect either succeed or fail. That is a gap in verification, not a finding
that the fix is broken.

## Follow-ups

- A genuine mid-stream error `QuitReason` needs a way to be induced deliberately
  on this fork for testing (a devtools-only hook, gated per AGENTS.md rule 4, is
  the shape other tickets have used for similar problems) — without one, "verify
  the error-quit-and-Reconnect path on real hardware" is not reliably repeatable.
- Total (or near-total) symmetric network loss during an active stream leaves the
  session permanently wedged (`Takion failed to send raw: Bad file descriptor`,
  `StreamConnection failed to send heartbeat` once a second, forever) instead of
  raising a `Session has quit` event — confirmed for 4+ minutes post-cut. No
  Reconnect dialog is ever offered; the only recovery is leaving the Activity
  manually (Back key/Quit). Worth its own ticket with `lib/src/ctrl.c` /
  `lib/src/takion.c` as the likely starting point (no idle-timeout watchdog
  independent of an explicitly-received disconnect message or the kernel's own
  multi-minute TCP retransmission ceiling).
- Whether `StreamActivity` finishing shortly after **any** non-config-change
  `onPause()` (confirmed for screen-off; Home-button likely identical, untested)
  is intended is worth a one-line confirmation from whoever owns `StreamSession.kt`
  — it matches the commit's own description of the intended behaviour, but it also
  means "pause" is not a general-purpose background/foreground primitive for this
  app outside the Reconnect-dialog's narrow use, which is easy to assume otherwise.
