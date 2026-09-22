# PLE-198: replicated battery/thermal A/B of `stream_thread_priority_boost`

**Answer up front.** PLE-71's CPU saving replicates, and its heat cost does not. Over
three sessions per arm, run in both orders, the boost cuts the app's CPU cost by about 9 %:

- batterystats `cpu`: 17.13 → 15.63 mAh (−8.8 %)
- whole-app CPU time: 0.644 → 0.580 cores (−9.9 %)
- UID total: 26.13 → 24.67 mAh (−5.6 %)

The arm ranges **do not overlap** on any of the three. The worst ON session beats the best
OFF session.

Skin temperature shows **no difference**. The four sessions that started from the same
31.7 °C baseline ran at a SKIN mean of 36.27 / 36.29 °C (OFF) and 36.24 / 36.32 °C (ON), and
peaked at 38.1 °C in all four. Each of them showed LIGHT throttling in 1 of 10 samples.
PLE-71's single-pair +0.6 °C / +0.4 °C penalty came from its fixed off-then-on order and
the drifted prefs it ran with, not from the boost. Stream health is identical: no loss and
0-3 drops in every session.

**On power and heat, nothing now argues against promoting the boost.** This round measures
cost only: it says nothing about latency. **No default was changed here.**

## 1. Setup

- Phone: SM-S908B, serial `192.168.40.101:5555` (Wi-Fi ADB), AC-powered, battery at
  100 % / `status: 5` (FULL) at the start of every session (`<s>_battery.txt`), so
  batterystats accumulates (see PLE-71's note).
- Console: PS5-466 `192.168.1.164`, `ps5-discover.py`: `ready`.
- APK: `assembleDebug` of `4f336e23` (`fork/android-port` head, gate PASS), `install -r`
  after `app-state.sh backup` (verified, `registered_host rows=1`).
- Harness: `scripts/dev/ab/soak.sh` frozen from workspace `10c9819e` into the captures dir.
  This directory's `setup.sh` (install + prefs), `session.sh` (one session) and
  `cooldown.sh` (baseline wait) wrap it. `AB_NET_PROFILE=clean`.
- Captures: `/home/wnt/gta6/build/dispatch/ple-198/captures/` (gitignored, never commit).
  Per session: `<s>_batterystats.txt`, `<s>_thermal_samples.txt`, `<s>_proc_cpu.txt`,
  `<s>_affinity.txt`, `<s>_battery.txt`, `<s>_logcat.txt`, `<s>_session.log`,
  `<s>_prefs_on_device.xml`. `aggregate.md` is the output of `aggregate.py`.

### Arms

The two arms differ only in `stream_thread_priority_boost`. Every other flag is pinned to
its **shipped default**, using PLE-517's arm-A set: 720p, H.265, auto bitrate,
`low_latency` preset, and performance mode, the Wi-Fi low-latency lock, real timestamps
and the overlay all off. `stream_feedback_stats_log=true` is added for the counters.
**This differs from PLE-71**, which inherited drifted stored prefs:
`stream_performance_mode`, the Wi-Fi low-latency lock and real timestamps were all on.

### Order and session shape

The order was **ON, OFF, OFF, ON, ON, OFF** (`r1`..`r6`), captured 2026-09-22 05:46-07:06
UTC. It starts with ON, the order PLE-71 lacked, and each arm occupies positions 1+4+5 or
2+3+6.

Each session was one Samsung reservation. It ran `batterystats --reset`, then `soak.sh
start`, then waited for three `Feedback stats` lines. After that came 9 × 50 s of `soak.sh
drive` (synthetic on-screen-stick swipes), then `soak.sh collect` and `batterystats
--charged`. `thermalservice` and `scaling_cur_freq` were sampled before the reset, at
stream start and after every chunk. `/proc/<pid>/stat` utime+stime was read at stream
start and at the end of driving.

The measured window is **~7.6 min of driving (8m10s of batterystats)**, not PLE-71's
10 min. A worker's Bash call is capped at 10 minutes, and a session that spans two calls
releases the phone in between, where another worker could grab it.

Between sessions, `cooldown.sh` waited until SKIN was ≤ 31.8 °C (session 1's 30.8 °C
baseline + 1 °C) with `mStatus=0`. That took 6-7 min every time. Sessions 3-6 all started
at 31.7 °C. Session 2 started at 31.4 °C, after PLE-527 used the phone during the first
cooldown. Session 1 started at 30.8 °C.

## 2. Results

`pinned thr` is the number of app threads whose `Cpus_allowed_list` was `4-7`, read once
per session at stream start. `app CPU` is the utime+stime delta of the app process
(CLK_TCK 100) over the wall-clock drive window. `cores` is that delta divided by wall
time. `LIGHT` counts the drive samples with SKIN `mStatus>0`. Frequencies are the mean
`scaling_cur_freq` over the drive samples: cpu0-3 A510, cpu4-6 A710, cpu7 X2. `drops` and
`lost` are session-cumulative maxima from `Feedback stats`, and `unrec` is the sum of the
windows.

| session | pinned thr | bstats dur | UID mAh | UID cpu mAh | app CPU s / wall s | cores | SKIN start | SKIN peak | SKIN mean | LIGHT | AP mean | A510 MHz | A710 MHz | X2 MHz | frames rx | decoded | drops | lost | unrec |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| r1_on | 8 | 8m 10s 410ms | 24.7 | 15.6 | 263.5 / 458 | 0.575 | 30.8 | 37.2 | 35.35 | 0/10 | 35.5 | 1125 | 1451 | 1376 | 28298 | 28296 | 2 | 0 | 0 |
| r2_off | 0 | 8m 9s 384ms | 26.2 | 17.1 | 295.3 / 458 | 0.645 | 31.4 | 37.7 | 35.78 | 0/10 | 36.0 | 1109 | 1463 | 1280 | 28237 | 28236 | 1 | 0 | 0 |
| r3_off | 0 | 8m 10s 182ms | 26.3 | 17.3 | 297.6 / 459 | 0.648 | 31.7 | 38.1 | 36.27 | 1/10 | 36.8 | 1131 | 1451 | 1344 | 28235 | 28235 | 0 | 0 | 0 |
| r4_on | 8 | 8m 9s 813ms | 24.7 | 15.7 | 266.8 / 458 | 0.583 | 31.7 | 38.1 | 36.24 | 1/10 | 36.7 | 1131 | 1440 | 1376 | 28231 | 28228 | 3 | 0 | 0 |
| r5_on | 8 | 8m 7s 55ms | 24.6 | 15.6 | 266.0 / 457 | 0.582 | 31.7 | 38.1 | 36.32 | 1/10 | 36.8 | 1077 | 1376 | 1301 | 28056 | 28054 | 2 | 0 | 0 |
| r6_off | 0 | 8m 10s 174ms | 25.9 | 17.0 | 292.3 / 458 | 0.638 | 31.7 | 38.1 | 36.29 | 1/10 | 36.7 | 1085 | 1312 | 1312 | 28238 | 28237 | 0 | 0 | 0 |

Per arm (mean of 3; ranges in brackets):

| metric | off mean [min-max] | on mean [min-max] | on-off | on-off % |
|---|---|---|---|---|
| UID mAh | 26.13 [25.90-26.30] | 24.67 [24.60-24.70] | -1.47 | -5.6 % |
| UID cpu mAh | 17.13 [17.00-17.30] | 15.63 [15.60-15.70] | -1.50 | -8.8 % |
| app CPU cores | 0.644 [0.638-0.648] | 0.580 [0.575-0.583] | -0.064 | -9.9 % |
| SKIN peak | 37.97 [37.70-38.10] | 37.80 [37.20-38.10] | -0.17 | -0.4 % |
| SKIN mean | 36.11 [35.78-36.29] | 35.97 [35.35-36.32] | -0.14 | -0.4 % |
| AP mean | 36.51 [36.03-36.77] | 36.32 [35.54-36.75] | -0.18 | -0.5 % |
| A510 kHz | 1108444 [1085333-1130667] | 1111111 [1077333-1130667] | +2667 | +0.2 % |
| A710 kHz | 1408704 [1312000-1463444] | 1422222 [1376000-1450667] | +13519 | +1.0 % |
| X2 kHz | 1312000 [1280000-1344000] | 1351111 [1301333-1376000] | +39111 | +3.0 % |
| decoded | 28236 [28235-28237] | 28193 [28054-28296] | -43 | -0.2 % |

## 3. Reading it

- **CPU cost goes down, consistently.** Two independent measures agree: the batterystats
  per-UID `cpu` estimate and the kernel's own utime+stime. Each moves 9-10 %, with a
  spread of ±0.5 % inside each arm. The mechanism fits: the same work runs on faster cores
  and takes fewer CPU-seconds, and the power model charges big-core time at a higher rate
  per second without cancelling the saving. PLE-71's −9.5 % cpu-component figure was right.
- **Heat is a wash.** The arm-mean SKIN difference (−0.14 °C) is smaller than the effect
  of the starting temperature. `r1_on` started 1 °C cooler and has the lowest mean in
  the table. Compared at matched 31.7 °C starts, the arms are indistinguishable. The small
  upward drift in the X2 and A710 clocks is in the direction the affinity predicts, but it
  is within the per-arm ranges, and `scaling_cur_freq` is a clock proxy, not residency.
- **The set of pinned threads is not the set the code names.** The callback
  (`chiaki-jni.c`) pins three thread roles: Takion, the decoder output and the video
  presenter. With shipped defaults the presenter role never runs, because its thread is
  `ChiakiVsync` (`video-presenter.c` `vsync_thread_func`), which only starts when
  timestamped release is enabled. It is absent from every `<s>_affinity.txt`. Meanwhile
  eight threads are on CPUs 4-7 in every ON session:
  - `ChiakiVideoOut` and the two `Chiaki Takion` threads, which the code names;
  - two `Chiaki GKCrypt`, `AAudio_1`, one `NDK MediaCodec_` and `Thread-7`, which it
    doesn't.

  The inferred cause is that Linux threads inherit affinity and nice from their creator,
  so threads started from a pinned thread end up pinned too. The creator of each thread was
  not traced. The CPU saving above belongs to this whole set. Per-thread work, PLE-199
  included, should expect these eight threads, not the three roles.

## 4. Caveats

- The measured window is 8 minutes, not 10 (see §1). No session reached MODERATE
  throttling. LIGHT appeared once, late, in four of six sessions, one per arm pair.
- Input was the synthetic on-screen-stick swipe only, not a physical controller or a
  human playing, and the console content was whatever was on screen. Heavier content may
  heat the phone more.
- Nothing here measures latency or frame pacing: there was no Perfetto trace. The
  promotion question still needs the latency side, from PLE-59's own capture or a new
  round.
- The phone was released between sessions, and PLE-527 used it once, during the first
  cooldown. The cooldown gate still held each start within 1 °C of baseline.
