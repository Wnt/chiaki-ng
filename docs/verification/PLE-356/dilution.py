#!/usr/bin/env python3
"""PLE-356: why no constant could have rescued the per-packet jitter estimator.

Both estimators, transcribed from lib/src/takion.c, driven by a synthetic arrival
pattern: a stream at `fps` whose every frame is alternately `pdv_ms` early and late,
delivered as `pkts` packets spaced `spacing_us` apart. The per-packet form's answer is
the real delay variation divided by roughly the packets per frame -- and the packets
per frame is a function of bitrate and frame size, so it is not a constant.
"""

def run(pdv_ms, pkts, fps=60, spacing_us=60, frames=600):
    frame_us = 1000000 // fps
    raw_q4 = q4 = 0
    prev_arr = prev_idx = None
    f_arr = f_idx = None
    for frame in range(frames):
        base = 1000000 + frame * frame_us + (int(pdv_ms * 1000) if frame % 2 else 0)
        for pkt in range(pkts):
            arrival, index = base + pkt * spacing_us, frame
            if prev_arr is None:
                prev_arr, prev_idx = arrival, index
            else:
                d = abs((arrival - prev_arr) - (index - prev_idx) * 1000000 // fps)
                raw_q4 += d - ((raw_q4 + 8) >> 4)
                prev_arr, prev_idx = arrival, index
            if f_idx is None:
                f_arr, f_idx = arrival, index
            elif index > f_idx:
                d = abs((arrival - f_arr) - (index - f_idx) * 1000000 // fps)
                q4 += d - ((q4 + 8) >> 4)
                f_arr, f_idx = arrival, index
    return ((q4 + 8) >> 4) / 1000.0, ((raw_q4 + 8) >> 4) / 1000.0

print("Real per-frame delay variation vs what each estimator reports, in ms.")
print("`per-frame` is the fix; `per-packet` is what shipped. CONSTRAINED_JITTER_MS = 4.0,")
print("POOR_JITTER_MS = 10.0.\n")
print("| real PDV | pkts/frame | per-frame (fix) | per-packet (old) | old/real |")
print("|---|---|---|---|---|")
for pdv in (1.0, 4.0, 10.0, 20.0, 32.0):
    for pkts in (1, 5, 10, 20):
        new, old = run(pdv, pkts)
        print(f"| {pdv:.1f} | {pkts} | {new:.2f} | {old:.2f} | {old/pdv:.3f} |")
