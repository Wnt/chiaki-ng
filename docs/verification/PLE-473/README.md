# PLE-473: surface the stall figure in the connection-info popup

## Decision: connection-info popup, one row, "last stall, N s ago"

PLE-371's popup (`streamMenuButton`'s `PopupMenu`) already carries the
Connection/MTU/RTT rows the ticket asks to extend; the badge's own dock has
"no room in it for mode + peer address + MTU alongside the existing
RTT/jitter/loss line" (`StreamActivity.kt`'s own comment above
`showDisplayModeMenu`), so a fourth number there was never realistic. The
popup is the one place built for exactly this: a disabled, read-only row per
fact.

What to show, and why not the alternatives the ticket named:

- **Not a live figure.** `NetworkQualityClassifier`'s window is
  `FAST_WINDOW_SECONDS` = 5 s. A player who watches a freeze, registers it,
  and taps through to the overlay has usually taken longer than that — the
  live value has already decayed back toward 0 by the time they look, which
  would make the row lie about the freeze they just saw.
- **Not a session-worst.** An early, one-off stall would keep answering "why
  did it just freeze" long after it stopped being true, for the rest of the
  session.
- **"Last stall, N s ago"** — the value shown here — answers exactly the
  question a player has when they open this popup right after a freeze: how
  bad was it, and is it the thing I just felt. `StallInfo.kt` holds the
  tracker (`StallTracker`) and presenter (`StallInfoPresenter`); both are
  plain, context-free classes, tested in `StallInfoTest.kt`.

**Hiding on a clean session.** The row is absent (not "0 ms") until a stall
reaches `NetworkQualityThresholds.STALL_MS` — the same 500 ms bar that moves
the quality badge off GOOD. PLE-464 measured the worst clean-LAN gap at 19 ms
and the worst non-outage profile (wifi-slow) at 31 ms; a lower bar would make
the row flicker on a healthy link, which is exactly the noise the ticket
asks not to show. Confirmed on device below: a clean session never shows the
row in either orientation.

**No instructional text.** The row is a plain fact, same shape as
Connection/MTU/RTT — nothing to discover beyond what those three already
require.

## What was run on hardware

Device: Galaxy S22 Ultra, SM-S908B, serial `192.168.40.101:5555`, reserved
for the whole run via the ticket's `resource:samsung` lease
(`scripts/dev/device.py status` held `PLE-473` throughout). Console: PS5-466,
`192.168.1.164`, confirmed `ready` with `scripts/dev/ps5-discover.py` before
capture. `scripts/dev/app-state.sh backup` ran first (AGENTS rule 11). The
impairment guest script was reinstalled from this worktree
(`scripts/net/impairctl.py install --commit`) before capturing — it was
`DRIFT` beforehand — and `status` read `IN SYNC` afterward.

### 1. `roam-1200ms`, both orientations — `docs/verification/PLE-473/capture.sh`

One real stream, `roam-1200ms` applied after a 15 s clean settle, then a
screenshot of the popup in each orientation. `pattern_check.txt` (via
[PLE-418](https://linear.app/pleikkari/issue/PLE-418)'s guard) confirms the
capture's own link-silence series actually shows the profile's outage shape:

```
OK  01_roam-1200ms: 3 excursions in 86.5s from link silence (expected 4 +/-1), widths [3, 2, 2] (expected 2-4 for a 1.2s pulse) -- OK
```

- Portrait: `build/captures/ple473/portrait.png`,
  `build/captures/ple473/portrait_ui.xml` — row reads `Last stall: 1,2 s,
  0:14 ago`, fully visible with no scroll.
- Landscape: `build/captures/ple473/landscape.png`,
  `build/captures/ple473/landscape_ui.xml` — same row, `0:10 ago`, but only
  after `capture.sh` scrolls the popup (see finding below).

The comma decimal separator (`1,2 s` not `1.2 s`) is the phone's configured
locale (`fi_FI`, confirmed in the capture's `session_logcat.txt`
configuration dump) — `getString`'s `%.1f` respects device locale by design,
same as the rest of the app's non-ASCII-locked UI text. `MTU`/`RTT` never
show this because they format integers.

### 2. Clean session — `docs/verification/PLE-473/capture-clean.sh`

Same recipe with no impairment applied. `session_logcat.txt` confirms the
real ceiling: `stall_ms` peaked at 209 (below the 500 ms bar) across the
whole run.

- Portrait/landscape `build/captures/ple473-clean/{portrait,landscape}_ui.xml`:
  six rows only (`Fit`/`Zoom`/`Stretch`/`Connection`/`MTU`/`RTT`) — no stall
  row in either orientation, confirming it hides correctly.

### 3. Contrast

Sampled with Pillow from the `roam-1200ms` screenshots above (brightest pixel
in the row's title bounding box, same method PLE-383/456 used) — **not**
computed from theme values:

```
portrait stall text brightest: #E2E2E9   portrait bg sample: #121318
landscape stall text brightest: #E2E2E9  landscape bg sample: #121318
contrast: 14.39:1
```

Identical to the other five rows (PLE-456 already fixed all of them to the
same colour) — the new row inherits the same `ForegroundColorSpan`,
so no new colour decision was needed, only a re-measurement.

### 4. `layout-quality` fixture

`scripts/dev/fixtures/layout-quality/connection-info-popup.json` (workspace
repo) updated in `wt/ple-473-ws`, branch `ple-473`. Portrait gained the row
in place (`s22-portrait`/`s25-portrait`, plenty of vertical room). Landscape
did not fit it:

**Finding, reported not fixed (popup layout geometry is PLE-371's territory,
explicitly out of this ticket's scope):** the popup's `ListView` in landscape
was already at 1430.667 of 1440 px with six rows (PLE-383's own measurement)
— essentially zero margin. A seventh row does not fit; `capture.sh` had to
scroll the list to reach it, and the accessibility tree confirms the row
does not exist in the tree at all until scrolled (a `ListView`, not a
non-virtualized layout). `scrollable="true"` was already present before this
ticket, but nothing about the popup hints a player that a further row exists
below the fold. Modelling the overflowed state as ordinary fixture bounds
would itself fail the guard's own viewport-containment check, so
`s22-landscape`/`s25-landscape` are left exactly as PLE-383 captured them
(the real state at open, six rows, still correct), and two new capture
entries `s22-landscape-scrolled`/`s25-landscape-scrolled` cover the other
real, on-screen state — Zoom through the stall row, after a scroll. See
`scripts/dev/fixtures/layout-quality/connection-info-popup.json`'s
provenance field for the full derivation.

`python3 -c` validation against `layout-quality.py`'s `validate_fixture`
directly: `OK: no failures`. `scripts/dev/test-workspace.sh`:
`WORKSPACE GATE: PASS`.

### 5. Gate

`scripts/dev/gate.sh` from this worktree: `GATE: PASS` (host build, ctest,
chiaki-unit case count, `assembleDebug` arm64-v8a, `testDebugUnitTest`
including `StallInfoTest`).

## What was run on hardware vs merely compiled

- **Run on hardware:** two real streams to PS5-466 on the S22 Ultra (one
  under `roam-1200ms`, one clean), popup opened and read in both
  orientations for both, screenshots and `uiautomator` dumps captured,
  `pattern_check.txt` confirming the impairment's real shape, rig returned
  to `clean` (confirmed via `impairctl.py status --commit`) and the guest
  script confirmed `IN SYNC` afterward.
- **Merely compiled/checked:** `scripts/dev/gate.sh` (fork side),
  `scripts/dev/test-workspace.sh` (workspace side). No emulator smoke test
  adds anything beyond what the two hardware captures above already cover.
