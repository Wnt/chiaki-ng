# A de-jitter policy for chiaki-ng, and what that means for the PTS work

**Ticket:** PLE-83. **Inputs:** PLE-78 (`DEC_RENDER_MODE` decoded —
[`geforcenow-apk/ANALYSIS.md`](../../../android-low-latency-examples/geforcenow-apk/ANALYSIS.md)),
PLE-82 ([`GFN-VVSYNC.md`](../../../android-low-latency-examples/GFN-VVSYNC.md)),
PLE-108 ([`VVSYNC-CLIENT-SIDE.md`](VVSYNC-CLIENT-SIDE.md)),
PLE-75 ([`PTS-decode-latency.md`](../verification/PTS-decode-latency.md)),
PLE-64 ([`AB-2026-09-16-round2.md`](../verification/AB-2026-09-16-round2.md)),
PLE-47 ([`AB-2026-09-16.md`](../verification/AB-2026-09-16.md)).

**Method:** source reading on CT950 only. No phone, no PS5, no GFN session in this
ticket. Every number that is not a literal from the source or a cell of an existing
capture table is a *proposal*, not a measurement. No fork code is changed.

**Scope.** This note owns the **steady-state de-jitter policy**: which quantity is
measured, how the buffer depth is chosen, and what release time comes out. It does
**not** own the vvsync-like *trigger* (PLE-108 owns that, and this note reuses its
defects rather than re-deriving them), the release-lead constant (PLE-106, landed as
`stream_video_presenter_lead`) or the round-3 A/B itself (PLE-116).

---

## 0. One-paragraph answer

PLE-78's finding changes the target, not the machinery. GFN's `DEC_RENDER_MODE` is a
ladder of **mutually exclusive** states — `vvsync 8 → adjb 4 → cinematic 16 → ts 2 :
frame 1` — and on a 60 fps SDR stream it sits in `adjb`, with `ts` as the *fallback*
when the de-jitter buffer is off. chiaki-ng today runs the one combination GFN never
runs: a presentation-timestamp timeline (`ts`) whose offset is *fed* by a de-jitter
estimator (`adjb`), so the buffer depth is applied as a one-off shift of the timeline
(`video-presenter.c:233`) rather than as the timeline itself. That is why the depth is
a ratchet, why it moves in panel-refresh-sized steps, and why the estimator's input is
the wrong quantity. The de-jitter policy proposed here replaces the PTS timeline
instead of decorating it: reconstruct the source cadence from `frame_index` and the
**network-complete instant of the frame** (measured in `lib/`, where no decoder jitter
has been added yet), hold a continuously-adjusted depth `D` in milliseconds, and
release at `anchor + n·T + D`, snapped to the vsync grid with PLE-106's lead. That
policy needs **neither PLE-16's real PTS nor PLE-23's timeline** — `frame_index`
already reaches the decoder unconditionally (`video-decoder.c:575`), only its use as a
`presentationTimeUs` is gated — which is the single most useful consequence of reading
PLE-78 and PLE-75 together: PLE-75 showed that using real PTS at all is what made the
Exynos decoder slow (fixed since by `operating-rate=480`), so a de-jitter policy that
does not touch the codec's input timestamps carries none of that risk. On the two
default questions: **PLE-16 and PLE-23 both stay default-off**, for a reason that has
changed — no longer "they cost 6 ms of decode latency" (PLE-75 fixed that) but "the
paced path they enable has never once executed on the test phone", which §8 shows is
true of both capture rounds.

---

## 1. The model, restated

### 1.1 What PLE-78 established

| value | GFN label | what it is |
|---|---|---|
| 1 | `frame` | render on decode; no timestamp, no buffer, no pacing |
| 2 | `ts` | timestamp rendering |
| 4 | `adjb` | adaptive de-jitter buffer |
| 8 | `vvsync` | server-triggered fixed-depth/age FIFO |
| 16 | `cinematic` | cinematic pacing |

`MediaCodecDecoder::updateRenderModeChange()` (`0x22968`) reduces four independent
`bool`s to one value with a **first-match-wins** ladder, so these are states, not a
bitmask, even though the constants are powers of two. `adjb` outranks `ts`: whenever
the dynamic de-jitter buffer is on, the mode *is* `adjb`, and `ts` only appears when it
is off. The S25 capture's `async render mode: 4` is `adjb` (the logged number is a
remapped enum in which 4 happens to be the identity). `switchRenderingMethod` tears
`ts` **down** when it enables `adjb`'s successor — `strb wzr,[+0xbe3]` clears timestamp
rendering — it does not layer them.

### 1.2 What chiaki-ng does today, in that vocabulary

`android/app/src/main/cpp/video-presenter.c`, as of `4743daf6`:

* `ANDROID_CHIAKI_VIDEO_PACING_DISABLED` / `LOWEST_LATENCY` ≈ **`frame`**: immediate
  release, optionally dropping all but the newest (`drain_immediate_locked`,
  `handle_direct_frame`).
* `BALANCED` / `SMOOTHEST` ≈ **`ts` and `adjb` at the same time**. The release target is
  a PTS timeline (`align_nearest(pts_ns + timeline_offset_ns, …)`, `:332-333`), and
  `adjust_dejitter_buffer_locked()` (`:187-238`) folds the buffer depth into that
  timeline's offset (`:233`).

So the fork's "de-jitter buffer" is not a buffer at all: it is a **constant added to a
timestamp timeline, once, at each adjustment**. Everything in §4 follows from that.

### 1.3 Why the distinction is not cosmetic

A timestamp timeline answers *"which vsync does frame n belong on"*. A de-jitter buffer
answers *"how long must I hold a frame so that the next one is here when I need it"*.
The first needs a trustworthy source clock; the second needs an arrival-time
distribution and nothing else. Conflating them means a change to the second (a depth
adjustment) is expressed as a discontinuity in the first (a timeline shift), and the
system inherits the failure modes of both: it needs real PTS to work at all (PLE-108
D1) *and* it ratchets (§4, D4).

---

## 2. Where jitter actually enters

One frame's path, with the component that adds variance at each hop:

| # | hop | variance source | measured today? |
|---|---|---|---|
| 1 | PS5 encoder output | source cadence: scene changes, encoder rate control | no, and unmeasurable from the client (there is no `vvsyncStatus` equivalent — PLE-108 §1) |
| 2 | LAN / Wi-Fi | queueing, retransmit-free UDP loss, AP airtime | no |
| 3 | Takion reassembly | FEC completion, **head-of-line wait up to `TAKION_AV_REORDER_TIMEOUT_US` = 16 ms** (`takion.c:53`) | no |
| 4 | `video_sample_cb` → `AMediaCodec_queueInputBuffer` | decoder-input thread scheduling (PLE-15) | partly: `record_input_queued`, diagnostics only |
| 5 | decode | **8.0 ms mean / 10 ms p95 at `operating-rate=480`; 13.9 / 30.7 at 60** (PLE-75) | yes, `record_output_available`, diagnostics only |
| 6 | presenter queue + release | policy — the thing being designed | `arrival_ns` at `:517`/`:462`/`:479` |
| 7 | SurfaceFlinger latch + panel | vsync phase, panel mode changes | in the trace, not in the app |

Two consequences that decide the design:

* **Hop 3 is already a fixed 16 ms de-jitter buffer at the packet layer**, and nobody
  counts it. `takion.c:1107-1217` waits up to 16 ms for a missing head packet before
  skipping it. Any presenter-side depth `D` is *in addition* to that wait, and on a
  clean LAN most of hop-3's contribution is zero while on a lossy link it is a
  16 ms-wide bimodal spike. A policy that measures arrival at hop 6 sees hop 3's
  16 ms as "network jitter" and buys a second 16 ms of buffer to absorb it. The
  existing `stream_takion_video_packet_reordering_disabled` setting turns hop 3 off;
  the two knobs interact and must be measured together, never separately.
* **Hop 5's magnitude is a configuration choice, not a property of the stream**
  (PLE-75's curve: 14 ms at rate 60, 8 ms at rate 480). Measuring jitter downstream of
  hop 5 — which is exactly what `arrival_ns` does, PLE-108 D2 — makes the de-jitter
  estimate depend on the decoder's DVFS state. Round 2's side observation that *touch
  input* makes the decoder 40 % faster means the same stream produces a different
  jitter estimate depending on whether the player's thumb is on the screen.

A de-jitter buffer can absorb 2 and 3. It cannot absorb 1 (that is what vvsync exists
for — PLE-108), and it should not be asked to absorb 5 (the fix for 5 is
`operating-rate`, already landed).

---

## 3. What already exists and is reusable

| piece | where | reusable as |
|---|---|---|
| `frame_index` on every sample, **independent of any setting** | `session.h:242`, `videoreceiver.c:307`, `video-decoder.c:575,605` | the cadence index `n` — no PLE-16 needed |
| PTS → input-side metadata ring, 256 entries | `video-presenter.c:28-56` + `record_input_queued` | the map from an output buffer back to its frame, already written and already keyed the way the presenter needs |
| vsync period EWMA + phase | `video-presenter.c:301-310` | the output grid (but see D9) |
| queue with head-drop on full | `video-presenter.c:535-539` | the FIFO; needs an age bound |
| per-second stats → overlay | `chiaki-jni.c` → `StreamDiagnostics.kt` | how the A/B harness reads the policy's state off logcat |
| A/B harness with a decode-latency metric | `scripts/dev/ab/` | §9 |
| `stream_video_presenter_lead` (`2ms` / `half_vsync`) | PLE-106 | the release lead; **do not introduce a second one** |

---

## 4. Defects the policy must fix

PLE-108 §3 established three; they are restated in one line each because the design
depends on them, not re-derived. Line numbers below are current at `4743daf6` (PLE-106
shifted them by ~10 from the numbers in PLE-108's note).

* **D1** — with `stream_real_video_timestamps=false` (the default), `presentationTimeUs`
  is a 1 µs-per-input-buffer counter (`video-decoder.c:512-513`), so
  `arrival_ns − pts_ns` (`:244`) ramps by a frame period per sample and the depth pegs
  at the 32 ms cap. *The default configuration of `stream_video_pacing_enabled` is the
  broken one.*
* **D2** — `arrival_ns` is taken at `dequeueOutputBuffer` return (`:517`), i.e.
  downstream of the decoder, so it carries hop 5 as well as hops 2–3.
* **D3** — `record_arrival_locked` is only reached from `enqueue_paced_frame` (`:544`),
  which requires `timestamped_release_enabled`, which is false at 120 Hz. The estimator
  has never been observed running on the test phone.

Six more, found while reading the estimator against PLE-78's model:

### D4 — the depth is a ratchet, and a single outlier holds it up for five seconds

`adjust_dejitter_buffer_locked` takes the **min-subtracted p99.7 of a 300-sample
window** (`:195-203`). p99.7 of 300 samples is the 299th order statistic — i.e. the
maximum but one. One 30 ms hiccup therefore *is* the statistic for the next 300 frames
(5 s at 60 fps). Increase is immediate (`:207-212`); decrease needs
`VIDEO_PRESENTER_DJB_DECREASE_WINDOWS = 3` consecutive adjustments below the
4 ms decrease margin. With a 60-sample adjustment interval over a 300-sample window
(`:15`, `:249`) consecutive decisions share 80 % of their samples, so "3 consecutive
windows" is 3 × 60 = 180 frames of *new* evidence, and the three decisions are not
independent — the outlier that blocked the first also blocks the second and third.
Worst case a single late frame costs one full step of permanent latency for ≥ 8 s.

### D5 — the adjustment step is the panel's vsync period, not anything about the stream

`:208` and `:217-221` move the depth by `presenter->vsync_period_ns`. On a 60 Hz panel that
is 16.7 ms. Starting at `VIDEO_PRESENTER_DJB_START_NS` = 8 ms, the reachable depths are
{8, 24.7, 32 (cap)} — **three values**, two adjustments from floor to ceiling. On a
90 Hz panel they would be {8, 19.1, 30.2, 32}. A buffer depth is a property of the
arrival distribution and should be quantised, if at all, in milliseconds; only the
*target vsync* belongs on the refresh grid.

### D6 — a depth change is applied as a timeline discontinuity

`:233` adds the depth delta straight into `timeline_offset_ns`. A 16.7 ms increase
therefore moves every subsequent frame one whole vsync later, in one frame — the visible
result is one duplicated frame, and the latency is taken as a step rather than absorbed.
GFN's `adjb`, by contrast, *is* the timeline; there is nothing for a depth change to
discontinuously shift.

### D7 — the queue can hold more than the policy ever wants, and has no age bound

`ANDROID_CHIAKI_VIDEO_PRESENTER_QUEUE_CAPACITY = 5` is 83 ms of frames at 60 fps,
against a depth cap of 32 ms. In `SMOOTHEST` the producer *blocks* on a full queue
(`:520-521`), pushing back onto the decoder output thread; in the other modes the head
is dropped (`:535-539`). Neither is an age bound: a frame can sit in the queue
arbitrarily long as long as nothing behind it arrives. GFN's `pushFixed` bounds both
depth and age, from server-supplied values. PLE-108 §6 already recommends a
bounded-age head-drop in the steady state; this note seconds it and gives it a number
in §5.5.

### D8 — the estimator runs on the output thread under the presenter mutex

`record_arrival_locked` → `adjust_dejitter_buffer_locked` does **two `qsort`s of 300
`int64_t`** (`:195`, `:199`) every 60 frames while holding `presenter->mutex`, which
`on_vsync` needs to release a frame. The cost itself is small; the *lock hold* is on the
release path, once per second, which is precisely the interval at which a stall shows
up as a missed vsync. PLE-108 §4.2 proposes a histogram instead; the same fix applies
here and is worth more for the lock than for the cycles.

### D9 — the presenter never learns that the panel changed mode

`refresh_hz` enters the presenter only through `android_chiaki_video_presenter_start`
and `..._set_timing`, and `set_timing` is called **only from
`android_chiaki_video_decoder_set_surface` when a codec already exists**
(`video-decoder.c:308`), i.e. on a surface swap. `surfaceRefreshHz` itself is the
*requested* mode's rate (`StreamActivity.kt:503`) or `display.refreshRate` sampled once
at surface creation (`StreamSession.kt:220`). There is no `DisplayManager.DisplayListener`
in the path. Two consequences:

1. `timestamped_release_eligible()` (`:130-137`) is evaluated once, so a panel that
   drops from 120 Hz to 60 Hz mid-stream leaves the presenter in immediate release with
   a stale 120 Hz assumption. **This is what AB-2026-09-16 note 7 recorded**: both
   PLE-23 runs logged `timestamped release disabled at 120 Hz` at attach, SurfaceFlinger
   then dropped the panel to 60 Hz, and 15–17 % of frames were never latched.
2. The vsync-period EWMA cannot recover, either. `:302` only accepts an observed period
   inside `(period/2, period·3/2)`; a 120→60 change doubles the period, which lands in
   the `>= period·3/2` branch (`:304`) that counts *missed vsyncs* and never updates
   `vsync_period_ns`. After a 120→60 mode change the presenter permanently believes the
   panel is 120 Hz and reports one phantom missed vsync per frame.

D9 is a prerequisite for any policy in this note: a de-jitter buffer that pins its
release to a vsync grid must know which grid it is on.

---

## 5. The proposed policy

### 5.1 Shape: a mode ladder, not a stack of features

Mirror GFN's exclusivity. Introduce an explicit release policy, chosen once per frame
by the same first-match-wins rule:

```
BURSTY   (PLE-108's trigger, if it is ever built)   -> fixed depth 2 / age 2T FIFO
DEJITTER (this note)                                -> cadence clock + depth D
TIMELINE (today's BALANCED/SMOOTHEST)               -> PTS timeline, no depth feedback
DIRECT   (today's DISABLED/LOWEST_LATENCY)          -> immediate release
```

`DEJITTER` and `TIMELINE` are alternatives, never simultaneous. Concretely: when
`DEJITTER` is active, `timeline_offset_ns` is unused and `adjust_dejitter_buffer_locked`
is not called; when `TIMELINE` is active, the depth is fixed at
`VIDEO_PRESENTER_DJB_START_NS` and the estimator does not run. That single change
removes D1, D4 and D6 by construction, because no depth is ever folded into a timeline.

### 5.2 Input 1: the network-complete instant, measured in `lib/`

Add to the video sample callback, or to a parallel out-of-band struct, the monotonic
time at which the frame became complete — the moment
`chiaki_video_receiver_flush_frame` calls `video_sample_cb`
(`videoreceiver.c:305-307`). Call it `t_ready(n)` for frame index `n`.

Why there and not at hop 6:

* it is **upstream of the decoder**, so it does not move when `operating-rate`, DVFS or
  a thermal throttle move (D2, PLE-75's 8↔14 ms);
* it is **downstream of FEC and reordering**, so it is the first instant at which the
  frame is a decodable unit — which is what a de-jitter buffer must schedule;
* it is one `chiaki_time_now_monotonic_us()` per frame on a thread that already calls it.

`frame_index` is already the callback's third argument and already reaches
`android_chiaki_video_decoder_video_sample` unconditionally (`video-decoder.c:575`), so
`(n, t_ready)` is available at the JNI boundary **with no change to the codec's input
timestamps** and therefore with no exposure to PLE-75's decoder-rate effect.

Carrying `(n, t_ready)` to the output side reuses the ring that already exists for
diagnostics (`video-presenter.c:28-56` and `record_input_queued`), keyed by the same
`presentationTimeUs` the codec echoes back. Two details that ring gets wrong today and a
production use must not:

* it is gated on `diagnostics_enabled`. The policy needs it always.
* it only records when `codec_sample_size == buf_size` (`video-decoder.c:488`), i.e. it
  silently skips any frame large enough to be split across input buffers — the IDR
  frames, exactly the frames whose timing is most interesting. Key the entry on the
  **first** chunk's PTS, which is the PTS the codec reports on output.

### 5.3 Input 2: decoder output cadence, as a margin, not as the signal

Keep the existing decode-latency pairing (`record_output_available`) but demote it to a
single EWMA (α = 1/32) of `available_ns − queued_ns`, computed unconditionally, plus a
slow-moving high-water mark `C_hi` (the max over the last 4 windows). It is not part of
the jitter statistic — it is the margin the policy must leave so that a frame released
at `t_release` has actually come out of the decoder by then:

```
D_min_decode = C_hi − C_mean          // the decoder's own spread, not its latency
```

At `operating-rate=480` PLE-75 measured mean 7.7–8.1 ms and p95 9.8–10.1 ms, so this
term is ~2 ms. At rate 60 it was mean 13.9 / p95 30.7 — ~17 ms. That the de-jitter depth
would triple purely because of a codec configuration is the quantitative form of "do not
measure jitter downstream of the decoder" (D2), and it is also the argument for keeping
`stream_decoder_operating_rate_auto` on.

### 5.4 The cadence clock and the release time

Maintain a virtual source clock rather than reacting to each arrival:

```
T      = 1e9 / stream_fps                       // nominal frame period, ns
anchor = t_ready(n0) for the first frame after a (re)start
err(n) = t_ready(n) − (anchor + (n − n0)·T)     // signed, ns
```

`err(n)` is the arrival deviation from a perfectly-paced source, with no PTS involved
and with a constant end-to-end latency cancelling out. It is the correct input for the
depth estimator, and it is also exactly the quantity PLE-108 approximates with its
PTS-free difference `d_i` — the two are related by `d_i = err(i) − err(i−1)`. PLE-108's
trigger wants the difference (it asks "is the cadence irregular *now*"); a steady-state
buffer wants the level (it asks "how far behind the pace can a frame be").

**Anchor slew.** The anchor is not re-set per frame; it is dragged toward the observed
minimum so that a permanently-early or permanently-late clock does not accumulate:

```
if err(n) < 0:  anchor += err(n)                        // a frame arrived early: the clock was slow
else:           anchor += min(err(n), SLEW_NS) / 64     // drift correction, ≤ ~0.25 ms/s at 60 fps
```

`SLEW_NS` = 1 ms. The asymmetry is deliberate: arriving early is proof the clock is
wrong, arriving late is only evidence. This is the standard minimum-tracking clock
recovery a de-jitter buffer uses, and it is the replacement for `align_nearest`'s
silent per-frame re-snap, which absorbs source/panel clock drift at the cost of one
duplicated or dropped frame every time the phase wraps.

**Depth.** Over a window of `W = 120` frames (2 s), with a 1 ms-bucket histogram of
`err` capped at 64 ms (no `qsort`, D8):

```
D_target = p99(err over the window) + D_min_decode + D_GUARD     // D_GUARD = 1 ms
D       := max(D_target, D)                        immediately   // rise on the first window
D       := D − DECAY_NS  per window while D_target + 4 ms < D    // DECAY_NS = 1 ms
D       := clamp(D, D_FLOOR = 4 ms, D_CAP = 32 ms)
```

Differences from today, each keyed to a defect:

| today | proposed | fixes |
|---|---|---|
| p99.7 of 300, min-subtracted | p99 of 120, relative to the cadence clock | D4 (one outlier is 1.2 % of the window, not 100 % of the statistic) |
| step = vsync period (8.3 / 16.7 ms) | rise to target, fall 1 ms per window | D5, D4 |
| depth added to `timeline_offset_ns` | depth *is* the release offset | D6 |
| floor 8 ms, cap 32 ms | floor 4 ms, cap 32 ms | 8 ms is a whole frame of latency charged before any jitter is observed; the floor should be below the smallest useful buffer, and the cap stays where PLE-23 put it |

**Release time.** For the head frame `n`:

```
target_ns  = anchor + (n − n0)·T + D
release_ns = align_at_or_after(target_ns, next_vsync_ns, vsync_period_ns)
             − presenter_lead_ns(presenter)        // PLE-106's knob, unchanged
```

and the frame is handed to `AMediaCodec_releaseOutputBufferAtTime(index, release_ns)`
exactly as `release_frame_locked` does today. Note `align_at_or_after`, not
`align_nearest`: with a clock that is already drift-corrected, rounding *down* to a
vsync that has passed is never right.

### 5.5 Queue discipline

Independently of everything above, and worth having even if the policy is rejected
(PLE-108 §6 asks for this too):

* **bounded age**: drop any queued frame whose `target_ns` is more than `2·T` behind the
  newest queued frame's, at push time. This is GFN's `pushFixed` shape with a
  client-chosen bound, since nothing tells us NVIDIA's.
* **head-drop, never producer-block**: `SMOOTHEST`'s `chiaki_cond_wait` on a full queue
  (`:520-521`) applies back-pressure to the decoder output thread, which is the one
  thread in the pipeline that must never stall. Replace with head-drop in every mode.
* **capacity** follows the cap: `ceil(D_CAP / T) + 2` = 4 at 60 fps, not 5 — small
  enough that a full queue is a signal rather than a buffer.

### 5.6 Settings

Per AGENTS.md rule 4, all default to today's behaviour:

| key | type | default | effect |
|---|---|---|---|
| `stream_video_dejitter_enabled` | boolean | `false` | selects `DEJITTER` over `TIMELINE` when the presenter is paced |
| `stream_video_dejitter_floor_ms` / `_cap_ms` | int | `4` / `32` | the clamp; experiment knobs |
| `stream_video_dejitter_queue_age_frames` | int | `2` | the age bound; `0` disables it |

The statistic (`err` histogram, `D_target`, transitions) should be **computed and
logged whenever the presenter runs**, in every pacing mode, even with the setting off —
it is a subtraction and a histogram increment per frame, and it is what makes stage 0
of §9 possible without shipping any behaviour change. That is the same split PLE-108
proposes for its trigger, and the two share the per-frame `err` computation.

One log line per window, in the shape `summarize.py` can grep:

```
Video presenter DJB D=%.1f ms target=%.1f ms (err p50 %.1f p99 %.1f ms, decode %.1f/%.1f ms, drops %u)
```

and `D`, `D_target` and the window's `err` p99 added to
`AndroidChiakiVideoPresenterStats` so they reach the once-per-second overlay next to
the existing `DJB` field.

### 5.7 What this policy deliberately does not do

* **It does not react to source-cadence irregularity.** That is PLE-108's trigger, and
  it is a different time scale. If both are built, `BURSTY` outranks `DEJITTER` in the
  ladder and suspends it, exactly as `vvsync` tears down `adjb`.
* **It does not touch the codec's input timestamps.** Whether real PTS is on is
  irrelevant to it; see §7.
* **It does not measure packet-level Takion jitter.** Hop 3 is deliberately treated as
  part of the network (§2). Sub-frame packet arrival statistics would be a better input
  for a *packet-layer* buffer, i.e. for tuning `TAKION_AV_REORDER_TIMEOUT_US`, which is
  a separate ticket in §10.
* **It does not adapt `T` to a stream running at other than its nominal rate.** A PS5
  title capped at 30 fps inside a 60 fps stream produces `err` of one full period on
  alternate frames; the policy would buy 16.7 ms of buffer for it. Detecting a halved
  source rate is listed in §10 as a follow-up, not solved here.

---

## 6. Interaction with the existing tickets

* **PLE-106** (`stream_video_presenter_lead`) — consumed, not duplicated. §5.4 calls
  `presenter_lead_ns()`.
* **PLE-108** (trigger) — shares the per-frame `err` computation; outranks this policy
  in the ladder; its D1–D3 are prerequisites for both. If only one of the two is ever
  built, build this one: PLE-108 §7.1 says so itself ("if that is the dominant failure
  mode, the correct conclusion is to delete the trigger and make the steady-state policy
  robust instead").
* **PLE-107** (flush-on-transition vs timeline-shift) — measures the recovery strategy
  this note assumes. `DEJITTER` has no timeline to shift, so PLE-107's "flush" arm is
  the only one it can implement; its result decides whether §5.5's head-drop should
  instead be a full flush.
* **PLE-116** (round 3: real PTS + auto operating-rate as default candidates) — owns the
  PLE-16/PLE-23 default decision *as a measurement*. §8 below is the recommendation
  PLE-116 should test, not a substitute for it.
* **PLE-73** (frame-index wraparound over 20 minutes) — `frame_index` is
  `ChiakiSeqNum16`, so `n` wraps every 65 536 frames ≈ 18.2 min at 60 fps. The cadence
  clock needs the same unwrapper PLE-16 added (`chiaki_seq_num_16_unwrap`,
  `video-decoder.c:433`), and inherits PLE-73's open question.

---

## 7. The policy does not need PLE-16

Stated plainly because it is the practical payoff of reading PLE-78 and PLE-75 together:

1. PLE-16's `stream_real_video_timestamps` changes what the app passes to
   `AMediaCodec_queueInputBuffer`.
2. PLE-75 proved that this input is what the Exynos decoder reads to pick a performance
   level: real 60 fps PTS → 13.9 ms mean / 30.7 ms p95; the default 1 µs counter →
   7.9 ms / 9.7 ms. The fix (`operating-rate=480`, auto-applied) restores 7.7–8.1 ms,
   but it is a workaround for a behaviour the client cannot see and did not choose.
3. The cadence clock in §5.4 is indexed by `frame_index`, which reaches the decoder
   unconditionally, and anchored on `t_ready`, which is measured in `lib/`. Neither
   quantity travels through `presentationTimeUs`.

So a de-jitter policy can ship with the codec's input timestamps left exactly as they
are today, and the whole PLE-16 question becomes orthogonal to it. That is a strictly
better position than PLE-108 §6's conclusion that `stream_video_pacing_enabled` must
*imply* `stream_real_video_timestamps`: under this design, it need not.

The one thing real PTS still buys is a decoder-side reorder/timing sanity check and the
`ts` fallback mode. Both are worth keeping as a setting; neither is worth being a
precondition.

---

## 8. Should PLE-16 and PLE-23 stay default-off?

**Yes, both — and the reason has changed.**

The reason has to be re-derived because PLE-75 removed the old one. Round 2 rejected
them for a decode-latency regression (+5.5 ms mean, +18 ms p95, 3.5–4.2 % of frames
never latched). PLE-75 found the cause and fixed it: with
`stream_decoder_operating_rate_auto` on, `v_realpts_auto` decodes in 7.66 ms mean /
9.77 p95 and `v_ple23_balanced` in 8.10 / 10.05, both inside the 8.01–8.23 / 9.79–10.39
baseline band, with never-latched back to 1.1–1.6 % against 0.7–1.5 % for the baselines.
The regression is gone.

What remains is worse than a regression: **an untested code path.**

| round | scenario | what the presenter actually did |
|---|---|---|
| 1 (PLE-47) | `ple23_balanced`, `ple23_lowest` | attached at 120 Hz → `timestamped release disabled at 120 Hz`; SF then dropped the panel to 60 Hz and the presenter never found out (D9). Immediate release at 60 Hz, 15–17 % of frames never latched. |
| 2 (PLE-64) | `ple23_balanced`, `ple23_lowest`, `ple16_realpts` | PLE-54 pinned 120 Hz; `timestamped_release_eligible()` is false ≥ 119 Hz, so immediate release again. |
| PLE-75 | `exp4_pacing_lowest`, `v_ple23_balanced` | 120 Hz throughout; `exp4`'s row is *identical* to plain real PTS, which PLE-75 itself notes means "presenter not involved". |

In every capture ever taken on this hardware, `stream_video_pacing_enabled=true`
produced **immediate release plus stale-frame dropping**. `enqueue_paced_frame` has
never run; `record_arrival_locked` has never been called; no `DJB adjusted` line has
ever appeared. Both rounds' numbers for PLE-23 are numbers for *the direct path with
real PTS turned on*, and the difference between `balanced` and `lowest_latency` that
round 1 and round 2 tabulate is, on this evidence, the difference between two
drop-stale predicates and nothing else.

Recommendations, in the form PLE-116 can execute:

| flag | recommendation | condition to revisit |
|---|---|---|
| `stream_real_video_timestamps` (PLE-16) | **stay off** | PLE-116 may flip it if round 3 shows it inside the baseline band on every column *and* names a benefit; "no longer harmful" is not a reason to change a default. PLE-73's 20-minute wraparound run is a prerequisite either way. |
| `stream_video_pacing_enabled` (PLE-23) | **stay off** | not flippable at all until one capture exists in which the paced path executed. That needs `stream_display_refresh_rate=string:match_stream` (60 Hz) **and** D9 fixed, because at 60 Hz the panel is where round 1 found it and the presenter is where round 1 left it. |
| `stream_decoder_operating_rate_auto` (PLE-75) | **stay on** | already correct; it is inert unless PLE-16/23 are on. |
| the combination pacing-on + real-PTS-off | **make it unreachable or make it safe** | today it is the *default* combination when a user enables pacing alone, and D1 makes it peg the buffer at 32 ms. Under §5.1 it becomes safe for free (`DEJITTER` ignores PTS); until then it is a latent 24 ms latency bug behind one settings toggle. |

---

## 9. How it would be measured

`scripts/dev/ab/` (PLE-60) unchanged in shape: one build, one night, one phone
(SM-S908B), PS5-466, 15 s Perfetto traces, `summarize.py` at the end, at least two
baselines per round, `app-state.sh backup` first, `install -r` only, the phone held for
the whole round by `device.py run PLE-NN`.

**The harness needs one thing it does not have: a 60 Hz scenario that stays at 60 Hz.**
`stream_display_refresh_rate=string:match_stream` requests it (`StreamActivity.kt:379`),
and round 1's `ple7_match60` shows the panel does go there — but D9 means the presenter
must be fixed before the resulting capture means anything. **Fixing D9 is therefore a
prerequisite of every scenario below, not an item in the list.**

### Stage 0 — instrument only, no behaviour change

Build with `err`, `D_target`, the decode EWMA and the per-window log line computed in
every pacing mode, and `stream_video_dejitter_enabled=false`.

| scenario | prefs on top of defaults | question |
|---|---|---|
| `dj_base_120` | none | what is `err` p50/p99 on a clean LAN, on the path users actually run? |
| `dj_base_60` | `stream_display_refresh_rate=string:match_stream` | same at 60 Hz, and does the presenter now log the *right* refresh rate (D9 regression test)? |
| `dj_base_60_touched` | + `touched=1` | does gameplay change `err`? (round 2 found touch changes decode by 40 %; `err` must not move, since it is measured upstream of the decoder — this is the D2 control) |
| `dj_reorder_off_60` | + `stream_takion_video_packet_reordering_disabled=boolean:true` | how much of `err` is hop 3's 16 ms head-of-line wait? |
| `dj_loaded_60` | as `dj_base_60`, second device pulling a bulk download through the same AP | does `err` p99 move under real congestion? |

Pass/fail, each of which kills or rescales the design:

* **P0-a.** `dj_base_60` and `dj_base_60_touched` have `err` p99 within 1 ms of each
  other. If touch moves `err`, the measurement point is not upstream of the decoder
  after all and §5.2 is wrong.
* **P0-b.** `dj_base_*` has `err` p99 ≤ 4 ms, i.e. `D_target` ≤ `D_FLOOR + D_min_decode`.
  If a clean LAN already needs more than the floor, every constant in §5.4 must be
  rescaled to the measured distribution before stage 1.
* **P0-c.** `dj_loaded_60` has `err` p99 ≥ 8 ms. If congestion visible on screen does
  not move the statistic, there is nothing to de-jitter and the ticket ends with a
  negative result.
* **P0-d.** `dj_reorder_off_60` differs from `dj_base_60` by a measurable amount, in
  either direction. If it does not, hop 3 is inert on a clean LAN (likely) and the two
  knobs can be tuned independently after all.
* **P0-e (free).** `dj_base_60`'s logcat contains `timestamped_release=enabled` and at
  least one window's `DJB` line — the first evidence in this project's history that the
  paced path executes.

### Stage 1 — the A/B proper

Only if stage 0 passes. Same scenarios with `stream_video_dejitter_enabled=true`, each
paired against its stage-0 twin on the same build.

* **P1-a (no cost when idle).** `dj_base_60`: queue→present p90 within the baseline band
  and `D` resting at `D_FLOOR` for the whole trace. A de-jitter buffer that buys latency
  on a clean LAN is a regression, full stop.
* **P1-b (it helps when loaded).** `dj_loaded_60` against its twin: never-latched share
  down, and queue→present p99 down or unchanged. The mean is *expected* to rise by up to
  `D`; that is the trade being bought, and it is only worth it if the tail pays for it.
* **P1-c (it gives the latency back).** After the bulk download stops, `D` returns to
  within 2 ms of `D_FLOOR` within 20 s (the 1 ms-per-2 s decay from a 32 ms cap is 60 s
  worst case — if that is too slow in practice, `DECAY_NS` is the knob, and this test is
  how you learn it).
* **P1-d (against the incumbent).** `dj_base_60` and `dj_loaded_60` also run with
  `stream_video_pacing_enabled=true` + `stream_real_video_timestamps=true` +
  `stream_video_dejitter_enabled=false` — i.e. today's `ts`+`adjb`. This is the only
  comparison that answers PLE-83's actual question, and it has never been run.

Harness addition: `summarize.py` must lift `D`, `D_target`, `err` p50/p99 and the drop
count from the per-window log line into `<scenario>.json`, the way it already lifts
`Number Missed Vsync` from `gfxinfo`.

**What this cannot settle.** Congestion on the AP is not source-cadence irregularity
(PLE-108 §8 makes the same caveat). And a 15 s trace contains 7 windows; `D`'s decay
behaviour needs the PLE-57 soak harness, not `ab.sh`.

---

## 10. Not established

* Nothing here ran. No phone, no PS5, no GFN session in this ticket.
* D4–D9 are read off the source at `4743daf6` and off the two A/B write-ups. D9's second
  half (the vsync-period EWMA cannot cross a 2× mode change) is arithmetic on
  `video-presenter.c:301-310`, not an observation; P0-e would confirm it by contrast.
* Every constant in §5.4 and §5.5 (`W`, `D_FLOOR`, `D_GUARD`, `DECAY_NS`, `SLEW_NS`,
  age `2·T`, capacity 4) is a proposal derived from GFN's behaviour and from the
  constants already in `video-presenter.c`. None is measured.
* The claim that `t_ready` is free of decoder-induced variance is structural, not
  measured; P0-a is the test.
* What GFN's server actually sends as its `DYANAMIC_DEJITTER_PARAMS` struct
  (`ANALYSIS.md` idx 18, `0x1b0a0`) is unknown, so none of the depths here is matched
  against NVIDIA's own choice.

---

## 11. Proposed tickets

In dependency order. Estimates are the usual 1/2/3/5/8 scale.

| # | title | est | notes |
|---|---|---|---|
| 1 | **Presenter learns its refresh rate: `DisplayManager.DisplayListener` → `set_timing`, and a vsync-period estimator that survives a 2× mode change** (D9) | 3 | Prerequisite for every other item and for any 60 Hz capture. Two defects, one fix. Behind no setting — it is a bug fix, current behaviour is not a behaviour anyone chose. |
| 2 | **Unconditional per-frame timing metadata: `(frame_index, t_ready)` from `lib/` to the presenter** (§5.2) | 5 | Extends the video sample callback (all frontends must keep compiling — same shape as PLE-16), moves the input-timestamp ring out of `diagnostics_enabled`, fixes the split-buffer gap at `video-decoder.c:488`. No behaviour change. |
| 3 | **Stage-0 instrumentation: `err` histogram, `D_target`, decode EWMA, per-window log line, overlay fields** (§5.6) | 3 | Computed in every pacing mode, changes no release decision. Shares the per-frame `err` with PLE-108's trigger — whichever lands first owns the computation. |
| 4 | **A/B stage 0** (§9) | 5 | `resource:samsung`. Answers P0-a…P0-e and PLE-108's P0-b in the same night. Needs #1–#3. |
| 5 | **Bounded-age head-drop and no producer blocking in the presenter queue** (§5.5, D7) | 3 | Independent of the policy; PLE-108 §6 asks for it too. Behind `stream_video_dejitter_queue_age_frames`. |
| 6 | **The `DEJITTER` release policy** (§5.1, §5.4) behind `stream_video_dejitter_enabled` | 8 | Only after #4 passes and its constants have been rescaled to the measured distribution. |
| 7 | **A/B stage 1** (§9) | 5 | `resource:samsung`. Includes P1-d, the first head-to-head of `DEJITTER` against today's `ts`+`adjb`. |
| 8 | **Tune `TAKION_AV_REORDER_TIMEOUT_US` against the measured hop-3 contribution** (§2) | 3 | Only if P0-d says hop 3 is visible. The 16 ms constant has never been examined; it is a whole frame period of head-of-line blocking. |
| 9 | **Detect a halved source frame rate (30 fps content in a 60 fps stream) and adapt `T`** (§5.7) | 5 | Research. Also the scenario PLE-108 §8 names as the only real test of a source-cadence trigger. |
| 10 | **Retire `TIMELINE` or make it unreachable without real PTS** (§8, last row) | 2 | The pacing-on + real-PTS-off combination is reachable from the settings screen today and pegs the buffer at 32 ms (D1). Cheapest possible fix if #6 slips. |
