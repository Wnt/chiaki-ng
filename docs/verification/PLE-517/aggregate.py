#!/usr/bin/env python3
"""PLE-517: per-arm aggregate of a resolution x bitrate A/B round.

Reads the captures dir produced by scripts/dev/ab/ab.sh + summarize.py and prints one
Markdown row per scenario and one per arm. Inputs per scenario:

  <scenario>.json        summarize.py output (queue->present, decode, stalls, latch)
  <scenario>_session.log "Feedback stats" windows (drops, loss, target/measured bps)
                         and the "Switched to profile ... resolution" line
  <scenario>_screen.png  full-screen screenshot; a fixed crop of the static console UI
                         gives a sharpness proxy (variance of a 4-neighbour Laplacian)

Usage: aggregate.py <captures-dir> [scenario=arm ...]
With no scenario list, every <scenario>.json whose name ends in _<arm> is used.
Needs numpy and Pillow (system python3 on CT950 has both).
"""
import json
import re
import statistics
import sys
from pathlib import Path

import numpy as np
from PIL import Image

# Crop (x0, y0, x1, y1) in the phone's 2316x1080 screenshot: the driver/car text
# columns of the console's results table, clear of the on-screen gamepad widgets.
CROP = (540, 280, 1480, 840)

STATS_RE = re.compile(r"Feedback stats: (.*)")
KV_RE = re.compile(r"([a-z_]+) (-?[0-9.]+)")


def feedback_windows(log: Path):
	rows = []
	for line in log.read_text(errors="replace").splitlines():
		m = STATS_RE.search(line)
		if m:
			rows.append({k: float(v) for k, v in KV_RE.findall(m.group(1))})
	return rows


def resolution(log: Path):
	m = re.search(r"Switched to profile \d+, resolution: (\d+x\d+)", log.read_text(errors="replace"))
	return m.group(1) if m else "?"


def sharpness(png: Path):
	img = np.asarray(Image.open(png).convert("L").crop(CROP), dtype=np.float64)
	lap = (-4 * img[1:-1, 1:-1] + img[:-2, 1:-1] + img[2:, 1:-1] + img[1:-1, :-2] + img[1:-1, 2:])
	return float(lap.var())


def scenario_row(cap: Path, name: str):
	d = json.loads((cap / f"{name}.json").read_text())
	log = cap / f"{name}_session.log"
	w = feedback_windows(log)
	# received/decoded/unrecoverable are per window; the dropped_* and lost counters are
	# session-cumulative, so their maximum is the session total (not the last window: the
	# log is pulled while the stream runs and its final line can be cut short).
	sums = {k: sum(r.get(k, 0) for r in w) for k in ("received", "decoded", "unrecoverable")}
	sums.update({k: max(r.get(k, 0) for r in w) for k in ("dropped_input", "dropped_presenter", "dropped_bounded_age", "lost")})
	vl, dl = d["video_latency"], d["decode_latency"]["decode"]
	return {
		"scenario": name,
		"res": resolution(log),
		"q2p_avg": vl["q2p_avg"], "q2p_p50": vl["q2p_p50"], "q2p_p99": vl["q2p_p99"],
		"dec_avg": dl["mean"], "dec_p95": dl["p95"],
		"stalls": d["present_timeline"]["stalls_over_100ms"],
		"never_latched_pct": d["never_latched_pct"],
		"fps_in": sums["received"] / max(1, len(w)),
		"drops": sums["dropped_input"] + sums["dropped_presenter"] + sums["dropped_bounded_age"],
		"lost": sums["lost"], "unrecoverable": sums["unrecoverable"],
		"windows": len(w),
		"target_mbps": statistics.median(r["target_bps"] for r in w if "target_bps" in r) / 1e6,
		"measured_mbps": statistics.median(r["measured_bps"] for r in w if "measured_bps" in r) / 1e6,
		"sharp": sharpness(cap / f"{name}_screen.png"),
	}


COLS = [("scenario", "{}"), ("res", "{}"), ("q2p_avg", "{:.2f}"), ("q2p_p50", "{:.2f}"), ("q2p_p99", "{:.2f}"),
	("dec_avg", "{:.2f}"), ("dec_p95", "{:.2f}"), ("stalls", "{:.1f}"), ("never_latched_pct", "{:.2f}"),
	("fps_in", "{:.1f}"), ("drops", "{:.0f}"), ("lost", "{:.0f}"), ("unrecoverable", "{:.0f}"),
	("target_mbps", "{:.2f}"), ("measured_mbps", "{:.2f}"), ("sharp", "{:.0f}")]


def md(row):
	return "| " + " | ".join(f.format(row[k]) for k, f in COLS) + " |"


def main():
	cap = Path(sys.argv[1])
	if len(sys.argv) > 2:
		pairs = [a.split("=", 1) for a in sys.argv[2:]]
	else:
		pairs = [(p.stem, p.stem.rsplit("_", 1)[1]) for p in sorted(cap.glob("r*_*.json"))]
	rows = {}
	for name, arm in pairs:
		rows.setdefault(arm, []).append(scenario_row(cap, name))
	print("| " + " | ".join(k for k, _ in COLS) + " |")
	print("|" + "---|" * len(COLS))
	for arm in sorted(rows):
		for r in rows[arm]:
			print(md(r))
	print()
	print("| " + " | ".join(k for k, _ in COLS) + " |")
	print("|" + "---|" * len(COLS))
	for arm in sorted(rows):
		rs = rows[arm]
		mean = {k: float(statistics.mean(r[k] for r in rs)) for k, f in COLS if k not in ("scenario", "res")}
		mean.update(scenario=f"{arm} mean of {len(rs)}", res=rs[0]["res"])
		print(md(mean))


if __name__ == "__main__":
	main()
