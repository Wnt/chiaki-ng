#!/usr/bin/env python3
"""PLE-366: read the badge's own 1 Hz verdict out of a capture's logcat.

The proof this card needs is a transition, so the series is the primary artifact:
what the badge said each second, next to the medians and tail rates it said it on.
"""
import re
import sys
import os
import datetime
import statistics

OUT = sys.argv[1] if len(sys.argv) > 1 else "/home/wnt/gta6/build/captures/ple366"
TZ = datetime.timezone(datetime.timedelta(hours=3))
YEAR = 2026

Q = re.compile(
    r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d).*Quality badge: level (\w+) cause (\w+)"
    r" \| median rtt_ms ([\d.]+) jitter_ms ([\d.]+) loss_pct ([\d.]+)"
    r" \| tail_rate jitter ([\d.]+) loss ([\d.]+) cut ([\d.]+)")
FS = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d).*Feedback stats: window")
JIT = re.compile(r"packet_jitter_ms (\d+\.\d+)")
PRB = re.compile(r"probe_rtt_ms (\d+\.\d+)")
LOSS = re.compile(r"congestion_loss measured=(\d+\.\d+)")
TAK = re.compile(r"takion_raw expected_per_s (\d+\.\d+) received_per_s (\d+\.\d+)")


def stamp(s):
    return datetime.datetime.strptime(f"{YEAR}-{s}", "%Y-%m-%d %H:%M:%S.%f").replace(
        tzinfo=TZ).timestamp()


spans, phases = {}, []
for line in open(f"{OUT}/phases.txt"):
    kind, tag, ts = line.split()
    phases.append((kind, tag, float(ts)))
for i, (kind, tag, ts) in enumerate(phases):
    if kind == "PHASE_BEGIN":
        end = next((t for k, g, t in phases[i + 1:] if k == "PHASE_END" and g == tag), None)
        if end:
            spans[tag] = (ts, end)

badges, raws = [], []
for line in open(f"{OUT}/session_logcat.txt", errors="replace"):
    m = Q.search(line)
    if m:
        badges.append(dict(t=stamp(m.group(1)), level=m.group(2), cause=m.group(3),
                           rtt=float(m.group(4)), jit=float(m.group(5)),
                           loss=float(m.group(6)), tj=float(m.group(7)),
                           tl=float(m.group(8)), cut=float(m.group(9))))
        continue
    m = FS.search(line)
    if not m:
        continue
    j = JIT.search(line)
    if not j:
        continue
    p, l, tk = PRB.search(line), LOSS.search(line), TAK.search(line)
    exp, rec = (float(tk.group(1)), float(tk.group(2))) if tk else (0.0, 0.0)
    raws.append(dict(t=stamp(m.group(1)), jit=float(j.group(1)),
                     rtt=float(p.group(1)) if p else 0.0,
                     loss=max((exp - rec) * 100.0 / exp if exp > 0 else 0.0,
                              float(l.group(1)) * 100 if l else 0.0)))


def phase_of(t):
    return next((g for g, (a, b) in spans.items() if a <= t <= b), "-")


def near(t):
    c = [x for x in raws if abs(x["t"] - t) < 0.75]
    return c[0] if c else None


print(f"{len(badges)} badge verdicts, {len(raws)} stats lines\n")
print("| phase | n | badge GOOD | CONSTRAINED | POOR | worst jitter sample | worst loss sample "
      "| max tail rate |")
print("|---|---|---|---|---|---|---|---|")
for tag, (a, b) in spans.items():
    # first 10 s of a phase is the shaper step settling through the 5 s window
    g = [x for x in badges if a + 10 <= x["t"] <= b]
    r = [x for x in raws if a + 10 <= x["t"] <= b]
    if not g:
        continue
    n = len(g)
    counts = {lv: sum(1 for x in g if x["level"] == lv) for lv in
              ("GOOD", "CONSTRAINED", "POOR")}
    print("| {} | {} | {} ({:.0f}%) | {} ({:.0f}%) | {} ({:.0f}%) | {:.2f} | {:.2f} | {:.2f} |".format(
        tag, n,
        counts["GOOD"], counts["GOOD"] * 100.0 / n,
        counts["CONSTRAINED"], counts["CONSTRAINED"] * 100.0 / n,
        counts["POOR"], counts["POOR"] * 100.0 / n,
        max((x["jit"] for x in r), default=float("nan")),
        max((x["loss"] for x in r), default=float("nan")),
        max(max(x["tj"], x["tl"]) for x in g)))

print("\n--- badge transitions ---")
prev = None
for x in badges:
    if x["level"] != prev:
        s = near(x["t"])
        print("  {:6.1f}s {:14s} {:11s} <- {:11s}  sample jit={:6.2f} loss={:6.2f} rtt={:5.2f} "
              "| tail j={:4.2f} l={:4.2f}".format(
                  x["t"] - badges[0]["t"], phase_of(x["t"]), x["level"], str(prev),
                  s["jit"] if s else float("nan"), s["loss"] if s else float("nan"),
                  s["rtt"] if s else float("nan"), x["tj"], x["tl"]))
        prev = x["level"]

print("\n--- per-second series ---")
t0 = badges[0]["t"]
for x in badges:
    s = near(x["t"])
    print("{:6.1f}s {:14s} {:11s} {:8s} med rtt={:6.2f} jit={:6.2f} loss={:5.2f} | "
          "sample jit={:6.2f} loss={:6.2f} | tail j={:4.2f} l={:4.2f}".format(
              x["t"] - t0, phase_of(x["t"]), x["level"], x["cause"],
              x["rtt"], x["jit"], x["loss"],
              s["jit"] if s else float("nan"), s["loss"] if s else float("nan"),
              x["tj"], x["tl"]))
