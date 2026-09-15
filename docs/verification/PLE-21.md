# PLE-21 — PS Remote Play runtime capture on the Galaxy S22 Ultra

## Run identity

- Worker: Claude, `claude-opus-5`; CT950.
- Worktree: `/home/wnt/gta6/wt/ple-21`; branch
  `jonni/ple-21-runtime-capture-of-ps-remote-play-on-the-galaxy-s22-ultra`.
- Captured 2026-09-15 21:13–21:17 UTC (device clock 2026-09-16 00:13–00:17).
- Device: **SM-S908B (Galaxy S22 Ultra)**, Exynos 2200 (`ro.soc.model` s5e9925,
  `ro.board.platform` universal9925), Android 16 / SDK 36, not rooted,
  `ro.serialno` **R3CT30WLVFV**, Wi-Fi ADB transport `192.168.1.105:36437`.
- Console: **PS5-466**, 192.168.1.164:9302, host-id C0151B3DD8BC, system 13600007, `ready`
  (probed with `scripts/dev/ps5-discover.py` before anything else; a live stream ran throughout).
- App under capture: `com.playstation.remoteplay` 9.1.0 (`versionCode` 9010000), uid 10203.
- No code was changed. No branch was pushed or merged.

## Artefacts

Traces, logcat and dumps (workspace repo, gitignored bulk area — AGENTS.md rule 11):

```
android-low-latency-examples/psremoteplay-apk/runtime-capture-s22/
  README.md                      how the capture was taken and how to re-run the analysis
  device.txt                     serial + getprop, copied into each scenario dir
  psrp_trace.cfg                 gfn_trace.cfg with atrace_apps=com.playstation.remoteplay
  analyze_s22.py analyze23_s22.py analyze4_s22.py analyze4fast_s22.py  _common.py
  analyze23_all.txt analyze4_all.txt                                   analysis output
  scenario1-stream-start/  device.txt psrp1.perfetto-trace logcat_full.txt
                           logcat_codec_session.txt logcat_ringbuffer_all.txt display.txt sf_list.txt
  scenario2-steady-state/  device.txt psrp2.perfetto-trace logcat_full.txt sf_full.txt sf_list.txt
                           display.txt gfxinfo.txt window_displays.txt uiautomator.xml
                           topactivity.txt screencap_secure_black.png
  scenario3-steady-touch/  device.txt psrp3.perfetto-trace logcat_full.txt
                           sf_full_gamepad.txt sf_list_gamepad.txt gfxinfo_gamepad.txt display_gamepad.txt
```

Write-up with the four answers, the metric table and the S22-vs-S25 list:
**`android-low-latency-examples/psremoteplay-apk/ANALYSIS.md`, section "S22 Ultra runtime (PLE-21)"**.
That file lives in the workspace repo (`Wnt/pleikkari-android`), not in this fork, so it cannot be
committed on this branch; this document is the copy that lands with the fork.

## The four answers

1. **Decoder:** `c2.exynos.hevc.decoder` (Samsung vendor Codec2 store, MFC via `/dev/video6`,
   `SEC_HW_H265`). HEVC branch only; the avc variant never appears in any log. Stream is
   1920x1080 8-bit. `dumpsys media.codec` does not exist on this device, so the name comes from
   logcat, confirmed on two independent stream starts.
2. **The `low-latency` string key is silently ignored, not rejected.** `configure()` succeeds and
   nothing is logged. `algo.low-latency` stays `0` for the whole session, even though the Exynos
   component supports the parameter (it is in the component's own queried config at `initialize()`).
   The effective mode is the opposite of low latency: output delay goes 8 → **16** after CSD parsing
   (`updateC2Config_OutputDelay output delay(16)`, `updating max output delay 23`). The Qualcomm
   key `vendor.qti-ext-dec-picture-order.enable` is dropped too — it does not exist on Exynos.
3. **Display mode: the app votes the highest refresh rate, 120 Hz, on the video layer itself**
   (`SurfaceFlinger: [...SurfaceView[...]...] setFrameRate: 120.000008, Default, OnlySeamless`;
   SF `scored - choose 120.00 Hz`; steady-state `activeMode={id=7, 1080x2316, vsyncRate=120.00 Hz}`,
   `renderRate=120.00 Hz`). The **cadence is not 120 Hz**: the stream is 60 fps, and with the overlay
   untouched SF composites 60×/s (HWC present interval 16.4–16.9 ms). With the overlay in use the
   overlay redraws at ~104 fps and SF composites 120×/s (8.0–8.6 ms).
4. **queueBuffer → present, untimestamped** (15 s per trace):

   | | stream start | steady state | steady + touch | GFN on S25 |
   |---|---|---|---|---|
   | queueBuffer → latch, avg | 6.40 ms | **6.51 ms** | 4.94 ms | 11.7 ms (timestamped) |
   | latch → HWC present, avg | 16.18 ms | **3.26 ms** | 1.68 ms | 1.1 ms |
   | queue → present, avg | 22.58 ms | **9.77 ms** | 6.62 ms | — |
   | queue → present p50/p90/p99 | 10.11/13.99/311.0 ms | 9.65/13.36/17.83 ms | 6.63/10.04/11.32 ms | — |
   | video layer composition | DEVICE (HWC) | DEVICE (HWC) | DEVICE (HWC) | DEVICE (HWC) |

   Steady state is a broad flat 5–14 ms plateau — the signature of untimestamped
   `releaseOutputBuffer(index, true)` landing at a uniformly random phase in the 16.6 ms composite
   period. The same path with SF composing at 120 Hz narrows to avg 6.62 ms / p99 11.32 ms.

## What this decides for other tickets

- **PLE-7:** the reference client chooses *highest refresh rate*, and the traces show why — but the
  vote alone is not enough. When the video is the only moving layer SF still composites at the
  content rate, so a fork that votes 120 Hz should expect the 9.77 ms steady-state figure, not the
  6.62 ms touch-scenario one.
- **PLE-6:** the official client's low-latency key is dead code, and `algo.low-latency` on
  `c2.exynos.hevc.decoder` is real, supported and currently off with a 16-frame output delay.
  Set it with `setInteger` on API 30+ behind a `Build.VERSION.SDK_INT` check (`minSdk` is 24).
- Anything in the GFN study resting on `c2.qti.*`, on `vendor.qti-ext-dec-*` or on the S25's 80 Hz
  mode does not transfer to this phone.

## Verification run here

- `scripts/dev/gate.sh` on this worktree — see the final report for the result.
- Emulator smoke: not applicable. This ticket changes documentation only and the emulator proves
  nothing about decoder, HWC composition, vsync timing or panel refresh mode (AGENTS.md rule 6).

## Limits of this capture

- One device, one console, one game session, 15 s per scenario. Not a statistical study.
- The touch scenario was driven by `adb shell input swipe` on the overlay d-pad, not by a human
  thumb; event rate (1455 MOVE / 51 DOWN / 50 UP over 15 s) is representative but synthetic.
- `actual_frame_timeline_slice.layer_name` is NULL for this app because the video layer is Secure,
  so the GFN per-layer jank table could not be reproduced directly; present cadence and composition
  were read from `HwcPresentOrValidateDisplay` and the SF composition slices instead.
- Nothing here measures end-to-end (controller-to-photon) latency; it measures the on-device
  present path only.
