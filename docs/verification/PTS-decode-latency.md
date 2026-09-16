# Why real PTS raised Exynos decode latency 8 → 14 ms, and the fix (PLE-75)

Follow-up of [`AB-2026-09-16-round2.md`](AB-2026-09-16-round2.md) (PLE-64), which found that every
timestamped run (PLE-16 real PTS, PLE-23 presenter) decoded in 13.5–14.3 ms mean / 28–34 ms p95 instead of
the ~8 ms of the default path, even with the panel pinned at 120 Hz. Same phone, same console, same
method, 13 scenarios on one night, 04:51–05:11 UTC.

**Result in one line:** the `c2.exynos.hevc.decoder` scales its per-frame latency with the frame rate it
believes it is serving, and it takes that rate from the *spacing of the input timestamps* unless a higher
`operating-rate` is configured. Real 60 fps PTS say "60 fps" and the decoder answers in 14 ms; the default
path's 1 µs steps say "1 MHz" and it answers in 8 ms. Configuring `operating-rate = 480` with real PTS
restores 7.7–8.1 ms, and the fix is now wired in automatically whenever PLE-16/PLE-23 are on.

## Run identity

- Worker: Claude Code, `claude-fable-5-1`, CT950, worktree `/home/wnt/gta6/wt/ple-75`, branch
  `jonni/ple-75-why-real-pts-ple-16ple-23-raises-exynos-decode-latency-814`.
- Device: **SM-S908B (Galaxy S22 Ultra)**, Exynos 2200, Android 16 / SDK 36, serial **R3CT30WLVFV**,
  Wi-Fi ADB `192.168.1.105:36437`, held for the whole run by the dispatcher's `device.py run PLE-75`
  reservation. Battery 100 % on charger, FHD+ 1080x2316, `mActiveModeId=8` = 120 Hz in every scenario
  (`<name>_display.txt`, 13/13). Every timestamped run logs `Requested display mode 8: 1080x2316@120.00001`
  (PLE-54) and the presenter attaches with `timestamped_release=disabled` / `using immediate release`
  at 120 Hz, so the presenter never holds a frame in any scenario below.
- Console: **PS5-466** 192.168.1.164:9302, host-id C0151B3DD8BC, `ready` by `scripts/dev/ps5-discover.py`
  at 04:51 UTC. Astro Bot idle on the same screen for every capture.
- Decoder: `c2.exynos.hevc.decoder` in every scenario (`AMediaCodec_configure() succeeded … at tier N`).
- Builds: two `assembleDebug` APKs of this worktree, both gated (`build/dispatch/ple-75/gate-1.log`,
  `gate-2.log`, `GATE: PASS`). `apk_ple75.apk` (sha256 `13f0285e…79b1`, experiment settings only) for the
  ten experiment scenarios; `apk_ple75_v2.apk` (`88e5fa23…b3a2`, adds the auto operating-rate) for the
  three `v_*` verification scenarios. Each was installed with `install -r` after
  `scripts/dev/app-state.sh backup` (`~/.config/pleikkari/backups/fork-app-20260916T045046Z`,
  registered_host rows=1, nickname PS5-466) and verified by hashing `pm path`'s base.apk on the device.
- Harness: `scripts/dev/ab/` exactly as in round 2 (`ab.sh`, untouched, 25 s settle, 15 s
  `chiaki_trace.cfg` trace, `summarize.py`). Decode latency is the PLE-55 metric: input
  `onQueueInputBuffer` → `kWhatDrainThisBuffer` on the video codec's own thread. "Never latched" is
  `1 − (latch intervals + 1) / codec queueBuffers` from `<name>.json`.

## Settings added for the experiments (all default = previous behaviour)

| key | type | default | effect |
|---|---|---|---|
| `stream_decoder_operating_rate` | int | 0 | explicit `operating-rate` plus `frame-rate = stream fps` in the MediaCodec format at every PLE-6 tier; 0 = unset |
| `stream_video_timestamp_rate_hz` | int | 0 | PTS timeline rate for real PTS: `PTS = frame_index × 1e6 / rate`; 0 = stream fps |
| `stream_decoder_realtime_priority` | boolean | false | `priority = 0` (realtime) in the format |
| `stream_decoder_operating_rate_auto` | boolean | **true** | **the fix**: with real PTS on (PLE-16, or PLE-23 which enables real PTS) and no explicit operating rate, configure `operating-rate = 480` |

The auto switch defaults on because it only acts when PLE-16/PLE-23 are on, which are themselves off by
default; the shipped default path is byte-for-byte unchanged. Turning it off reproduces the round-2
regression (row `v_realpts_auto_off`), so both halves of the A/B stay measurable.

## Results (ms; CPU = ms of the 15 s trace; untouched, 120 Hz, one run each)

| scenario | prefs (on top of defaults) | rate the codec sees | q→p avg | p50 | p90 | p99 | max | decode mean | p50 | p95 | max | queued → latched (never latched) | decoder CPU | app CPU | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `base_untouched` | none | ~1 MHz (1 µs PTS steps) | 8.49 | 8.28 | 12.22 | 13.43 | 17.43 | 7.95 | 8.08 | 9.98 | 20.14 | 873 → 860 (1.5 %) | 4507 | 15386 | reference |
| `base_untouched_end` | none | ~1 MHz | 8.46 | 8.32 | 12.08 | 14.23 | 18.42 | 7.94 | 7.90 | 9.72 | 19.94 | 874 → 868 (0.7 %) | 4598 | 15954 | reference |
| `ple16_realpts` (plain real PTS) | `stream_real_video_timestamps` | 60 fps | 9.09 | 9.07 | 12.80 | 14.27 | 17.83 | **13.92** | 11.86 | **30.66** | 68.61 | 880 → 840 (**4.5 %**) | 4916 | 16627 | round 2 reproduced |
| (1) `exp1_oprate120` | real PTS + `stream_decoder_operating_rate=120` | 120 | 9.25 | 9.12 | 12.87 | 14.33 | 21.76 | 13.06 | 12.01 | 17.04 | 54.09 | 875 → 842 (3.8 %) | 4928 | 16962 | tail halves, mean stays |
| (1b) `exp1b_oprate480` | real PTS + `stream_decoder_operating_rate=480` | 480 | 8.65 | 8.51 | 12.39 | 13.30 | 17.01 | **8.09** | 7.97 | **9.98** | 16.70 | 883 → 873 (1.1 %) | 4747 | 16266 | **recovered** |
| (2) `exp2_pts120` | real PTS + `stream_video_timestamp_rate_hz=120` | 120 (by PTS spacing) | 9.19 | 9.32 | 12.88 | 14.14 | 18.53 | 12.71 | 11.74 | 16.01 | 49.62 | 878 → 848 (3.4 %) | 4881 | 16507 | same as (1): the codec keys off spacing |
| (2b) `exp2b_pts1000` | real PTS + `stream_video_timestamp_rate_hz=1000` | 1000 (by PTS spacing) | 8.87 | 8.67 | 12.51 | 13.62 | 17.23 | **8.47** | 8.36 | 10.39 | 14.21 | 872 → 855 (1.9 %) | 4768 | 16230 | recovered by spacing alone |
| (3) `exp3_lowlat` | real PTS + `stream_decoder_low_latency` (PLE-6 tier 0: `low-latency=1`, `operating-rate=240`, `priority=1`, `frame-rate=60`) | 240 | 9.21 | 9.27 | 12.77 | 13.87 | 19.55 | 9.37 | 9.16 | 11.57 | 28.50 | 877 → 862 (1.7 %) | 4901 | 16550 | mostly recovered; fits the 240 point of the curve |
| (4) `exp4_pacing_lowest` | real PTS + `stream_video_pacing_enabled`, mode `lowest_latency` | 60 fps | 9.34 | 9.47 | 12.86 | 13.97 | 18.00 | 13.94 | 11.79 | 28.77 | 68.53 | 872 → 841 (3.6 %) | 4975 | 16733 | identical to plain real PTS: presenter not involved |
| (5) `exp5_rtprio` | real PTS + `stream_decoder_realtime_priority` | 60 fps | 9.23 | 9.36 | 12.87 | 14.51 | 22.21 | 14.01 | 11.92 | 28.22 | 66.50 | 876 → 834 (4.8 %) | 4916 | 16517 | no effect |
| `v_realpts_auto` (**fix**, v2 APK) | `stream_real_video_timestamps` (auto switch at its default) | 480 (auto) | 8.36 | 7.97 | 12.20 | 13.55 | 17.35 | **7.66** | 7.67 | **9.77** | 18.68 | 877 → 867 (1.1 %) | 4531 | 15248 | **in the baseline band on every column** |
| `v_realpts_auto_off` (v2 APK) | real PTS + `stream_decoder_operating_rate_auto=false` | 60 fps | 9.45 | 9.61 | 12.93 | 14.66 | 19.42 | 14.15 | 12.01 | 32.80 | 68.21 | 874 → 834 (4.6 %) | 4906 | 16491 | regression reproduced with the switch off |
| `v_ple23_balanced` (**fix**, v2 APK) | `stream_video_pacing_enabled`, mode `balanced` | 480 (auto) | 8.70 | 8.60 | 12.24 | 13.22 | 19.46 | **8.10** | 7.99 | 10.05 | 21.27 | 881 → 867 (1.6 %) | 4774 | 16288 | PLE-23 now decodes at baseline speed |

Every `on` scenario has its override confirmed as `<boolean>`/`<int>`/`<string>` in
`<name>_prefs_on_device.xml` and by the evidence line in its logcat (`Decoder operating-rate override:
operating-rate=N frame-rate=60`, `Frame-index video timestamps enabled at 60 fps (PTS timeline N Hz)`,
`Decoder realtime priority override: priority=0`, `configure() succeeded … at tier 0`). The codec
acknowledges the rate in the CCodec diff (`c2::float algo.rate.value = 120 / 240 / 480`) and
`ResourceManagerService` reports the same number as `fps:` (`fps:30` when nothing is set).

### The curve

Decode latency against the frame rate the decoder is told or infers, whichever is higher:

| rate seen by the codec | how | decode mean | p95 |
|---|---|---|---|
| 60 | real PTS (`ple16_realpts`, `exp4`, `exp5`, `v_realpts_auto_off`) | 13.9–14.2 | 28–33 |
| 120 | `operating-rate=120` (`exp1`) / PTS 120 Hz (`exp2`) | 13.1 / 12.7 | 17.0 / 16.0 |
| 240 | PLE-6 tier 0 (`exp3`) | 9.4 | 11.6 |
| 480 | `operating-rate=480` (`exp1b`, `v_realpts_auto`, `v_ple23_balanced`) | 7.7–8.1 | 9.8–10.1 |
| 1000 | PTS 1 kHz (`exp2b`) | 8.5 | 10.4 |
| ~1 000 000 | default 1 µs PTS steps (baselines) | 7.9–8.0 | 9.7–10.0 |

Two independent knobs (a configured rate and a timestamp spacing) land on the same curve, so the
mechanism is one rate-dependent policy inside the decoder, not the timestamps as such.

## Cause

Stated: **the Exynos 2200 HEVC decoder path (`c2.exynos.hevc.decoder` → MFC hardware) picks a
performance level from the frame rate it is serving, and derives that rate from input-timestamp spacing
when no higher `operating-rate` is configured.** A 60 fps stream gets a level whose per-frame time is
~12–14 ms with a long tail; the default path never triggered it because its 1 µs PTS steps read as a
megahertz frame rate. This is the documented behaviour of Samsung's MFC driver QoS (frame rate from the
last N input timestamps, floored by the operating rate, mapped to an MFC clock/bus level), and it also
explains round 2's side observation that touch input makes the decoder 40 % faster: DVFS.

Not proven at the clock level: `/sys/class/devfreq/17000090.devfreq_mfc0/cur_freq` exists on the phone
but is unreadable unprivileged, so the MFC frequency itself was not read. The evidence is behavioural.

Ruled out by the table: presenter hold (exp 4 equals plain real PTS, and every scenario used immediate
release at 120 Hz), codec priority (exp 5), output-buffer starvation (round 2 trace), and the
`low-latency` key by itself (exp 3's gain is what its 240 operating rate predicts; the key's own
contribution, if any, is inside run-to-run noise and was not separated further).

The 3.5–4.8 % never-latched frames are a consequence, not a separate defect: with a 30 ms p95 the codec
delivers frames in bunches, and SurfaceFlinger latches one buffer per 8.3 ms vsync, so the older of two
buffers queued inside one vsync is replaced. At 480 the never-latched share is back at 1.1–1.6 %
(baselines 0.7–1.5 %).

## What changed in the app

`android/app/src/main/cpp/video-decoder.c`: when real PTS is on (PLE-16, or PLE-23 which enables it),
no explicit `stream_decoder_operating_rate` is set, and `stream_decoder_operating_rate_auto` is on (its
default), the decoder format gets `operating-rate = 480` and `frame-rate = stream fps` at every PLE-6
tier, logged as `Decoder operating-rate override: operating-rate=480 frame-rate=60 (auto for frame-index
timestamps)`. An explicit rate wins over the auto value; PLE-70's performance mode (240 at tier ≤ 3) is
overridden by it too when real PTS is on. 480 rather than 240 because 240 (exp 3) leaves +1.4 ms mean
and +1.6 ms p95 on the table; 1000 Hz spacing (exp 2b) did not beat 480.

## Promote to default

| flag | recommendation | why |
|---|---|---|
| `stream_decoder_operating_rate_auto` | ships on (only acts with PLE-16/PLE-23) | rows `v_realpts_auto`, `v_ple23_balanced` vs `v_realpts_auto_off` |
| PLE-16 `stream_real_video_timestamps` | re-evaluate: the decode penalty is gone; queue→present now inside the band (8.36 avg / 13.55 p99) | one run; needs the PLE-79-style A/B with the fix in place before flipping |
| PLE-23 `stream_video_pacing_enabled` | re-evaluate for the same reason; at 120 Hz it is immediate release plus stale-frame dropping | `v_ple23_balanced`: 8.70 / p99 13.22, decode 8.10 |
| `stream_decoder_operating_rate` (explicit), `stream_video_timestamp_rate_hz`, `stream_decoder_realtime_priority` | keep 0 / 0 / off | experiment knobs; documented here for the next investigation |
| PLE-6 `stream_decoder_low_latency` | unchanged (keep off) | its 240 operating rate is the part that helped; note its `priority=1` means *non*-realtime in MediaCodec terms (no effect measured either way) |

The PLE-6 tier values quoted above (`operating-rate=240`, `priority=1`) are what tiers 0/1 set when
these captures were taken. PLE-111 has since raised the low-latency tiers to `operating-rate=480`
(`DECODER_LOW_LATENCY_OPERATING_RATE`), so exp 3 no longer reproduces as run here; the PLE-75 auto
value and an explicit `stream_decoder_operating_rate` still win over the tier value at every tier.

## Artefacts

Everything under `/home/wnt/gta6/build/dispatch/ple-75/captures/` (gitignored, not committed):
`<scenario>.perfetto-trace`, `_logcat.txt`, `_session.log`, `_prefs_on_device.xml`, `_display.txt`,
`_window.txt`, `_sf_full.txt`, `_sf_list.txt`, `_gfxinfo_{pre,post}.txt`, `_threads.txt`, `_main.png`,
`_screen.png`, `<scenario>.json`, `summary.json`, `summary_table.md`, `prefs_*.xml`, `base_prefs.xml`
(PSN account id; mode 600), `apk_ple75.apk` / `apk_ple75_v2.apk` + `.sha256`. Gate logs
`build/dispatch/ple-75/gate-1.log`, `gate-2.log`.

## State left on the phone

`apk_ple75_v2.apk` (`88e5fa23…`) installed, `shared_prefs` restored to the pre-run content
(byte-compared), rotation freed, stream left with BACK, app in `MainActivity` at 120 Hz.

## Harness lessons

- `summarize.py <names>` re-analyses only the named scenarios but prints the table for all JSONs present;
  its "queued→latched" column is not in the table, derive it from `latch_interval.n + 1` in the JSON.
- The two `NDK MediaCodec_` threads (video and Opus) share a name; any ad-hoc trace query must pick the
  codec thread by its `c2.exynos` slices as `analyze_ab.py` does, or the decode numbers are garbage.
- `mkprefs.py` accepts `int:` and `string:` overrides; the harness README's `bool:` alias now maps to
  `boolean`.
