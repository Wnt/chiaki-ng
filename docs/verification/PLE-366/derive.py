#!/usr/bin/env python3
"""PLE-366: derive a tail threshold and a rate cut for the quality badge from real captures.

Input corpus: every capture on this box whose `packet_jitter_ms` is PLE-356's per-frame
delay-variation estimator (ple343/* predate it and carry the old packet-gap EWMA, so they
are excluded -- their numbers are not the quantity the classifier reads today).

For each capture we rebuild exactly what the classifier sees: one sample per second,
jitter = packet_jitter_ms, loss = max(takion loss%, congestion measured loss%),
rtt = probe_rtt_ms. Then we slide the classifier's own 5-sample window over the series
and, for each candidate threshold, report the distribution of the tail rate
(count of samples at or above the threshold, divided by the window size).

Each capture's estimator generation is named on stderr (not folded into the
pooled numbers below, which are unchanged from before PLE-405): a capture
whose `Feedback stats:` lines predate PLE-356 (no `packet_jitter_raw_ms`
field) has its `packet_jitter_ms` reading the *old* packet-gap EWMA, not the
frame-boundary estimator the badge reads today -- see
feedback_stats.capture_generation(). This is reporting, not filtering: this
script's corpus and output are unchanged (PLE-405 is a refactor); a capture
found to be the wrong generation is a decision for whoever picked the corpus,
not something this script silently overrides.

Usage: derive.py [capture-dir ...]   (defaults to the ple356 + ple357 corpus)
"""
import re
import sys
import os
import statistics

# PLE-405: shared with every other capture's analyze/derive script; imported
# by an explicit path that fails loudly if it's missing (PLE-378's rule for
# capture.sh's IMPAIR path, applied here too).
REPO = os.environ.get("REPO", "/home/wnt/gta6")
_FB_MODULE = os.path.join(REPO, "scripts", "dev", "feedback_stats.py")
if not os.path.isfile(_FB_MODULE):
    sys.exit(f"derive.py: feedback_stats.py not found at {_FB_MODULE} (set REPO= to override)")
sys.path.insert(0, os.path.dirname(_FB_MODULE))
import feedback_stats as fb  # noqa: E402

DEFAULT_CAPTURES = [
    "/home/wnt/gta6/build/captures/ple356",
    "/home/wnt/gta6/build/captures/ple357",
]
WINDOW = 5  # NetworkQualityThresholds.FAST_WINDOW_SECONDS

FS = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d).*Feedback stats: window (\d+) ms")
JIT = re.compile(r"packet_jitter_ms (\d+\.\d+)")
PRB = re.compile(r"probe_rtt_ms (\d+\.\d+)")
LOSS = re.compile(r"congestion_loss measured=(\d+\.\d+)")
TAK = re.compile(r"takion_raw expected_per_s (\d+\.\d+) received_per_s (\d+\.\d+)")
stamp = fb.stamp


def load(capture):
    spans = fb.load_phases(capture)
    rows = []
    for line in open(f"{capture}/session_logcat.txt", errors="replace"):
        m = FS.search(line)
        if not m:
            continue
        j = JIT.search(line)
        if not j:
            continue
        p, l, t = PRB.search(line), LOSS.search(line), TAK.search(line)
        exp, rec = (float(t.group(1)), float(t.group(2))) if t else (0.0, 0.0)
        takion_loss = (exp - rec) * 100.0 / exp if exp > 0 else 0.0
        rows.append(dict(
            t=stamp(m.group(1)),
            jit=float(j.group(1)),
            rtt=float(p.group(1)) if p else 0.0,
            loss=max(takion_loss, float(l.group(1)) * 100 if l else 0.0),
        ))
    return spans, rows


def phase_rows(spans, rows, tag, settle=10):
    a, b = spans[tag]
    return [x for x in rows if a + settle <= x["t"] <= b]


def windows(series):
    """Every full 5-sample window of a phase, as the classifier would hold it."""
    return [series[i:i + WINDOW] for i in range(0, max(0, len(series) - WINDOW + 1))]


def tail_rates(series, key, threshold):
    return [sum(1 for s in w if s[key] >= threshold) / float(WINDOW) for w in windows(series)]


def main():
    captures = sys.argv[1:] or DEFAULT_CAPTURES
    clean, blip = [], []
    print("== corpus ==")
    for cap in captures:
        print(f"{cap}: {fb.capture_generation(cap)}", file=sys.stderr)
        spans, rows = load(cap)
        name = os.path.basename(cap)
        for tag in spans:
            series = phase_rows(spans, rows, tag)
            if len(series) < WINDOW:
                continue
            kind = "clean" if tag.endswith("clean") else (
                "blip" if "blip" in tag else "other")
            print(f"  {name:8s} {tag:16s} {kind:6s} n={len(series):3d} "
                  f"jit med={statistics.median(s['jit'] for s in series):5.2f} "
                  f"max={max(s['jit'] for s in series):6.2f}  "
                  f"loss max={max(s['loss'] for s in series):6.2f}  "
                  f"rtt med={statistics.median(s['rtt'] for s in series):5.2f} "
                  f"max={max(s['rtt'] for s in series):6.2f}")
            if kind == "clean":
                clean.append(series)
            elif kind == "blip":
                blip.append(series)

    def pool(groups, key):
        return [s[key] for g in groups for s in g]

    print("\n== pooled sample distributions ==")
    for key, label in (("jit", "jitter ms"), ("loss", "loss %"), ("rtt", "probe rtt ms")):
        cv, bv = pool(clean, key), pool(blip, key)
        for label2, v in (("clean", cv), ("blip ", bv)):
            s = sorted(v)
            print(f"  {label:12s} {label2} n={len(s):3d} med={statistics.median(s):6.2f} "
                  f"p90={s[int(len(s)*0.9)]:6.2f} p99={s[min(len(s)-1,int(len(s)*0.99))]:6.2f} "
                  f"max={max(s):7.2f}")

    print("\n== candidate tail thresholds: worst clean window vs blip windows ==")
    print("  (rate = samples at or above the threshold, out of the 5-sample window)")
    grid = {
        "jit": [2.5, 3.0, 3.2, 3.5, 4.0, 5.0, 6.0],
        "loss": [0.5, 1.0, 2.0, 3.0, 5.0],
        "rtt": [20.0, 25.0, 30.0, 40.0, 60.0],
    }
    for key, label in (("jit", "jitter ms"), ("loss", "loss %"), ("rtt", "probe rtt ms")):
        print(f"  -- {label}")
        for thr in grid[key]:
            cw = [r for g in clean for r in tail_rates(g, key, thr)]
            bw = [r for g in blip for r in tail_rates(g, key, thr)]
            cmax = max(cw) if cw else 0.0
            hit = sum(1 for r in bw if r > 0) / float(len(bw)) if bw else 0.0
            nclean = sum(1 for v in pool(clean, key) if v >= thr)
            nblip = sum(1 for v in pool(blip, key) if v >= thr)
            print(f"     thr={thr:6.2f}  clean: {nclean:3d} samples over, "
                  f"worst window rate={cmax:4.2f}  |  "
                  f"blip: {nblip:3d} samples over, {hit*100:5.1f}% of windows non-zero")

    print("\n== chosen arm, evaluated ==")
    chosen = [("jit", 4.0), ("loss", 2.0), ("rtt", 30.0)]
    cut = 1 / float(WINDOW)
    for g, name in ((clean, "clean"), (blip, "blip")):
        fired = 0
        total = 0
        for series in g:
            for w in windows(series):
                total += 1
                if any(sum(1 for s in w if s[k] >= t) / float(WINDOW) >= cut for k, t in chosen):
                    fired += 1
        print(f"  {name:5s}: {fired}/{total} windows fire the tail arm "
              f"({(fired*100.0/total if total else 0):.1f}%)")
    cmax = 0.0
    for k, t in chosen:
        cw = [r for g in clean for r in tail_rates(g, k, t)]
        cmax = max(cmax, max(cw) if cw else 0.0)
    print(f"  clean margin: worst clean tail rate over any arm = {cmax:.2f}, cut = {cut:.2f}")


if __name__ == "__main__":
    main()
