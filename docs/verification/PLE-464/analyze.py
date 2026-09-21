#!/usr/bin/env python3
"""PLE-464: read the badge's 1 Hz verdict and the new stall input out of a capture.

PLE-366's analyze.py with one column added: `stall_ms`, the longest gap between two
inbound datagrams inside each one-second window, which is the input the stall arm reads
and the only one that can see a total outage (`takion_packets_lost` cannot -- see
lib/src/videoreceiver.c:162, the single site that raises it, which runs only when a
packet arrives).

Two tables, because the ticket asks two questions of one run: what the badge said per
phase, and -- for the derivation -- the worst gap each profile produced, which is what
the cuts in NetworkQualityThresholds have to sit between.
"""
import os
import re
import sys

REPO = os.environ.get("REPO", "/home/wnt/gta6")
_FB_MODULE = os.path.join(REPO, "scripts", "dev", "feedback_stats.py")
if not os.path.isfile(_FB_MODULE):
    sys.exit(f"analyze.py: feedback_stats.py not found at {_FB_MODULE} (set REPO= to override)")
sys.path.insert(0, os.path.dirname(_FB_MODULE))
import feedback_stats as fb  # noqa: E402

OUT = sys.argv[1] if len(sys.argv) > 1 else "/home/wnt/gta6/build/captures/ple464"
stamp = fb.stamp

Q = re.compile(
    r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d).*Quality badge: level (\w+) cause (\w+)"
    r" \| median rtt_ms ([\d.]+) jitter_ms ([\d.]+) loss_pct ([\d.]+)"
    r" \| tail_rate jitter ([\d.]+) loss ([\d.]+) cut ([\d.]+)"
    r" \| stall_ms ([\d.]+) cut ([\d.]+) poor ([\d.]+)")
FS = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d).*Feedback stats: window")
JIT = re.compile(r"packet_jitter_ms (\d+\.\d+)")
PRB = re.compile(r"probe_rtt_ms (\d+\.\d+)")
LOSS = re.compile(r"congestion_loss measured=(\d+\.\d+)")
TAK = re.compile(r"takion_raw expected_per_s (\d+\.\d+) received_per_s (\d+\.\d+)")
RECV = re.compile(r"Feedback stats: window \d+ ms video received (\d+)")
GAP = re.compile(r"takion_silence_ms (\d+) window_max_gap_ms (\d+)")

spans = fb.load_phases(OUT)

badges, raws = [], []
for line in open(f"{OUT}/session_logcat.txt", errors="replace"):
    m = Q.search(line)
    if m:
        badges.append(dict(t=stamp(m.group(1)), level=m.group(2), cause=m.group(3),
                           rtt=float(m.group(4)), jit=float(m.group(5)),
                           loss=float(m.group(6)), tj=float(m.group(7)),
                           tl=float(m.group(8)), cut=float(m.group(9)),
                           stall=float(m.group(10)), scut=float(m.group(11)),
                           spoor=float(m.group(12))))
        continue
    m = FS.search(line)
    if not m:
        continue
    j = JIT.search(line)
    if not j:
        continue
    p, l, tk = PRB.search(line), LOSS.search(line), TAK.search(line)
    g, rc = GAP.search(line), RECV.search(line)
    exp, rec = (float(tk.group(1)), float(tk.group(2))) if tk else (0.0, 0.0)
    raws.append(dict(t=stamp(m.group(1)), jit=float(j.group(1)),
                     rtt=float(p.group(1)) if p else 0.0,
                     recv=int(rc.group(1)) if rc else -1,
                     silence=int(g.group(1)) if g else -1,
                     gap=int(g.group(2)) if g else -1,
                     loss=max((exp - rec) * 100.0 / exp if exp > 0 else 0.0,
                              float(l.group(1)) * 100 if l else 0.0)))

if not badges:
    sys.exit("analyze.py: no 'Quality badge:' lines with a stall_ms field -- old APK?")
if all(x["gap"] < 0 for x in raws):
    sys.exit("analyze.py: no 'window_max_gap_ms' field in the stats line -- old APK?")


def phase_of(t):
    return next((g for g, (a, b) in spans.items() if a <= t <= b), "-")


def near(t):
    c = [x for x in raws if abs(x["t"] - t) < 0.75]
    return c[0] if c else None


print(f"{len(badges)} badge verdicts, {len(raws)} stats lines\n")
print("| phase | n | badge GOOD | CONSTRAINED | POOR | worst jitter | worst loss "
      "| max tail rate | worst gap ms | min recv/s |")
print("|---|---|---|---|---|---|---|---|---|---|")
for tag, (a, b) in spans.items():
    # first 10 s of a phase is the shaper step settling through the 5 s window
    g = [x for x in badges if a + 10 <= x["t"] <= b]
    r = [x for x in raws if a + 10 <= x["t"] <= b]
    if not g:
        continue
    n = len(g)
    counts = {lv: sum(1 for x in g if x["level"] == lv) for lv in
              ("GOOD", "CONSTRAINED", "POOR")}
    print("| {} | {} | {} ({:.0f}%) | {} ({:.0f}%) | {} ({:.0f}%) | {:.2f} | {:.2f} | {:.2f} "
          "| {} | {} |".format(
              tag, n,
              counts["GOOD"], counts["GOOD"] * 100.0 / n,
              counts["CONSTRAINED"], counts["CONSTRAINED"] * 100.0 / n,
              counts["POOR"], counts["POOR"] * 100.0 / n,
              max((x["jit"] for x in r), default=float("nan")),
              max((x["loss"] for x in r), default=float("nan")),
              max(max(x["tj"], x["tl"]) for x in g),
              max((x["gap"] for x in r), default=-1),
              min((x["recv"] for x in r if x["recv"] >= 0), default=-1)))

print("\n--- the stall input, per phase (this is the derivation) ---")
print("| phase | n | worst gap ms | p95 gap ms | median gap ms | samples >= cut | >= poor |")
print("|---|---|---|---|---|---|---|")
cut = badges[0]["scut"]
poor = badges[0]["spoor"]
for tag, (a, b) in spans.items():
    v = sorted(x["gap"] for x in raws if a + 10 <= x["t"] <= b and x["gap"] >= 0)
    if not v:
        continue
    print("| {} | {} | {} | {} | {} | {} | {} |".format(
        tag, len(v), v[-1], v[int(0.95 * len(v)) - 1], v[len(v) // 2],
        sum(1 for x in v if x >= cut), sum(1 for x in v if x >= poor)))
print(f"\ncuts in force: CONSTRAINED >= {cut:.0f} ms, POOR >= {poor:.0f} ms")

print("\n--- badge transitions ---")
prev = None
for x in badges:
    if x["level"] != prev:
        s = near(x["t"])
        print("  {:6.1f}s {:14s} {:11s} <- {:11s}  sample jit={:6.2f} loss={:6.2f} "
              "gap={:5d} recv={:3d}".format(
                  x["t"] - badges[0]["t"], phase_of(x["t"]), x["level"], str(prev),
                  s["jit"] if s else float("nan"), s["loss"] if s else float("nan"),
                  s["gap"] if s else -1, s["recv"] if s else -1))
        prev = x["level"]

print("\n--- per-second series ---")
t0 = badges[0]["t"]
for x in badges:
    s = near(x["t"])
    print("{:6.1f}s {:14s} {:11s} {:8s} med rtt={:6.2f} jit={:6.2f} loss={:5.2f} | "
          "sample jit={:6.2f} loss={:6.2f} recv={:3d} | tail j={:4.2f} l={:4.2f} | "
          "gap={:5d} silence={:5d} stall={:6.0f}".format(
              x["t"] - t0, phase_of(x["t"]), x["level"], x["cause"],
              x["rtt"], x["jit"], x["loss"],
              s["jit"] if s else float("nan"), s["loss"] if s else float("nan"),
              s["recv"] if s else -1,
              x["tj"], x["tl"],
              s["gap"] if s else -1, s["silence"] if s else -1, x["stall"]))
