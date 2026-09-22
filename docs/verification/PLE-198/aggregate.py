#!/usr/bin/env python3
"""PLE-198: aggregate the six boost on/off soak sessions into per-session and per-arm tables.

usage: aggregate.py <captures_dir>   (prints Markdown)

Per session: chiaki UID batterystats estimate (total and cpu component), whole-app CPU
time from /proc/<pid>/stat utime+stime over the drive window, SKIN temperature
(start, peak, mean over the drive samples, samples with mStatus>0), mean scaling_cur_freq
per cluster (cpu0-3 A510, cpu4-6 A710, cpu7 X2), and stream health from logcat
`Feedback stats` lines.
"""
import glob
import os
import re
import statistics as st
import sys

UID = "u0a182"  # com.metallic.chiaki on the S22 Ultra (PLE-71 and this run)


def battery(path):
    text = open(path).read()
    m = re.search(rf"^\s+UID {UID}: ([0-9.]+).*\n(.*)$", text, re.M)
    total = float(m.group(1))
    cpu = float(re.search(r"\bcpu=([0-9.]+)", m.group(2)).group(1))
    dur = re.search(r"cpu: [0-9.]+ apps: [0-9.]+ duration: ([^\n]+?)\s*$", text, re.M).group(1)
    return total, cpu, dur


def proc_cpu(path):
    rows = {}
    tck = 100
    tag = None
    for line in open(path):
        line = line.strip()
        if line.startswith(("start ", "end ")):
            tag, ts = line.split()[:2]
            rows[tag] = {"ts": int(ts)}
        elif line.startswith("clk_tck="):
            tck = int(line.split("=")[1])
        elif tag and ")" in line:
            f = line.rsplit(")", 1)[1].split()
            # after the comm field: f[0]=state (field 3), utime=field 14, stime=field 15
            rows[tag]["ticks"] = int(f[11]) + int(f[12])
    cpu_s = (rows["end"]["ticks"] - rows["start"]["ticks"]) / tck
    wall = rows["end"]["ts"] - rows["start"]["ts"]
    return cpu_s, wall


def thermal(path):
    samples = []
    for block in open(path).read().split("== ")[1:]:
        skin = re.search(r"mValue=([0-9.]+), mType=3, mName=SKIN, mStatus=(\d+)", block)
        ap = re.search(r"mValue=([0-9.]+), mType=0, mName=AP", block)
        freqs = [int(x) for x in re.findall(r"^(\d{5,})$", block, re.M)]
        samples.append((float(skin.group(1)), int(skin.group(2)), float(ap.group(1)), freqs))
    # samples[0] is before batterystats reset, samples[1] at stream start, then one per chunk
    drive = samples[1:]
    return {
        "skin_start": samples[0][0],
        "skin_peak": max(s[0] for s in drive),
        "skin_mean": st.mean(s[0] for s in drive),
        "skin_end": drive[-1][0],
        "light": sum(1 for s in drive if s[1] > 0),
        "n": len(drive),
        "ap_mean": st.mean(s[2] for s in drive),
        "little": st.mean(st.mean(s[3][0:4]) for s in drive[1:]),
        "big": st.mean(st.mean(s[3][4:7]) for s in drive[1:]),
        "prime": st.mean(s[3][7] for s in drive[1:]),
    }


def stream(path):
    rx = dec = unrec = 0
    drops = lost = 0
    for line in open(path, errors="replace"):
        if "Feedback stats:" not in line:
            continue
        g = lambda k: int(re.search(rf"\b{k} (\d+)", line).group(1))
        rx += g("received")
        dec += g("decoded")
        unrec += g("unrecoverable")
        drops = max(drops, g("dropped_input") + g("dropped_presenter") + g("dropped_bounded_age"))
        lost = max(lost, g("lost"))
    return rx, dec, drops, lost, unrec


def main():
    cap = sys.argv[1]
    names = sorted(os.path.basename(p)[: -len("_batterystats.txt")] for p in glob.glob(f"{cap}/r*_batterystats.txt"))
    rows = []
    for n in names:
        total, cpu, dur = battery(f"{cap}/{n}_batterystats.txt")
        cpu_s, wall = proc_cpu(f"{cap}/{n}_proc_cpu.txt")
        th = thermal(f"{cap}/{n}_thermal_samples.txt")
        rx, dec, drops, lost, unrec = stream(f"{cap}/{n}_logcat.txt")
        pinned = sum(1 for l in open(f"{cap}/{n}_affinity.txt") if l.rstrip("\n").endswith("\t4-7"))
        rows.append(dict(name=n, arm=n.split("_")[1], total=total, cpu=cpu, dur=dur, cpu_s=cpu_s, wall=wall,
                         util=cpu_s / wall, rx=rx, dec=dec, drops=drops, lost=lost, unrec=unrec, pinned=pinned, **th))

    print("| session | pinned thr | bstats dur | UID mAh | UID cpu mAh | app CPU s / wall s | cores | SKIN start | SKIN peak | SKIN mean | LIGHT | AP mean | A510 MHz | A710 MHz | X2 MHz | frames rx | decoded | drops | lost | unrec |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        print(f"| {r['name']} | {r['pinned']} | {r['dur']} | {r['total']:.1f} | {r['cpu']:.1f} | {r['cpu_s']:.1f} / {r['wall']} | {r['util']:.3f} | "
              f"{r['skin_start']:.1f} | {r['skin_peak']:.1f} | {r['skin_mean']:.2f} | {r['light']}/{r['n']} | {r['ap_mean']:.1f} | "
              f"{r['little']/1000:.0f} | {r['big']/1000:.0f} | {r['prime']/1000:.0f} | {r['rx']} | {r['dec']} | {r['drops']} | {r['lost']} | {r['unrec']} |")

    print()
    keys = [("total", "UID mAh", 2), ("cpu", "UID cpu mAh", 2), ("util", "app CPU cores", 3),
            ("skin_peak", "SKIN peak", 2), ("skin_mean", "SKIN mean", 2), ("ap_mean", "AP mean", 2),
            ("little", "A510 kHz", 0), ("big", "A710 kHz", 0), ("prime", "X2 kHz", 0), ("dec", "decoded", 0)]
    print("| metric | off mean [min-max] | on mean [min-max] | on-off | on-off % |")
    print("|---|---|---|---|---|")
    for k, label, d in keys:
        a = [r[k] for r in rows if r["arm"] == "off"]
        b = [r[k] for r in rows if r["arm"] == "on"]
        ma, mb = st.mean(a), st.mean(b)
        f = lambda v: f"{v:.{d}f}"
        print(f"| {label} | {f(ma)} [{f(min(a))}-{f(max(a))}] | {f(mb)} [{f(min(b))}-{f(max(b))}] | {mb-ma:+.{d}f} | {100*(mb-ma)/ma:+.1f} % |")


if __name__ == "__main__":
    main()
