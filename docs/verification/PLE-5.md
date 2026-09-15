# PLE-5 — Baseline Perfetto/logcat measurement of the chiaki fork on the Galaxy S22 Ultra

## Run identity

- Worker: Claude, `claude-sonnet-5`; CT950 (attempt 3; attempts 1-2 blocked on PS5 registration,
  cleared by the coordinator before this run — see Linear comments on PLE-5).
- Worktree: `/home/wnt/gta6/wt/ple-5`; branch
  `jonni/ple-5-baseline-perfettologcat-measurement-of-the-chiaki-fork-on`.
- Captured 2026-09-16 01:31-01:32 UTC device clock (host clock 2026-09-15 22:31-22:32).
- Device: **SM-S908B (Galaxy S22 Ultra)**, Exynos 2200, Android 16 / SDK 36, not rooted,
  `ro.serialno` **R3CT30WLVFV**, Wi-Fi ADB transport `192.168.1.105:36437`.
- Console: **PS5-466**, 192.168.1.164:9302, host-id C0151B3DD8BC, `ready`
  (probed with `scripts/dev/ps5-discover.py` before anything else).
- App under capture: `com.metallic.chiaki`, the PLE-25 build at `fc0ef30d`.
  **Installed APK SHA-256 (re-pulled and re-hashed from the device, matches the ticket):**
  `fe076f711aa32b13d3823df190e64277834255191eb009747537149c148412e1`.
- `com.metallic.chiaki`'s `chiaki` sqlite database (read via `run-as`, including the WAL file)
  has one `registered_host` row for PS5-466; `StreamActivity` was foreground and a stream was
  already live at the start of this run. No new PIN registration was performed or needed.
- No code was changed. No branch was pushed or merged.

## Artefacts

All under `/home/wnt/gta6/build/dispatch/ple-5/captures/` (gitignored, never committed):

```
chiaki_trace.cfg                        gfn_trace.cfg with atrace_apps=com.metallic.chiaki
chiaki_start.perfetto-trace             scenario 1: stream (re)start, 15 s
chiaki_untouched.perfetto-trace         scenario 2: steady state, overlay untouched, 15 s
chiaki_touched.perfetto-trace           scenario 3: steady state, continuous touch-overlay use, 15 s
logcat_full.txt                         threadtime logcat spanning all three scenarios
sf_full_{start,untouched,touched}.txt   dumpsys SurfaceFlinger
sf_list_{untouched,touched}.txt         dumpsys SurfaceFlinger --list
display_{start,untouched,touched}.txt   dumpsys display
gfxinfo_{start,untouched,touched}.txt   dumpsys gfxinfo com.metallic.chiaki
window_displays_{untouched,touched}.txt dumpsys window windows
analyze_video_latency.py                queueBuffer -> latchBuffer -> HWC present + frame timeline
analyze_frames_cpu.py                   frame timeline jank, decode-thread CPU, per-thread CPU
analysis_{start,untouched,touched}_video.txt        analyze_video_latency.py output, saved
analysis_{start,untouched,touched}_frames_cpu.txt   analyze_frames_cpu.py output, saved
```

The analysis scripts mirror `android-low-latency-examples/geforcenow-apk/runtime-capture/analyze.py`
and `analyze4.py` (same queries, `like` patterns adjusted for `com.metallic.chiaki` /
`StreamActivity` / `NDK MediaCodec%`), and follow the merge-join fix PLE-21 already made for this
same device (`psremoteplay-apk/runtime-capture-s22/analyze4fast_s22.py`): the GFN-inherited
correlated-subquery SQL is quadratic and did not finish in reasonable time here either, so
queue->latch->present matching is done with a Python `bisect` merge instead. Same quantities,
different implementation.

## How the three scenarios were captured

1. **Stream start**: with the app already on the PS5 host list (registered), backed out of the
   live `StreamActivity` to the host list (`adb shell input keyevent 4`), started `perfetto`, then
   tapped the PS5-466 tile (`adb shell input tap 998 232`) to reconnect while the trace ran.
2. **Steady state, untouched**: 15 s Perfetto trace + dumpsys snapshots on the already-running
   stream, no synthetic input.
3. **Steady state, touched**: same, with `adb shell input swipe` repeated once/second for ~13 s of
   the 15 s trace to simulate continuous touch-overlay use.

## The metrics (PLE-5 acceptance)

### 1. Decoder name

`c2.exynos.hevc.decoder` for video (confirmed in logcat: `CCodec: allocate(c2.exynos.hevc.decoder)`,
`MediaCodec: [c2.exynos.hevc.decoder] setting surface generation...`), `c2.android.opus.decoder`
for audio. Same Exynos MFC decoder family PLE-21 found for PS Remote Play on this phone; `dumpsys
media.codec` does not exist on this device, so the name comes from the app's own log lines, as the
recipe anticipated.

### 2. Display mode during stream

`mActiveModeId=8` throughout all three captures: **1080x2316 (FHD+ group), 120.00001 Hz vsync**
(`DisplayDeviceInfo` `modeId 8`, `renderFrameRate 120.00001`). Unchanged across stream restart and
both steady-state captures — the fork does not appear to change display mode on stream
start/stop in this build (PLE-7 lands that behaviour later; this is the pre-PLE-7 baseline).

### 3. queueBuffer-to-present latency distribution

| | stream start (n=700) | steady, untouched (n=880) | steady, touched (n=882) | GFN on S25 | PS Remote Play on S22 (PLE-21, steady) |
|---|---|---|---|---|---|
| queueBuffer -> latch, avg | 6.16 ms | 6.25 ms | 5.48 ms | 11.7 ms | 6.51 ms |
| latch -> HWC present, avg | 2.38 ms | 2.41 ms | 1.86 ms | 1.1 ms | 3.26 ms |
| queue -> present, avg | 8.55 ms | 8.66 ms | 7.35 ms | - | 9.77 ms |
| queue -> present p50/p90/p99 | 8.47/12.08/13.23 ms | 8.56/12.45/13.47 ms | 7.35/10.73/14.69 ms | - | 9.65/13.36/17.83 ms |

Full histograms in `analysis_*_video.txt`. The chiaki fork's queue->present latency is close to
PLE-21's PS Remote Play numbers on the same phone (both ~8-10 ms average) and noticeably tighter
than the GFN/S25 queue->latch figure — expected, since GFN's queue->latch is deliberately
timestamped (design choice), while chiaki and PS Remote Play both release buffers immediately.

Frame-timeline (Android's own expected-vs-actual API) data was only available for the **stream
start** scenario: avg 8.43 ms, n=266 (min 2.39, max 177.73 ms, the tail driven by the initial
connection ramp-up). For the two steady-state traces `actual_frame_timeline_slice` /
`expected_frame_timeline_slice` returned **zero rows** for this process — no join was possible.
This matches PLE-21's finding on the same device/app family (`actual_frame_timeline_slice.layer_name`
is NULL for `com.playstation.remoteplay` because the video layer is Secure); the chiaki video
layer is very likely Secure too, and SurfaceFlinger's frame-timeline history for it appears to not
survive long enough for a long-running session to still have entries when the trace starts, even
though it worked for the few seconds right after a fresh `BLASTBufferQueue` was created at stream
start. Treat the frame-timeline number as directional only, not a steady-state baseline.

### 4. Missed vsync count

- Frame-timeline `present_type` (only available for the stream-start capture): 159/266 frames
  `Late Present`, 105 `On-time Present`, 2 `Dropped Frame` — i.e. ~60% of frames in the first 15 s
  after reconnect land late relative to their predicted vsync. This window includes the
  post-handshake ramp-up, not steady playback.
- `dumpsys gfxinfo com.metallic.chiaki` **Number Missed Vsync** (cumulative since app process
  start, Android's own counter, works regardless of the frame-timeline gap above): **4** at the
  stream-start snapshot (fresh process/session), **2** at both the untouched and touched snapshots
  (same long-running session, dumped seconds apart — the count did not move between them).

### 5. Decode thread CPU time

Video decode (`c2.exynos.hevc.decoder`) NDK MediaCodec thread running time within each 15 s trace:

| scenario | tid | running time | % of one core |
|---|---|---|---|
| stream start | 9196 | 1779 ms | 12.0% |
| steady, untouched | 8363 | 2192 ms | 14.6% |
| steady, touched | 8363 | 1043 ms | 7.0% |

(Audio, `c2.android.opus.decoder`, ran on a separate NDK MediaCodec thread — 2094/2534/1252 ms in
the same order — not part of "decode thread" for this metric but visible in
`analysis_*_frames_cpu.txt` if needed.)

### 6. Overlay redraw rate while touched

**Not conclusively measured.** `dumpsys gfxinfo` "Total frames rendered" was identical (285) between
the untouched and touched snapshots taken seconds apart in the same session — i.e. the synthetic
`adb shell input swipe` touch sequence produced no measurable increase in UI-layer redraws, unlike
GFN's virtual gamepad (~73 fps while held, GFN `ANALYSIS.md`). `StreamActivity.kt` does reference
on-screen touch controls, so a redrawing overlay exists in the code; either the synthetic swipe
coordinates did not land on an interactive control that calls `invalidate()`, or this build's touch
handling doesn't redraw a Canvas overlay at all (e.g. if touch is forwarded straight to the stream
as trackpad/gyro input without a visible virtual control). **This needs a retry with a real touch
on a known on-screen button (or a `dumpsys gfxinfo` sample restricted to the overlay's own View),
not a blind swipe** — flagged under "Not proven" below rather than reported as a number.

### 7. Takion thread wakeups per second

- **Log-confirmed** (stream-start trace only): `Takion connecting (version 7)` at 01:32:32.570
  tagged tid **9192**. That thread shows 44 scheduler wake events over the 14.77 s trace (**~3.0/s
  overall**), clustered as a ~2.2 s handshake burst (38 wakeups between 0.41-2.62 s post-trace-start)
  followed by a steady **~1 wakeup/s** for the rest of the window — consistent with `takion_thread_func`
  (the single thread chiaki spawns for the whole Takion/SCTP session, `lib/src/takion.c:440`) idling
  in a periodic (likely 1 s select-timeout) housekeeping loop rather than draining every packet
  itself.
- For the two steady-state traces the Takion connection predates the logcat capture window (the
  stream was already live when this run started), so its tid could not be confirmed the same way.
  The most active otherwise-unidentified thread in that session, tid 8394 ("Thread-13"), shows
  741-850 wakeups/s in the untouched/touched traces — a plausible Takion candidate by elimination
  (it isn't `RenderThread`, `NDK MediaCodec_`, `CodecLooper`, `AAudio_1`, or a `binder:` thread, and
  it is the single highest-activity thread besides those), but **this identification is not
  log-confirmed** and is reported at low confidence. Given the same tid appears consistently across
  both same-session captures, if it is Takion, packet-level wakeup rate during active gameplay is
  two-to-three orders of magnitude above the confirmed handshake-adjacent ~1/s.

## What this decides for other tickets

- **PLE-6** (decoder low-latency config): confirms the same `c2.exynos.hevc.decoder` target as
  PLE-21 found for PS Remote Play; that ticket's finding (`algo.low-latency` supported but off, 16
  ms output delay) is the same component and should transfer.
- **PLE-7** (display mode / refresh rate matching): this build does not change display mode around
  stream start in either direction — mode stayed at id 8 (120 Hz) throughout. PLE-7's own
  verification doc should describe the *before* state as "no mode change", matching this baseline.
- **PLE-9** (output-thread late-frame drop / backlog IDR): the stream-start scenario's 60%
  late-present rate and its 177 ms present-latency tail are exactly the backlog symptom that ticket
  targets; the steady-state number (queue->present avg ~8-9 ms, tight histogram) is the target to
  return to once a stream has stabilized.
- **PLE-8** (Takion reorder queue): the ~1 Hz steady wakeup rate on the log-confirmed Takion thread,
  if representative, suggests the receive loop itself is not obviously busy-polling; if the higher
  741-850/s candidate thread from the untouched/touched traces is confirmed as Takion in a follow-up
  capture, that would be the more relevant number for a reorder-queue-hold analysis.

## Verification run here

- `scripts/dev/gate.sh` on this worktree — see the final report for the result.
- Device: held for the whole run via `scripts/dev/device.py run PLE-5 -- <command>` (`device.py
  status` showed holder `PLE-5` throughout).
- Emulator smoke: not applicable — this ticket changes documentation only, and the emulator proves
  nothing about decoder, HWC composition, vsync timing or panel refresh mode (AGENTS.md rule 6).

## Limits of this capture

- One device, one console, one game session (Astro Bot), 15 s per scenario. Not a statistical study.
- The touch scenario used `adb shell input swipe`, not a human thumb on a known control; unlike
  PLE-21's swipe (which did land on the PS Remote Play virtual d-pad and showed a clear overlay
  effect), this run's swipe produced no measurable overlay-redraw change — see metric 6 above.
- Frame-timeline (`actual_frame_timeline_slice`) data was only present for the stream-start
  scenario; the steady-state missed-vsync and expected-present figures fall back to `dumpsys
  gfxinfo`'s cumulative counter, which is coarser (see metric 4).
- Takion thread identification is only log-confirmed for the stream-start scenario; the
  steady-state number is a best-effort, low-confidence candidate (metric 7).
- Nothing here measures end-to-end (controller-to-photon) latency; it measures the on-device
  present path only, same scope as PLE-21.
