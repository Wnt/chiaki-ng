# PLE-79 — `stream_performance_mode` off/on A/B on SM-S908B (2026-09-16, 05:50–06:01 UTC)

Follow-up asked for by the PLE-70 worker: one Perfetto A/B of the `stream_performance_mode`
switch, comparing CPU clocks, decoder duration, queue-to-present latency, thermals and power.
Eight scenarios, one build, one night, `stream_performance_mode` the only pref that differs.

**Headline: on this phone the switch is not a governor feature at all.** Two of its three legs
are rejected by the platform, and the one leg that survives — a MediaCodec `operating-rate=240`
hint — buys nothing measurable and costs ~1–3 % more CPU. Do not promote it to default on Exynos.

## Run identity

- Worker: Claude Code, `claude-opus-5`, CT950, worktree `/home/wnt/gta6/wt/ple-79`, branch
  `jonni/ple-79-run-an-sm-s908b-perfetto-ab-capture-with`.
- Device: **SM-S908B (Galaxy S22 Ultra)**, `ro.board.platform=universal9925` (Exynos 2200),
  Android **16** / SDK **36**, serial **R3CT30WLVFV**, Wi-Fi ADB `192.168.1.105:36437`, held for the
  whole round by `device.py run PLE-79`. Display `mActiveModeId=8` = 1080x2316 @ 120 Hz in every
  scenario (`<name>_display.txt`); landscape (`wm user-rotation lock 1`) everywhere.
- Console: **PS5-466** `192.168.1.164:9302`, host-id `C0151B3DD8BC`, `system=13600007`, reported
  `ready` by `scripts/dev/ps5-discover.py` at 05:47 UTC. Same idle game screen for every capture.
- Decoder in every scenario: `c2.exynos.hevc.decoder`, configured at **tier 3** (low-latency setting
  off) in all eight — the flag does not change which tier configures.
- Build: one APK, `assembleDebug` of this worktree at branch tip (PLE-75 landed), sha256
  `5bbc756c73f78c4249aac0e76a6f7867c4dc468e1c78f11a1c2212acaf0f3b30`, kept as
  `build/dispatch/ple-79/captures/apk_ple79.apk`. Installed with `install -r` after
  `scripts/dev/app-state.sh backup`
  (`~/.config/pleikkari/backups/fork-app-20260916T054929Z`, registered_host rows=1, nickname PS5-466);
  the on-device `base.apk` hashes to the same sha256 (the APK it replaced was `88e5fa23…`).
- Gate on this worktree: `GATE: PASS`, `build/dispatch/ple-79/gate.log` (host configure/build/ctest +
  `assembleDebug`, 89 s).
- Captures (gitignored, never committed): `build/dispatch/ple-79/captures/` — 8 × ~36–48 MB
  `.perfetto-trace` plus `_logcat.txt`, `_prefs_on_device.xml`, `_display.txt`, `_gfxinfo_*`,
  `_threads.txt`, `_thermal.txt`, screenshots, `<name>.json`, `summary.json`.

## Method

`scripts/dev/ab/ab.sh <name> <touched> <prefs.xml>` (PLE-47 harness, PLE-55 decode metric, PLE-76
per-thread CPU): leave the live stream with BACK, force-stop, write `shared_prefs` through `run-as`
and byte-compare it back, launch, lock landscape, tap the PS5-466 tile, wait for the Takion init
acks, settle 25 s, dump `gfxinfo`/`display`/`window`/threads, record a 15 s `chiaki_trace.cfg`
Perfetto trace, pull everything. `touched=1` drives 13 left-stick swipes during the trace.
`summarize.py` produced the table. Run in three foreground `device.py run PLE-79` chains,
interleaved base/perf so thermal drift is shared rather than assigned to one arm.

Thermals and battery are not part of the harness, so a snapshot
(`dumpsys thermalservice`, `dumpsys battery`, `current_now`, `scaling_cur_freq`) was taken
immediately before and after each scenario into `<name>_thermal.txt`.

Flag application is verified per scenario, not assumed:

| check | base_* (4 runs) | perf_* (4 runs) |
|---|---|---|
| `stream_performance_mode` in `<name>_prefs_on_device.xml` | `false` | `true` |
| `Stream performance mode requesting MediaCodec operating-rate=240` in logcat | absent | present |
| `PowerHintSessions are not supported` in logcat | absent | present |

## What `stream_performance_mode` actually does on this device

From `perf_untouched_logcat.txt` (identical in all four `perf_*` runs):

```
W StreamActivity: Sustained performance mode is unavailable
I Chiaki  : ADPF performance hints enabled with a 60 fps frame budget
E perf_hint: createSessionUsingConfig: PerformanceHint cannot create session. PowerHintSessions are not supported!
W PerformanceHints: ADPF rejected hint session for roles=[12]
I Chiaki  : Stream performance mode requesting MediaCodec operating-rate=240
```

- **Sustained performance mode: unavailable.** `PowerManager.isSustainedPerformanceModeSupported`
  is false on this device, so `StreamActivity.configurePerformanceMode` never calls
  `Window.setSustainedPerformanceMode`.
- **ADPF: rejected by the platform.** Android 16 on universal9925 reports *"PowerHintSessions are
  not supported"*; `PerformanceHintManager` cannot create a session for any role. Our code path is
  correct and the OS refuses it — this is the answer to PLE-70's open question for this phone, and no
  amount of client work changes it.
- **What is left:** `create_decoder_format` at tier ≤ 3 adds `frame-rate=60` and
  `operating-rate = target_fps × 4 = 240`. That is the entire measured delta.
  (`video-decoder.c:206-213`; tier 3 = `DECODER_CONFIGURE_BASELINE_TIER`. It also raises the
  fallback tier ceiling to 4, unused here since tier 3 configured in every run.)

## Results

`summarize.py` over all eight scenarios (15 s each, 120 Hz panel, ms unless noted):

| scenario | q→present avg | p50 | p90 | p99 | max | decode avg | decode p50 | decode p95 | latch>25 ms | decoder CPU ms | takion CPU ms | takion wk/s | app CPU ms |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| base_untouched | 8.46 | 8.29 | 12.03 | 13.36 | 17.01 | 8.20 | 8.28 | 10.32 | 135 | 4686 | 2676 | 587.5 | 15928 |
| base_untouched_2 | 8.26 | 7.90 | 11.93 | 13.45 | 16.89 | 7.87 | 7.80 | 9.85 | 126 | 4573 | 2698 | 591.0 | 16113 |
| **perf_untouched** | 8.54 | 8.23 | 12.09 | 13.74 | 20.33 | 8.03 | 7.94 | 9.86 | 152 | 4721 | 2731 | 581.4 | 16307 |
| **perf_untouched_2** | 8.62 | 8.57 | 12.31 | 13.53 | 17.29 | 7.90 | 7.85 | 9.76 | 126 | 4721 | 2746 | 589.4 | 16244 |
| base_touched | 7.62 | 7.52 | 11.19 | 13.91 | 19.76 | 6.06 | 5.50 | 9.18 | 101 | 2925 | 1961 | 641.3 | 12576 |
| base_touched_2 | 7.17 | 7.08 | 10.76 | 13.04 | 16.13 | 5.87 | 5.33 | 8.75 | 100 | 2899 | 1960 | 647.4 | 12836 |
| **perf_touched** | 7.05 | 6.90 | 10.65 | 12.82 | 15.49 | 5.90 | 5.36 | 8.73 | 118 | 2918 | 1965 | 639.1 | 12797 |
| **perf_touched_2** | 7.36 | 7.35 | 10.78 | 13.14 | 18.73 | 6.08 | 5.48 | 8.85 | 94 | 2926 | 1994 | 658.9 | 12553 |

Frames: 869–887 `NDK MediaCodec_` queueBuffers per 15 s in every scenario (≈58 fps), no reorder
timeouts, loss clamps, stale drops or IDR requests in any session.

### Verdicts against the baseline band (n=2 per arm; inside the band = no signal)

| metric | untouched band (base) | perf untouched | verdict |
|---|---|---|---|
| queue→present avg | 8.26–8.46 | 8.54, 8.62 | **marginally worse** (+0.1…+0.4 ms, ~2–4 %) |
| decode latency mean | 7.87–8.20 | 8.03, 7.90 | no signal |
| decode p95 | 9.85–10.32 | 9.86, 9.76 | no signal |
| latch intervals > 25 ms | 126–135 | 152, 126 | no signal (one high, one inside) |
| decoder (NDK MediaCodec) CPU | 4573–4686 ms | 4721, 4721 | **worse** (+0.7…+3.2 %, both outside) |
| whole-app CPU | 15928–16113 ms | 16307, 16244 | **worse** (+0.8…+2.4 %, both outside) |

| metric | touched band (base) | perf touched | verdict |
|---|---|---|---|
| queue→present avg | 7.17–7.62 | 7.05, 7.36 | no signal (one 0.12 ms below, one inside) |
| decode latency mean | 5.87–6.06 | 5.90, 6.08 | no signal |
| decoder CPU | 2899–2925 ms | 2918, 2926 | no signal |
| whole-app CPU | 12576–12836 ms | 12797, 12553 | no signal |

### CPU clocks (average of the per-CPU `cpufreq` counter over the trace, MHz)

| scenario | little (cpu0–3) | mid (cpu4–6) | big (cpu7) |
|---|---|---|---|
| base_untouched | 645 | 1200 | 1546 |
| base_untouched_2 | 632 | 1076 | — |
| perf_untouched | 633 | 1074 | — |
| perf_untouched_2 | 641 | 1187 | 960 |
| base_touched | 903 | 1327 | 1248 |
| base_touched_2 | 908 | 1332 | 1247 |
| perf_touched | 906 | 1360 | 1205 |
| perf_touched_2 | 904 | 1414 | 1273 |

Untouched: the two arms interleave completely — no clock signal. Touched: the mid cluster sits
2–6 % higher with the flag on (1360/1414 vs 1327/1332), consistent with the slightly higher CPU
time, but it is two samples against two and the round warms monotonically, so treat it as a hint,
not a result. `cpu7` has no `cpufreq` events at all in two untouched traces (the big core is
largely parked in this workload), so its column is not comparable across scenarios.

### Thermals and power

`Thermal Status: 0` (no throttling) in all sixteen snapshots; no cooling devices reported by the HAL.

| scenario (in run order) | AP °C pre→post | SKIN °C pre→post |
|---|---|---|
| base_untouched | 23.3 → 27.6 | 29.0 → 31.1 |
| perf_untouched | 27.6 → 28.7 | 31.1 → 31.8 |
| base_untouched_2 | 28.7 → 29.3 | 31.8 → 32.2 |
| perf_untouched_2 | 29.0 → 29.9 | 32.2 → 32.6 |
| base_touched | 30.0 → 31.2 | 32.9 → 33.6 |
| perf_touched | 31.2 → 31.9 | 33.6 → 33.6 |
| base_touched_2 | 31.3 → 32.2 | 33.7 → 34.1 |
| perf_touched_2 | 32.2 → 32.6 | 34.1 → 34.1 |

The phone warms monotonically through the round (AP 23.3 → 32.6 °C over 11 minutes) at ≈+0.6…+1.2 °C
per scenario **irrespective of the arm**; the per-scenario deltas are run order, not the flag. A
10-minute-per-arm soak would be needed to separate them, and nothing here got close to throttling.

**Power was not measurable.** The phone is on AC (`AC powered: true`, `level: 100`, `status: 5`),
so `current_now`/`charge_counter` describe charging, not load; battery temperature rises 27.8 → 31.1 °C
across the round with the same order effect. The usable power proxy is CPU time, above: the flag costs
~1–3 % more app CPU untouched and nothing detectable touched. A real power comparison needs the phone
off the charger for a long steady arm, which this round cannot do remotely.

## Conclusion

1. `stream_performance_mode` should **stay off by default** on Exynos 2200. It does not reduce
   queue→present or decode latency on this device, it is marginally worse on queue→present untouched,
   and it costs a little CPU.
2. Its ADPF and sustained-performance legs are **dead code on this phone** — the platform has no
   PowerHintSessions. Anything PLE-70 or a successor wants from ADPF has to be validated on a device
   whose `PerformanceHintManager` accepts a session; on SM-S908B it never will.
3. The one live leg, `operating-rate=240`, is a weaker version of what PLE-111 already put in the
   low-latency tier (`operating-rate=480`) and what PLE-75's explicit override exposes. The flag's
   decoder leg is therefore redundant with the operating-rate work; the sweep in **PLE-116** is the
   place to settle the rate, and the flag itself is a candidate for removal or for being narrowed to
   the ADPF legs behind a "PowerHintSession available" check so it does not log a promise it cannot keep.
