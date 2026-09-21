# PLE-485: frames discarded while waiting for an IDR are counted in no loss figure

**Answer up front.** A separate session-cumulative counter,
`video_frames_discarded_for_idr` (native: `ChiakiVideoReceiver.frames_discarded_for_idr_total`),
now counts P-frames that arrived and were fully assembled but thrown away in
`chiaki_video_receiver_flush_frame()`'s "Skipping P-frame ... while waiting for IDR" branch.
It is deliberately not folded into `video_frames_lost` -- PLE-474/PLE-475 pinned that field's
meaning to transport loss, and this is the opposite: the frame arrived intact.

On a real `roam-3000ms` device capture, the new counter never moved -- **not because the fix
is wrong, but because the code path it measures is dead on Android today.** The only place
that ever sets `waiting_for_idr = true` is gated by
`session->connect_info.enable_idr_on_fec_failure` (`lib/src/videoreceiver.c`), and the
Android JNI layer (`chiaki-jni.c`'s `session_create`) never sets that field, so it is always
`false`. Recovery from a lost/undecodable frame on Android goes through the
missing-reference-frame path instead (already counted in `video_frames_lost` since PLE-474,
unchanged here). The honest answer to "is the recovery cost material" is stronger than
"small": **it is zero, provably, until something turns `enable_idr_on_fec_failure` on.**

## 1. The two counts, from a device capture

`build/captures/ple485`: SM-S908B `192.168.40.101:5555`, PS5-466 `192.168.1.164`. APK built
from `1f1629e05f39` (PLE-410's freshness guard: OK, matches
`session.h`'s last change `28614fa30161`). App state backed up first, installed with
`install -r`. `clean -> roam-3000ms -> clean`, 120 s/phase, `docs/verification/PLE-476/capture.sh`.

| phase | `video_frames_lost` (`lost` in logcat) | `video_frames_discarded_for_idr` (`discarded_for_idr`) |
|---|---|---|
| 01_clean | 0 -> 0 | 0 -> 0 |
| 02_roam-3000ms | 0 -> 965 | 0 -> 0 |
| 03_clean | 965 -> 1098 (see caveat below) | 0 -> 0 |

- **`video_frames_lost`** rose by 965 over the `roam-3000ms` phase -- in the same range as
  PLE-474's own capture (181-201 per outage, ~976 total across 5 outages; this run's 5
  outages summed to 965). `video_frames_lost`'s value and meaning are unchanged by this
  ticket: same code path, same mutex, same accounting. This confirms requirement 3 directly.
- **`video_frames_discarded_for_idr`** stayed 0 through every phase, impaired or clean. Corroborated
  by `grep -c` over the full 20k-line `session_logcat.txt`: `requested IDR`, `FEC failed,
  waiting for IDR`, `Received IDR frame`, and `Skipping P-frame` all occur **zero** times.
  What *does* occur 224 times is `Failed to complete frame` / `Missing reference frame N for
  decoding` -- the reference-frame recovery path PLE-474 already accounts for. This
  satisfies requirement 4 (clean-phase behaviour): the new field produces no signal at all
  on this build, impaired or not.
- **Caveat on 03_clean's +133:** `docs/verification/PLE-476/capture.sh`'s own dynamic-profile
  pattern guard flagged both clean phases (`pattern_check.txt`): 1 excursion 44 s into
  01_clean, 12 clustered excursions 78-92 s into 03_clean -- impairment leaking past the
  phase boundary. This matches the known rig issue (`ip_forward` unpersisted / `dnsmasq`
  gaps after a reboot; see `impairment-rig-reboot-gaps` in project memory) with fixes
  written but not deployed to the rig -- not a regression from this change. It explains
  03_clean's `video_frames_lost` growth (leaked impairment causes real transport loss) and
  is irrelevant to `video_frames_discarded_for_idr`, which never moved regardless of phase or
  leak.

Reproduce:

```bash
OUT_DIR=build/captures/<yours> PROFILES="clean roam-3000ms clean" \
  scripts/dev/device.py run <ticket> -- bash docs/verification/PLE-476/capture.sh
```

## 2. Where the discarded count belongs

A new field, kept separate from `video_frames_lost`:

- **Native:** `ChiakiVideoReceiver.frames_discarded_for_idr_total` (`videoreceiver.h`/`.c`),
  incremented at the exact "Skipping P-frame" return site, under the same
  `frames_lost_mutex` as the other counters. Getter:
  `chiaki_video_receiver_get_frames_discarded_for_idr_total()`.
- **Stats event:** `ChiakiStreamStatsEvent.video_frames_discarded_for_idr` (`session.h`),
  session-cumulative like `video_frames_lost`, set in `streamconnection.c`'s 1 Hz tick.
- **JNI/Kotlin:** appended to `eventStreamStats`'s JNI call and `StreamStatsEvent`
  (`Chiaki.kt`), and to the logcat `Feedback stats:` line and the on-screen diagnostics
  overlay (`StreamDiagnostics.kt`, `discarded-for-idr %d`) for visibility during a capture.
- **Not touched:** `NetworkQuality.kt` and its arms, `StreamSummary`'s `video_frames_lost`
  handling, PLE-464's stall field, PLE-423's watchdog. Nothing reads the new field.

Why not fold it into `video_frames_lost`: that field's whole value, established across
PLE-474/PLE-475, is "did the transport fail to deliver this frame." A discarded P-frame is
the opposite case -- every unit of it arrived. Merging the two would make `video_frames_lost`
answer a different question depending on build configuration (whether
`enable_idr_on_fec_failure` is set), which is exactly the kind of blurring PLE-474/PLE-475
were written to prevent.

## 3. Is the recovery cost material?

No -- and not marginally so. `enable_idr_on_fec_failure` is wired into the Qt desktop GUI
(`gui/include/streamsession.h`) but never set from `chiaki-jni.c`'s `session_create()`, so
Android's `ChiakiConnectInfo.enable_idr_on_fec_failure` is always `false`. The
`waiting_for_idr` flag it gates never becomes `true`, so `chiaki_video_receiver_flush_frame()`'s
IDR-gate branch (the `if(chiaki_video_receiver_get_waiting_for_idr(...))` block) is reached on
every H.264/H.265 slice, but the discard path inside it is unreachable: `waiting_for_idr` is
always false, so control always takes the "not waiting" path. Zero-cost to measure, zero
frames counted, on every build shipped to the test phone today.

This does **not** mean the counter is wasted: `enable_idr_on_fec_failure` is a real, if
currently Android-dormant, recovery strategy (PS5/PS4 Remote Play's console side supports
IDR requests), and if a future ticket wires it into the Android connect path, this counter
starts reporting a real number with no further plumbing. The cost of having added it now is
one `uint64_t`, one mutex-protected increment already on a locked path, and one appended
field through the existing stats plumbing -- cheaper than re-deriving all of this if the flag
is ever turned on and someone asks the same question again.

## 4. Host tests

`test/videoreceiver.c` (`/chiaki/video_receiver/*`), driven through the real
`chiaki_video_receiver_av_packet()` public API with real H.264 NAL fixtures (the same slice
bytes `test/bitstream.c` parses directly), not a re-implementation of the branch:

- a P-frame that arrives whole while `waiting_for_idr` is armed is discarded, counted once in
  `frames_discarded_for_idr_total`, and does **not** touch `frames_lost_total`
- a P-frame arriving with `waiting_for_idr` unset is not discarded
- the next I-frame after a discard clears `waiting_for_idr` and is itself not counted as
  discarded

`test/chiaki-unit-cases.txt` regenerated on this branch (PLE-399): +3 cases.

## 5. Classifier behaviour

Unchanged. Nothing in `NetworkQuality.kt` or its arms reads `video_frames_discarded_for_idr`.
Whether anything should is PLE-486's question, dispatched separately.
