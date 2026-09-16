# PLE-57 — PLE-19 (4 ms feedback interval) over two 16-minute PS5 sessions

Answers the PLE-19 acceptance criterion ("30-minute session at 4 ms with continuous
stick input: no disconnects, no increase in console-side dropped feedback") and
absorbs PLE-45.

**Verdict: keep 4 ms as an opt-in setting. Do not promote it to default yet** — see
*What this does not prove*.

## Hardware and build

| | |
|---|---|
| phone | Galaxy S22 Ultra, SM-S908B, serial `192.168.1.105:36437` |
| console | PS5-466, `192.168.1.164:9302`, `id=C0151B3DD8BC system=13600007 ready` |
| build | `jonni/ple-57-…` at the commit below, `assembleDebug`, installed with `install -r` |
| date | 2026-09-16, 06:32–07:04 device local time |
| harness | `scripts/dev/ab/soak.sh` (start / drive / collect) in the workspace repo |

`scripts/dev/app-state.sh backup` ran first:
`/home/wnt/.config/pleikkari/backups/fork-app-20260916T033057Z/data.tar`
(`registered_host rows=1 nickname=PS5-466`).

## Why a code change was needed first

The session log contained **no record of a feedback packet at all**, so "count feedback
packets per second in the session log" was not possible against the shipped build.
This branch adds a counter in the feedback sender thread that emits one line per window:

```
[I] Feedback stats: window 1001 ms state 120 history 0 total 120 | per_s total 119.880 state 119.880 history 0.000
```

It is off by default (`ChiakiConnectInfo.feedback_stats_log_interval_ms == 0`), behind
the new **Log feedback packet rate (diagnostic)** switch
(`stream_feedback_stats_log`, default false), which selects a 1000 ms window.

## Method

Two halves on the same build and the same night, differing in exactly one preference
(`stream_feedback_reduced_interval`), with the stats switch on in both:

* **off8ms** — PLE-19 off, `state_min_interval_ms` = 8 (upstream default)
* **on4ms** — PLE-19 on, `state_min_interval_ms` = 4

Each half: write prefs through `run-as`, launch, tap the PS5-466 tile, wait for both
Takion init acks, then drive the on-screen left stick continuously for ~16 minutes and
leave with BACK. The input loop runs on the device (`while …; do input swipe 260 900
420 760 200; input swipe 420 760 260 900 200; done`) in one adb call per chunk — the
queue front-end adds hundreds of ms per call, which would make per-gesture adb
intermittent rather than continuous.

No human was available, so this is **synthetic touch input on the on-screen stick**,
not a physical DualSense.

## Result

| | off8ms (8 ms) | on4ms (4 ms) |
|---|---|---|
| windows / covered | 962 / 966 s (16.1 min) | 953 / 955 s (15.9 min) |
| feedback packets total | 116 041 | 212 184 |
| **packets/s mean** | **120.2** | **222.2** |
| packets/s p05 / p50 / p95 | 117.8 / 120.3 / 122.3 | 213.6 / 222.8 / 230.0 |
| packets/s min / max | 113.3 / 123.4 | 187.6 / 237.3 |
| history (button) packets | 0 | 0 |
| windows below 20 packets/s | 0 | 0 |
| mid-session disconnects | 0 | 0 |
| `[E]` / `[W]` during streaming | 0 | 0 |
| ctrl heartbeats answered | 193 | 191 |
| decoder / ACodec / ANR / FATAL in logcat | 0 | 0 |

Reading:

* The setting does what it claims. 120.2/s is the 8 ms ceiling (125/s) minus scheduling
  slack; 222.2/s is the 4 ms ceiling (250/s) minus the same. A **1.85× rate increase**,
  so the limiter, not the input source, was the binding constraint in both halves —
  the test actually exercised the reduced interval.
* **No disconnect, no error and no warning after the handshake in either half.** The only
  `[E]`/`[W]` lines are the `Received Session Id is too short` pair at line 30–34, which
  appears identically in both halves *before* streaming starts and is a pre-existing
  handshake quirk, not a 4 ms effect.
* Heartbeat cadence is unchanged (193 vs 191 over ~16 min), so the console was not
  falling behind on the ctrl channel at 222 packets/s.
* The `[W]`/`[E]` and disconnect lines at the end of each log are the clean BACK exit
  (`StreamConnection sending Disconnect` → `Session has quit`).

## What this does not prove

* **No button presses were sent.** The on-screen stick produces only feedback *state*
  packets; `history` was 0 in both halves, so the PLE-19 criterion "no increase in
  console-side dropped feedback (watch for missing button presses)" is **untested**.
  That needs a physical controller and a human watching the TV.
* **No console-side measurement.** Everything here is client-side. Whether the PS5
  silently dropped state packets at 222/s cannot be seen from the phone.
* **Latency was not measured.** This run answers "does 4 ms survive and at what rate",
  not "is input faster". No Perfetto trace was taken (a 16-minute trace is impractical);
  use `scripts/dev/ab/ab.sh` for that.
* **One run per arm**, not a repeated study.

## Recommendation for PLE-19

**Keep 4 ms, keep it opt-in.** The console tolerated a sustained 222 packets/s for
16 minutes with zero disconnects and zero errors, which clears the disconnect and
stability half of the acceptance criterion. Promoting it to default should wait until
someone has driven a real DualSense through a button-heavy session and confirmed no
dropped presses.

## Evidence

Under `build/dispatch/ple-57/captures/` (gitignored — never commit these):

* `off8ms_session.log` (157 884 B), `on4ms_session.log` (156 424 B) — the counted logs
* `off8ms_logcat.txt` (17.9 MB), `on4ms_logcat.txt` (19.0 MB)
* `off8ms_prefs_on_device.xml`, `on4ms_prefs_on_device.xml` — prefs read back off the phone
* `off8ms_hosts.png` / `off8ms_end.png`, `on4ms_hosts.png` / `on4ms_end.png`

Gate for this branch: `scripts/dev/gate.sh` → `GATE: PASS`
(host configure/build/ctest + `assembleDebug`, APK 20 537 560 B).
