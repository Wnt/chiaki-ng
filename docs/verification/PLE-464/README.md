# PLE-464 — why a total outage is invisible, and the one measurement that fixes it

**Answer up front.** `takion_packets_lost` cannot see a total outage, and not by a small
margin — the counter is only ever raised *when a packet arrives*. PLE-404 was right that
there was nothing to tune. What was missing was a measurement, not a threshold, so this
ticket adds one: the longest gap between two inbound datagrams in each 1 Hz window. On
device, under `roam-3000ms`, the badge now reads **POOR in 5 blackouts of 5** where it
read GOOD for 94 % of the same phase before, and reads **GOOD for 100 %** of both clean
phases with **zero** samples over the cut in `clean`, `blip-200ms`, `4g` or `wifi-slow`.

---

## 1. The root cause, from source

`takion_packets_lost` is `ChiakiPacketStats.gen_lost_total`. Exactly one function raises
it:

```c
// lib/src/frameprocessor.c:199
CHIAKI_EXPORT void chiaki_frame_processor_report_packet_stats(ChiakiFrameProcessor *frame_processor, ChiakiPacketStats *packet_stats)
{
	uint64_t received = frame_processor->units_source_received + frame_processor->units_fec_received;
	uint64_t expected = frame_processor->units_source_expected + frame_processor->units_fec_expected;
	chiaki_packet_stats_push_generation(packet_stats, received, expected - received);
}
```

and exactly one site calls it:

```c
// lib/src/videoreceiver.c:158-163, inside chiaki_video_receiver_av_packet()
	// next frame?
	if(video_receiver->frame_index_cur < 0 ||
		chiaki_seq_num_16_gt(frame_index, (ChiakiSeqNum16)video_receiver->frame_index_cur))
	{
		if(video_receiver->packet_stats)
			chiaki_frame_processor_report_packet_stats(&video_receiver->frame_processor, video_receiver->packet_stats);
```

**It runs when a packet arrives.** Nothing arriving means nothing counted, so
`streamconnection.c:440-441`'s per-window deltas —

```c
			stats_event.stream_stats.takion_packets_received = packets_received - previous_packets_received;
			stats_event.stream_stats.takion_packets_lost = packets_lost - previous_packets_lost;
```

— are both `0`, `expected_per_s == received_per_s`, and `NetworkQuality.kt`'s `packetLoss`
is `0/0`. That is PLE-404's observation over 920 stats lines, explained.

There is a second layer under it. `chiaki_frame_processor_alloc_frame()` runs on the first
arriving unit of a frame, so a frame **no unit of which arrived** is never instantiated and
its units are never entered as `units_source_expected` either. Such frames are noticed only
as `"Detected missing or corrupt frame(s)"` (`videoreceiver.c:176`) and folded into
`video_frames_lost`.

So the counter's meaning is **units missing from frames that partly arrived** — intra-frame
FEC-generation loss. A burst that kills whole frames is outside its definition. No cut on it
can reach a blackout, at any threshold, ever.

## 2. What signal *is* available

PLE-423 already stamps every inbound datagram before decryption
(`takion_note_receive`, `lib/src/takion.c`), for the 10 s link watchdog. That stamp is the
signal; the watchdog's deadline is simply too coarse to read it at. Mined out of the
existing captures' `StreamConnection link: silence N ms` line, the 1 Hz series separates the
fault classes with no overlap at all — the property PLE-404 could not find in jitter:

| profile | worst 1 Hz silence sample | source |
|---|---|---|
| `clean` (6 phases, 772 samples) | **19 ms** | ple404, ple404b, ple423-blip, ple423-impair |
| `4g` | 29 ms | ple423-impair |
| `wifi-slow` | 31 ms | ple423-impair |
| `blip-200ms` | 199 ms | ple423-impair |
| `roam-1200ms` | 1130 ms | ple404b |
| `roam-3000ms` | **2706 ms** | ple404b |

One thing that series *cannot* do is carry a threshold on its own: it is the silence still
**running** at the poll instant, so an outage that opens and closes between two polls is
invisible to it and one that ends just after a poll is reported at a fraction of its width.
That is why `roam-1200ms`'s five outages read back as 264–1130 ms rather than ~1200 ms each.

## 3. The change

Three files of plumbing and one arm; no heuristic anywhere.

- **`lib/src/takion.c`** keeps a *window-scoped* maximum inter-arrival gap beside PLE-423's
  session-cumulative one. `chiaki_takion_take_window_max_receive_gap_ms()` reads and resets
  it, so each diagnostics window reports its own worst gap exactly once — including a gap
  that has already closed. The fold itself (`chiaki_takion_receive_gap_fold()`) is exported
  so its clock-race and wrap guards are testable on the host without a socket.
- **`lib/src/streamconnection.c`** puts that and the instantaneous silence, which it already
  computes for the watchdog, into `ChiakiStreamStats`.
- **`chiaki-jni.c` / `Chiaki.kt`** carry them to `StreamStatsEvent` as
  `takionMaxReceiveGapMillis` / `takionSilenceMillis`, and add
  `takion_silence_ms … window_max_gap_ms` to the 1 Hz stats line.
- **`NetworkQuality.kt`** gains a third arm on that one input, *beside* the two arms
  PLE-411 confirmed. Their cuts are untouched.

The two fields are read as `max(gap, silence)` and neither is redundant: a second in which
*nothing at all* arrived has no arriving packet to close a gap with, so it reports silence
and not gap; the second after it reports the whole gap. §5's series shows both.

One more thing had to move. `update()` returned UNKNOWN when no packets were counted and the
console's quality payload was absent — which is the exact shape of the second this arm
exists to read. A window with a measured silence is now classified.

**Rule 4.** A badge reading GOOD while the picture is frozen is wrong, not an alternative
worth measuring, so this ships on and unflagged.

## 4. The cuts, and the clean margin

Derived from `build/captures/ple464`, one continuous LAN stream with all five profiles
stepped under it, so every number below is measured on the same stream, phone and console.
`SM-S908B` (`192.168.40.101:5555`), PS5-466 (`192.168.1.164`), 120 s per phase, APK
freshness confirmed by PLE-410's guard (built from `15fbdfd011f6`), `install -r` after
`app-state.sh backup`.

| phase | n | worst gap ms | p95 | median | samples ≥ 500 | ≥ 1000 |
|---|---|---|---|---|---|---|
| 01_clean | 115 | 42 | 21 | 18 | **0** | **0** |
| 02_blip-200ms | 113 | 212 | 24 | 18 | **0** | **0** |
| 03_4g | 115 | 39 | 35 | 29 | **0** | **0** |
| 04_wifi-slow | 115 | 56 | 47 | 36 | **0** | **0** |
| 05_roam-3000ms | 113 | **3055** | 23 | 17 | **5** | **5** |
| 06_clean | 115 | 25 | 21 | 18 | **0** | **0** |

Five samples over the cut in `roam-3000ms` is five outages of five — full coverage, not a
majority.

| cut | value | worst non-outage sample | margin |
|---|---|---|---|
| `STALL_MS` (CONSTRAINED) | 500 ms | 212 ms (`blip-200ms`) | **2.4×** |
| `STALL_MS` vs clean alone | 500 ms | 42 ms | **11.9×** |
| `POOR_STALL_MS` | 1000 ms | 212 ms | **4.7×** |

Nothing between **212 ms and 3039 ms** occurs anywhere in the capture, and nothing between
199 ms and 1071 ms occurs in PLE-404's two. Both cuts land in an empty band rather than
inside a distribution — the test PLE-404's candidate jitter tier failed at **−0.11 ms**.

## 5. The blackout, per second

The fourth outage of `05_roam-3000ms`, straight out of `analyze.py`. Every pre-existing
input is at its clean-phase value throughout — jitter 1.41 ms, loss 0.00 %, both tail rates
0.00 — while `video received` goes 60 → 45 → 0 → 0 and the new input goes 249 → 1252 → 2253
→ 3039:

```
 566.9s GOOD  NONE  jit=1.24 loss=0.00 recv= 60 | tail j=0.00 l=0.00 | gap=  20 silence=  12 stall=  20
 568.0s GOOD  NONE  jit=1.41 loss=0.00 recv= 45 | tail j=0.00 l=0.00 | gap=  20 silence= 249 stall= 249
 568.9s POOR  LAN   jit=1.41 loss=0.00 recv=  0 | tail j=0.00 l=0.00 | gap=   0 silence=1252 stall=1252
 569.9s POOR  LAN   jit=1.41 loss=0.00 recv=  0 | tail j=0.00 l=0.00 | gap=   0 silence=2253 stall=2253
 570.9s POOR  CON.  jit=2.86 loss=91.27 recv= 3 | tail j=0.00 l=0.20 | gap=3039 silence=   1 stall=3039
 572.0s POOR  LAN   jit=1.02 loss=0.00 recv= 60 | tail j=0.00 l=0.20 | gap=  17 silence=   1 stall=3039
```

The badge reaches POOR **within the first fully silent second**, not after the outage ends.
It then holds for the 5 s the sample takes to age out of the window plus the
`RECOVERY_SAMPLES` confirmations at each step down — 10 s, POOR → CONSTRAINED → GOOD, never
POOR → GOOD (PLE-357), which
`theStallArmRecoversInTheSameEightSecondsAsTheTailArm` asserts exactly.

## 6. No new false positives

The badge's verdicts per phase, against `build/captures/ple423-impair` read with PLE-366's
unmodified `analyze.py` as the before:

| phase | before (ple423-impair) | after (ple464) |
|---|---|---|
| clean | GOOD 100 % | GOOD 100 % (both clean phases) |
| blip-200ms | GOOD 81 % / CONSTRAINED 19 %, POOR 0 | GOOD 75 % / CONSTRAINED 25 %, POOR 0 |
| 4g | POOR 100 % | POOR 100 % |
| wifi-slow | POOR 100 % | POOR 100 % |

`4g` and `wifi-slow` already read POOR before this ticket, on the median arm's jitter
(11.9–12.8 ms against `POOR_JITTER_MS = 10.0`) — PLE-343's finding, unchanged. The stall arm
is provably not what fires there: **0 samples over its cut in either phase** (§4). The
`blip-200ms` difference is the same two arms reading a different run of the same profile;
the stall arm contributed 0 samples there too.

## 7. PLE-418's pattern guard, on a non-blind witness

The capture failed the guard on its first reading, and the guard was right to be suspicious
and wrong about why: it counts excursions in the app's **jitter and loss** samples, which
are the blind inputs this whole ticket is about. It saw 7 excursions of median width 1 in
`roam-3000ms`, where the wire had 5 outages 3 s wide, and 4 of 6 pulses in `blip-200ms`.
PLE-404's ping witness could not rescue either: `blip-200ms` is *delay*, so the ping never
loses a reply.

Since this capture is the first that carries a measurement of the wire, the guard now
prefers it — `load_link_gap_samples()`, falling back to jitter/loss when the field is absent.
On the same phases it reads 5 outages of width 4 and 5 pulses of width 1, and both pass.
The cut is 100 ms: 1.8× the worst second any non-dynamic profile produced (`wifi-slow`,
56 ms) and half the smallest real pulse (201 ms), i.e. inside the empty band between them.

Verdicts on every existing capture on this box are unchanged, because none of them carries
the field: ple356 PASS, ple411 PASS, ple404 PASS, ple404b PASS, ple366 / ple423-blip /
ple423-impair FAIL exactly as before. `test_verify_blip_pattern.py` covers the new source in
6 further cases (14 total, no device), including that a capture without the field reads
exactly as it did.

## 8. Not proven

- **Nothing here says what the player sees.** "Unplayable" is argued from the frame counters
  (60 → 0 for three seconds) and the silence, not from a recorded video or a human.
- **`roam-1200ms` was not re-captured** with the new field. Its 1.2 s outage is 2.4× the
  CONSTRAINED cut and 1.2× the POOR cut, so the arm should reach POOR on it, but the only
  `roam-1200ms` data on this box predates the field and reads the *instantaneous* silence
  (1071–1130 ms worst per phase, 264–1130 ms per outage). Filed as a follow-up.
- **Only one blackout width was captured.** The band between `blip-200ms`'s 212 ms and
  `roam-3000ms`'s 3039 ms is empty because nothing in that range was ever run, not because
  it was run and found empty. A profile at 500–800 ms would test where the cut actually
  sits.
- **The lockless reset is a benign race, argued rather than measured.** The window maximum is
  reset by a plain 32-bit store from the stats thread, so a gap closing in that same instant
  can be dropped; the next window reports it anyway. No test exercises the race.
