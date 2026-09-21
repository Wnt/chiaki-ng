# PLE-382: device screenshot of the connection-info menu (PLE-371 AC4)

## Verdict: CORRECT on hardware, both orientations — no defects found

Device: Galaxy S22 Ultra, SM-S908B, serial `192.168.40.101:5555`, reserved for
the whole run via the ticket's `resource:samsung` lease
(`scripts/dev/device.py status` showed `holder PLE-382` throughout). Console:
PS5-466, `192.168.1.164`, confirmed `ready` with `scripts/dev/ps5-discover.py`
before and after. App: this worktree's own build — `require_current_apk` in
`capture.sh` (via `docs/verification/lib/capture-guard.sh`) refused a stale
installed APK (built from `5bdc28c7`, missing `bd987403`) up front, so
`scripts/dev/gate.sh` (`GATE: PASS`) built and `adb install -r`'d a fresh one
from `1fa91bba` (this branch's base, `fork/android-port`, which already
includes PLE-371) before any capture ran. `scripts/dev/app-state.sh backup`
ran first, per AGENTS rule 11.

Network at capture time: phone on VLAN 40 (`192.168.40.101`), console on
`192.168.1.164` — different subnets, but both on the same physical LAN behind
the impairment rig with no VPN active on the phone (no WireGuard, no other
`TRANSPORT_VPN` network up). Rig left `clean` (no impairment profile applied).

## What each row reads, and whether it matches reality

`docs/verification/PLE-382/capture.sh` opens `streamMenuButton`'s popup after
~25 s settle and reads, identically in both orientations:

| Row | Value | Matches reality? |
|---|---|---|
| Connection | `Direct · 192.168.1.164:9295` | Yes. RFC1918 peer, no VPN transport active on the phone → the classifier's own documented rule for Direct (PLE-371's spec). Port 9295 is the fork's actual stream port, not a placeholder. |
| MTU | `1454 (measured)` | Plausible and, importantly, **not** silently defaulted: it says "measured", meaning senkusha's own probe (`senkusha.c`) succeeded and this is not the `session.c:753-755` untunneled-Ethernet fallback PLE-371 called out. 1454 is also the value an untunneled probe on this LAN should return. |
| RTT | `4-5 ms (measured)` | Matches the same run's `Feedback stats:` logcat line at the same wall-clock time (`rtt_ms 4.99`, `probe_rtt_ms 5.05`) and the phone-to-console distance on a clean LAN. "(measured)" confirms this is `session->rtt_us_measured`'s real branch, not the fabricated 1000 µs fallback. |

No row read "unknown" or a placeholder; `ConnectionMode.UNKNOWN`'s single-line
fallback (`connectionInfoLines` in `StreamActivity.kt:979-980`) never appeared
because the mode resolved well before the 25 s settle completed.

## Screenshots and backing dumps

- Portrait: `build/captures/ple382/portrait.png`, dump
  `build/captures/ple382/portrait_ui.xml` (grep for `text="` shows the exact
  three rows above plus `Fit`/`Zoom`/`Stretch`).
- Landscape: `build/captures/ple382/landscape.png`, dump
  `build/captures/ple382/landscape_ui.xml` (same rows).
- Full session log: `build/captures/ple382/session_logcat.txt` (67
  `Feedback stats:` lines over the run, `Session quit: reason=stopped` at the
  clean end — no error).

Visual check against both screenshots: text is fully legible in both
orientations, none of the four disabled rows (`Fit`/`Zoom`/`Stretch` are the
existing display-mode items; `Connection:`/`MTU`/`RTT` are PLE-371's) is
clipped, and the popup does not overlap the video or the touch controls in
either orientation — it draws below the dock, above the D-pad/face buttons,
same as the display-mode items it was appended to. Nothing to report as a
defect.

## A real hazard found while getting the landscape shot (not a StreamActivity defect, reporting per the ticket's brief)

Opening the menu in **landscape** cannot be done with a tap anywhere on the
video, unlike portrait. In landscape `aspectRatioLayout` (the video's
click-to-`showOverlay()` target) spans the *entire* screen, and
`DefaultTouchControlsFragment`'s touch-capture layer sits on top of nearly all
of it for gamepad/touchpad emulation — a tap in the screen's vertical middle
is consumed as controller input to the **console**, not as a click on the
video. One such stray tap during exploration (screen-centre, before the safe
zone below was found) visibly moved the PS5's own home-screen selection focus
onto a different card. Harmless here (no app launched, no state changed), but
it is a real side effect against shared hardware from what looks like an inert
tap, and worth knowing before anyone else scripts landscape interaction here.

The only reliable safe zone found empirically is a strip near the very top
edge of the screen (`y ≈ aspectRatioLayout.top + 80px`, horizontally centred),
which is clear of both the touch-control graphics and, apparently, their
invisible hit-regions, in both orientations. `capture.sh` derives this from a
live `aspectRatioLayout` dump each orientation rather than a hardcoded pixel
value, since the exact bounds differ by orientation. This is an artifact of
`DefaultTouchControlsFragment`'s layout, not of `StreamActivity`'s overlay
code — reporting it here as the ticket instructs, not fixing it.

## Method (the auto-hide the ticket warned about)

The connection-info rows live in `streamMenuButton`'s `PopupMenu`
(`StreamActivity.kt:938 showDisplayModeMenu`), which — unlike PLE-352's
chip — does **not** auto-dismiss once open. The only race is *opening* it:
`streamMenuButton` sits in the overlay dock, which auto-hides
`HIDE_UI_TIMEOUT_MS` (3.5 s) after last shown, and a bare `uiautomator dump`
alone takes ~2 s. `capture.sh` learns the button's coordinates once per
orientation (retrying the tap-video-then-dump pair up to 8 times), then opens
the menu for real from the cached coordinates with no dump in between,
verifying the popup's `Connection: ` text actually landed before screenshotting
(retrying up to 10 times). Both orientations succeeded on the first real
attempt in this run.

## What was run on hardware vs merely compiled

- **Run on hardware**: one real stream to PS5-466 on the S22 Ultra above,
  `streamMenuButton` opened and read in both portrait and landscape, screenshots
  and `uiautomator` dumps captured for both, full session logcat captured
  end to end. `scripts/dev/device.py status` held the `PLE-382` reservation for
  the whole run. The stream ended cleanly (`Session quit: reason=stopped`) and
  the console was confirmed `ready` again afterwards with `ps5-discover.py`.
- **Merely compiled/checked**: `scripts/dev/gate.sh` (host build, ctest,
  chiaki-unit case count, `assembleDebug` arm64-v8a, `testDebugUnitTest`) —
  `GATE: PASS`, run to produce the fresh APK this capture required; no source
  was changed by this ticket, so no emulator smoke test adds anything beyond
  what the gate and the hardware capture above already cover.
