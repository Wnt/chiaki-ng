# PLE-366 — a tail-rate arm beside the quality badge's median

**Defect.** The badge's classifier holds a 5-sample, 1 Hz window and decides on its median.
A median over five samples is by construction blind to anything narrower than half the
window, and `blip-200ms` — 200 ms of 200 ms delay on both directions every 20 s, the profile
that models a recurring hitch — is exactly one bad second in twenty. PLE-356 measured the
stalled second at **9.87 ms** of per-frame jitter against a **2.52 ms** clean ceiling and the
median over the window holding it at **1.63 ms**: GOOD, straight through a stall the player
feels.

**Fix.** A second arm over the same window counts the *rate* of samples at or above a
threshold. The worse of the two arms decides the level. This is a defect fix per AGENTS.md
rule 4 — on, unconditional, no preference and no build flag.

---

## 1. Where the numbers come from

Corpus for the derivation: `build/captures/ple356` and `build/captures/ple357`. These are the
only captures on this box whose `packet_jitter_ms` is PLE-356's per-frame delay-variation
estimator; `build/captures/ple343*` predate it and carry the old packet-gap EWMA, which is a
different quantity and is not comparable. The series is rebuilt exactly as the classifier
reads it — 1 Hz, `jitter = packet_jitter_ms`, `loss = max(takion loss %, congestion measured
loss %)`, `rtt = probe_rtt_ms`.

Reproduce with `derive.py` (no arguments):

```
$ python3 docs/verification/PLE-366/derive.py
```

`blip-200ms` fires **six** times across the two captures, 20 s apart, one second wide each:

| event | jitter ms | loss % | probe rtt ms |
|---|---|---|---|
| ple356 +13 s | 3.71 | 1.76 | 3.70 |
| ple356 +33 s | 4.85 | 12.02 | 6.73 |
| ple356 +53 s | 9.87 | 14.53 | 7.62 |
| ple357 +12 s | 89.77 | 16.94 | 7.46 |
| ple357 +33 s | 1.95 | 1.73 | 5.88 |
| ple357 +53 s | 2.03 | 1.94 | 6.46 |

Against **335 clean samples** (all six clean phases, first 10 s of each dropped while the
shaper step settles): jitter max **3.71 ms**, loss exactly **0.0000 %** in every one, probe
RTT max **12.07 ms**.

Three conclusions follow, and each of them decided part of the design:

1. **No RTT tail arm.** The probe is itself a ~1 Hz measurement and never once landed inside a
   200 ms blip: the blip phases' probe max, **11.74 ms**, is *below* the clean phases'
   **12.07 ms**. There is no measured separation to cut on, and a cut picked without one is
   the intuition this card exists to replace. `rttBlipShapedSpikesStillDoNotFlipTheLevel`
   pins that down — a lone high probe sample stays what PLE-343 called it, a retransmit.
2. **Jitter alone cannot carry the arm.** Only three of the six events reach 4.0 ms, and the
   worst clean sample in the corpus (3.71 ms) is *equal* to one of the blip events.
3. **Loss is what separates them.** All six events reach at least **1.73 %** while clean never
   leaves 0.

## 2. What was chosen

| constant | value | derivation |
|---|---|---|
| `TAIL_RATE_CUT` | 0.2 | one sample in the 5-sample window. Events are 20 s apart, so two in one window never happens — any cut above 1/5 detects nothing. |
| `TAIL_JITTER_MS` | 4.0 | 0 of 335 clean samples reach it (clean max 3.71 ms). |
| `TAIL_LOSS_PERCENT` | 1.0 | 0 of 335 clean samples reach it (clean max 0.0000 %); every blip event reaches 1.73 %. |

Verdict is a single tier, **CONSTRAINED**. A 1 %-duty stall is a hitch, not a bad path;
sustained badness stays the median arm's job and still reads POOR. `candidate =
maxOf(medianLevel, tailLevel)`, so the worse of the two decides and nothing the median
already caught changes.

Replayed over the corpus: **0 of 311 clean windows fire the arm; 6 of 6 blip events are
caught**, 22 of 100 blip windows firing.

Recovery needed no new code. While the outlier is still in the window the tail arm holds
`candidate` at CONSTRAINED, which lands on the existing `candidate.ordinal >= level.ordinal`
branch and resets `recoveryCount` — so the badge cannot return to GOOD until the outlier has
aged out of the 5-sample window and `RECOVERY_SAMPLES` have confirmed. One deliberate
asymmetry: a tail firing does **not** block the step down from POOR to CONSTRAINED. Otherwise
a stall recurring every 20 s would pin the badge at POOR indefinitely after one bad spell,
which is PLE-357's defect wearing a different hat
(`aRecurringStallDoesNotPinTheBadgeAtPoorForever`).

## 3. Device capture

`SM-S908B` (`192.168.40.101:5555`), PS5-466 at `192.168.1.164` over the LAN, one continuous
stream with `clean → blip-200ms → clean` stepped underneath it, 120 s per phase. The badge's
own verdict is logged at 1 Hz (`Quality badge: ...`, behind the existing
`stream_feedback_stats_log` debug preference) because the excursion this card is about lasts
7 s — shorter than any screenshot cadence.

- harness: `capture.sh` · analysis: `analyze.py`
- artifacts: `build/captures/ple366/` — `session_logcat.txt`, `phases.txt`, `analysis.txt`,
  `phone_ping.txt`, `0{1,2,3}_*_overlay.png`, `0{1,2,3}_*_net.txt`

```
| phase          |   n | badge GOOD | CONSTRAINED | POOR | worst jitter | worst loss | max tail rate |
|----------------|-----|------------|-------------|------|--------------|------------|---------------|
| 01_clean       | 116 | 116 (100%) |     0 ( 0%) |    0 |      2.47 ms |     0.00 % |          0.00 |
| 02_blip-200ms  | 114 |  78 ( 68%) |    36 (32%) |    0 |     18.23 ms |    16.15 % |          0.20 |
| 03_clean       | 117 | 117 (100%) |     0 ( 0%) |    0 |      2.35 ms |     0.00 % |          0.00 |
```

Six excursions in the blip phase, one per blip, each **7.0 s** long, every one returning to
GOOD on its own before the next blip:

```
  172.4s 02_blip-200ms  CONSTRAINED <- GOOD   sample jit= 7.88 loss=13.38 | tail j=0.20 l=0.20
  179.4s 02_blip-200ms  GOOD        <- CONSTRAINED
  192.5s 02_blip-200ms  CONSTRAINED <- GOOD   sample jit=18.23 loss= 8.64 | tail j=0.20 l=0.20
  199.5s 02_blip-200ms  GOOD        <- CONSTRAINED
  213.5s 02_blip-200ms  CONSTRAINED <- GOOD   sample jit= 1.81 loss= 2.52 | tail j=0.00 l=0.20
  220.5s 02_blip-200ms  GOOD        <- CONSTRAINED
  233.6s 02_blip-200ms  CONSTRAINED <- GOOD   sample jit= 3.69 loss= 2.80 | tail j=0.00 l=0.20
  240.6s 02_blip-200ms  GOOD        <- CONSTRAINED
  253.6s 02_blip-200ms  CONSTRAINED <- GOOD   sample jit= 6.30 loss= 0.00 | tail j=0.20 l=0.00
  260.6s 02_blip-200ms  GOOD        <- CONSTRAINED
  273.7s 02_blip-200ms  CONSTRAINED <- GOOD   sample jit=13.47 loss=16.15 | tail j=0.20 l=0.20
  280.7s 03_clean       GOOD        <- CONSTRAINED
```

Two things this run settles that the derivation could only argue:

- **The median arm is still blind.** Over the whole blip phase the badge's median jitter never
  exceeded **1.96 ms** — *below* the 2.47 ms clean ceiling of the phase before it — and the
  median loss and RTT never moved at all. On all 114 seconds the median arm alone would have
  said GOOD. Every one of the 36 CONSTRAINED seconds is the tail arm's doing.
- **Both arms are load-bearing.** Of the six events, one (`253.6 s`, jitter 6.30 ms, no loss)
  was caught by the jitter arm alone and two (`213.5 s`, `233.6 s`, jitter 1.81/3.69 ms) by
  the loss arm alone. Dropping either arm loses events.

## 4. The clean margin, as a number

The cut is **0.20**. Over the **233 clean seconds** on the device (both clean phases, first 10 s of each
dropped) the worst tail rate on either arm was **0.00**. The margin is the entire cut;
clean did not produce a single sample within reach of either threshold.

At sample level, on the same device run:

| | clean measured | threshold | headroom |
|---|---|---|---|
| jitter | max 2.35–2.47 ms | 4.0 ms | 1.53 ms (62 %) |
| loss | max 0.0000 % | 1.0 % | the whole threshold |

Over the wider derivation corpus (335 clean samples, two earlier sessions) the jitter headroom
is thinner — clean max 3.71 ms against the 4.0 ms threshold, 8 % — which is precisely why the
loss arm exists rather than a lower jitter threshold: dropping the jitter threshold to catch
more events would cross that clean sample, while the loss arm separates all six events from
every clean second with the full 1.0 pp to spare.

## 5. Not proven

- Nothing here says anything about how the chip *looks*; the overlay layout and the badge's
  rendering were out of scope and untouched.
- The `POOR` tier is unchanged and was not exercised by this capture: `blip-200ms` never
  produced a POOR verdict, by design.
