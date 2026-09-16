# PLE-71 — Battery/thermal session for PLE-59's big-core affinity

Answers whether PLE-59's `stream_thread_priority_boost` big-core affinity
(`sched_setaffinity` to CPUs 4–7 for the network-receive, decoder-output and
video-presenter threads) is worth its thermal/battery cost before it can be
promoted to default. **No default flip in this ticket.**

**Verdict: modest win, modest cost, doesn't clearly pay for itself on a single
10-minute session.** With the boost on, the app's own battery-stats CPU
estimate dropped 6.6% (chiaki UID: 30.3→28.3 mAh) and 9.5% on the CPU
sub-component alone (21.1→19.1 mAh), but skin temperature ran 0.4°C higher at
the peak and 0.6°C higher on average, and LIGHT thermal-throttle status
started about a minute earlier and stayed on in one more of the eleven
sampling windows. This is one sample per arm with no replication — see
[Caveats](#caveats) — so treat the direction as suggestive, not proof, and
don't read the magnitude too precisely.

## Hardware and build

| | |
|---|---|
| phone | Galaxy S22 Ultra, SM-S908B, serial `192.168.1.105:36437` (Wi-Fi ADB) |
| console | PS5-466, `192.168.1.164:9302`, `id=C0151B3DD8BC system=13600007 ready` |
| build | `fork/android-port` at `db71d3da` (no code changed for this ticket — pure measurement) |
| power | AC-powered via USB throughout, battery at 100% / `status: 5` (FULL) for both arms |
| date | 2026-09-16, 08:51:48–09:18:38 UTC (device local log time) |
| harness | `scripts/dev/ab/soak.sh` (start / drive×10 / collect), `scripts/dev/ab/mkprefs.py` for the two prefs variants, ad hoc `dumpsys thermalservice`/`scaling_cur_freq` sampling every ~55 s, `dumpsys batterystats --reset`/`--charged com.metallic.chiaki` around each arm |

## Method

Two ~10-minute streaming sessions, same synthetic on-screen-stick input via
`soak.sh drive` (no physical DualSense/human available), same 1280×720
stream, differing only in `stream_thread_priority_boost`:

1. **off** (08:51:48–09:01:36): boost disabled (today's default).
2. Cooldown: polled `dumpsys thermalservice` SKIN sensor every 60 s until it
   returned to the pre-session baseline (31.9°C → 32.9°C, `mStatus=0`
   both times) before starting the second arm — 7 minutes.
3. **on** (09:08:51–09:18:38): boost enabled.

For each arm: `dumpsys batterystats --reset` immediately before `soak.sh
start`, `dumpsys thermalservice` + `cat
/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq` sampled roughly once
per `soak.sh drive` chunk (~55 s cadence, 11 samples per arm including the
pre-drive sample), `dumpsys batterystats --charged com.metallic.chiaki`
immediately after `soak.sh collect`.

Raw artifacts (not committed — captures dir is gitignored):
`/home/wnt/gta6/build/ab/captures-ple71/{off,on}_{session.log,thermal_samples.txt,batterystats.txt,prefs_on_device.xml}`.

### Battery measurement note (corrects a PLE-101 finding for this rig)

PLE-101 found `dumpsys batterystats` doesn't accumulate on this rig because
the S22 Ultra is AC-powered for every ADB-tethered session. That holds when
the battery is *charging*, but not when it's already at 100% / `status: 5`
(FULL): Android then treats the device as "on battery" for batterystats
purposes even with `AC powered: true`, and the per-UID CPU energy estimate
does accumulate — confirmed with a 90 s idle reset/sample before this
session (see ticket comment). Both arms ran at 100%/FULL throughout, so the
comparison below is valid; a future power ticket on this device should check
`dumpsys battery` for `level`/`status` before assuming PLE-101's finding
applies.

## Result

### Battery (`dumpsys batterystats --charged com.metallic.chiaki`, per-UID estimate)

| | off | on | Δ |
|---|---|---|---|
| session duration (batterystats) | 10m 6s | 9m 51s | — |
| chiaki UID (`u0a182`) total estimate | 30.3 mAh | 28.3 mAh | −6.6% |
| chiaki UID `cpu` component | 21.1 mAh | 19.1 mAh | −9.5% |
| chiaki UID `video` component | 5.00 mAh | (not broken out separately on) | — |
| whole-device `cpu`/`apps` estimate | 75.3 mAh | 73.2 mAh | −2.8% |

`Computed drain: 0, actual drain: 0` in both dumps (device is AC-powered, so
the charge-counter fields are meaningless, as the ticket anticipated — this
table uses only the per-UID CPU energy estimate).

### Thermal (`dumpsys thermalservice`, SKIN sensor, 11 samples/arm at ~55 s cadence)

| | off | on | Δ |
|---|---|---|---|
| baseline SKIN before session | 31.9°C (`mStatus=0`) | 32.9°C (`mStatus=0`, re-checked cooldown) | — |
| peak SKIN | 38.7°C | 39.1°C | +0.4°C |
| mean SKIN (11 samples) | 36.9°C | 37.5°C | +0.6°C |
| samples with `mStatus>0` (LIGHT) | 4 / 11 (from 6m30s in) | 5 / 11 (from 5m34s in) | +1 sample, ~1 min earlier onset |
| post-session SKIN | 38.7°C (`mStatus=1`) | 39.1°C (`mStatus=1`) | +0.4°C |

No sample in either arm reached `mStatus` above 1 (LIGHT) — no MODERATE/SEVERE
throttling observed in either arm.

### Big-core residency (`scaling_cur_freq`, mean across the 11 samples)

| | off | on | Δ |
|---|---|---|---|
| little cluster (cpu0–3) mean | 1 108 364 kHz | 1 082 182 kHz | −2.4% |
| big cluster (cpu4–7) mean | 1 320 000 kHz | 1 344 000 kHz | +1.8% |

Direction is consistent with the affinity doing what it says — slightly less
little-cluster clock, slightly more big-cluster clock — but `scaling_cur_freq`
is a clock-frequency proxy, not a residency/utilization counter, so this is
weak evidence, not a measurement of which threads actually ran where.

### Takion CPU claim

PLE-59's "26–37% Takion CPU" figure isn't reproducible from this session: the
`Feedback stats:` log line (`stream_feedback_stats_log=true`, sampled 1 Hz in
both session logs) reports packet-rate (`per_s takion ≈650–690`), not thread
CPU%, and neither `soak.sh` nor this ticket captured a `top`/Perfetto CPU
trace per thread. The battery-stats CPU delta above (−9.5% on the app's `cpu`
component) is the closest available proxy and is a different metric measuring
different things (whole-app CPU energy vs. one thread's CPU-time fraction) —
don't treat it as confirming or refuting PLE-59's number.

## Caveats

- **n=1 per arm.** No replication, no randomized order (off ran first both
  times an arm could plausibly drift with skin temperature or Wi-Fi
  conditions over the ~35-minute span of this ticket). Treat magnitudes as
  indicative only.
- Synthetic on-screen-stick input, not a physical controller — same
  limitation as every `soak.sh` session on this rig.
- Sampling cadence (~55 s) means the reported "onset" times for throttle
  status changes have ±55 s resolution.
- AC-powered/100%-battery measurement path is unusual enough (see note above)
  that it's worth an independent sanity check before this data drives a
  promotion decision.

## Follow-ups (not done here)

- A `top -H` or Perfetto per-thread CPU-time capture during an on/off pair
  would directly test PLE-59's 26–37% Takion-thread claim instead of the
  whole-app proxy used here.
- Replicate this A/B (both orders, ideally 2–3 reps per arm) before using it
  to promote or reject the default — a single 10-minute session per arm is
  the minimum the ticket asked for, not enough to be confident in the
  direction at these small deltas.
