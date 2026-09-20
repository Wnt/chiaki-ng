# PLE-423 — total network loss must quit the stream, impairment must not

Device: Galaxy S22 Ultra, **SM-S908B**, serial `192.168.40.101:5555`, on the
`Kari-Impair` VLAN behind CT 240. Console: **PS5-466**, `192.168.1.164`,
`ready` per `scripts/dev/ps5-discover.py`. APK built from `bd98740333f7` and
verified fresh on the device by `check-apk-freshness.py` at the start of every
run below. `stream_feedback_stats_log=true` (the 1 Hz diagnostics line rides
that pref).

The network is cut **at the rig**, never on the phone. `scripts/net/impair.sh`
exempts tcp/5555 in both directions, so a `custom --loss 100%` profile kills
the stream while leaving adb and logcat alive — the phone's Wi-Fi settings are
never touched.

## How to reproduce

```bash
# never fires under deliberate impairment (~12 min)
OUT_DIR=build/captures/ple423-impair MODE=impairment PHASE_SECONDS=120 \
  scripts/dev/device.py run PLE-423 -- docs/verification/PLE-423/capture.sh

# fires on a real cut (~2 min)
OUT_DIR=build/captures/ple423-loss MODE=loss \
  scripts/dev/device.py run PLE-423 -- docs/verification/PLE-423/capture.sh

python3 docs/verification/PLE-423/analyze.py build/captures/ple423-impair
```

`OUT_DIR` is mandatory (PLE-412). `build/captures/` is gitignored, so the
numbers below are the durable record, not the directories.

## 1. The timeout never fires under impairment

`build/captures/ple423-impair`, one continuous stream, 120 s per profile:

```
phase             polls  gap added ms  worst gap ms  peak silence ms   margin
01_clean            126             0           198               18     9982
02_blip-200ms       123            19           217              199     9801
03_4g               126             0           217               29     9971
04_wifi-slow        125             0           217               31     9969
05_clean            125             0           217               15     9985

whole session: worst inbound gap 217 ms, peak silence at a poll 199 ms, watchdog limit 10000 ms
PASS: the watchdog never came within 9801 ms of firing
```

`build/captures/ple423-blip`, a second run of `clean → blip-200ms → clean` at
140 s per phase:

```
whole session: worst inbound gap 212 ms, peak silence at a poll 135 ms, watchdog limit 10000 ms
PASS: the watchdog never came within 9865 ms of firing
```

`worst gap` is cumulative over the stream and only rises, so a profile's own
contribution is the step inside its window (`gap added`). `4g` and `wifi-slow`
added nothing, which bounds their worst gaps below blip-200ms's 217 ms rather
than giving them an exact figure — that is the honest reading of a monotone
counter, and it is enough, because the constant is set from the maximum.

**Read this before changing the threshold:** the measured worst case is
**217 ms** and the shipped limit is **10 000 ms**, a factor of 46. The `4g` and
`wifi-slow` profiles produce steady *loss and delay*, not silence: the console
keeps sending, so the stream is degraded rather than quiet. Only `blip-200ms`
produces an actual gap, and it is one delay step long by construction. The
figure that really guards the limit is not in the rig at all — it is the
floor on inbound traffic in a healthy stream, one console DATA_ACK per second
for our 1 Hz heartbeat, even with video fully stalled. Ten seconds is ten
consecutive misses of that.

### A caveat on the dynamic-profile check

The PLE-418 pattern check failed on the first run (`4` excursions where `6±1`
were expected) and on the second run's *first clean phase* (one excursion at
+56 s). The blip phase itself passed on the second run (`6`, expected `7±1`),
which is why the blip numbers above come from two runs rather than one. This
matches the known flakiness of `blip_loop` on the shared remote host
(PLE-418). It does not weaken the finding here: a blip loop that under-fires
produces *fewer and shorter* silences, so the 217 ms worst gap is a lower
bound on what a perfectly cycling loop would produce — and both runs agree to
within 5 ms anyway.

## 2. A real cut quits with an error, fast

`build/captures/ple423-loss`. Stream established on a clean network, 30 s of
steady streaming, then `impairctl.py profile custom --loss 100%`:

```
09-21 01:03:31.466 E/Chiaki: StreamConnection has received nothing from the console for 5682 ms
09-21 01:03:32.469 E/Chiaki: StreamConnection has received nothing from the console for 6684 ms
09-21 01:03:33.470 E/Chiaki: StreamConnection has received nothing from the console for 7686 ms
09-21 01:03:34.472 E/Chiaki: StreamConnection has received nothing from the console for 8687 ms
09-21 01:03:35.474 E/Chiaki: StreamConnection has received nothing from the console for 9689 ms
09-21 01:03:36.476 E/Chiaki: StreamConnection link watchdog: nothing received from the console for 10691 ms (limit 10000 ms), quitting
09-21 01:03:36.476 I/Chiaki: StreamConnection link: worst inbound gap 171 ms, watchdog limit 10000 ms
09-21 01:03:36.476 I/Chiaki: StreamConnection sending Disconnect
09-21 01:03:36.476 E/Chiaki: StreamConnection closing after the console became unreachable
09-21 01:03:36.478 E/Chiaki: StreamConnection timed out: the console became unreachable
09-21 01:03:36.478 I/Chiaki: Session has quit
09-21 01:03:36.478 I/Chiaki: Session quit: reason=stream_connection_timeout remote_reason=""
```

**13.5 s** from the `impairctl` call returning to the quit being observed,
against *four minutes and counting* before this change (PLE-393). The lib's own
figure is 10 691 ms of silence: the limit plus one 1 Hz poll, plus the ~1.7 s
the netem apply itself takes over ssh.

Android then does the rest with **no change on its side**:

```
09-21 01:03:36.534 I/WindowManager: WindowManagerGlobal#addView ... [StreamActivity],
  caller=android.app.Dialog.show:540 com.metallic.chiaki.stream.StreamActivity.stateChanged:1155
```

`03_error_dialog.png` shows it: **"Session has quit: The console stopped
responding"** with **Quit** and **Reconnect**. That is the existing error
dialog, reached because `chiaki_quit_reason_is_error()` is true for anything
that is not `STOPPED` or `REMOTE_SHUTDOWN` — which is why the new reason was
appended at the end of `ChiakiQuitReason` and needed no UI work.

## What is not proven here

* **Reconnect actually recovering** after this quit. The dialog and its
  Reconnect button are PLE-393's and PLE-428's subject, not this ticket's; all
  that is shown above is that the error path is entered correctly.
* **An exact worst gap for `4g` and `wifi-slow`** — see the bound above.
* **Real-world Wi-Fi stalls** an AP roam or a DFS channel change would produce.
  The rig does not reproduce them; the 46x margin is the answer to them, not a
  measurement of them.
