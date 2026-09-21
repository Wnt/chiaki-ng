# PLE-404 — a short, severe stall, and why no second tail tier follows from it

**Answer up front: the evidence does not justify a second tier, and the reason is not
that the stall is too mild.** It is that the three numbers the classifier reads carry
almost no signal for a short total outage. A **3 second complete blackout**, repeated
every 20 s, drove `video received` from 60 frames/s to **0** five times out of five and
lost 10–18 frames each time — and the badge said **GOOD for 94 % of that phase**, with a
worst jitter sample of **2.77 ms** against a clean-LAN ceiling of **2.88 ms**. There is
nothing to cut on. A tier derived from these captures would have to fire below the clean
floor.

The hole PLE-404 describes is real — the badge under-reports the worst-feeling faults —
but it is one layer lower than the tail arm's thresholds, and fixing it there would be
inventing a cut, which is exactly what [PLE-366](../PLE-366/README.md) refused to do.

---

## 1. The profile, and why its shape is realistic

`scripts/net/impair_profiles.py` gains two dynamic profiles (workspace repo, branch
`ple-404`):

| profile | idle | pulse | width | cadence |
|---|---|---|---|---|
| `roam-1200ms` | `delay 0ms loss 0%` | `delay 0ms loss 100%` | 1.2 s | 20 s |
| `roam-3000ms` | `delay 0ms loss 0%` | `delay 0ms loss 100%` | 3.0 s | 20 s |

**What the network does.** `blip-200ms` adds *delay*: the packets still arrive, just late.
The fault class this ticket is about does not. An 802.11 roam between APs, or a DFS radar
event forcing the AP off-channel, deauthenticates the client and the frames are simply
gone until it has scanned, reassociated and redone the handshake — published times for a
full (non-802.11r) reassociation run from the high hundreds of milliseconds to a couple of
seconds, and a DFS channel move sits at the long end because the client has to find the AP
again on a new channel. So: dropped, not delayed, and measured in seconds.

**Why 1.2 s.** Not a round number — a derived one. The classifier samples at 1 Hz and its
median arm needs 3 of 5 samples to move, so it is blind to anything under ~3 s however
severe. 1.2 s is the shortest outage that is *guaranteed* to touch two 1 Hz samples
wherever it lands relative to the tick, while staying well under the three the median arm
needs. That places it exactly in the gap the ticket hypothesises.

**Why a 3.0 s sibling.** A null result on `roam-1200ms` alone has two readings: "the tail
arm needs a second tier" or "the metrics carry no signal at all". `roam-3000ms` separates
them — same real fault at the long end of its range, and wide enough for the *median* arm
to move on its own if the metrics register it. Both were captured in one session.

**Why the 20 s cadence is unchanged.** So these and `blip-200ms` are read the same way,
and PLE-418's guard counts the same number of excursions per phase for all three.

Mechanically, the guest script's single hard-coded `blip_loop` became a table: what netem
is in force during a pulse, what between them, how wide, how often. The idle state now
names every field the pulse names (`loss 0%` explicitly) — the loop toggles with
`tc qdisc replace`, which only touches the fields it names, so an idle state of plain
`delay 0ms` would have latched 100 % loss on permanently after the first pulse (PLE-388).
`test_impair.py` runs that case against a real qdisc in a network namespace, and holds the
shell table against the Python one through a new `__dynamic` verb.

## 2. Captures

`SM-S908B` (`192.168.40.101:5555`), PS5-466 (`192.168.1.164`), one continuous LAN stream
with the profile stepped underneath it, 120 s per phase, badge verdict logged at 1 Hz.
Harness: `capture.sh` (this directory). Analysis: PLE-366's `analyze.py`, unmodified.
APK freshness confirmed by PLE-410's guard before each run (`built from commit
b9beec838458`); `install -r` after `app-state.sh backup`.

- `build/captures/ple404` — `clean → roam-1200ms → clean`
- `build/captures/ple404b` — `clean → roam-1200ms → roam-3000ms → clean`

```
ple404
| phase          |   n | badge GOOD | CONSTRAINED | POOR | worst jitter | worst loss | max tail rate |
|----------------|-----|------------|-------------|------|--------------|------------|---------------|
| 01_clean       | 115 | 115 (100%) |     0 ( 0%) |    0 |      2.37 ms |     0.00 % |          0.00 |
| 02_roam-1200ms | 113 | 106 ( 94%) |     7 ( 6%) |    0 |      3.12 ms |    76.25 % |          0.20 |
| 03_clean       | 116 | 116 (100%) |     0 ( 0%) |    0 |      2.60 ms |     0.00 % |          0.00 |

ple404b
| phase          |   n | badge GOOD | CONSTRAINED | POOR | worst jitter | worst loss | max tail rate |
|----------------|-----|------------|-------------|------|--------------|------------|---------------|
| 01_clean       | 115 | 115 (100%) |     0 ( 0%) |    0 |      2.40 ms |     0.00 % |          0.00 |
| 02_roam-1200ms | 114 | 100 ( 88%) |    14 (12%) |    0 |      2.97 ms |    84.25 % |          0.20 |
| 03_roam-3000ms | 115 | 108 ( 94%) |     7 ( 6%) |    0 |      2.77 ms |     5.37 % |          0.20 |
| 04_clean       | 115 | 115 (100%) |     0 ( 0%) |    0 |      2.50 ms |     0.00 % |          0.00 |
```

**POOR is never reached — and neither is the tail cut's second sample.** The worst tail
rate in either roam phase is **0.20**, one sample in five, identical to `blip-200ms`'s.

### The outages did happen — this is not a dead loop

The phone's own `ping` to the console, recorded alongside the stream over the same
impaired leg, lost contact five times per phase, 21 s apart, losing **1–2 replies** per
`roam-1200ms` pulse and exactly **3** per `roam-3000ms` pulse — the wire did precisely
what the profile says. Per outage, against the metrics measured in the same second
(`ple404b`):

```
02_roam-1200ms   pings lost [1, 2, 1, 1, 1]
   at |  min recv/s | lost frames | max jit ms | takion loss % | cong loss % | badge
 21.3 |           0 |          10 |       1.98 |        0.0000 |        0.00 | GOOD
 42.3 |          11 |          13 |       2.42 |        0.0000 |        0.00 | GOOD
 64.4 |           3 |          19 |       2.97 |        3.1468 |        0.00 | CONSTRAINED
 85.5 |           3 |           5 |       2.59 |        2.9412 |       84.25 | CONSTRAINED
106.5 |           0 |           9 |       2.15 |        0.0000 |        0.00 | GOOD

03_roam-3000ms   pings lost [3, 3, 3, 3, 3]
   at |  min recv/s | lost frames | max jit ms | takion loss % | cong loss % | badge
 21.1 |           0 |          13 |       2.01 |        0.0000 |        0.00 | GOOD
 44.2 |           0 |          10 |       1.91 |        5.3691 |        0.00 | CONSTRAINED
 67.3 |           0 |          14 |       2.23 |        0.0000 |        0.00 | GOOD
 90.4 |           0 |          18 |       2.16 |        0.0000 |        0.00 | GOOD
113.5 |           0 |          16 |       2.77 |        0.0000 |        0.00 | GOOD
```

Clean phases, for scale: `video received` 59–60/s every second, **0** frames lost, takion
loss exactly 0.

## 3. Why no second tier follows

Three facts, each from the table above:

1. **The jitter input does not move.** Ten total outages across two sessions; the worst
   jitter sample in any of them is **3.12 ms** (`ple404`) and **2.97 ms** (`ple404b`).
   Pooled clean samples on the same estimator (ple356, ple366, ple411, ple404, ple404b —
   1093 samples, excluding PLE-366's documented blip-bleed phase) reach **2.88 ms**. The
   distributions overlap: the worst second of a 3 s blackout (2.77 ms) is *quieter* than
   the worst clean second on this LAN. A tier on jitter would need a cut inside the clean
   distribution, which is a tier that fires on a clean LAN — worse than no tier
   ([PLE-366](../PLE-366/README.md) §4's standard, applied and failed).
2. **The loss input is absent, not small.** `takion_raw expected_per_s` equals
   `received_per_s` in every line of both captures, including seconds where zero video
   frames arrived — `expected = received + takion_packets_lost`
   (`android/app/src/main/cpp/chiaki-jni.c:380`), so the classifier's `packetLoss` term is
   structurally 0 here whatever the network does. What loss the badge did see came from
   the console's own `congestion_loss measured=`, which fired on **3 of 10** outages, at
   3 %, 5 % and 76–84 %. A three-in-ten detector with a 25× spread in magnitude is not a
   basis for a threshold.
3. **The median arm cannot rescue it either.** `roam-3000ms` is wide enough for a 3-of-5
   median — it still never moved, for the same reason: the samples it would need to move
   on are clean-looking.

So the question "what should the second tier's threshold be?" has no answer derivable from
these captures, and PLE-366's own rule says not to invent one. **`NetworkQuality.kt` is
unchanged.**

## 4. The clean margin, stated for the tier that was not added

For completeness, the margin the ticket asks for — measured, and negative:

| candidate | value needed to catch the roam outages | worst clean sample | margin |
|---|---|---|---|
| tail jitter threshold | ≤ 2.77 ms (to catch 5/5 of `roam-3000ms`) | 2.88 ms | **−0.11 ms** |
| tail jitter threshold | ≤ 2.97 ms (to catch 2/5 of `roam-1200ms`) | 2.88 ms | +0.09 ms (3 %) |
| tail loss threshold | any — fires on 3 of 10 outages at best | 0.0000 % | n/a, coverage 30 % |
| tail rate cut ≥ 0.4 (2 of 5) | never reached: worst roam tail rate is 0.20 | — | not reachable |

The shipped cuts (`TAIL_JITTER_MS = 4.0`, `TAIL_LOSS_PERCENT = 1.0`, `TAIL_RATE_CUT = 0.2`)
are untouched and unaffected; nothing here re-derives what PLE-411 confirmed.

## 5. What *would* work, and why it is not this ticket

The signal is in the same log line. `video received` goes 60 → 0–11 per second in every
one of the ten outages and is 59–60 in every one of 461 clean seconds; `video lost`
increments by 5–19 per outage and by 0 across whole clean phases. That separates perfectly,
with no overlap in either direction, on data already collected once per second.

But a frame-delivery arm is a **new arm on a new input**, not a second tier of the existing
tail arm — and the two-arm structure is explicitly not this ticket's (it is PLE-366's).
Filed as a follow-up rather than smuggled in here.

## 6. The pattern guard (PLE-418), extended

Two changes, both forced by this capture:

- **A phase's profile now comes from the table**, not from the string "blip" in its tag.
  Before, a dynamic profile whose name did not contain "blip" fell through to "not
  checked" — the exemption PLE-418 exists to refuse.
- **Excursion width is checked**, from the profile's own `on_seconds`: counting alone
  cannot tell a 1.2 s outage from a 200 ms hitch, since both fire once every 20 s.
- **The phone's ping is consulted as an independent witness** when the metric excursions
  are missing. This capture is exactly why: the first `roam-1200ms` run failed the guard
  with "2 of 6 excursions — the cycling loop was not running", and the loop was running
  fine; the metrics were blind. Cadence on the wire but not in the metrics now passes with
  that stated in the report; missing from both still fails as a dead loop.

`docs/verification/lib/test_verify_blip_pattern.py` covers all three on synthetic captures
(8 cases, no device). Verdicts on every existing capture on this box are unchanged
(ple356 PASS, ple411 PASS, ple366/ple423-blip/ple423-impair FAIL exactly as before).

## 7. Not proven

- Nothing here says anything about what the *player* sees. "Unplayable" is argued from the
  frame counters (60 → 0 for three seconds), not from a recorded video or a human.
- The reason `takion_packets_lost` stays 0 through a total outage was not root-caused —
  it is stated as measured, over two sessions and 523 + 397 stats lines. Whether the
  packets are eventually retransmitted, or the counter only ever counts something
  narrower, is a question for the follow-up that would use it.
- No emulator smoke test: this ticket changes no app code (`NetworkQuality.kt` untouched),
  so there is nothing for it to exercise.
