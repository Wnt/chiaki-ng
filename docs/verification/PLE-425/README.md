# PLE-425 — evidence on whether `StreamActivity` should finish after any `onPause()`

**This ticket decides nothing.** It assembles evidence for the operator. No app code
was changed.

Device: Samsung S22 Ultra (SM-S908B), serial/endpoint `192.168.40.101:5555`, reserved
via `scripts/dev/device.py run ple-425`. App: this worktree's own build (`fork/android-port`
+ this branch, no code changes), `scripts/dev/gate.sh` → `GATE: PASS`, installed with
`adb install -r` after `scripts/dev/app-state.sh backup`. PS5-466, 192.168.1.164,
confirmed `ready` with `scripts/dev/ps5-discover.py` before and after this run.

## 1. What `1acc5ab0` actually changed, and what it claims

`1acc5ab0` ("PLE-384: keep the stream alive through Samsung DeX window and display
changes") touches `StreamActivity.onPause()` directly:

```kotlin
override fun onPause()
{
	unregisterDisplayListener()
	configureWifiLock(false)
	super.onPause()
	configurePerformanceMode(false)
	if(debandRenderer != null) {
		binding.debandSurfaceView.onPause()
	}
	// A configuration change this activity does not handle in place (the density step when DeX
	// moves the window between the phone and the TV) recreates it; the view model and its
	// session outlive that, so keep streaming instead of ending the session (PLE-384).
	if(isChangingConfigurations)
		Log.i("StreamActivity", "Recreating the stream window for a configuration change; the session continues")
	else
		viewModel.pause()
}
```

The commit message's own words: *"A density change (phone panel <-> TV) still
recreates the views, but onPause no longer ends the session when
isChangingConfigurations; the decoder rebuilt on the new surface requests an IDR."*
Before this commit, **every** `onPause()` called `viewModel.pause()` unconditionally —
including the DeX density-change recreate, which is what broke DeX. The fix narrows
the condition to spare exactly `isChangingConfigurations`; it does not touch the
`else` branch. Every `onPause()` that is *not* a configuration change — which is
every case this ticket tests except rotation and (unverified) DeX — still runs
`viewModel.pause()` exactly as before `1acc5ab0`, unchanged by this commit.
So `1acc5ab0` is not what causes screen-off/shade/other-app/recents to end the
stream; that behaviour predates it. What `1acc5ab0` is responsible for is the
**one exemption** (`isChangingConfigurations`), and PLE-393 already confirmed the
Reconnect-surface part of the same commit works.

`viewModel.pause()` → `StreamSession.pause()` → `shutdown()`:

```kotlin
fun shutdown()
{
	handoffHandler.removeCallbacksAndMessages(null)
	session?.stop()
	session?.dispose()
	session = null
	_state.value = StreamStateIdle
}
```

`session?.stop()` runs synchronously here on the main thread, but the native
session's own background thread raises its `QuitEvent(reason=stopped)`
asynchronously; `eventCallback` posts `StreamStateQuit(STOPPED)` via `postValue`,
which lands on the main thread *after* `onPause()` (and this synchronous
`_state.value = StreamStateIdle`) have already returned — this is the "postValue
from a background thread after onPause() has already returned" PLE-393's worker
described. `StreamActivity`'s state observer then sees `StreamStateQuit`, and
because `reason=stopped` is `chiaki_quit_reason_is_error() == false`, it hits the
`else` branch of the observer, which calls `finish()` with **no dialog at all**.
Confirmed on device below (`Stop JNI Session` / `Session quit: reason=stopped` /
`Shutting down JNI Session`, no `alert_message_session_quit` dialog in any
screenshot).

## 2. Case-by-case result

| Case | Tested / reasoned | `onPause()` fires a non-config-change pause? | What happens | What the user sees |
|---|---|---|---|---|
| Screen off, then screen on | **Tested** | Yes | Session torn down (`Stop JNI Session` → `Session quit: reason=stopped` → `Shutting down JNI Session`) ~0.9 s after the power key; `StreamActivity` finishes | **Silent.** Unlocking lands on the console list (`MainActivity`), no dialog, no explanation — just a "Last session 0:12, 0 drops, Good network" summary card as if the user had quit deliberately |
| Notification shade pulled down, then collapsed | **Tested** | No | No `Stop JNI Session` anywhere between the two markers; the stream is still live in the after-screenshot, showing the PS5 UI and on-screen controls | Nothing — stream uninterrupted throughout |
| Another app takes foreground briefly (Settings), then Back | **Tested** | Yes | `Stop JNI Session` fires 0.18 s after `am start` of Settings — before the user has even seen Settings appear; session ends immediately | **Silent.** Returning from Settings lands on the console list with a "Last session" card, same as screen-off |
| Recents switcher (`APP_SWITCH`), then dismissed | **Tested** | Yes | `Stop JNI Session` fires ~0.95 s after the switcher opens | **Silent.** Same console-list-with-summary-card outcome |
| Rotation (portrait ↔ landscape) | **Tested** | No — `onPause()` is never even called | `AndroidManifest.xml` declares `orientation\|screenSize\|smallestScreenSize\|screenLayout\|...` as handled in place for `StreamActivity`; on device, rotating produced two `"Configuration changed in place: ..."` log lines (`823x384 dp` then back to `384x823 dp`) and **no** `Stop JNI Session` between them — confirmed via `dumpsys activity activities` (`topResumedActivity=StreamActivity` throughout) and a landscape screenshot showing the live stream with the on-screen gamepad | Nothing — stream uninterrupted, screen simply rotates |
| DeX attach (PLE-384's own path) | **Not tested — reasoned only** | N/A | No DeX dongle is attached to this rig: `adb shell dumpsys display` shows exactly one `Display Id=` line (the internal panel). Reasoning from the code only: a DeX attach/detach is a *density* change, which is **not** in `StreamActivity`'s `configChanges` list, so it *does* recreate the activity and *does* call `onPause()` with `isChangingConfigurations=true` — the one case `1acc5ab0` exempts. If that reasoning is right, DeX should behave like rotation (survives); this run did not observe it | Not observed |

The first attempt at the rotation case used `settings put system user_rotation 1`
through the guarded `device-bin/adb` wrapper without `PLEIKKARI_ALLOW_DANGEROUS=1`;
the wrapper correctly refused every `settings put` call (see
`docs/DEVICE-CAPTURE.md`), so the phone never actually rotated and the first
`E_rotated.png` is a false negative (still portrait). The retest
(`rotation_retest.sh`, `PLEIKKARI_ALLOW_DANGEROUS=1`, rotation setting restored to
`accelerometer_rotation=1` immediately after) is the one reported above and is
the one with real evidence (`E2_rotated.png` genuinely landscape,
`E2_rotation_before.txt`/`E2_rotation_during.txt` showing `user_rotation` 0→1,
`E2_result.txt`: `STILL_STREAMING=yes`).

## 3. The cost of each side

**Cost of the current behaviour** (any non-config-change `onPause()` ends the
session, silently): a player who glances at a notification, checks another app for
ten seconds, or uses the recents switcher to peek at something else loses the
entire stream with **no on-screen explanation** — they discover it only by
returning to a console list and a "Last session" summary card, which reads
identically to a deliberate quit. There is no way, from the UI alone, to
distinguish "I quit" from "the OS decided I quit." Reconnecting costs a fresh
handshake (and, per PLE-428, sometimes a `rp_in_use` retry cycle on top).

**Cost of the naive alternative** ("keep the session alive across any pause"):
this is close to what the code did *before* some past change introduced
`shutdown()`-on-pause at all — the very split `1acc5ab0` did not touch. Two
things a naive always-keep-alive would have to solve, neither of which `1acc5ab0`
addresses (it only carved out configuration changes):
- **The surface.** `StreamSession.shutdown()`'s own comment says the surface is
  deliberately left alone across a `shutdown()`/`resume()` pair because the
  `SurfaceView` owns it and clears it via its own callbacks when it goes away —
  this is exactly the bug `1acc5ab0` fixed for Reconnect (PLE-393 verified it). A
  paused-but-not-torn-down session still holding a `Surface` that Android may
  destroy behind its back (`onPause` doesn't guarantee the `SurfaceView` survives
  backgrounding on all OEM skins) reopens the same "decode into nothing" class of
  bug from a different angle.
- **Backgrounded decoding and battery/console-side cost.** A `Session` left
  running while the activity is not visible keeps decoding video nobody watches,
  keeps the Wi-Fi lock and any performance-mode requests active
  (`configureWifiLock(false)` and `configurePerformanceMode(false)` are the first
  two lines of the current `onPause()`, both undone on the assumption the session
  is ending), and keeps the console's own encoder running for a client that is
  not receiving frames. None of that is measured by this ticket, but it is not
  free.

Both costs are real; this ticket is not the place to weigh them.

## 4. Question for the operator

`1acc5ab0` deliberately narrowed the pause-ends-session rule to exempt
**only** `isChangingConfigurations` (in-place config changes like rotation, and
by reasoning-only, DeX's density step); every other pause — screen off,
notification shade taking temporary focus [**tested as surviving, see below**],
another app coming forward, or opening the recents switcher — still ends the
session **silently**, with no error dialog, indistinguishable in the UI from a
deliberate quit. Should "any backgrounding-that-is-not-a-config-change ends the
session" remain the rule (cheapest to keep, but every glance at a notification or
app-switch during a game costs the whole stream with no explanation), or should
some subset of these (e.g. anything that returns within N seconds) instead
survive like a config change does — accepting that doing so revives the surface-
lifecycle and backgrounded-decoding costs in §3 that `1acc5ab0`'s narrower fix
did not have to solve?

(Correction to the paragraph above: the notification shade does **not** call
`onPause()` at all — see the table — so it already survives today, with no code
change needed. The open question is about the app-switch/other-app/screen-off
group, which does call `onPause()` today.)

## Evidence paths

All under `build/captures/ple425/` (gitignored, `OUT_DIR=build/captures/ple425`):
- `session_logcat.txt` — full `adb logcat -v time` across all five in-run cases
  (screen-off, shade, other-app, recents, first/invalid rotation attempt).
- `A_00_main.png` / `A_screen_off_on_after.png` — before/after the screen-off case;
  the after shot is the console list with a "Last session" card, no dialog.
- `B_00_main.png` / `B_shade_after.png` — the shade case; the after shot is the
  live stream, unaffected.
- `C_00_main.png` / `C_during_settings.png` / `C_other_app_after.png` — the
  other-app case.
- `D_00_main.png` / `D_during_recents.png` / `D_recents_after.png` — the recents
  case.
- `E_00_main.png` / `E_rotated.png` / `E_rotation_after.png` — the first,
  invalid rotation attempt (settings silently refused, phone never rotated).
- `E2_rotated.png`, `E2_restored.png`, `E2_rotation_before.txt`,
  `E2_rotation_during.txt`, `E2_result.txt` — the corrected rotation retest
  (`rotation_retest.sh`, run separately with `PLEIKKARI_ALLOW_DANGEROUS=1`),
  genuinely landscape, `STILL_STREAMING=yes`.
- `F_dex_not_tested.txt` — the DeX-not-attached statement, with the
  `dumpsys display` count backing it.
- `results.tsv`, `run.log` — the main run's log and (unfilled, left as the
  script's scaffold) results header; verdicts are in the table in §2 instead,
  derived from `session_logcat.txt` line numbers cross-referenced against each
  case's `PLE425 case=... BEGIN/END` markers (`adb shell log -t PLE425 ...`,
  itself visible in logcat).

`docs/verification/PLE-425/capture.sh` (`OUT_DIR=...` required) reproduces the
five in-run cases; `rotation_retest.sh` (kept in `build/captures/ple425/`,
gitignored, not committed — it is a one-off retest, not a reusable capture) is the
corrected rotation-only script, run with `PLEIKKARI_ALLOW_DANGEROUS=1`, which
restores `accelerometer_rotation` to `1` immediately after.

## Rig state at the end of this run

- `scripts/dev/ps5-discover.py`: `192.168.1.164:9302 PS5 PS5-466 … ready` (checked
  both before and after).
- `scripts/dev/device.py status`: no queue, this ticket still holds the lease for
  the remainder of its own run.
- Phone left on the launcher home screen, no stray dialogs, `accelerometer_rotation`
  restored to `1` (auto-rotate on) after the rotation retest — matches the
  pre-run default.
- No AvCap wedge encountered; `ps5-discover.py` reported `ready` after every case.
