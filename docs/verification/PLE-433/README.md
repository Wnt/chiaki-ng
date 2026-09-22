# PLE-433: Reconnect after a genuine watchdog timeout — CONFIRMED

## Result: CONFIRMED — the fix works for a real link-watchdog quit, the same as PLE-393 proved for an injected one

Device: Samsung S22 Ultra (SM-S908B), `192.168.40.101:5555`, reserved via
`scripts/dev/device.py run PLE-433`. Console: PS5-466, `192.168.1.164`, `ready`
per `scripts/dev/ps5-discover.py`. App: this worktree's own build, `GATE: PASS`
(`scripts/dev/gate.sh`), installed with `adb install -r` and confirmed as the
running APK by `check-apk-freshness.py` before the capture (built from
`03ac09a332d7`).

## Why this ticket exists, and what it adds over PLE-393

PLE-393 proved Reconnect works after an *injected* generic error quit
(`quit_reason=10`, delivered through the PLE-422 devtools broadcast straight
into `StreamSession.remoteEvent()` — a Kotlin-layer shortcut, not the native
session-teardown path). PLE-423 separately proved a *genuine* network-loss
watchdog timeout quits correctly (`reason=stream_connection_timeout`,
originating in `streamconnection.c`'s link-watchdog check) but explicitly did
not test Reconnect afterward — its README says so under "What is not proven
here". Nobody had combined the two: a real watchdog-timeout quit followed by
an actual Reconnect tap, on hardware.

`1acc5ab0` (PLE-384) is the same fix under test as PLE-393: `shutdown()` no
longer nulls the `SurfaceView`'s `surface` field, so the next `resume()` (via
Reconnect) still finds a valid surface to decode into instead of the field
being pre-emptively cleared.

## What the capture did

`docs/verification/PLE-433/capture.sh` (`OUT_DIR=build/captures/ple433`):

1. Connected to PS5-466, confirmed healthy streaming (`Feedback stats:`
   climbing, `decoder_init_count_before=1`).
2. Cut the network 100% at the rig (PLE-423's `MODE=loss` technique,
   `scripts/net/impairctl.py profile custom --loss 100% --commit`).
3. Waited for the native quit. It came **13.9 s** after the cut:
   ```
   09-22 12:44:35.773 E/Chiaki: StreamConnection link watchdog: nothing received from the console for 10918 ms (limit 10000 ms), quitting
   09-22 12:44:35.776 I/Chiaki: Session quit: reason=stream_connection_timeout remote_reason=""
   ```
   `03_error_dialog.png`: the real dialog, **"Session has quit: The console
   stopped responding"**, with **Quit** / **Reconnect**.
4. Restored a clean network (the console has to be reachable again for any
   Reconnect attempt to have a chance) and tapped **Reconnect**
   (`android:id/button1`), using PLE-393's anchored-to-teardown method: success
   is only counted from `Feedback stats:` lines logged after the most recent
   `Shutting down JNI Session` line, so stale stats from the session being left
   can't produce a false positive. The loop also detects and re-taps through a
   PLE-428 `rp_in_use` race if the console hasn't released the prior session
   yet — none occurred in this run (`already_in_use_seen=0`).
5. The decoder was torn down and re-established exactly once
   (`decoder_init_count_before=1 decoder_init_count_after=2`), and real video
   resumed:
   ```
   Feedback stats: window 1001 ms video received 60 decoded 60 ... | takion_silence_ms 7 window_max_gap_ms 13
   ```
   `06_final.png` shows the PS5 UI live on the phone with a "MegaJontero
   connected using Remote Play" toast — the console's own confirmation of a
   fresh, successful connection, not just decoded pixels.

## Verdict

**Confirmed on hardware.** Reconnect after a genuine, network-induced
watchdog-timeout quit (`reason=stream_connection_timeout`, not an injected
substitute) tears down the old decoder and re-establishes a new one that
decodes real video, exactly as PLE-393 showed for the injected generic error
quit. `1acc5ab0`'s fix covers both the injected path and the real watchdog
path — there is no separate code path for the watchdog quit that the earlier
verification could have missed.

## Evidence paths

All under `build/captures/ple433/` (gitignored; this file is the durable
record):
- `session_logcat.txt` — full `adb logcat -v time` across the whole run.
- `00_main.png`, `01_streaming.png` — baseline before the cut.
- `02_watchdog_line.txt`, `02_quit_reason.txt` — the watchdog-fire and
  `Session quit:` lines.
- `03_error_dialog.png` — the genuine error dialog after the real timeout.
- `04_error_dialog_clean_net.png` — dialog still up after the network was
  restored, immediately before the Reconnect tap.
- `05_dialog_ui.xml` — `uiautomator dump` proving `android:id/button1` is
  "Reconnect".
- `post_reconnect_tail.txt` — log tail anchored to the teardown that preceded
  the successful reconnect.
- `06_final.png` — PS5 UI live on the phone with the "connected" toast.
- `decoder_counts.txt`, `RESULT.txt` — machine-readable summary.
- `retry_classifications.tsv` — empty of PLE-428 races this run (would record
  them if they occurred).

## Not proven here

- **Repeatability across multiple watchdog-timeout cycles in one session** —
  this run tested one cut/reconnect cycle, the same scope PLE-393 used for its
  injected-quit scenario.
- **A PLE-428 `rp_in_use` race on this exact path** — none occurred in this
  run; the retry machinery that PLE-393 needed twice is wired in but was not
  exercised here.
