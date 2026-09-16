# PLE-73 — PLE-16 frame-index wraparound + PTS validation (20+ minute soak)

Answers the PLE-16 follow-up (absorbs PLE-33): does the frame-index PTS unwrapper stay
monotonic across the PS5's 16-bit frame-index wrap (65 536 frames, ~18.2 min at 60 fps)?

**Verdict: pass.** The unwrapper crosses the wrap cleanly — one wrap event, decode and
pacing statistics identical before/after, no decoder error or reset, picture confirmed
moving with the setting on. No fix needed; PLE-16's real-PTS path is safe to keep
enabled across arbitrarily long sessions.

## Hardware and build

| | |
|---|---|
| phone | Galaxy S22 Ultra, SM-S908B, serial `192.168.1.105:36437` |
| console | PS5-466, `192.168.1.164:9302`, `id=C0151B3DD8BC system=13600007 ready` |
| build | `jonni/ple-73-…` at `1757914d` (PLE-73 wrap-log commit), `assembleDebug`, installed with `install -r` |
| date | 2026-09-16, 11:02:44–11:27:27 UTC (device local log time) |
| harness | `scripts/dev/ab/soak.sh` (start / drive×3 / collect) + `scripts/dev/ab/freeze_test.sh`, workspace repo |

`scripts/dev/app-state.sh backup` ran first:
`/home/wnt/.config/pleikkari/backups/fork-app-20260916T080203Z/data.tar`
(`registered_host rows=1 nickname=PS5-466`).

## Why a code change was needed first

`chiaki_seq_num_16_unwrap()` (`lib/include/chiaki/seqnum.h`) never logged anything, so
there was no way to see the wrap happen from logcat/session log. This branch adds one
default-off `CHIAKI_LOGI` in `android_chiaki_video_decoder_queue_sample()`
(`android/app/src/main/cpp/video-decoder.c`) that fires exactly when the unwrapped
64-bit counter crosses a 65536 boundary, gated behind the existing stats-log flag
(`decoder->stats_log_enabled`, driven by `stream_feedback_stats_log` /
diagnostics-overlay preference — no new setting added):

```c
uint64_t previous_unwrapped_frame_index = decoder->frame_index_unwrapper.value;
uint64_t unwrapped_frame_index = chiaki_seq_num_16_unwrap(&decoder->frame_index_unwrapper, frame_index);
if(decoder->stats_log_enabled && (previous_unwrapped_frame_index >> 16) != (unwrapped_frame_index >> 16))
    CHIAKI_LOGI(decoder->log, "Frame-index unwrapper wrap: raw=%u unwrapped=%" PRIu64,
            frame_index, unwrapped_frame_index);
```

Off by default; costs one extra field on the decoder struct and a branch per queued
sample when the stats flag is off.

## Method

One continuous session, `stream_real_video_timestamps=true` (PLE-16) and
`stream_feedback_stats_log=true` (to enable the new wrap log and the 1 Hz `Feedback
stats:` / `Video presenter DJB` lines), 1280×720 stream, `soak.sh start` then three
`drive` chunks of 480 s each (24 min of continuous synthetic stick input, the worker's
Bash calls are bounded to 10 min) followed by `soak.sh collect`. No human was available,
so input is **synthetic touch on the on-screen stick**, not a physical DualSense.

`freeze_test.sh` ran afterward as a separate short stream with the same prefs (it
restarts the app, so it is not literally mid-wrap in the same session — it confirms
the real-PTS path renders a live, moving picture in general, not specifically the
instant after this session's wrap).

## Result

Wrap observed once, at 11:21:03.466, 18 min 19 s (1099 s) after stream start — matches
the predicted 65536/60 = 1092.3 s (18.2 min) within measurement resolution (the wrap
log fires on the first *decoded* frame past the boundary, not the instant the PS5 sent
it).

```
09-16 11:02:44.519  Chiaki  : Frame-index video timestamps enabled at 60 fps (PTS timeline 60 Hz)
09-16 11:21:03.466  Chiaki  : Frame-index unwrapper wrap: raw=0 unwrapped=65536
```

Only one wrap line for the whole 24-minute/~85 000-frame session — correct, since
frame indices only cross a 65536 boundary once every ~18.2 min.

| | before wrap (11:02:47.9–11:21:02.8, 1095.9 s) | after wrap (11:21:03.8–11:27:24.1, 381.3 s) |
|---|---|---|
| `Feedback stats:` windows | 1095 | 381 |
| video received (sum) | 65 369 | 22 816 |
| video decoded (sum) | 65 366 | 22 817 |
| effective decode fps | 59.65 | 59.84 |
| cumulative `lost` at segment end | 195 | 195 (+0 across the wrap) |
| cumulative `dropped_input` at segment end | 2 | 2 (+0) |
| cumulative `reorder_timeouts` at segment end | 4 | 4 (+0) |
| `Video presenter DJB` windows | 545 | 190 |
| DJB err p50 mean | 62.85 ms | 64.00 ms |
| DJB err p99 mean | 63.04 ms | 64.00 ms |
| DJB decode-time mean | 5.37 ms | 5.44 ms |
| DJB windows within ±6 s of the wrap (11:20:59–11:21:09) | `D=32.0 ms target=32.0 ms err p50/p99 64.0/64.0 ms decode 5.3–5.7 ms drops 0` unchanged across all 6 samples | — |
| decoder error / `CodecException` / reset near the wrap (±5 s) | 0 | 0 |
| `[E]`/`[W]` in session log within ±5 s of the wrap | 0 | 0 |
| freeze test (separate short run, same prefs) | t0→t3 diff 0.405, t3→t6 diff 0.408 (live-scene range from PLE-47 was 0.28–0.47; 0.000 = frozen) | — |

PTS increment: with `stream_real_video_timestamps=true`,
`presentation_time_us = unwrapped_frame_index * 1 000 000 / 60`, so every unit step of
the unwrapped frame index is **exactly 16.667 ms** by construction (this is an
arithmetic identity, not something that drifts with the wrap) — a lost-frame gap of *n*
frame indices is therefore *n* × 16.667 ms by the same identity. The soak's job was to
confirm the unwrapper keeps producing a correctly-accumulating frame index across the
16-bit rollover rather than resetting or going non-monotonic, which the single clean
wrap line, the flat `lost`/`dropped_input`/`reorder_timeouts` counters and the unchanged
DJB pacing error across the boundary all confirm.

Session-wide (24 min 40 s, before + after): 88 185 frames decoded, 88 186 received, 195
lost total (0.22%), 4 reorder timeouts, 2 dropped_input, 0 dropped_presenter, 0 decoder
errors, 0 `CodecException`. The `[W]`/`[E]` lines that did occur (FEC failures, missing
FEC units, a handful of corrupt-frame reports) are ordinary Wi-Fi-level packet loss
scattered through the whole session — none fall within 5 s of the wrap timestamp.

## What this does not prove

* Not a physical DualSense — synthetic on-screen swipes only, same limitation as
  PLE-57.
* `freeze_test.sh` ran as its own short stream after the soak, not literally the frame
  immediately following this session's wrap — it corroborates the general claim
  ("real PTS does not freeze the picture"), not "the exact next frame after the wrap
  rendered".
* No Perfetto trace was captured this round (soak.sh does not take one), so there is no
  frame-by-frame `analyze_ab.py` decode-latency table spanning the wrap — the PTS
  increment claim is established analytically (see above) plus the unchanged pacing/
  loss counters, not from a per-frame trace.
* Single run, single network condition, single night.

## Artifacts

`build/dispatch/ple-73/captures/` (gitignored, not committed):
`ple73_wrap_session.log`, `ple73_wrap_logcat.txt` (29 MB), `ple73_wrap_prefs_on_device.xml`,
`ple73_wrap_hosts.png`, `ple73_wrap_end.png`, `ple73_wrap_postwrap_t0/t3/t6.png`,
`ple73_wrap_postwrap_logcat.txt`, `prefs_realpts_wrap.xml`, `feedback_lines.txt`
(parsed `Feedback stats:` lines).
