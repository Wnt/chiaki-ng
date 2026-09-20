#!/usr/bin/env python3
"""PLE-418: verify a dynamic-profile capture actually shows the impairment it claims.

`impairctl.py`'s dynamic profiles (`blip-200ms` is the one in use) run a cycling
loop on a shared remote host that is not scoped to the device reservation
(scripts/net/impair.sh's `blip_loop`, `docs/verification/PLE-411/README.md`
section 3). That loop can die or leave a stray delay applied, and when it does,
`capture.sh` still exits 0: `phases.txt` is well-formed and the artifacts look
complete, the only symptom is that the raw jitter/loss samples don't show the
expected pattern. This script is the check that makes that failure loud instead
of silent: it reads every `PHASE_BEGIN`/`PHASE_END` pair in a capture's
`phases.txt`, in file order (not deduplicated by tag, so a capture directory
that was reused across a retry -- e.g. build/captures/ple403 -- is checked once
per occurrence, not just its last one), and for each pair:

  * a "blip" phase (profile name contains "blip") must show close to the
    expected number of excursions for its duration -- one every ~20s, the
    period `blip_loop` cycles on. Tolerance is +/-1 cycle: tight enough that a
    phase which only caught 3 of 6 expected excursions still fails, loose
    enough to absorb the +/-1 boundary jitter every real capture on this box
    shows (a cycle landing 1s either side of a phase edge, PLE-411 section 3).
  * every phase whose name contains "clean" must show *zero* excursions after
    a short settle window (default 5s, covering the netem qdisc-switch
    settling documented in PLE-366/analyze.py) -- any excursion inside a
    "clean" phase beyond that window is impairment that leaked past its own
    phase, the direct symptom of a `blip_loop` that didn't die on schedule but
    also didn't stop on command. Other statically-impaired profiles (5g,
    wifi-slow, loss-2, ...) are supposed to show sustained impairment
    throughout and are not checked -- out of scope for this ticket.

An "excursion" here is our own raw-sample threshold (jitter_ms >= 3.0 or
loss_pct >= 2.0, adjacent hits within 2s merged into one event) -- not
NetworkQuality.kt's classifier or its tail-rate cut, which this ticket does not
touch. 3.0 ms / 2.0 % sits above the clean-phase noise floor seen in every real
capture on this box (worst clean-phase jitter across ple356/ple411/ple403 is
2.5 ms with 0% loss) and below a genuine blip-200ms pulse's smallest observed
sample (2.6-4.0 ms jitter with measurable loss), so it separates signal from
noise on the data actually on this box rather than a guess.

Usage: verify-blip-pattern.py <capture-dir> [--cycle-seconds N] [--tolerance N]
                               [--jitter-threshold-ms F] [--loss-threshold-pct F]
                               [--settle-seconds F] [--merge-gap-seconds F]
Exit 0 and a pass report if every phase matches its expected pattern, exit 1
and a report naming every failing phase otherwise.
"""
import argparse
import os
import sys

REPO = os.environ.get("REPO", "/home/wnt/gta6")
_FB_MODULE = os.path.join(REPO, "scripts", "dev", "feedback_stats.py")
if not os.path.isfile(_FB_MODULE):
    sys.exit(f"verify-blip-pattern.py: feedback_stats.py not found at {_FB_MODULE} "
              "(set REPO= to override)")
sys.path.insert(0, os.path.dirname(_FB_MODULE))
import feedback_stats as fb  # noqa: E402


def load_phase_occurrences(capture_dir):
    """Read every PHASE_BEGIN/PHASE_END pair from phases.txt, in file order.

    Unlike feedback_stats.load_phases(), this does not dedupe by tag: a
    capture directory reused across a retry has one PHASE_BEGIN/PHASE_END pair
    per attempt for the same tag, and every attempt gets checked.
    """
    events = []
    with open(f"{capture_dir}/phases.txt") as f:
        for line in f:
            kind, tag, ts = line.split()
            events.append((kind, tag, float(ts)))
    occurrences = []
    stack = []
    for kind, tag, ts in events:
        if kind == "PHASE_BEGIN":
            stack.append((tag, ts))
        elif kind == "PHASE_END" and stack and stack[-1][0] == tag:
            begin_tag, begin_ts = stack.pop()
            occurrences.append((begin_tag, begin_ts, ts))
    return occurrences


def check_dynamic_phase(tag, begin, end, samples, *, cycle_seconds, tolerance,
                         jitter_threshold_ms, loss_threshold_pct, merge_gap_s):
    window = [s for s in samples if begin <= s[0] <= end]
    if not window:
        return False, f"{tag}: no raw samples in [{begin:.1f}, {end:.1f}] " \
            "(session_logcat.txt does not cover this phase -- cannot verify anything)"
    duration = end - begin
    expected = max(1, round(duration / cycle_seconds))
    excursions = fb.find_excursions(window, jitter_threshold_ms, loss_threshold_pct, merge_gap_s)
    got = len(excursions)
    lo, hi = expected - tolerance, expected + tolerance
    if lo <= got <= hi:
        return True, f"{tag}: {got} excursions in {duration:.1f}s (expected {expected} +/-{tolerance}) -- OK"
    return False, (f"{tag}: {got} excursions in {duration:.1f}s (expected {expected} "
                    f"+/-{tolerance}) -- the dynamic profile's cyclic pattern is not present, "
                    "this capture measured something other than what it claims")


def check_static_phase(tag, begin, end, samples, *, settle_seconds,
                        jitter_threshold_ms, loss_threshold_pct, merge_gap_s):
    window = [s for s in samples if begin + settle_seconds <= s[0] <= end]
    excursions = fb.find_excursions(window, jitter_threshold_ms, loss_threshold_pct, merge_gap_s)
    if not excursions:
        return True, f"{tag}: 0 excursions after the {settle_seconds:.0f}s settle window -- OK"
    spans = ", ".join(f"+{t0 - begin:.1f}s..+{t1 - begin:.1f}s" for t0, t1 in excursions)
    return False, (f"{tag}: {len(excursions)} excursion(s) after the {settle_seconds:.0f}s settle "
                    f"window ({spans}) -- impairment leaked into a phase that should be clean")


def verify(capture_dir, *, cycle_seconds, tolerance, jitter_threshold_ms, loss_threshold_pct,
           settle_seconds, merge_gap_s, dynamic_marker="blip", clean_marker="clean"):
    occurrences = load_phase_occurrences(capture_dir)
    if not occurrences:
        return False, ["no PHASE_BEGIN/PHASE_END pairs found in phases.txt"]
    samples = fb.load_raw_samples(capture_dir)
    ok = True
    lines = []
    for tag, begin, end in occurrences:
        if dynamic_marker in tag:
            passed, line = check_dynamic_phase(
                tag, begin, end, samples, cycle_seconds=cycle_seconds, tolerance=tolerance,
                jitter_threshold_ms=jitter_threshold_ms, loss_threshold_pct=loss_threshold_pct,
                merge_gap_s=merge_gap_s)
        elif clean_marker in tag:
            passed, line = check_static_phase(
                tag, begin, end, samples, settle_seconds=settle_seconds,
                jitter_threshold_ms=jitter_threshold_ms, loss_threshold_pct=loss_threshold_pct,
                merge_gap_s=merge_gap_s)
        else:
            # A statically-impaired, non-"clean" profile (5g, wifi-slow, loss-2, ...) is
            # supposed to show sustained impairment throughout -- out of this ticket's
            # scope (only dynamic profiles and their bounding clean phases are checked).
            passed, line = True, f"{tag}: not a dynamic or clean phase -- not checked"
        lines.append(("OK" if passed else "FAIL") + "  " + line)
        ok = ok and passed
    return ok, lines


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("capture_dir")
    p.add_argument("--cycle-seconds", type=float, default=20.0)
    p.add_argument("--tolerance", type=int, default=1)
    p.add_argument("--jitter-threshold-ms", type=float, default=3.0)
    p.add_argument("--loss-threshold-pct", type=float, default=2.0)
    p.add_argument("--settle-seconds", type=float, default=5.0)
    p.add_argument("--merge-gap-seconds", type=float, default=2.0)
    args = p.parse_args()

    ok, lines = verify(
        args.capture_dir, cycle_seconds=args.cycle_seconds, tolerance=args.tolerance,
        jitter_threshold_ms=args.jitter_threshold_ms, loss_threshold_pct=args.loss_threshold_pct,
        settle_seconds=args.settle_seconds, merge_gap_s=args.merge_gap_seconds)
    for line in lines:
        print(line)
    print("PASS" if ok else "FAIL: dynamic-profile pattern check failed, see above")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
