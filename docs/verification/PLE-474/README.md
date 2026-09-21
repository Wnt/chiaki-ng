# PLE-474: a frame that lost every unit now counts in `video_frames_lost`

**Answer up front.** The client can tell that a frame was due and never arrived, but only
after the fact. The console numbers video frames with a 16-bit `frame_index` that goes up
by one per frame. So when the next frame arrives, the gap shows exactly how many frames
delivered nothing. `frames_lost_total`, which feeds `video_frames_lost` and the logcat
`lost`, now counts those frames. Under `roam-3000ms` it rises by 181–182 frames per 3 s
blackout; before this change it rose by about 2. Under `clean` it stays 0.

## 1. How the client knows a frame was due

- **Every AV packet carries the frame number.** `ChiakiTakionAVPacket.frame_index`
  (`lib/include/chiaki/takion.h`) is a `ChiakiSeqNum16`, parsed from each packet.
- **The receiver already relies on consecutive numbering.** `chiaki_video_receiver_av_packet()`
  (`lib/src/videoreceiver.c`) works out `next_frame_expected = frame_index_prev_complete + 1`.
  When a later frame shows up, it logs `Detected missing or corrupt frame(s) from A to B` and
  tells the console the skipped range with `stream_connection_send_corrupt_frame()`. That
  CORRUPTFRAME message only makes sense if the numbering has no holes.
- **Measured cadence.** On the clean phases of `build/captures/ple404b` and
  `build/captures/ple474`, the index advances at 59.95 frames/s (10253→11528 in 21.27 s) and
  there are no gaps at all.
- **Gaps match blackout widths.** On `ple404b`, `roam-1200ms` blackouts leave gaps of 74
  frames and `roam-3000ms` blackouts leave gaps of 182 frames.

What this cannot do:

- **It is retroactive.** The count moves when the stream resumes, not while the blackout
  lasts. Live detection stays with PLE-464's receive-gap field.
- **A blackout that never ends is never counted.** If the PLE-423 watchdog quits the stream,
  those frames never appear in the total.
- **It counts frames, not units.** A frame that never arrived has an unknown unit count, so
  the unit counter cannot be corrected honestly.

Cost: two `int32_t` per receiver and a few comparisons per *frame*, not per packet.

## 2. The change

`ChiakiFrameLossTracker` (`videoreceiver.h`/`.c`) records the last frame index that arrived
and the highest index already counted. `frames_lost_total` gains:

- **Frames with zero units:** the gap between the previous arrived frame and a new one,
  counted when the new frame arrives.
- **FEC failures and missing references:** as before, except that each frame index is now
  counted once. Before, the FEC path added the whole span from the last *complete* frame, so
  back-to-back failures kept re-adding the same frames (1 + 2 + 3 for three in a row).

Not changed:

- **The per-sample `frames_lost` passed to `video_sample_cb`.** Android ignores it
  (`video-decoder.c`); the Qt GUI shows it as dropped frames.
- **The unit counter, `takion_partial_frame_units_missing`**, and the packet stats behind it.
- **The CORRUPTFRAME and IDR messages to the console.**

## 3. Who reads the counter, and what changes

| reader | before → after |
|---|---|
| logcat `Feedback stats: … lost N` (session-cumulative) | rises by a blackout's frames once it ends. `scripts/dev/ab/summarize.py` reads it as `takion_loss` and takes the max, which is still correct |
| overlay `lost %d` (`StreamDiagnostics.kt`) | the same total, shown live |
| `StreamSummary` "drops" on the home card | it used to add the *running total* on every 1 Hz event. With this change that would have re-added a 182-frame blackout every second for the rest of the session, so it now adds only the growth since the last event (`StreamSummaryTest.videoFramesLostCountsOnlyItsGrowth`). The other two terms have the same flaw; that is a follow-up |
| quality badge (`NetworkQuality.kt`) | **none**: it does not read `videoFramesLost`. PLE-464's stall arm and PLE-476's cuts are untouched |
| adaptive loss report / congestion packet (PLE-365) | **none, before or after.** `congestioncontrol.c` reads `chiaki_packet_stats_get()` (video units from partly-arrived frames plus audio sequence gaps), never `frames_lost_total`. The toggle defaults off (`stream_adaptive_loss_report` is not set on the test phone). With it on, it would still behave exactly as before |
| Qt desktop GUI (`gui/src/streamsession.cpp`) | takes the delta of the total. It gains the zero-unit frames the same way |

A corrected frame-loss figure would **not** fire the badge, because the badge never reads it.
Whether the classifier *should* read it next to the stall arm is a separate decision, not
made here.

## 4. Device capture

`build/captures/ple474`:

- **Setup:** SM-S908B `192.168.40.101:5555`, PS5-466 `192.168.1.164`. The APK was built from
  `28614fa30161` (PLE-410's freshness guard: OK). App state was backed up, then installed with
  `install -r`. The rig's guest script was IN SYNC, and it was left `clean` (`net_final_clean.txt`).
- **Run:** `clean → roam-3000ms → clean`, 120 s per phase, driven by PLE-476's `capture.sh`.
  The pattern check passed with 5 outages of 3–4 s.

| phase | `lost` over the phase | frame-index gaps |
|---|---|---|
| 01_clean | 0 → 0 | none |
| 02_roam-3000ms | 0 → 976 (182, +8, +195, +199, +191, +201) | 10255..10435, 11637..11818, 13019..13200, 14401..14581, 15782..15962 (181–182 frames each) |
| 03_clean | 976 → 976 | none |

Each step is the blackout's zero-unit frames (181–182) plus the P-frames after it that arrived
without their reference (8–19). The `roam-3000ms` phase of `build/captures/ple464` ran on the
old build over the same five-blackout pattern: `lost` went from 33 to 215. The only big jump,
+60/+60/+26, came from back-to-back FEC spans, not from the blackouts. The other outages added
about 10 each.

Reproduce:

```bash
OUT_DIR=build/captures/<yours> PROFILES="clean roam-3000ms clean" \
  scripts/dev/device.py run <ticket> -- bash docs/verification/PLE-476/capture.sh
```

## 5. Host tests

The `test/frameloss.c` suite (`/chiaki/frame_loss/*`) covers:

- a steady stream across the 16-bit wrap
- the first frame of a stream
- fully-lost frames, including ple404b's 74-frame gap and a gap that crosses the wrap
- the ple404b sequence of undecodable frames after a gap, each counted once
- back-to-back FEC failures
- a run of more than 32768 frames with no loss
- a partly-lost frame, through the real frame processor: its missing units reach the unit
  counter and it counts as one lost frame, while the nine fully-lost frames after it reach
  the frame count and add nothing to the unit counter
