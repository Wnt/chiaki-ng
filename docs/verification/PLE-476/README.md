# PLE-476 — the empty band, sampled; `roam-1200ms` resolved on the current build

**Answer up front.** `roam-1200ms` reaches **POOR**, confirming PLE-464's prediction, not
refuting it — five outages of five, each crossed within the first fully silent second, on
`build/captures/ple476` (installed APK built from `3e754bd0b2ff`, PLE-464's own landing
commit). The band between the two cuts is no longer empty: `roam-350ms` (below `STALL_MS`)
never trips the stall arm, and `roam-700ms` (between the cuts) trips CONSTRAINED on every
one of its five outages and POOR on none. Both cuts sit exactly where their names say they
do, on data now measured on both sides of each. **No cut is moved by this ticket.**

---

## 1. The capture

One continuous LAN stream, `clean -> roam-350ms -> roam-700ms -> roam-1200ms -> clean`,
120 s per phase — PLE-464's harness with the profile list widened to cover the empty band
plus a fresh `roam-1200ms`. `SM-S908B` (`192.168.40.101:5555`), PS5-466 (`192.168.1.164`),
APK freshness confirmed by PLE-410's guard (built from `3e754bd0b2ff`, which has PLE-464's
`15fbdfd011f6` as an ancestor), `install -r` after `app-state.sh backup`.

`roam-350ms` and `roam-700ms` did not exist before this ticket. They are PLE-464's
`roam-1200ms`/`roam-3000ms` shape (a total outage — 100% loss both directions — on a 20 s
cycle) at two new widths, landed in the workspace repo
(`wt/ple-476-ws`, branch `ple-476`): `scripts/net/impair_profiles.py` gains the two
`Profile` entries and `scripts/net/impair.sh`'s dynamic table (`dynamic_on_netem` /
`dynamic_off_netem` / `dynamic_on_seconds` / `dynamic_cycle_seconds`, plus the `profile_impl`
netem case and the usage string) learns their names — the two tables that
`test_every_dynamic_profile_matches_the_python_table` holds against each other. The guest
script installed on CT 240 was refreshed from `wt/ple-476-ws` before this capture
(`impairctl.py install --commit`, hash `2dcef0078a59b2756a8dd4a642642206`) and must be
reinstalled from the tracked `scripts/net/impair.sh` once this ticket lands, or every other
worker's `impairctl.py status` reads DRIFT.

Reproduce with:

```bash
WS=/home/wnt/gta6/wt/ple-476-ws OUT_DIR=build/captures/<yours> \
  bash docs/verification/PLE-476/capture.sh
```

(`WS` only needs to point at the ws worktree until PLE-476's workspace commit lands; after
that the default `/home/wnt/gta6` carries the two new profiles.)

## 2. Per-width table

The stall input (`max(window_max_gap_ms, takion_silence_ms)`), read the same way
PLE-464's `analyze.py` reads it — first 10 s of each phase excluded (netem qdisc-switch
settling):

| width | n samples | worst gap | p95 gap | median gap | samples ≥ 500 ms (CONSTRAINED) | samples ≥ 1000 ms (POOR) | badge tier reached | time to reach it |
|---|---|---|---|---|---|---|---|---|
| `clean` (01, bookend) | 116 | 28 ms | 22 | 19 | 0 | 0 | GOOD (100%) | n/a |
| `roam-350ms` | 113 | **397 ms** | 24 | 19 | **0** | 0 | CONSTRAINED (6% of phase) — via the **tail-loss arm**, not stall | 1.0 s after the pulse's loss sample (jitter/loss arm, PLE-411) |
| `roam-700ms` | 113 | **744 ms** | 23 | 18 | **5 of 5 outages** | 0 | **CONSTRAINED** (33% of phase), never POOR | within the same 1 Hz sample the outage closes in (e.g. gap=741 at 295.5s → CONSTRAINED already showing) |
| `roam-1200ms` | 114 | **1245 ms** | 33 | 18 | 5 of 5 | **5 of 5** | **POOR** (32% of phase) | **within the first fully silent second** — 417.7s stall=126 (still GOOD), 418.7s stall=1128 → POOR |
| `clean` (05, bookend) | 115 | 39 ms | 22 | 18 | 0 | 0 | GOOD (100%) | n/a |

Badge-level summary (same phases, all 1 Hz samples):

| phase | n | GOOD | CONSTRAINED | POOR |
|---|---|---|---|---|
| 01_clean | 116 | 116 (100%) | 0 | 0 |
| 02_roam-350ms | 113 | 106 (94%) | 7 (6%) | 0 |
| 03_roam-700ms | 113 | 76 (67%) | 37 (33%) | 0 |
| 04_roam-1200ms | 114 | 59 (52%) | 18 (16%) | 37 (32%) |
| 05_clean | 115 | 115 (100%) | 0 | 0 |

`roam-350ms`'s 7 CONSTRAINED samples are **not the stall arm**: its worst gap (397 ms) never
reaches `STALL_MS` (500 ms). They come from one loss spike (34.55% measured loss on one
1 Hz sample at 232.4s, `gap=384` — still under cut) tripping PLE-411's tail-rate arm, the
same mechanism `blip-200ms` used in PLE-464. The stall arm contributes **0** samples to that
phase, confirmed by "samples ≥ 500 ms" being 0.

## 3. `roam-1200ms`, resolved

PLE-464 predicted POOR (1.2 s is 2.4× `STALL_MS`, 1.2× `POOR_STALL_MS`) but never re-captured
it with `takion_silence_ms`/`window_max_gap_ms` live — the only `roam-1200ms` data on this
box predated the field and read the *instantaneous* silence poll (264–1130 ms per outage),
not the windowed maximum. This capture confirms the prediction outright: 5 outages of 5,
every one crossing into POOR, worst gap 1245 ms. Series around the first outage:

```
410.7s 04_roam-1200ms GOOD              stall=    20
...
417.7s 04_roam-1200ms GOOD              stall=   126   <- outage begins, still GOOD
418.7s 04_roam-1200ms POOR    LAN       stall=  1128   <- POOR, same second the outage closes
419.7s 04_roam-1200ms POOR    CONSOLE   gap=1231 stall=1231
420.7s 04_roam-1200ms POOR    LAN       stall=  1231   <- holds through RECOVERY_SAMPLES
426.7s 04_roam-1200ms CONSTRAINED       <- steps down, never POOR -> GOOD (PLE-357)
429.7s 04_roam-1200ms GOOD
```

Same shape PLE-464 found for `roam-3000ms`: POOR inside the first fully silent second, then
POOR → CONSTRAINED → GOOD on the way down, never POOR → GOOD directly. This is not a new
result for the arm's mechanics — it is the missing confirmation that the arm reaches its
*second* tier on a real outage at this width, not only on `roam-3000ms`'s.

## 4. The cuts, on measured data on both sides

| cut | value | worst sample below it | margin below | worst sample it should catch | margin above |
|---|---|---|---|---|---|
| `STALL_MS` (CONSTRAINED) | 500 ms | 397 ms (`roam-350ms`, entire phase) | **1.3×** | 744 ms (`roam-700ms`, reliably crosses on all 5 outages) | — |
| `POOR_STALL_MS` | 1000 ms | 744 ms (`roam-700ms`, never crosses) | **1.3×** | 1245 ms (`roam-1200ms`, reliably crosses on all 5 outages) | — |

The margins are tighter than PLE-464's (which were 2.4×/4.7× against an untested 3055 ms),
because this capture puts a real sample close on both sides of each cut rather than one far
above it. **1.3× is not a reason to move a cut** — `roam-350ms` never once approaches
`STALL_MS` (worst of 113 samples is 397 ms, a full 103 ms short) and `roam-700ms` never once
approaches `POOR_STALL_MS` (worst of 113 samples is 744 ms, 256 ms short) — but it is the
honest number, not the wider one from measuring against an outage 2–4× the cut's own size.
Both cuts land where PLE-464 put them, confirmed rather than assumed: no change to
`NetworkQuality.kt` is justified by this data.

## 5. No new false positives on `clean`

Both `clean` phases (bookending the run, same as PLE-464's) read **GOOD 100%**, worst gap
28/39 ms, zero samples over either cut — the standing bar, unmoved.

## 6. PLE-418's pattern guard

```
OK  01_clean: 0 excursions after the 5s settle window -- OK
OK  02_roam-350ms: 5 excursions in 123.5s from link silence (expected 6 +/-1), widths [1, 1, 1, 1, 2] (expected 1-3 for a 0.35s pulse) -- OK
OK  03_roam-700ms: 6 excursions in 123.5s from link silence (expected 6 +/-1), widths [1, 2, 1, 1, 2, 2] (expected 1-3 for a 0.7s pulse) -- OK
OK  04_roam-1200ms: 5 excursions in 123.5s from link silence (expected 6 +/-1), widths [3, 2, 2, 2, 3] (expected 2-4 for a 1.2s pulse) -- OK
OK  05_clean: 0 excursions after the 5s settle window -- OK
PASS
```

The guard needed no change: it already reads the profile table's `on_seconds` for its width
band (PLE-464 §7), so `roam-350ms`/`roam-700ms` teaching that table their shape was
sufficient for the guard to check them correctly on the first run.

## 7. The lockless reset race — a host test, not a device measurement

PLE-464 argued (not measured) that `take()`'s unlocked reset of
`window_max_receive_gap_ms` can clobber a gap that `takion_note_receive()` folds in between
`take()`'s read and its store — both are plain, unsynchronized field operations on a
`uint32_t` no mutex protects. Reproducing that with **real threads** would be
timing-dependent: the race window is a handful of instructions wide, so a test built on
actual concurrency would pass on almost every run and only rarely catch the interleaving —
exactly the flaky, can't-reliably-fail shape this ticket was told to avoid.

What is deterministic is the interleaving itself: both operations are field reads/writes
that the existing test file already manipulates directly on a stack `ChiakiTakion`
(`test/takion.c`'s `test_takion_window_max_receive_gap`), so calling them in the exact order
PLE-464's argument describes reproduces its outcome on one thread, no socket or scheduler
involved. Added `test_takion_window_max_receive_gap_reset_race`
(`test/takion.c`, registered as `/chiaki/takion/window_max_receive_gap_reset_race` in
`test/chiaki-unit-cases.txt`):

1. A receive earlier in the window folds in a 50 ms gap.
2. `take()` reads that value (50 ms — what this window's stats event will report).
3. Before `take()` stores its reset, a receive closes a 4000 ms gap and folds it into the
   *pre-reset* value `take()` already read past.
4. `take()`'s reset lands last, storing 0 over the fresh 4000 ms value.
5. The next `take()` call reads back **0**, not 4000 — the drop PLE-464 argued.
6. Asserts the race is **benign, not corrupting**: it never touches `last_receive_ms`, so a
   still-open outage is not lost with it — the next receive folds against the true previous
   timestamp and reports the full elapsed gap regardless of what the window accumulator did.

This turns "argued" into "demonstrated, with the exact before/after values" without
introducing a test that can never fail: a future change to the fold's read order, or to
what gets zeroed on reset, would change these numbers and fail the assertions above.

## 8. Not proven

- **"Unplayable" is still argued from frame counters and the silence field, not from a
  recorded video or a human** — unchanged from PLE-464 §8, out of this ticket's scope.
- **A real multi-threaded reproduction of the reset race was not attempted** — see §7 for
  why it would be flaky rather than more rigorous; the host test models the interleaving
  directly instead.
- **Only five widths exist on this box now** (`clean`, `roam-350ms`, `roam-700ms`,
  `roam-1200ms`, `roam-3000ms`). The band is sampled at two points, not densely; a cut
  failure between, say, 700 ms and 1000 ms would still not be visible.
- **Emulator smoke test not run** — this ticket touches host tests, a Python profile table
  and a shell guest script; nothing in the Android app itself changed, so there is no new
  runtime behavior to smoke-test. `gate.sh`'s `assembleDebug` step still built and installed
  cleanly (§9).

## 9. Rig state and gate

- Rig left at `clean` (capture.sh's own `cleanup` trap runs `impair clean --commit`
  regardless of exit path); `net_final_clean.txt` in the capture directory is that command's
  own output.
- `scripts/dev/gate.sh` from `wt/ple-476`: **PASS** (see final report).
- `scripts/dev/test-workspace.sh` from `wt/ple-476-ws`: **PASS**, except
  `scripts/dev/device-bin/test-adb-serial.sh`, which fails identically on the unmodified
  `/home/wnt/gta6` checkout — pre-existing, not touched by this ticket.
