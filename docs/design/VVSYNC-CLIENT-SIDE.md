# Can chiaki-ng infer a vvsync-like trigger client-side?

**Ticket:** PLE-108 (follow-up of PLE-82 [`GFN-VVSYNC.md`](../../../android-low-latency-examples/GFN-VVSYNC.md),
input to PLE-83 `docs/design/DEJITTER.md`). **Method:** source reading on CT950 only.
No phone, no PS5, no GFN session. Every number below that is not a literal from the
source is a *proposed* threshold, not a measured one.

**Scope.** This note defines the **trigger** — the signal, its thresholds and its
hysteresis — and what the presenter would do while the trigger is active. It does not
define the steady-state de-jitter policy (PLE-83) and it does not implement the
release-lead knob (PLE-106, in flight). No fork code is changed by this ticket.

---

## 0. One-paragraph answer

**Yes, but it is a strictly worse trigger than GFN's, and the statistic chiaki-ng
keeps today cannot carry it without being rebuilt first.** GFN's `vvsyncStatus` is a
*server* statement, one byte per frame, about the source's own cadence
(`libgrid.so:0x77acdc`); the client reacts within one frame and never guesses. A
client-side analogue can only observe the cadence *after* the network and the decoder
have both added their own jitter, and it needs a window of frames before it can
conclude anything — so it is a second-scale trigger against GFN's frame-scale one.
That asymmetry is fundamental and it decides the design: the client-side trigger is
worth building only if it is **cheap, rare-firing and reversible**, and the real win
is in the two things vvsync *does* (bounded-age queue, half-vsync lead), which are
worth having whether or not a trigger ever fires. Concretely: replace the PTS-relative
arrival-offset statistic with a **PTS-free inter-arrival deviation** statistic that runs
in every pacing mode, trigger on `p95(|Δ − T|) ≥ T/2` or a frame gap, hold with 1-window
entry / 3-window exit hysteresis and a 2 s minimum dwell, and while active suspend the
timestamp timeline in favour of a depth-2 / age-2T head-dropping FIFO released at
`next_vsync − vsync_period/2`.

---

## 1. What GFN's trigger actually is, restated as requirements

From `GFN-VVSYNC.md` §2, §3, §5, the properties a client-side analogue has to match or
consciously give up:

| GFN property | evidence | client-side analogue |
|---|---|---|
| Decision is made by the **server**, which knows its own frame cadence | `vvsyncStatus` byte per decode unit, `libgrid.so:0x77acdc` | **impossible.** The PS5 exposes no such channel. Must be inferred downstream of network + decoder. |
| Reacts in **one frame** | edge-detected in `updateVvsyncStatus` @`0x77ca84`, committed by the render thread on the next iteration | **given up.** An estimator needs a window; ≥1 s at 60 fps for a stable percentile. |
| **60 fps streams only** | `a0.C()` ORs bit `8` only on the 60 fps branch | keep: gate the trigger on `stream_fps == 60` and a 59–61 Hz panel, which is exactly `timestamped_release_eligible()`'s window (`video-presenter.c:117-125`). |
| Entry **flushes the queue** — the latency step is taken at once | `AsyncFrameQueue::push` @`0x3bea8` → `drop(true)` | keep. Flushing is cheaper than the timeline shift we do today (`video-presenter.c:334-336`), and it is what makes the mode change legible in a trace. |
| Entry **saves the predecessor** and restores it on exit, re-checked against the environment | `switchRenderingMethod` @`0x1ffdc`, `+0xe84` | keep: remember the pacing mode, restore it on exit only if still eligible. |
| Buffering becomes a **bounded-depth, bounded-age FIFO with head-drop**, both bounds from the server | `pushFixed` @`0x3b114` | keep the shape; the bounds must be client-chosen constants, since nothing tells us. |
| Release lead moves from 4 ms to **½ vsync interval** | `waitForRenderNextFrame` @`0x20510`, `0x20664`→`0x205a4` | keep; PLE-106 is adding the knob (`2ms` / `half_vsync`). |
| The real vsync keeps driving everything | `needVsyncEvents()` @`0x23174` | keep. Our presenter is Choreographer-driven and stays that way. |

The two properties we cannot have (server knowledge, one-frame latency) are precisely
the two that make GFN's version safe to toggle often. Ours must therefore toggle
*rarely* — which is why §4 spends its budget on hysteresis rather than on sensitivity.

---

## 2. What chiaki-ng already measures

All in `android/app/src/main/cpp/video-presenter.c` unless stated.

| quantity | where | shape |
|---|---|---|
| **arrival offset** `arrival_ns − pts_ns` | `record_arrival_locked` :227-241 | ring of 300 (`ANDROID_CHIAKI_VIDEO_PRESENTER_JITTER_WINDOW`, `video-presenter.h:17`) |
| **p99.7 of that window, min-subtracted** | `adjust_dejitter_buffer_locked` :174-190 | two `qsort`s of 300 `int64_t` every 60 samples (:236-240) |
| **de-jitter depth** | :194-215 | 8 ms start, 32 ms cap, ±1 vsync period steps, increase margin 1 ms, decrease margin 4 ms, decrease needs 3 consecutive windows (:11-16) |
| **vsync period** | `on_vsync` :286-297 | 7:1 EWMA of the observed Choreographer interval |
| **missed vsyncs** | :293-296, :326 | counter, from both a Choreographer gap and a frame that targets a vsync already past |
| **dropped frames** | `record_release_locked` :138-144 | counter |
| **queue depth** | `queue_size`, capacity 5 (`video-presenter.h:16`) | head-drop on full already exists (:522-527) |
| **decode latency** | `record_output_available` :28-56 | ring of 256, mean and p95, **only when `diagnostics_enabled`** |
| exported once per second | `chiaki-jni.c:332-353` → `StreamStatsEvent` → `StreamDiagnostics.kt:47-72` | `q`, `vsync-miss`, `DJB … ms`, decode mean/p95 in the overlay |

So the raw material is there: arrival timestamps, a decode-latency ring, a vsync-period
estimate, and a once-a-second export path that an A/B run can read off logcat.

---

## 3. Three defects that must be fixed before any trigger can rest on it

These are findings about the current code, not proposals.

### D1 — the arrival offset is not a jitter measure in the default configuration

`record_arrival_locked` stores `frame->arrival_ns − info.presentationTimeUs * 1000`
(:230-231). That is only a jitter measure if `presentationTimeUs` is a source-side
clock. It is one **only when `stream_real_video_timestamps=true`**, which defaults to
`false` (`res/xml/preferences.xml:116-120`):

```c
// video-decoder.c:399-404
uint64_t presentation_time_us = decoder->timestamp_cur;
if(decoder->real_pts_enabled)
    presentation_time_us = unwrapped_frame_index * 1000000ULL / decoder->fps;
...
// video-decoder.c:481-482  — the default path
if(!decoder->real_pts_enabled)
    presentation_time_us++;      // +1 microsecond, per *input buffer*, not per frame
```

In the default path the PTS advances **1 µs per input buffer chunk** while wall-clock
advances ~16 667 µs per frame. The offsets in the window therefore ramp by ≈ one frame
period per sample, and `adjust_dejitter_buffer_locked`'s min-subtracted p99.7
(:183-190) over a 300-sample window is on the order of **300 × 16.7 ms ≈ 5 s**, not
milliseconds. The `percentile_ns + 1 ms > dejitter_buffer_ns` branch (:194) is then
taken at every adjustment and the depth ratchets to the 32 ms cap within a handful of
windows, never to come back down. The A/B overlay would show `DJB 32.0 ms` and the
timeline offset would have absorbed 24 ms of pure latency for no reason.

This is not hypothetical arithmetic about a path nobody runs — it is the path
`stream_video_pacing_enabled=true` takes by default.

### D2 — `arrival_ns` is a decoder-output timestamp, not a network timestamp

It is `monotonic_time_ns()` at `enqueue_paced_frame` (:504), i.e. when
`AMediaCodec_dequeueOutputBuffer` returned on the output thread. It therefore contains
network jitter **plus** decode-queue latency **plus** the output thread's own
scheduling. For a de-jitter *buffer* that is the wrong quantity (you want the network
component). For a **vvsync-like trigger** it is arguably the right one: the question
being asked is "has the cadence reaching the presenter stopped being predictable",
and a decoder hiccup makes it unpredictable just as surely as a network burst does.
The design below uses it deliberately, and §7 lists the false positive this buys.

### D3 — the estimator only runs in two pacing modes, and has never been observed running

`record_arrival_locked` is called only from `enqueue_paced_frame` (:531), which is only
reached when `timestamped_release_enabled` (:516, :564), which is
`timestamped_release_eligible(mode, refresh_hz, stream_fps)` — BALANCED or SMOOTHEST,
refresh < 119 Hz, and 59–61 Hz or matching the stream fps (:117-125). On the project's
S22 Ultra at its default 120 Hz that is false, so the direct path runs and **no arrival
sample is ever taken**. That is the actual explanation of
[`docs/verification/AB-2026-09-16.md`](../verification/AB-2026-09-16.md) §7 — "No `DJB
adjusted` line appeared in either 15 s window" — the presenter logged
`using immediate release … (timestamped release disabled at 120 Hz)` at attach. The
de-jitter estimator in this fork has, as of `00f3245f`, never been shown to execute on
the test hardware.

A trigger must be computed on a path that runs in **every** mode, or it cannot decide
to switch *into* a paced mode; and it must be observable in the A/B harness regardless
of panel refresh.

---

## 4. The proposed signal

### 4.1 Base quantity: PTS-free inter-arrival deviation

Let `a_i` be `arrival_ns` of the i-th decoded frame (`info.size != 0`) and
`T = 1e9 / stream_fps` the nominal frame period. Define

```
d_i = (a_i − a_{i−1}) − T          // signed, nanoseconds
```

`d_i` needs no PTS at all, so it is immune to D1 and works in the shipped default
configuration. It is sampled in a new `record_arrival_locked` call site on **both** the
paced path (:531) and the direct path (`handle_direct_frame`, which already computes
`arrival_ns` at :449 and :466), fixing D3. Its cost is one subtraction per frame.

Note that `d_i` is a *differenced* series: a constant end-to-end latency offset cancels,
which is what we want — the trigger must fire on cadence irregularity, not on latency.

### 4.2 Window statistics

Over a sliding window of `W = 120` samples (2 s at 60 fps; two windows fit in a 15 s A/B
trace with room to spare), evaluated once per completed window:

| symbol | definition | why |
|---|---|---|
| `J` | p95 of `abs(d_i)` | burstiness of arrival, insensitive to 5 % outliers |
| `G` | count of `i` with `d_i > T/2` | *gaps*: a frame arriving more than half a period late |
| `E` | count of `i` with `d_i < −T/2` | *bunching*: the catch-up frames that follow a gap |
| `C` | EWMA (α = 1/32) of decode latency, from the existing `record_output_available` pairing | separates "the network stuttered" from "the decoder stalled" |

`J` should be computed **without a `qsort`**: the existing estimator sorts 300 elements
twice every 60 frames (:182, :186) on the output thread while holding the presenter
mutex, which is already more work than it needs to do. A 32-bucket histogram over
`|d|` with 1 ms buckets, capped at 32 ms, gives p95 to 1 ms resolution in O(1) per
sample and O(32) per window, and 1 ms is finer than any threshold below.

`C` is currently gated on `diagnostics_enabled` (:31-32). The trigger needs it always,
so the EWMA (not the 256-entry ring, not the p95) moves out of the diagnostics guard.
An EWMA needs no storage and no sort.

### 4.3 Trigger, thresholds, hysteresis

State machine with two states, `STEADY` and `BURSTY` (the vvsync analogue). At 60 fps,
`T = 16.67 ms`:

```
enter BURSTY  (from STEADY, requires 1 window):
      J ≥ T/2  (8.3 ms)                      // arrival spread is half a frame
   OR G ≥ 2                                  // two late frames in 2 s
   OR (G ≥ 1 AND E ≥ 1 AND C ≥ 2·C_steady)   // a decoder stall, not a network burst

exit  BURSTY  (requires 3 consecutive windows, i.e. 6 s):
      J ≤ T/4  (4.2 ms)  AND  G == 0  AND  E == 0

minimum dwell in either state: 2 s (1 window). Transitions are rate-limited to
at most one per 2 s regardless of the above.
```

Rationale for each number:

* **`J ≥ T/2` for entry.** Half a frame period is the point at which a timestamp
  timeline mis-predicts by more than it can correct without either dropping or showing
  a frame a whole vsync late — exactly the condition `on_vsync` already treats as
  special (`± vsync_period_ns / 3` at :321 and :324; `T/2` is deliberately *outside*
  that band, so the trigger fires only for jitter the existing logic is already failing
  to absorb). It is also the size of the lead vvsync adopts, which is not a coincidence:
  half a period is the slack GFN buys when it stops trusting the cadence.
* **`J ≤ T/4` for exit.** A 2:1 gap between entry and exit thresholds. Anything
  narrower chatters, because `J` at 60 fps on a clean LAN is expected to sit a few ms
  below the entry threshold, not an order of magnitude below.
* **1 window in, 3 windows out.** The same asymmetry the existing DJB already uses
  (`VIDEO_PRESENTER_DJB_DECREASE_WINDOWS = 3`, :16): react to degradation immediately,
  return to the low-latency state only on sustained evidence. Being wrong in the
  `BURSTY` direction costs half a frame of latency; being wrong in the `STEADY`
  direction costs visible judder.
* **2 s minimum dwell and a rate limit.** Entry and exit both flush the queue (§5).
  GFN can afford to flush on every toggle because its toggles are server-timed and
  correct; ours are inferred and can be wrong, so a flush storm is the failure mode to
  engineer against. One toggle per 2 s bounds the damage to one flush per 120 frames.
* **`W = 120`.** Small enough that the trigger reacts in ~2 s, large enough that p95
  means something (6 samples above the line). `W = 60` would react in 1 s with p95
  resting on 3 samples; that is the knob to try second if 120 proves too slow.

`G`, `E` and `J` all come from the same `d_i`, so the whole per-frame cost is one
subtraction, one absolute value, one histogram increment and two compares.

### 4.4 What is deliberately *not* in the signal

* **Packet-level Takion arrival jitter.** It is the physically correct input for a
  de-jitter buffer, and PLE-83 should consider it, but it lives in `lib/` on the other
  side of the JNI boundary and it does not see decoder stalls. Out of scope here.
* **`missed_vsyncs`.** It is polluted by the Choreographer-gap branch (:291-296), which
  fires when the *app* is descheduled, not when the stream misbehaves.
* **`dropped_frames`.** It is an output of the policy, not an input; feeding it back
  into the trigger closes a loop whose stability nobody has analysed.

---

## 5. What the presenter does while `BURSTY` is active

Mirroring `switchRenderingMethod` (`GFN-VVSYNC.md` §3) and `pushFixed` (§5.2):

**On entry** (`STEADY → BURSTY`):

1. Save the current pacing mode and `timestamped_release_enabled` (the "predecessor",
   GFN's `+0xe84`).
2. **Flush the queue**: release the newest frame with `render = true`, drop the rest,
   `timeline_valid = false`. This is `drain_immediate_locked`'s drop-stale shape
   (:261-274) and `AsyncFrameQueue::drop(true)`'s semantics. Do **not** take the
   `timeline_offset_ns` shift path (:334-336): the point of the transition is that the
   timeline is no longer trusted.
3. Suspend the PTS timeline for the duration: `timeline_valid` stays false and
   `adjust_dejitter_buffer_locked` is not called. GFN is explicit that the de-jitter
   estimator and timestamp rendering are *torn down* on vvsync entry, not run in
   parallel (`switchRenderingMethod` clears `+0xd20` and `+0xbe3`).

**While active**, `on_vsync` releases at most one frame per callback (unchanged,
:341-342) but chooses it differently:

| aspect | `STEADY` (today) | `BURSTY` (proposed) |
|---|---|---|
| target vsync | `align_nearest(pts + timeline_offset, …)` (:319) | `next_vsync_ns` — always the next one |
| queue depth | 5, head-drop on full (:522-527) | **2**, head-drop on full |
| age bound | none | **drop any frame older than `2·T` relative to the newest queued frame** |
| release time | `target − VIDEO_PRESENTER_LEAD_NS` (2 ms, :10) | `target − vsync_period_ns / 2` |
| late frame | timeline shift, or drop in BALANCED (:324-337) | not reachable — the target is always the next vsync |

* **Depth 2, age 2T** are the client-chosen stand-ins for GFN's server-supplied
  `maxQueuedFrames` / `frameDropThreshold`. Depth 2 is one frame in flight plus one
  spare; depth 1 would make every decode-latency spike a dropped frame. Age `2·T`
  (33 ms at 60 fps) is the smallest bound that does not fight a single late frame,
  and it is within the 8–32 ms range the existing DJB was designed to cover
  (:11-12). Both are constants, both belong next to `VIDEO_PRESENTER_DJB_*`, and both
  are guesses until §8's experiment measures them.
* **`vsync_period_ns / 2` is PLE-106's `half_vsync` lead.** This note must not
  reimplement it: when `BURSTY` is implemented it should *select* PLE-106's lead value,
  not introduce a second one.

**On exit** (`BURSTY → STEADY`): flush again (same shape), restore the saved pacing
mode, and re-run `timestamped_release_eligible()` against the *current* refresh rate
before re-enabling the timestamp timeline — the panel may have changed mode while
`BURSTY` was active. This is exactly the re-check GFN does on restore (the
`m_MaxFps <= 2` / `m_eglRendererMode == 0` guards at `0x20184`), and on this project's
phone panel-mode changes mid-stream are a known event (AB-2026-09-16 §7).

**Where it goes** (sketch only — no code is written by this ticket):

* `video-presenter.h`: `AndroidChiakiVideoCadenceState { STEADY, BURSTY }`, the
  histogram, `last_arrival_ns`, `window_count`, `steady_windows`, `last_transition_ns`,
  `saved_mode`, and a `cadence_trigger_enabled` flag.
* `video-presenter.c`: `update_cadence_trigger_locked()` called at the end of
  `record_arrival_locked()`; `record_arrival_locked()` itself called from both
  `enqueue_paced_frame()` and `handle_direct_frame()`.
* Setting `stream_video_cadence_trigger`, `SwitchPreference`, **`defaultValue="false"`**,
  plumbed like `stream_video_pacing_enabled` (AGENTS.md rule 4). With the setting off,
  the statistic may still be *computed and logged* (it is nearly free and it is what
  makes the A/B round in §8 possible) but must never change a release decision.
* One log line per transition, in the shape the harness already greps:
  `Video presenter cadence BURSTY (jitter p95 %.1f ms, gaps %u, bunched %u, decode %.1f ms)`.
* Add the state and the transition count to `AndroidChiakiVideoPresenterStats` so it
  reaches the once-per-second overlay (`StreamDiagnostics.kt:51`), next to `DJB`.

---

## 6. Interaction with the tickets around it

* **PLE-83 (`DEJITTER.md`)** owns the steady-state policy. This note hands it three
  things: D1 (its input statistic is broken by default), D3 (that statistic has never
  run on the phone), and the recommendation that a **bounded-age head-drop** is worth
  having in `STEADY` too, independent of any trigger.
* **PLE-106** owns `VIDEO_PRESENTER_LEAD_NS` as a per-mode value. `BURSTY` consumes it.
* **PLE-16 (`stream_real_video_timestamps`)** becomes a *precondition* of the current
  DJB rather than an independent flag. Either the DJB switches to the PTS-free `d_i`
  statistic proposed here, or `stream_video_pacing_enabled` must imply
  `stream_real_video_timestamps`. Shipping them as independent switches, as today, has
  a combination (pacing on, real PTS off) that is actively harmful.

---

## 7. Why this might not work, stated before it is measured

1. **It cannot fire before the damage.** One window is 2 s. GFN reacts in one frame.
   If the PS5's cadence irregularity is bursty at the 100 ms scale — a scene change, a
   single dropped encoder frame — the trigger will still be in `STEADY` while it
   happens and will enter `BURSTY` after it is over, adding half a frame of latency to
   a stream that has already recovered. The 2 s dwell then keeps it there. **If that is
   the dominant failure mode on this hardware, the correct conclusion is to delete the
   trigger and make the steady-state policy robust instead** (bounded-age head-drop
   always on), not to shorten the window until it chatters.
2. **D2 makes it fire on our own decoder.** `arrival_ns` is downstream of MediaCodec, so
   a thermal throttle or a competing app produces exactly the same `d_i` signature as a
   congested Wi-Fi link. The `C ≥ 2·C_steady` term in the entry condition exists to tell
   those apart in the *logs*; it does not change the response, because the response
   (stop trusting the timeline) is right either way.
3. **The thresholds are guesses.** `T/2`, `T/4`, `G ≥ 2`, `W = 120`, depth 2, age `2T`:
   none of these is measured. The first job of §8 is to find out what `J` and `G`
   actually are on a clean LAN; if baseline `J` is already near 8 ms, every number here
   is wrong and must be rescaled to the measured distribution.
4. **It is 60 Hz-only by construction**, like GFN's. On this phone's default 120 Hz
   panel it never engages, so it also depends on PLE-7 `highest`/PLE-54's pinning
   behaving as AB-2026-09-16 §7 describes.

---

## 8. The experiment that would validate it

Run on the packaged A/B harness (`scripts/dev/ab/`, PLE-60), same method as
[`AB-2026-09-16-round2.md`](../verification/AB-2026-09-16-round2.md): one build, one
night, one phone (SM-S908B), PS5-466, 15 s Perfetto traces, `summarize.py` at the end.

**Stage 0 — instrument only, no behaviour change.** Build with the statistic computed
and logged but `stream_video_cadence_trigger=false`. This stage is worth running even if
the trigger is never implemented, because it answers D1/D3 with numbers.

| scenario | prefs | asks |
|---|---|---|
| `cad_base_60` | `stream_video_pacing_enabled=boolean:true`, `stream_video_pacing_mode=string:balanced`, refresh pinned 60 Hz | what are `J`, `G`, `E` on a clean LAN, untouched? |
| `cad_base_60_touched` | same, `touched=1` | does gameplay input change them? |
| `cad_base_120` | same, panel at 120 Hz | confirms D3: expect **zero** arrival samples on the paced path and a `timestamped release disabled` line |
| `cad_realpts_60` | + `stream_real_video_timestamps=boolean:true` | confirms D1 by contrast: the old p99.7 statistic should be sane here and absurd in `cad_base_60` |
| `cad_loaded_60` | as `cad_base_60`, with a second device pulling a bulk download through the same AP for the whole trace | does `J` actually rise above `T/2` under real congestion? |

**Pass/fail for stage 0** — all four are prerequisites for stage 1, and any one failing
kills the design as written:

* **P0-a.** In `cad_base_60`, the logged `DJB` reaches 32.0 ms and the logged
  p99.7 is ≥ 1 s, while in `cad_realpts_60` both are in single-digit milliseconds.
  *Confirms D1.* If it does not reproduce, D1's arithmetic is wrong and §3 must be
  rewritten before anything is built.
* **P0-b.** `cad_base_120` logs zero arrival samples on the paced path. *Confirms D3.*
* **P0-c.** In `cad_base_60` and `cad_base_60_touched`, `J` ≤ `T/4` (4.2 ms) and
  `G == 0` in **every** window. This is the false-positive test: a trigger that fires on
  a clean LAN is useless. If `J` sits between `T/4` and `T/2`, rescale the thresholds to
  the measured distribution (entry at p99 of the baseline `J`, exit at its median) and
  re-run stage 0 before proceeding.
* **P0-d.** In `cad_loaded_60`, `J ≥ T/2` or `G ≥ 2` in at least one window. This is the
  sensitivity test. If congestion heavy enough to be visible on screen does not move
  `J`, the signal does not exist and the ticket ends here with a negative result.

**Stage 1 — the A/B proper**, only if stage 0 passes all four. Same five scenarios with
`stream_video_cadence_trigger=boolean:true`, each paired against its stage-0 twin on the
same build:

* **P1-a (no regression).** On `cad_base_60`: **zero** `BURSTY` transitions in 15 s, and
  `analyze_ab.py`'s queue→present p90 within noise of the stage-0 run. The trigger must
  cost nothing when it does not fire.
* **P1-b (it fires and it helps).** On `cad_loaded_60`: ≥ 1 transition, and relative to
  the stage-0 twin, latched-frame count up **or** queue→present p90 down, with neither
  materially worse. Judder is checked with `freeze_test.sh` and by eye on the screenshots.
* **P1-c (it recovers).** In `cad_loaded_60`, after the bulk download stops, the trigger
  returns to `STEADY` within 8 s (3 windows + dwell), logged.
* **P1-d (it does not thrash).** Across every scenario, no two transitions closer than
  2 s, and ≤ 3 transitions in any 15 s trace.

The harness needs one addition: `summarize.py` must count
`Video presenter cadence` lines per scenario and emit `cadence_transitions`,
`cadence_j_p95_ms` and `cadence_gaps` into `<scenario>.json`, the same way it already
lifts `Number Missed Vsync` from `gfxinfo` (`summarize.py:128`).

**What this experiment cannot settle.** Whether the PS5 ever produces the kind of
irregular *source* cadence that `vvsyncStatus` exists to signal. The loaded-AP scenario
induces jitter in the network, which is a different physical cause with a similar
signature; a genuine source-cadence test needs a title that misses its frame deadline
(a heavy scene, or a 30 fps-capped game streamed at 60), and that belongs in its own
capture round.

---

## 9. Not established

* Nothing here was observed at runtime. No phone, no PS5, no GFN session in this ticket.
* D1 and D3 are read off the source and off AB-2026-09-16 §7's log line; neither has
  been reproduced deliberately. P0-a and P0-b exist to do that.
* Every threshold in §4.3 and every bound in §5 is a proposal derived from GFN's
  behaviour and from the constants already in `video-presenter.c`, not a measurement.
* The claim that a 32-bucket histogram is cheaper than the current double `qsort` is
  arithmetic, not a profile.
* What the GFN server actually sends for `maxQueuedFrames` and `frameDropThreshold`
  remains unknown (`GFN-VVSYNC.md` §8), so "depth 2, age 2T" is not matched against
  NVIDIA's own choice.

---

## 10. Addendum: the combined native-vsync init/query slot

**Ticket:** PLE-117. **Method:** static disassembly of
`libmediacodecdecoder.so`; no runtime observation.

PLE-109 identified `ADAPTOR_DEC_PARAMS` index 24 as unusual. It is not a setter. In
`MediaCodecDecoderInterface::setDecoderParam(ADAPTOR_DEC_PARAMS, void*, void*)`, the
handler at `0x1ae08` interprets its two opaque arguments as an output pointer and an
operation selector:

```text
setDecoderParam(24, out_bool, non_null):
    if decoder.initializeNdkVsyncHandler(): return 0
    // initialization failure deliberately falls through

setDecoderParam(24, out_bool, null):
    *out_bool = decoder.needVsyncEvents()
    return 1
```

The exact branches are `cbz x3,0x1b0e8` at `0x1ae0c`, the init call at
`0x1ae1c`, and the query call plus byte store at `0x1b0ec`-`0x1b0fc`. This also
explains the otherwise surprising return values: zero means that native-handler
initialization succeeded; one means that the caller received the current demand bit.
An init failure is therefore a supported fallback negotiation, not a fatal decoder
error.

### What initialization owns

`MediaCodecDecoder::initializeNdkVsyncHandler()` at `0x23968`:

1. calls `createVsyncHandler()` and stores the result at decoder `+0x9e0`;
2. calls the handler's virtual `initialize()` method;
3. registers the decoder callback immediately only when `needVsyncEvents()` is already
   true; and
4. on failure, destroys the handler, clears `+0x9e0`, and returns false.

`createVsyncHandler()` at `0x3671c` returns null below SDK 29. On SDK 29+,
`MediaCodecVsyncHandler::initialize()` dynamically opens `libandroid.so`, resolves
`AChoreographer_getInstance`, `AChoreographer_postFrameCallback64`, and
`AChoreographer_postFrameCallbackDelayed64`, gets the thread's Choreographer, and posts
the first delayed callback. Each callback records the timestamp, notifies the decoder
when a callback is registered, and reposts itself. `registerCallback()` at `0x36ae0`
stores the decoder/user callback once; later mode enablement can call it again safely.

The query is demand, not capability. `needVsyncEvents()` at `0x23174` is true when any
of dynamic DJB, committed vvsync, cinematic pacing, or EGL renderer mode 2 needs the
clock. It says nothing about whether native initialization succeeded. This distinction
is why the combined slot needs both operations: libgrid first attempts to install the
SDK-29 native source, then queries whether it must supply events through the older
`setVsyncMethods` fallback.

### Consequence for chiaki-ng

There is no missing implementation to port into the PLE-23 presenter. Chiaki-ng owns
its `AChoreographer` loop directly in `video-presenter.c`, starts it only when
`timestamped_release_enabled` is true, and falls back to immediate release if its
vsync thread cannot start. Its minSdk-24 path uses the API-24
`AChoreographer_postFrameCallback`; the API-29 `...Callback64` symbol is weak and used
when present. GFN's index 24 exists at a plugin boundary so an external owner can
negotiate between a native source and callback-function fallback; chiaki-ng has no
equivalent boundary and gains nothing from reproducing the combined return-code
protocol.

The reusable design point is narrower: keep **capability/init success** separate from
**current demand**. If the presenter is later split behind an interface, expose those
as two typed operations rather than copying index 24's overloaded null-argument ABI.
No runtime change follows from this addendum.
