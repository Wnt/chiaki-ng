# PLE-422: injecting an error `QuitReason` into a live stream

There was no safe way to drive `StreamActivity` into the error-quit dialog on
real hardware: total network loss wedges the session instead of quitting it,
and screen-off/on exits through an unrelated `STOPPED` quit. Both substitutes
were tried by PLE-393 and both exercise a different code path than a real
error. This is the devtools affordance that closes that gap.

## What it does

`StreamActivity`, only in a **debug build** (`BuildConfig.DEBUG`), registers a
dynamic `BroadcastReceiver` for the action below. Delivering it feeds a
`QuitEvent` straight into the live `StreamSession` through
`StreamSession.remoteEvent()` — the same entry point the PSN remote-session
path already uses for events that did not originate in this process. From
there it is indistinguishable from a real error: `StreamSession` posts
`StreamStateQuit`, and `StreamActivity.stateChanged()` shows the real error
dialog with Reconnect wired to the real `reconnect()` (`viewModel.pause()` +
`viewModel.resume()`, which tears down and recreates the native `Session`).
There is no shortcut that shows the dialog without also driving the state
machine underneath it.

## How to use it

A stream must already be live (`StreamActivity` resumed, video visible).
Reserve the phone first (`scripts/dev/device.py run <ticket> -- <command>`).

```sh
adb shell am broadcast -a com.metallic.chiaki.debug.INJECT_QUIT_REASON --ei quit_reason <N>
```

`<N>` is the ordinal of `ChiakiQuitReason` (`lib/include/chiaki/session.h`).
The ones worth injecting:

| N  | reason                                     | `isError` |
|----|---------------------------------------------|-----------|
| 1  | `STOPPED`                                   | false — the normal quit, no dialog |
| 8  | `CTRL_CONNECT_FAILED`                       | true |
| 10 | `STREAM_CONNECTION_UNKNOWN`                 | true — closest analog to the network-loss wedge PLE-393 could not reproduce |
| 11 | `STREAM_CONNECTION_REMOTE_DISCONNECTED`     | true |
| 12 | `STREAM_CONNECTION_REMOTE_SHUTDOWN`         | false — no dialog, `finish()`s straight away |
| 13 | `PSN_REGIST_FAILED`                         | true |

Any `isError == true` value raises the same `MaterialAlertDialogBuilder`
dialog a real error takes, with **Reconnect** and **Quit Session** buttons.

Confirm the injection landed on the real path by grepping logcat for the
`StreamActivity` tag: the receiver itself logs the injected reason before
handing it to the session, and `StreamSession`'s own `QuitEvent` handling
(unmodified by this ticket) posts `StreamStateQuit` from there exactly as it
would for a real quit — there is no separate log line that only fires for
the injected path.

```sh
adb logcat -s StreamActivity:* StreamHandoff:*
```

## Why it cannot reach a release build

- The receiver is only *registered* — `ContextCompat.registerReceiver(...)`
  — inside `if(BuildConfig.DEBUG)` in `StreamActivity.onCreate()`. A release
  build's `BuildConfig.DEBUG` is compiled to `false`, so that branch never
  runs and no receiver for `com.metallic.chiaki.debug.INJECT_QUIT_REASON`
  exists in the process; the broadcast has nothing to deliver to. This is the
  same guard shape as the existing `EXTRA_DIAGNOSTICS_PREVIEW` preview states
  (PLE-89), enforced by `scripts/dev/flag-gate.py` via
  `android/flag-gate-allowlist.txt`.
- Nothing else in the app ever sends `ACTION_INJECT_QUIT_REASON` or writes
  `EXTRA_QUIT_REASON`; the only way to reach it is `adb shell am broadcast`
  (or an on-device shell), which the flag gate polices as an `adb-only-extra`
  finding.

## Why it is inert when unused

Even in a debug build, nothing changes unless the broadcast is actually
sent: the receiver only runs code inside `onReceive`, which only fires on a
matching broadcast. No polling, no timer, no altered default — a debug build
that never receives this broadcast behaves exactly as it did before this
ticket.

## Out of scope

This ticket built the injection point only. `StreamSession`'s real error
handling and the Reconnect logic are unmodified — verifying that Reconnect
actually restores video after an injected error is
[PLE-393](https://linear.app/pleikkari/issue/PLE-393)'s job, not this
ticket's.
