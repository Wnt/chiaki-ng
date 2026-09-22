# PLE-517: A/B of the shipped default quality preset, BALANCED (1080p) vs LOW_LATENCY (720p)

**Answer up front.** On the S22 Ultra, 1080p60 H.265 costs **+0.7 ms of hardware decode**
per frame over 720p. That cost comes from resolution alone: 1080p at 10 Mbps and at 15 Mbps
decode identically. Queue→present latency is **unchanged** (7.73-7.82 ms arm means, within
the rep-to-rep spread), frame drops and loss show no resolution effect, and the picture is
**~2.5x sharper** by a Laplacian-variance proxy, which is plainly visible in the screenshots.
The `LOW_LATENCY` name buys 0.7 ms (4 % of a 16.7 ms frame) at the price of a visibly softer
picture.

**Recommendation: promote `streamQualityPresetDefault` to `BALANCED`**, in a follow-up
ticket. Before that ticket lands, run one confirmation round on high-motion content (see
§4), because this round's console scene was static enough that bitrate was never the
binding constraint. **No default was changed here.**

## 1. Setup

- Phone: SM-S908B, serial `192.168.40.101:5555` (Wi-Fi ADB), panel mode 8 = 120 Hz in every
  scenario (`<scenario>.json` `mode_hz`). PS5-466 `192.168.1.164`, `ps5-discover.py`: ready.
- APK: `assembleDebug` of `dbb79cb0` (`fork/android-port` head, gate PASS), `install -r`
  after `app-state.sh backup`.
- Harness: `scripts/dev/ab/round.sh ple-517-round`, frozen at workspace
  `b8aecce3a9f3983f1f9302379f0fad072be9bf5b`, `AB_NET_PROFILE=clean`, untouched (no input),
  25 s settle + 15 s Perfetto per scenario.
- Captures: `/home/wnt/gta6/build/dispatch/ple-517/captures/` (gitignored, never commit).
  Per scenario: `<s>.perfetto-trace`, `<s>_logcat.txt`, `<s>_session.log`,
  `<s>_prefs_on_device.xml`, `<s>_screen.png`, `<s>.json`. Also `summary.md`/`summary.json`
  (`summarize.py`) and `aggregate.md` (`aggregate.py` in this directory).
- Content: GT7 "Clubman Cup" results screen, a static table over a softly animated
  background. The same scene held for all nine scenarios.

### Arms

Only resolution, bitrate and the preset label differ. Every other flag is pinned to its
**shipped default**, because the phone's stored prefs had drifted from them (1080p,
`stream_performance_mode`, thread priority boost, Wi-Fi low-latency lock, real video
timestamps and the diagnostics overlay were all on). `stream_feedback_stats_log=true` is
added for the counters.

| arm | `stream_resolution` | `stream_bitrate` | requested kbps | console `target_bps` (median) |
|---|---|---|---|---|
| A (today's default, `LOW_LATENCY`) | 720p | 0 (auto) | 10000 | 9.61 M |
| B (`BALANCED` as written) | 1080p | 0 (auto) | 15000 | 14.56 M |
| C (resolution isolated) | 1080p | 10000 | 10000 | 9.71 M |

Each session log confirms the negotiated resolution (`Switched to profile 0, resolution:
1280x720` / `1920x1080`).

Order (both directions, 3 reps per arm, per PLE-198): `r1: A B C`, `r2: C B A`, `r3: B A C`,
captured 2026-09-22 08:05-08:17 phone time. Skin temperature drift was ≤ 0.6 °C per scenario.

## 2. Results

Latency in ms. The `q→p` columns are queue→present (codec output `queueBuffer` → HWC present,
`analyze_ab.py`). The `decode` columns are MediaCodec input→output on the `c2.exynos.hevc`
thread. `drops` are the session totals of `dropped_input + dropped_presenter +
dropped_bounded_age`, and `lost`/`unrec` the video frames lost and FEC-unrecoverable, all
from `Feedback stats`. `meas Mbps` is the median `measured_bps` the client received. `sharp`
is the variance of a Laplacian over a fixed text crop of `<s>_screen.png`.

| scenario | res | q→p avg | q→p p50 | q→p p99 | decode avg | decode p95 | stalls>100ms | fps in | drops | lost | unrec | meas Mbps | sharp |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| r1_A | 1280x720 | 7.76 | 7.52 | 12.80 | 6.73 | 8.75 | 2 | 55.8 | 6 | 136 | 21 | 4.59 | 692 |
| r2_A | 1280x720 | 7.85 | 7.79 | 12.38 | 6.60 | 8.54 | 0 | 59.8 | 0 | 0 | 0 | 4.44 | 690 |
| r3_A | 1280x720 | 7.84 | 7.64 | 12.37 | 6.70 | 8.59 | 0 | 59.8 | 0 | 0 | 0 | 4.85 | 687 |
| r1_B | 1920x1080 | 8.06 | 7.98 | 12.43 | 7.26 | 9.07 | 0 | 59.7 | 0 | 0 | 0 | 8.05 | 1742 |
| r2_B | 1920x1080 | 7.53 | 7.32 | 12.23 | 7.40 | 9.26 | 0 | 59.8 | 3 | 0 | 0 | 7.17 | 1711 |
| r3_B | 1920x1080 | 7.74 | 7.65 | 12.71 | 7.47 | 9.27 | 0 | 59.8 | 7 | 0 | 0 | 7.70 | 1685 |
| r1_C | 1920x1080 | 7.66 | 7.48 | 12.43 | 7.48 | 9.23 | 0 | 59.7 | 4 | 0 | 0 | 5.09 | 1693 |
| r2_C | 1920x1080 | 7.68 | 7.68 | 12.34 | 7.40 | 9.31 | 0 | 59.8 | 2 | 0 | 0 | 3.76 | 1744 |
| r3_C | 1920x1080 | 7.86 | 7.67 | 12.69 | 7.33 | 9.08 | 0 | 59.6 | 2 | 0 | 0 | 5.74 | 1712 |

Per arm (mean of 3; ranges in brackets):

| arm | q→p avg | q→p p99 | decode avg | decode p95 | drops | meas Mbps | sharp | decoder CPU ms | Takion CPU ms | app CPU ms |
|---|---|---|---|---|---|---|---|---|---|---|
| A 720p@10 | 7.82 [7.76-7.85] | 12.52 | **6.68** [6.60-6.73] | 8.63 | 2 | 4.63 | 689 | 4447 | 2349 | 15374 |
| B 1080p@15 | 7.78 [7.53-8.06] | 12.46 | **7.38** [7.26-7.47] | 9.20 | 3 | 7.64 | 1713 | 4460 | 2704 | 15730 |
| C 1080p@10 | 7.73 [7.66-7.86] | 12.49 | **7.40** [7.33-7.48] | 9.21 | 3 | 4.86 | 1716 | 4509 | 2338 | 15436 |

CPU columns are per 15 s trace, from `summary.md`.

### Attribution

- **Decode time comes from resolution, not bitrate.** C−A = +0.72 ms, B−A = +0.70 ms,
  B−C = −0.02 ms. Every 1080p rep (7.26-7.48) sits above every 720p rep (6.60-6.73), so the
  bands do not overlap. p95 moves by the same amount (+0.6 ms).
- **Queue→present does not move.** Arm means lie within 0.09 ms of each other, inside a
  single arm's rep spread (B: 7.53-8.06). The post-decode path (latch, HWC) does not care
  about stream resolution, since the SurfaceView scales in HWC either way.
- **Bitrate moves network and Takion work only.** B carries 65 % more measured bits than
  C, costs 15 % more Takion-thread CPU (2704 vs 2338 ms) and 636 vs 364 Takion packets/s.
  It does not change decode, present latency or sharpness on this content.
- **Drops.** 1080p shows 2-7 `dropped_input` per session (the decoder input thread's
  latest-frame handoff superseding a frame), against 0 in the two clean 720p reps. That is
  a real but tiny effect: ≤ 7 of ~2 900 frames (0.2 %), with no presenter or age drops.
- **r1_A's loss burst is not a resolution effect.** It is the first scenario of the round:
  six bursts of 20-25 lost frames at 20-40 s into the session (`r1_A_session.log`), with
  2 present stalls > 100 ms (433 ms max) inside the trace. Neither the other two 720p reps
  nor any of the six 1080p reps (up to 15 Mbps) lost a frame. It reads as a Wi-Fi blip. The
  720p means above include it; excluding r1_A changes the A decode mean by < 0.05 ms.

### Picture quality

The measurement is the variance of the Laplacian over a fixed 940×560 crop of the results
table text in each `<s>_screen.png` (`aggregate.py`, `CROP`). The on-screen gamepad overlay
is identical in every arm. Results: 720p 687-692, 1080p 1685-1744 at either bitrate. The
difference is visible to the eye: the driver names are soft at 720p and crisp at 1080p. B
and C are indistinguishable on this static scene, which is expected with measured bitrates
of 4-8 Mbps against 10-15 Mbps targets. The encoder was not starved in either arm.

## 3. What this does and does not measure

- **Measured:** phone-side decode and post-decode latency, drops, loss, bitrate and a
  sharpness proxy, on one static scene, on a clean LAN.
- **Not measured:**
  - PS5 encode time. There is no console-side timestamp, and 1080p encode is plausibly
    slower.
  - Wire time of the larger frames. At 7.6 Mbps and 60 fps a frame is ~16 KB against ~10 KB
    at 720p, a few hundred microseconds at typical Wi-Fi PHY rates. This is an estimate, not
    a measurement.
  - Glass-to-glass latency.
  - High-motion content, where 1080p@15 can be bitrate-bound and B vs C may diverge in
    quality and loss.

## 4. Recommendation

Promote `Preferences.streamQualityPresetDefault` to `StreamQualityPreset.BALANCED` (and
align `resolutionDefault`, `Preferences.kt:90`, which independently picks 720p) in a
follow-up ticket. The measured cost is +0.7 ms decode and a ~0.2 % `dropped_input` rate. The
measured gain is a ~2.5x sharper picture, with no present-latency, loss or thermal penalty.

Keep `BALANCED`'s auto 15000 kbps. On this scene it bought nothing over 10000 and cost 15 %
more Takion CPU. Whether it pays off on motion is the one open question. Before promoting,
run one high-motion confirmation round: 3 arms × 2-3 reps with a race replay playing on the
console (`AB_NET_PROFILE=clean`, then `wifi-slow`). If B shows loss or stalls that C does
not, ship `BALANCED` with a 10000 kbps preset bitrate instead of the auto 15000.

## 5. Reproduce

```bash
export AB_CAPTURES=/home/wnt/gta6/build/dispatch/ple-517/captures
export AB_PYTHON=/home/wnt/gta6/build/dispatch/ple-5/venv/bin/python
source "$(scripts/dev/ab/round.sh ple-517-round)"
# round.sh's env still points AB_IMPAIRCTL at chiaki-ng/scripts/net/impairctl.py, which no
# longer exists; ab.sh then exits 2 before launch. Override it:
export AB_IMPAIRCTL=/home/wnt/gta6/scripts/net/impairctl.py
COMMON="stream_feedback_stats_log=boolean:true stream_performance_mode=boolean:false \
  stream_wifi_low_latency_lock=boolean:false stream_diagnostics_overlay=boolean:false \
  stream_real_video_timestamps=boolean:false stream_thread_priority_boost=boolean:false \
  stream_fps=string:60 stream_codec=string:h265"
$AB/mkprefs.py --pull-base
$AB/mkprefs.py $AB_CAPTURES/prefs_A.xml $COMMON stream_resolution=string:720p  stream_bitrate=int:0     stream_quality_preset=string:low_latency
$AB/mkprefs.py $AB_CAPTURES/prefs_B.xml $COMMON stream_resolution=string:1080p stream_bitrate=int:0     stream_quality_preset=string:balanced
$AB/mkprefs.py $AB_CAPTURES/prefs_C.xml $COMMON stream_resolution=string:1080p stream_bitrate=int:10000 stream_quality_preset=string:balanced
scripts/dev/device.py run PLE-517 -- bash -c '$AB/ab.sh r1_A 0 $AB_CAPTURES/prefs_A.xml; ...'
$AB_PYTHON $AB/summarize.py
python3 docs/verification/PLE-517/aggregate.py $AB_CAPTURES
```
