# PLE-411 — re-deriving PLE-366's tail thresholds from a clean corpus

**Not a fire.** PLE-366's shipped constants (`TAIL_RATE_CUT = 0.2`, `TAIL_JITTER_MS = 4.0`,
`TAIL_LOSS_PERCENT = 1.0`, `NetworkQuality.kt:55-57`) were device-verified independently by
PLE-366 itself and are unaffected by anything here. What was wrong was the *stated
derivation*: `derive.py`'s default corpus was `ple356 + ple357`, and `ple357` — captured
2026-09-18 01:28, after PLE-356 landed in source (2026-09-17 21:13) but with a stale APK
still installed — has `packet_jitter_ms` reading PLE-356's *predecessor* estimator (the old
packet-gap EWMA), not the per-frame delay-variation quantity the badge reads today. Half the
derivation corpus was the wrong metric, pooled in as if it were the same one.

## 1. What changed

- `derive.py` now calls `feedback_stats.capture_generation()` on every capture in the corpus
  before pooling anything, and **refuses to run** (non-zero exit, no numbers printed) if the
  set is not all the same generation — previously this was a stderr note beside otherwise
  unchanged pooled output (PLE-405), and a note that scrolls past is how `ple357` survived in
  the corpus. Demonstrated below (§4).
- The default corpus is now `ple356 + ple411` (this ticket's fresh capture) instead of
  `ple356 + ple357`.
- Two incidental corrections to `derive.py`'s own "chosen arm, evaluated" section, found while
  re-deriving: it evaluated `loss >= 2.0` and an `rtt >= 30.0` arm, neither of which is what
  ships — `NetworkQuality.kt` has `TAIL_LOSS_PERCENT = 1.0` and no RTT arm at all (the original
  PLE-366 README's own §1 explicitly decided against one: "No RTT tail arm"). The evaluated
  numbers below use `jit >= 4.0, loss >= 1.0` — what the classifier actually runs.

## 2. Both derivations, side by side

| | shipped corpus (`ple356+ple357`, generation-mixed) | clean corpus (`ple356+ple411`, both post-PLE-356) |
|---|---|---|
| clean samples | 335 | 399 |
| clean jitter max | 3.71 ms | 2.88 ms |
| clean loss max | 0.0000 % | 0.0000 % |
| blip samples | 108 | 167 |
| blip jitter max | **89.77 ms** (old packet-gap EWMA units, not comparable) | 16.64 ms |
| blip loss max | 16.94 % | 33.33 % |
| `TAIL_JITTER_MS=4.0` clean headroom | 0.29 ms (8 %) | 1.12 ms (28 %) |
| `TAIL_LOSS_PERCENT=1.0` clean headroom | the whole threshold | the whole threshold |
| chosen-arm clean tail rate (worst window) | 0.00 | 0.00 |
| chosen-arm clean margin vs cut 0.20 | 0.20 (the whole cut) | 0.20 (the whole cut) |
| chosen-arm blip windows firing | 22/100 (22.0 %) | 31/159 (19.5 %) |

Reproduce the clean corpus with `derive.py` (no arguments, now defaults to `ple356 + ple411`).
The shipped-corpus row above is historical — `derive.py` refuses to run on it directly (§4);
its numbers were recomputed once by calling `derive.py`'s own `load`/`windows`/`tail_rates`
functions against the two capture dirs, bypassing only the new refusal, to get a genuine
side-by-side rather than trusting the old README's transcription.

**Conclusion: the thresholds agree.** `TAIL_JITTER_MS=4.0` and `TAIL_LOSS_PERCENT=1.0` still
see zero clean samples over either threshold in 399 clean seconds across two capture sessions,
and the clean margin is still the entire 0.20 cut — no clean window ever produces a nonzero
tail rate on either arm. The clean-corpus jitter headroom is in fact *wider* than the shipped
derivation claimed (28% vs the stated 8%): that 8% number was itself an artifact of comparing
a 4.0 ms threshold against `ple357`'s contaminated 3.71 ms clean sample, which was never a
measurement of the estimator the badge reads. **No change to `NetworkQuality.kt` is
justified or made.**

## 3. Device capture on the current build

`SM-S908B` (`192.168.40.101:5555`), PS5-466 (`192.168.1.164`), same `capture.sh` harness as
PLE-366 (`clean -> blip-200ms -> clean`, 120 s/phase). Before capturing, the lesson this
ticket exists to teach: **the installed APK's freshness was confirmed, not assumed.** The
Samsung's install was rebuilt from this worktree's HEAD with `scripts/dev/gate.sh
--android-only` (`GATE: PASS`, arm64-v8a `app-debug.apk`) and installed with `adb install -r`
after `scripts/dev/app-state.sh backup`; `capture_generation()` on the resulting capture
confirms `post-ple356 (delay-variation estimator)`.

Artifacts: `build/captures/ple411/` (`session_logcat.txt`, `phases.txt`, `phone_ping.txt`,
`net.txt` files; `capture.sh`/`analyze.py` are PLE-366's, unmodified).

```
| phase          |   n | badge GOOD | CONSTRAINED | POOR | worst jitter | worst loss |
|----------------|-----|------------|-------------|------|--------------|------------|
| 01_clean       | 116 | 116 (100%) |     0 ( 0%) |    0 |      2.60 ms |     0.00 % |
| 02_blip-200ms  | 113 |  85 ( 75%) |    28 (25%) |    0 |     16.64 ms |    33.33 % |
| 03_clean       | 116 | 116 (100%) |     0 ( 0%) |    0 |      2.88 ms |     0.00 % |
```

Six impairment cycles fire in the 113 s blip phase (~20 s apart, as designed); every one is
caught, none in either clean phase produces a false CONSTRAINED:

```
  171.3s CONSTRAINED <- GOOD   sample jit= 4.04 loss= 0.00  | tail j=0.20 l=0.00
  178.3s GOOD        <- CONSTRAINED
  191.3s CONSTRAINED <- GOOD   sample jit= 7.25 loss=12.99  | tail j=0.20 l=0.20
  198.4s GOOD        <- CONSTRAINED
  211.4s CONSTRAINED <- GOOD   sample jit=16.64 loss=33.33  | tail j=0.20 l=0.20
  218.4s GOOD        <- CONSTRAINED
  232.4s CONSTRAINED <- GOOD   sample jit= 2.07 loss= 2.78  | tail j=0.00 l=0.20
  239.4s GOOD        <- CONSTRAINED
  272.5s CONSTRAINED <- GOOD   sample jit= 5.94 loss=14.96  | tail j=0.20 l=0.20   (bleeds 1s into 03_clean:
  279.5s GOOD        <- CONSTRAINED                          the outlier is still inside the 5-sample window,
                                                              same hysteresis PLE-366 documented, not a false
                                                              positive on a clean sample)
```

Five excursions land inside the labelled `02_blip-200ms` phase and the sixth crosses the
phase boundary by 1 s while the window still holds the last blip sample — six impairment
cycles, six catches, zero clean false positives, matching PLE-366's own device-verified
6-of-6 with this run's own numbers rather than reusing PLE-366's capture.

**A first attempt at this capture (discarded, not committed) showed a much weaker signal** —
only one excursion in the whole blip phase, jitter never exceeding 4.5 ms and loss staying at
0.00% throughout. The network impairment (`scripts/net/impairctl.py`) is a shared host
resource reached over `ssh lab -- pct exec 240` (PLE-349), not scoped to this ticket's device
reservation, and its background `blip_loop` cycling died out after two iterations for reasons
not root-caused here — worth naming because it produces a capture that "succeeds" (correct
phases, no error exit) while silently measuring almost nothing. The capture was re-run and the
second attempt (used above) shows the expected six clean cycles. **Anyone running an
impairment-based capture should sanity-check the raw jitter/loss samples during the blip phase
before trusting the result**, not just that `capture.sh` exited zero.

## 4. `derive.py` refusing a mixed corpus

```
$ REPO=/home/wnt/gta6 python3 docs/verification/PLE-366/derive.py \
    /home/wnt/gta6/build/captures/ple356 /home/wnt/gta6/build/captures/ple357
derive.py: refusing a generation-mixed corpus -- these captures were not all recorded on the same packet_jitter_ms estimator:
  /home/wnt/gta6/build/captures/ple356: post-ple356 (delay-variation estimator)
  /home/wnt/gta6/build/captures/ple357: pre-ple356 (packet-gap EWMA)
Drop the capture(s) on the wrong generation, or replace them with a fresh capture on the current build (confirm the installed APK is current first).
$ echo $?
1
```

## 5. Not proven

- No new device capture was run against a *changed* `NetworkQuality.kt`, because none was
  needed — the recommendation is no change, evidenced by the run in §3 against the unmodified
  shipped constants.
- The impairment infrastructure flake in §3 was not root-caused (it is a shared host resource
  outside this ticket's scope — `scripts/net/impair.sh`'s `blip_loop`, driven over
  `ssh lab -- pct exec 240`). Filed as a follow-up below.
