#!/usr/bin/env python3
"""PLE-356: join the 1 Hz Feedback stats line, the phone's ping and the phase log.

The ground truth for a jitter metric is the phone's own ping delay variation over the
same window: mean |consecutive difference| is exactly the quantity RFC 3550's jitter
estimates, so it is directly comparable with packet_jitter_ms. p10-p90 is the spread
the ticket quoted. ADB is exempt from the shaping and is never used as ground truth.
"""
import re, datetime, statistics, os
OUT = os.path.dirname(os.path.abspath(__file__))
TZ = datetime.timezone(datetime.timedelta(hours=3))
YEAR = 2026

phases = []
for line in open(f"{OUT}/phases.txt"):
    kind, tag, ts = line.split()
    phases.append((kind, tag, float(ts)))
spans = {}
for i, (kind, tag, ts) in enumerate(phases):
    if kind == "PHASE_BEGIN":
        end = next((t for k, g, t in phases[i+1:] if k == "PHASE_END" and g == tag), None)
        if end: spans[tag] = (ts, end)

def stamp(s):
    return datetime.datetime.strptime(f"{YEAR}-{s}", "%Y-%m-%d %H:%M:%S.%f").replace(tzinfo=TZ).timestamp()

FS = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d).*Feedback stats: window (\d+) ms video received (\d+)")
JIT = re.compile(r"packet_jitter_ms (\d+\.\d+) packet_jitter_raw_ms (\d+\.\d+)")
PRB = re.compile(r"probe_rtt_ms (\d+\.\d+)")
TAK = re.compile(r"per_s takion (\d+\.\d+)")
LOSS = re.compile(r"congestion_loss measured=(\d+\.\d+)")
rows = []
for line in open(f"{OUT}/session_logcat.txt", errors="replace"):
    m = FS.search(line)
    if not m: continue
    j, p, tk, l = JIT.search(line), PRB.search(line), TAK.search(line), LOSS.search(line)
    if not j: continue
    rows.append(dict(t=stamp(m.group(1)), win=int(m.group(2)), frames=int(m.group(3)),
                     jit=float(j.group(1)), raw=float(j.group(2)),
                     probe=float(p.group(1)) if p else float("nan"),
                     pkts=float(tk.group(1)) if tk else float("nan"),
                     loss=float(l.group(1)) * 100 if l else 0.0))

pings = []
for line in open(f"{OUT}/phone_ping.txt", errors="replace"):
    m = re.match(r"\[(\d+\.\d+)\].*time=(\d+(?:\.\d+)?) ms", line)
    if m: pings.append((float(m.group(1)), float(m.group(2))))

def pct(v, q):
    s = sorted(v); return s[min(len(s) - 1, int(len(s) * q))]
def med(v): return statistics.median(v) if v else float("nan")

print(f"{len(rows)} stats lines, {len(pings)} ping replies\n")
hdr = ("phase", "n", "ping med", "ping p10-p90", "ping mean|IPDV|", "ping max", "probe med",
       "packet_jitter_ms", "jitter p90", "jitter max", "raw (old)", "raw max",
       "pkts/frame", "loss%", "badge jitter verdict")
print("| " + " | ".join(hdr) + " |")
print("|" + "---|" * len(hdr))
for tag, (a, b) in spans.items():
    # first 10 s of a phase is the shaper step settling through the 5 s / 30 s windows
    r = [x for x in rows if a + 10 <= x["t"] <= b]
    p = [v for t, v in pings if a + 10 <= t <= b]
    if not r or len(p) < 5: continue
    ipdv = statistics.mean(abs(p[i] - p[i-1]) for i in range(1, len(p)))
    jm = med([x["jit"] for x in r])
    # the badge's own rule, on the jitter arm alone: 4.0 constrained, 10.0 poor
    verdict = "POOR" if jm >= 10.0 else "CONSTRAINED" if jm >= 4.0 else "GOOD"
    verdict_old = (lambda v: "POOR" if v >= 10.0 else "CONSTRAINED" if v >= 4.0 else "GOOD")(
        med([x["raw"] for x in r]))
    fps = med([x["frames"] * 1000.0 / x["win"] for x in r])
    pk = med([x["pkts"] for x in r])
    js = [x["jit"] for x in r]
    rs = [x["raw"] for x in r]
    print("| {} | {} | {:.1f} | {:.1f}-{:.1f} | {:.1f} | {:.0f} | {:.1f} | {:.2f} | {:.2f} | {:.2f} | {:.2f} | {:.2f} | {:.1f} | {:.2f} | {} (was {}) |".format(
        tag, len(r), med(p), pct(p, 0.1), pct(p, 0.9), ipdv, max(p),
        med([x["probe"] for x in r]), jm, pct(js, 0.9), max(js), med(rs), max(rs),
        pk / fps if fps else float("nan"),
        med([x["loss"] for x in r]), verdict, verdict_old))

print("\n--- per-second series ---")
t0 = rows[0]["t"]
for x in rows:
    near = [v for t, v in pings if abs(t - x["t"]) < 0.75]
    ph = next((g for g, (a, b) in spans.items() if a <= x["t"] <= b), "-")
    print(f"{x['t']-t0:6.1f}s {ph:14s} ping={near[0] if near else float('nan'):7.2f} "
          f"probe={x['probe']:7.2f} jit={x['jit']:7.2f} raw={x['raw']:6.2f} loss={x['loss']:5.2f}")
