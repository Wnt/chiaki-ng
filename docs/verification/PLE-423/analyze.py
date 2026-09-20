#!/usr/bin/env python3
"""PLE-423: read the link watchdog's own 1 Hz line back out of a capture.

The lib logs, once a second while `stream_feedback_stats_log` is on:

    StreamConnection link: silence <n> ms, worst gap so far <n> ms, limit <n> ms

`silence` is the instantaneous reading at that poll; `worst gap so far` is the
longest interval between two inbound datagrams since the stream began, so it
only ever rises. A phase that raised it is the phase whose window the step
lands in -- that is how a per-profile worst gap is attributed here, rather than
by resetting a counter the watchdog itself never resets.

Two numbers matter per phase, and they answer different questions:

  * `gap added` -- how much this phase raised the cumulative worst gap, i.e.
    the longest silence this profile produced that no earlier phase had already
    beaten. `worst gap` beside it is the cumulative figure at the phase's end. This is what sets CHIAKI_LINK_WATCHDOG_TIMEOUT_MS: the constant
    has to sit far above the worst of these or a deliberate impairment becomes
    a false quit mid-game.
  * `peak silence at a poll` -- the largest value the watchdog itself ever saw,
    which is what it actually compares against its limit. It is bounded below
    by the gap and above by gap + one poll interval.

Usage: analyze.py <capture-dir> [--limit-ms N]
Exit 0 with a per-phase table, exit 1 if any phase's peak silence reached the
limit (i.e. the watchdog would have fired) or if the log has no link lines.
"""
import argparse
import os
import re
import sys

REPO = os.environ.get("REPO", "/home/wnt/gta6")
sys.path.insert(0, os.path.join(REPO, "scripts", "dev"))
import feedback_stats as fb  # noqa: E402

LINK_RE = re.compile(
    r"StreamConnection link: silence (\d+) ms, worst gap so far (\d+) ms, limit (\d+) ms"
)


def load_link_samples(capture_dir):
    """(epoch, silence_ms, worst_gap_ms, limit_ms) for every link line, in order."""
    path = os.path.join(capture_dir, "session_logcat.txt")
    samples = []
    limit = None
    with open(path, errors="replace") as handle:
        for line in handle:
            match = LINK_RE.search(line)
            if not match:
                continue
            # logcat -v time: "MM-DD HH:MM:SS.mmm ..."
            try:
                epoch = fb.stamp(line[:18])
            except ValueError:
                continue
            silence, worst, lim = (int(g) for g in match.groups())
            limit = lim
            samples.append((epoch, silence, worst, lim))
    return samples, limit


def load_phases(capture_dir):
    path = os.path.join(capture_dir, "phases.txt")
    events, stack, phases = [], [], []
    with open(path) as handle:
        for line in handle:
            parts = line.split()
            if len(parts) != 3:
                continue
            events.append((parts[0], parts[1], float(parts[2])))
    for kind, tag, ts in events:
        if kind == "PHASE_BEGIN":
            stack.append((tag, ts))
        elif kind == "PHASE_END" and stack and stack[-1][0] == tag:
            begin_tag, begin_ts = stack.pop()
            phases.append((begin_tag, begin_ts, ts))
    return phases


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("capture_dir")
    parser.add_argument("--limit-ms", type=int, default=None,
                        help="override the limit the log itself reports")
    args = parser.parse_args(argv)

    samples, logged_limit = load_link_samples(args.capture_dir)
    if not samples:
        print("FAIL: no 'StreamConnection link:' lines in session_logcat.txt -- either the "
              "build predates PLE-423 or stream_feedback_stats_log is off")
        return 1
    limit = args.limit_ms or logged_limit

    phases = load_phases(args.capture_dir)
    failed = False
    print(f"{'phase':<16} {'polls':>6} {'gap added ms':>13} {'worst gap ms':>13} "
          f"{'peak silence ms':>16} {'margin':>8}")
    rows = []
    for tag, begin, end in phases:
        window = [s for s in samples if begin <= s[0] <= end]
        if not window:
            rows.append((tag, 0, None, None, None))
            continue
        # The cumulative worst gap is what this phase *added*: its value at the end
        # of the phase, less its value when the phase began.
        gap_before = min(s[2] for s in window)
        gap_after = max(s[2] for s in window)
        # A step inside the window is this phase's doing; no step means this phase
        # never beat what an earlier one had already produced.
        added = gap_after - gap_before
        rows.append((tag, len(window), added, gap_after, max(s[1] for s in window)))

    for tag, polls, added, worst, peak in rows:
        if polls == 0:
            print(f"{tag:<16} {0:>6} {'(no polls)':>13} {'-':>13} {'-':>16} {'-':>8}")
            continue
        margin = limit - peak
        flag = ""
        if peak >= limit:
            flag = "  <-- WOULD HAVE FIRED"
            failed = True
        print(f"{tag:<16} {polls:>6} {added:>13} {worst:>13} {peak:>16} {margin:>8}{flag}")

    overall_gap = max(s[2] for s in samples)
    overall_peak = max(s[1] for s in samples)
    print()
    print(f"whole session: worst inbound gap {overall_gap} ms, peak silence at a poll "
          f"{overall_peak} ms, watchdog limit {limit} ms")
    if overall_peak >= limit:
        print("FAIL: the watchdog would have fired during a deliberate impairment")
        return 1
    print(f"PASS: the watchdog never came within {limit - overall_peak} ms of firing")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
