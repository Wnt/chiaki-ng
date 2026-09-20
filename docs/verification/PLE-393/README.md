# PLE-393: Reconnect-after-an-error-quit verification

## Result: CONFIRMED — the fix works, on both a genuine error quit and the clean-quit control

Device: Samsung S22 Ultra (SM-S908B), serial/endpoint `192.168.40.101:5555`, reserved
via `scripts/dev/device.py run ple-393`. App: this worktree's own build (branch
`jonni/ple-393-...`, rebased onto `fork/android-port` after PLE-422 landed
`9f6b125b`/`dd010baa`), built by `scripts/dev/gate.sh` (GATE: PASS) and installed
with `adb install -r`. PS5-466, 192.168.1.164, confirmed `ready` with
`scripts/dev/ps5-discover.py` before starting.

An earlier attempt at this ticket (preserved in git history on this branch, see
the previous commit) could not reach a genuine error `QuitReason` on hardware —
two substitutes were tried and both exercised a different code path than
Reconnect. That gap is why [PLE-422](https://linear.app/pleikkari/issue/PLE-422)
exists: a devtools-only `adb shell am broadcast` hook
(`docs/devtools-quit-injection.md`) that feeds a real `QuitEvent` into the live
`StreamSession` through the same `remoteEvent()` entry point the PSN
remote-session path already uses. From there it is indistinguishable from a real
error: the state machine, the error dialog, and the Reconnect button are all the
genuine ones. This run uses that hook.

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

## 2. Scenario A: genuine injected error quit → Reconnect — WORKS

`docs/verification/PLE-393/capture.sh` (`OUT_DIR=build/captures/ple393`):
connected to PS5-466, confirmed healthy streaming (`Feedback stats:` climbing),
then ran:

```sh
adb shell am broadcast -a com.metallic.chiaki.debug.INJECT_QUIT_REASON --ei quit_reason 10
```

(`10` = `STREAM_CONNECTION_UNKNOWN`, `isError=true`.) Logcat immediately shows the
injection landing on the real path and the real dialog going up:

```
00:25:58.980 W/StreamActivity: PLE-422 devtools: injecting QuitEvent(reason=Unknown Error in Stream Connection, isError=true)
```

Screenshot `A_01_error_dialog.png`: the genuine `MaterialAlertDialogBuilder` dialog,
"Session has quit: Unknown Error in Stream Connection / PLE-422 devtools injection",
with **Quit** / **Reconnect** buttons.

Tapped **Reconnect** (`android:id/button1`). Full decoder lifecycle from logcat,
timestamps in device-local time:

```
00:25:41.643 Initializing decoder with mime video/hevc            (initial connect)
00:25:41.669 AMediaCodec_configure() succeeded ...

00:25:58.980 injecting QuitEvent(... isError=true)                (this ticket's injection)

00:26:05.080 Stop JNI Session                                     (Reconnect tapped -> shutdown())
00:26:05.082 Session quit: reason=stopped
00:26:05.082 Shutting down JNI Session                            (old decoder torn down)
00:26:05.303 Initializing decoder with mime video/hevc            (resume(): decoder re-init #1)
00:26:05.311 Session quit: reason=session_request_rp_in_use       (PLE-428 race, see below)
00:26:05.325 AMediaCodec_configure() succeeded ...

00:26:10.551 Stop JNI Session                                     (auto-retried Reconnect)
00:26:10.552 Shutting down JNI Session
00:26:10.604 Initializing decoder with mime video/hevc            (re-init #2)
00:26:10.613 Session quit: reason=session_request_rp_in_use       (PLE-428 race again)
00:26:10.628 AMediaCodec_configure() succeeded ...

00:26:15.749 Stop JNI Session                                     (auto-retried Reconnect again)
00:26:15.750 Shutting down JNI Session
00:26:15.807 Initializing decoder with mime video/hevc            (re-init #3 -- this one holds)
00:26:15.826 AMediaCodec_configure() succeeded ...
00:26:19.167 Feedback stats: ... video received 49 decoded 49 ...  (real video, after this teardown)
00:26:20.168 Feedback stats: ... video received 60 decoded 60 ...
00:26:21.170 Feedback stats: ... video received 60 decoded 60 ...
```

`A_post_reconnect_tail.txt` is the tail of the log starting at the last "Shutting
down JNI Session" line before success — i.e. it is anchored to the teardown that
preceded the successful re-init, not to the moment Reconnect was first tapped, so
it cannot include stale `Feedback stats:` lines from the session that was being
left. `A_04_final.png` shows the PS5 home screen live on the phone with a
"MegaJontero connected using Remote Play" toast, confirming the console's own view
of a fresh, successful connection.

**PLE-428 (a separate, already-filed defect) reproduced twice in this run**: the
first two Reconnect attempts landed while the console had not yet released the
prior session (`reason=session_request_rp_in_use`, which `chiaki_quit_reason_is_error()`
treats as an error, so each one raised its own dialog). This is not this ticket's
fix — `capture.sh` detects each new teardown and taps Reconnect again
automatically, which is what a user retrying past the same on-screen message would
do. `A_decoder_counts.txt`: `already_in_use_seen=1`, `decoder_init_count_before=1
decoder_init_count_after=4` (1 initial + 3 across the two failed attempts and the
one that held).

**Verdict for Scenario A: the decoder is torn down and re-established, and real
video resumes, after a genuine error-quit Reconnect.** `1acc5ab0`'s fix holds.

### A methodology trap worth recording
The first version of this run's capture script counted `Feedback stats:` lines
appearing anywhere after the Reconnect tap as success. That is wrong: the
**old** session keeps emitting `Feedback stats:` for a second or two after the tap,
until its own `shutdown()` actually runs — so the first script version reported a
false "OK" using stats from the session the user had just left, not the new one,
and never even noticed the PLE-428 race playing out underneath it. The fix was to
anchor the stats check to the most recently logged `Shutting down JNI Session`
line, which moves forward every time a new teardown happens, and to keep
re-tapping Reconnect for as long as new teardowns keep appearing. See `capture.sh`.

## 3. Scenario B: clean quit → fresh connect (the control) — WORKS

Left the stream with the hardware Back key (`adb shell input keyevent 4`).
`StreamActivity.dispatchKeyEvent` does not intercept Back for navigation, so this
reliably runs the same `finish()` the in-app Quit button's confirm dialog runs.
Logcat shows a fully graceful native teardown (`Stop JNI Session` → `Session quit:
reason=stopped` → `Shutting down JNI Session`), then a fresh connect from
`MainActivity`'s console list builds a brand-new `Surface`/`StreamSession` and
`Feedback stats:` resume within 10s (`B_RESULT.txt`: `OK: Feedback stats present
on the fresh connect (control)`; screenshot `B_03_control_streaming.png` shows the
PS5 home screen live).

This does not exercise the lines `1acc5ab0` changed (a fresh connect builds an
entirely new object graph, so `surface` was never non-null-but-stale to begin
with) — it is the control confirming that connecting to the console still works
at all, not evidence for the fix itself.

## 4. Both paths work — the fix is confirmed

Per the ticket's own instruction: "if both paths work, the fix is confirmed." Both
did.

## Evidence paths

All under `build/captures/ple393/` (this run; `OUT_DIR=build/captures/ple393`,
`--force` to overwrite this ticket's own earlier attempt in the same directory):
- `session_logcat.txt` — full `adb logcat -v time` across the whole sequence
  (initial connect, injection, three Reconnect taps including the two PLE-428
  races, and the clean-quit control).
- `00_main.png`, `02_streaming_before_A.png` — baseline.
- `A_01_error_dialog.png` — the genuine error dialog after injection.
- `A_02_dialog_ui.xml` — `uiautomator dump` proving `android:id/button1` is the
  real "Reconnect" button (and `android:id/button2` is "Quit").
- `A_post_reconnect_tail.txt` — log tail anchored to the teardown that preceded
  the successful reconnect, containing the real post-Reconnect `Feedback stats:`
  lines.
- `A_04_final.png` — PS5 home screen live, "MegaJontero connected using Remote
  Play" toast.
- `A_decoder_counts.txt`, `A_RESULT.txt` — machine-readable summary.
- `A_retry_at_line*_ui.xml` — dumps captured at each PLE-428 retry.
- `B_01_back_at_list.png`, `B_02_main_ui.xml`, `B_03_control_streaming.png`,
  `B_quit_reason.txt`, `B_RESULT.txt` — the clean-quit control.

`docs/verification/PLE-393/capture.sh` is the script that produced this run and
documents both scenarios in its header comment. An earlier, inconclusive run of
this ticket (which could not reach a genuine error quit and tried two substitutes
that turned out to exercise different code paths than Reconnect) is preserved in
this branch's git history for reference; its findings became
[PLE-422](https://linear.app/pleikkari/issue/PLE-422) (the injection hook used
here), [PLE-423](https://linear.app/pleikkari/issue/PLE-423) (the network-loss
wedge), and [PLE-424](https://linear.app/pleikkari/issue/PLE-424)/[PLE-425](https://linear.app/pleikkari/issue/PLE-425)
(other follow-ups).

## Verdict

**The fix works.** Reconnect after a genuine error quit (`isError=true`, injected
through the real `StreamSession.remoteEvent()` path) tears down the old decoder
and re-establishes a new one that decodes real video — confirmed by
`Shutting down JNI Session` → `Initializing decoder` → `AMediaCodec_configure()
succeeded` → real `Feedback stats:` lines with `video received`/`decoded` climbing,
plus a screenshot of live PS5 video with the console's own "connected" toast. The
clean-quit-then-fresh-connect control also works. Both paths pass, per the
ticket's own acceptance criterion.

## Follow-ups (not this ticket's scope, filed separately)

- **PLE-428** (already filed): the first Reconnect after an error can race the
  console's own session teardown and land on `session_request_rp_in_use`
  ("Remote Play on Console is already in use"). This run reproduced it twice in a
  row before the third attempt held. Worth checking whether the existing
  `SessionHandoffRetry` machinery (currently only armed for the "just linked"
  handoff, `StreamSession.kt:52`) should also cover a Reconnect-triggered
  `rp_in_use`, so the user does not have to tap through it manually.
