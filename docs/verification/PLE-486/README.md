# PLE-486 — should the classifier read the corrected `video_frames_lost` next to the stall arm?

**Answer up front. Not yet, and not as a blanket "read it next to the stall arm".** Re-read
against the framing the coordinator set (`does it add anything the stall arm does not already
catch, on the captures we have`): for outages at or above `STALL_MS` (500 ms) the two signals
are fully redundant and the frame counter is strictly later, so wiring it in there would only
ever re-confirm a verdict the stall arm already reached. Below `STALL_MS` — the `roam-350ms`
band the stall arm cannot reach by construction — the frame counter **does** carry real,
otherwise-invisible signal: 4 of 5 outages in the existing capture moved `video_frames_lost`
by 3–16 frames each while every other classifier input read exactly clean. That is a genuine
blind spot, but closing it needs a calibrated rate-based cut this ticket has no capture budget
to derive honestly, so the recommendation is a scoped follow-up, not a change made here.

No code changed. This is a decision made from existing data, per the coordinator's note that
PLE-476 sampled four blackout widths and "that can be answered from existing data rather than
new captures."

---

## 1. The question, and the data used

Three captures already carry both signals at 1 Hz, no new capture needed:

- `build/captures/ple474` — `clean -> roam-3000ms -> clean`, PLE-474's own capture confirming
  the corrected `video_frames_lost`.
- `build/captures/ple476` — `clean -> roam-350ms -> roam-700ms -> roam-1200ms -> clean`,
  PLE-476's stall-arm cut confirmation, on a build with PLE-474's corrected counter already
  landed (`3e754bd0b2ff` has `972309ab`, PLE-474's landing commit, as an ancestor).

Both logcats carry the same `Feedback stats:` line with `lost` (cumulative
`video_frames_lost`) and `takion_silence_ms … window_max_gap_ms` (the stall arm's input) side
by side, so the two signals can be read off the same samples with no reprocessing.

## 2. At or above `STALL_MS`: fully redundant, and always later

`build/captures/ple474`, `roam-3000ms`, first outage (local log time, `stall` =
`max(window_max_gap_ms, takion_silence_ms)`, the stall arm's own input):

```
06:03:58.746 recv=0  lost=0    stall=1040   <- already >= POOR_STALL_MS (1000ms): badge is POOR now
06:03:59.748 recv=0  lost=0    stall=2042
06:04:00.750 recv=0  lost=182  stall=3038   <- video_frames_lost finally moves, 2s after POOR fired
06:04:01.754 recv=52 lost=190  stall=16     <- stream has resumed
```

Every one of the five outages in that capture repeats the shape: the stall arm reaches POOR
on the **first** fully-silent second, while `lost` stays flat until the blackout ends and the
frame-index gap can finally be measured — 2 of the outage's 3 seconds later. `roam-1200ms`
(`build/captures/ple476`, PLE-476 §3) is the same shape at a shorter width: stall reaches POOR
at 418.7s, one second after the outage opens; the frame count cannot move until the outage
closes around a second after that.

So for every width the stall arm already reaches ([`roam-700ms`](../PLE-476/README.md) and up:
CONSTRAINED; [`roam-1200ms`](../PLE-476/README.md) and up: POOR), the frame counter would add
a second, later confirmation of a verdict already reached — never an earlier or different one.
Reading it here would be pure double-counting, the exact failure mode the coordinator's
framing named.

## 3. Below `STALL_MS`: not redundant — the counter sees outages the stall arm cannot

`roam-350ms`'s worst measured gap is 397 ms (PLE-476 §2), structurally short of
`STALL_MS = 500 ms` — the stall arm cannot reach this width by construction, not by a tuning
gap. Reading the same phase's `lost`/`stall`/congestion-loss series side by side, event by
event (`build/captures/ple476`, phase `02_roam-350ms`, five outages, matching PLE-476's own
count):

| local time | `lost` Δ | `stall` ms | congestion measured loss | any existing arm fires? |
|---|---|---|---|---|
| 05:01:50 | +3 | 393 | 0.0 % | **no** |
| 05:02:10 | +3 | 397 | 0.0 % | **no** |
| 05:02:31 | +14 | 397 | 0.0 % | **no** |
| 05:02:51 | +3 | 384 | 34.55 % | yes — tail-loss arm (PLE-476's "one loss spike") |
| 05:03:12 | +16 | 391 | 0.0 % | **no** |

**4 of 5 real outages — 36 of the phase's 39 lost frames — produced zero signal in any input
the classifier currently reads.** `stall` never approaches `STALL_MS`; `congestionMeasuredLoss`
is exactly 0 on four of the five; `takionPartialFrameUnitsMissing` is structurally blind to a
fully-lost frame for the same reason PLE-464 found for the stall arm's own gap in the first
place (PLE-464 §1, second layer — `alloc_frame()` never runs, so the units are never even
*expected*). The one outage that did trip something (05:02:51) did so by coincidence — a
console-reported congestion spike that happened to land in the same second, not because
anything reads frame loss.

Both `clean` phases in the same capture (`01_clean`, `05_clean`, 116/115 samples) show `lost`
flat at 0 throughout — the same clean margin the stall arm and tail arms already hold.

This is not a small effect and not a coincidence of one capture: it is the same shape PLE-464
found for the stall arm before it existed — a real, repeated event (a total outage, 5 of 5
times) invisible to every currently-wired input — just at a width one order of magnitude
shorter than PLE-464's `roam-3000ms`.

## 4. Why "just read it" is not the right shape for the fix

`video_frames_lost` is a session-cumulative counter, not a rate — StreamSummary.droppedFrames
already had to learn to read it as *growth since the last event* rather than the running total
(PLE-474 §3, the `StreamSummaryTest.videoFramesLostCountsOnlyItsGrowth` fix) to avoid
re-counting one blackout forever. A quality arm reading it would need the same per-window-delta
treatment PLE-366's tail arm already does for jitter/loss, at minimum:

- **A rate, not the total** — frames-lost-per-window, matching the tail arm's own shape (one
  bad sample in a five-sample window counts, an old one aging out stops counting).
- **A cut derived from captures, honestly** — §3's numbers happen to fall out of two captures
  that were never designed to calibrate this specific arm. `roam-350ms` is the only width
  sampled below `STALL_MS`; there is nothing measured between 0 and 350 ms, or between 397 ms
  and the existing cuts, to place a threshold against — the same "empty band" problem PLE-476
  was created to close for the stall arm itself.
- **A defined interaction with the existing tail-loss arm**, since §3's one exception
  (05:02:51) shows the two can co-occur on the same second; an added arm needs to not
  double-fire alongside it in a way that changes recovery timing (PLE-357's oscillation is the
  standing bar here).

None of that is derivable from the two captures this ticket had budget to reread. Wiring in a
raw read of the cumulative total — the literal ask in the follow-up's wording — would be worse
than nothing: it would fire once, on an arbitrary sample right after any blackout however long
ago it started, with no cut and no clean-phase margin ever measured for it.

## 5. Decision

**Do not wire `video_frames_lost` into `NetworkQuality.kt` in this ticket.** For the
overlapping regime (≥ `STALL_MS`) it adds nothing the stall arm has not already reported,
earlier. For the non-overlapping regime (< `STALL_MS`) it does carry real signal that nothing
else currently catches, but turning that into a classifier arm needs a rate-based cut derived
from dedicated captures — sampling short widths (100–400 ms) the way PLE-476 sampled the
500–1200 ms band — which is its own scoped ticket, not a decision this one can respond to with
existing data alone.

`NetworkQuality.kt` and its arms are unmodified; this ticket adds no build flag because it
changes no runtime behaviour (AGENTS.md rule 4 only applies to a behaviour change, and there is
none here).

## 6. Not proven

- Whether a calibrated sub-`STALL_MS` frame-loss-rate arm would change the badge's behaviour on
  `blip-200ms`, `4g`, or `wifi-slow` — not evaluated, since no arm is proposed with concrete
  cuts here.
- Whether outages shorter than 350 ms (not yet captured anywhere on this box) show the same
  blind spot at a smaller magnitude — plausible from the mechanism in §3, not measured.
- Device/emulator: none run. This ticket changed no code, so there is nothing to smoke-test or
  capture; `scripts/dev/gate.sh` was run only to confirm the docs-only change leaves the build
  green.

## Follow-ups

- Derive a sub-`STALL_MS` frame-loss-rate tail arm the way PLE-366 derived the jitter/loss tail
  arm and PLE-476 derived the stall cuts: capture `roam-100ms` through `roam-400ms` (new
  profiles, `roam-350ms` already exists), read `video_frames_lost` growth per 1 Hz window, and
  place a cut with a stated clean margin — this ticket's §3 is the existence proof that one is
  worth deriving, not the derivation itself.
