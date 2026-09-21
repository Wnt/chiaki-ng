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

  * a *dynamic* phase -- one whose profile is marked `dynamic` in
    scripts/net/impair_profiles.py -- must show close to the expected number
    of excursions for its duration, one every `cycle_seconds`. Tolerance is
    +/-1 cycle: tight enough that a phase which only caught 3 of 6 expected
    excursions still fails, loose enough to absorb the +/-1 boundary jitter
    every real capture on this box shows (a cycle landing 1s either side of a
    phase edge, PLE-411 section 3).
  * ...and its excursions must be the *width* that profile's pulse implies
    (PLE-404). Counting alone cannot tell two dynamic profiles apart: a
    1.2 s outage (`roam-1200ms`) and a 200 ms hitch (`blip-200ms`) both fire
    once every 20 s, so a capture that claims the first while the second was
    actually in force passes a count-only check. The expected width comes from
    the profile's own `on_seconds` rather than from this file: a pulse of P
    seconds sampled at 1 Hz occupies ceil(P) samples, and the band allows two
    more for a pulse straddling a tick and for the estimator's own settling.
    Checked on the median excursion width so one merged pair cannot fail a
    phase. Across every blip-200ms capture on this box (ple356, ple366,
    ple403, ple411, ple423-blip, ple423-impair) every excursion is exactly 1
    sample wide, which is what ceil(0.2)=1 predicts.
  * every phase whose name contains "clean" must show *zero* excursions after
    a short settle window (default 5s, covering the netem qdisc-switch
    settling documented in PLE-366/analyze.py) -- any excursion inside a
    "clean" phase beyond that window is impairment that leaked past its own
    phase, the direct symptom of a `blip_loop` that didn't die on schedule but
    also didn't stop on command. Other statically-impaired profiles (5g,
    wifi-slow, loss-2, ...) are supposed to show sustained impairment
    throughout and are not checked -- out of scope for this ticket.

PLE-404 added a second, independent witness for one specific reason. The check
above reads the *app's* metrics, so it cannot tell "the impairment never
happened" from "the impairment happened and the app's metrics are blind to
it" -- and the second is real: five 1.2 s total outages (`roam-1200ms`,
build/captures/ple404) moved `video received` from 60/s to 0-20/s and lost
4-13 frames each, while the three numbers the badge reads (takion loss,
packet jitter, probe RTT) stayed at their clean-phase values in four of the
five. Failing that capture as "the loop died" would have been wrong, and
retrying it forever would never fix it. So when the metric excursions are not
there, the phone's own `ping` log -- which every capture.sh on this box
already records alongside the stream, against the console, over the same
impaired leg -- is consulted as the witness of what the *wire* did. Cadence
present on the wire but absent from the metrics is reported as exactly that,
and passes; absent from both is a dead loop, and still fails.

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
import math
import os
import re
import statistics
import sys

REPO = os.environ.get("REPO", "/home/wnt/gta6")
_FB_MODULE = os.path.join(REPO, "scripts", "dev", "feedback_stats.py")
if not os.path.isfile(_FB_MODULE):
    sys.exit(f"verify-blip-pattern.py: feedback_stats.py not found at {_FB_MODULE} "
              "(set REPO= to override)")
sys.path.insert(0, os.path.dirname(_FB_MODULE))
import feedback_stats as fb  # noqa: E402

# PLE-404: the dynamic profiles and their pulse shapes are the impairment
# tool's own table, not a copy kept here -- a new dynamic profile teaches this
# guard its shape by being defined there (scripts/net/impair_profiles.py has
# on_netem/cycle_seconds/on_seconds, and scripts/net/test_impair.py holds the
# guest script's table against it).
_PROFILES_MODULE = os.path.join(REPO, "scripts", "net", "impair_profiles.py")
if not os.path.isfile(_PROFILES_MODULE):
    sys.exit(f"verify-blip-pattern.py: impair_profiles.py not found at {_PROFILES_MODULE} "
              "(set REPO= to override)")
sys.path.insert(0, os.path.dirname(_PROFILES_MODULE))
import impair_profiles  # noqa: E402

WIDTH_SLACK_SAMPLES = 2
_RE_PING = re.compile(r"^\[(\d+\.\d+)\].*icmp_seq=(\d+)")
_RE_LINK_GAP = re.compile(fb.TIMESTAMP_RE + r".*takion_silence_ms (\d+) window_max_gap_ms (\d+)")

# PLE-464: how long the console's socket must be silent in a 1 Hz window for that
# second to count as an excursion. Derived from build/captures/ple464, which stepped
# all five profiles under one stream with the field present; worst sample per phase:
#   clean 42 and 25 ms (240 samples)   4g 39 ms   wifi-slow 56 ms
#   blip-200ms 212 ms (5 pulses, 201-212)   roam-3000ms 3055 ms (5 outages)
# 100 ms is 1.8x the worst sample any non-dynamic profile produced and half the
# smallest real pulse, i.e. inside the empty band between them rather than inside
# either distribution.
GAP_THRESHOLD_MS = 100.0


def profile_of(tag):
    """The impairment profile a phase tag names, or None.

    Capture tags are `NN_<profile>` (`02_blip-200ms`), the form every
    capture.sh on this box builds them in.
    """
    name = tag.split("_", 1)[1] if "_" in tag else tag
    return impair_profiles.PROFILES.get(name)


def expected_width_band(profile):
    """[min, max] excursion width in samples for one pulse of `profile`."""
    low = max(1, math.ceil(profile.on_seconds))
    return low, low + WIDTH_SLACK_SAMPLES


def excursion_widths(excursions):
    """Each excursion's width in 1 Hz samples (a single-sample hit is 1)."""
    return [round(end - start) + 1 for start, end in excursions]


def load_link_gap_samples(capture_dir, logcat_name="session_logcat.txt"):
    """The per-second link-silence series, as fb.find_excursions' (t, value, 0.0) shape.

    PLE-464 is why this exists. The excursion source below it -- the app's jitter and
    loss samples -- is blind to a total outage: `takion_packets_lost` is only ever
    raised when a packet arrives (lib/src/videoreceiver.c:162 is the single caller),
    so a second in which nothing arrived registers as 0 received and 0 lost, i.e.
    clean. Reading the cadence of an outage profile off those samples therefore
    measures the app's blindness, not the wire: build/captures/ple464's roam-3000ms
    phase shows 7 jitter/loss excursions of median width 1 where the wire had exactly
    5 outages 3 s wide, and its blip-200ms phase shows 4 of 6 pulses.

    The same capture's stats line now carries the silence directly. `window_max_gap_ms`
    is the longest gap inside the window and `takion_silence_ms` the silence still
    running at the poll; the max of the two is taken because a wholly silent second
    has no arriving packet to close a gap with, so it reports the second and not the
    first. On the same phases that reads 5 outages of width 4 and 5 pulses of width 1.

    Returns None when no line carries the field -- every capture taken before PLE-464
    landed -- and those fall back to the jitter/loss source with their verdicts
    unchanged.
    """
    path = os.path.join(capture_dir, logcat_name)
    if not os.path.isfile(path):
        return None
    samples = []
    with open(path, errors="replace") as handle:
        for line in handle:
            m = _RE_LINK_GAP.search(line)
            if m:
                samples.append((fb.stamp(m.group(1)),
                                float(max(int(m.group(2)), int(m.group(3)))), 0.0))
    return samples or None


def load_ping_gaps(capture_dir, ping_name="phone_ping.txt"):
    """Outages seen by the phone's own ping to the console, as (start, end) spans.

    capture.sh runs `ping -D -i 1` from the phone to the PS5 for the whole
    capture, over the same impaired leg the stream uses, and its timestamps
    come from the same clock as logcat's. A missing `icmp_seq` run is an
    outage the wire actually had, independent of anything the app measured.
    Returns [] (not an error) when the file is absent: an older capture simply
    has no witness, and the metric check stands alone as before.
    """
    path = os.path.join(capture_dir, ping_name)
    if not os.path.isfile(path):
        return None
    seen = {}
    with open(path, errors="replace") as handle:
        for line in handle:
            m = _RE_PING.search(line)
            if m:
                seen[int(m.group(2))] = float(m.group(1))
    if len(seen) < 2:
        return None
    order = sorted(seen)
    gaps = []
    for previous, current in zip(order, order[1:]):
        if current == previous + 1:
            continue
        # The gap spans from the last reply before it to the first after it.
        gaps.append((seen[previous], seen[current]))
    return gaps


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
                         jitter_threshold_ms, loss_threshold_pct, merge_gap_s,
                         profile=None, ping_gaps=None, gap_samples=None,
                         gap_threshold_ms=GAP_THRESHOLD_MS):
    window = [s for s in samples if begin <= s[0] <= end]
    if not window:
        return False, f"{tag}: no raw samples in [{begin:.1f}, {end:.1f}] " \
            "(session_logcat.txt does not cover this phase -- cannot verify anything)"
    duration = end - begin
    expected = max(1, round(duration / cycle_seconds))
    # PLE-464: prefer the link-silence series when the capture carries it -- it sees
    # the wire, where the jitter/loss series sees only what the app could count.
    source, excursions = "jitter/loss", None
    if gap_samples is not None:
        gap_window = [s for s in gap_samples if begin <= s[0] <= end]
        if gap_window:
            source = "link silence"
            excursions = fb.find_excursions(gap_window, gap_threshold_ms,
                                            float("inf"), merge_gap_s)
    if excursions is None:
        excursions = fb.find_excursions(window, jitter_threshold_ms, loss_threshold_pct,
                                        merge_gap_s)
    got = len(excursions)
    lo, hi = expected - tolerance, expected + tolerance
    if not lo <= got <= hi:
        # PLE-404: before calling this a dead loop, ask the wire.
        if ping_gaps is not None:
            on_wire = [g for g in ping_gaps if begin <= g[0] <= end]
            if lo <= len(on_wire) <= hi:
                return True, (
                    f"{tag}: only {got} excursions in the app's metrics (expected {expected} "
                    f"+/-{tolerance}), but the phone's ping to the console lost contact "
                    f"{len(on_wire)} times in the same window -- the impairment ran, the "
                    "metrics this capture reads are blind to it. That is a measurement "
                    "result, not a broken capture; read it as one")
            return False, (f"{tag}: {got} excursions in {duration:.1f}s (expected {expected} "
                            f"+/-{tolerance}), and the phone's ping shows {len(on_wire)} "
                            "outage(s) in the same window -- neither the metrics nor the wire "
                            "show the profile's cadence, so the cycling loop was not running")
        return False, (f"{tag}: {got} excursions in {duration:.1f}s from {source} (expected "
                        f"{expected} +/-{tolerance}) -- the dynamic profile's cyclic pattern is "
                        "not present, this capture measured something other than what it claims")
    widths = excursion_widths(excursions)
    if profile is None or profile.on_seconds is None:
        # A phase whose tag names no known profile (an old capture, a renamed
        # profile): the count is all this can check, and saying so is better
        # than silently checking less than the caller thinks.
        return True, (f"{tag}: {got} excursions in {duration:.1f}s from {source} (expected "
                       f"{expected} +/-{tolerance}), widths {widths} -- OK (no profile table "
                       "entry, width not checked)")
    low, high = expected_width_band(profile)
    median_width = statistics.median(widths)
    if not low <= median_width <= high:
        return False, (f"{tag}: {got} excursions in {duration:.1f}s from {source} (count OK) "
                        f"but their widths "
                        f"are {widths} -- median {median_width:g} samples, expected {low}-{high} "
                        f"for a {profile.on_seconds:g}s pulse. The cadence is right and the shape "
                        "is not: this is a different impairment than the phase claims")
    return True, (f"{tag}: {got} excursions in {duration:.1f}s from {source} (expected "
                   f"{expected} +/-{tolerance}), widths {widths} (expected {low}-{high} for a "
                   f"{profile.on_seconds:g}s pulse) -- OK")


def check_static_phase(tag, begin, end, samples, *, settle_seconds,
                        jitter_threshold_ms, loss_threshold_pct, merge_gap_s,
                        gap_samples=None, gap_threshold_ms=GAP_THRESHOLD_MS):
    window = [s for s in samples if begin + settle_seconds <= s[0] <= end]
    excursions = fb.find_excursions(window, jitter_threshold_ms, loss_threshold_pct, merge_gap_s)
    # PLE-464: a clean phase must be clean on the wire too, not merely in the metrics
    # that cannot see an outage. Leaked impairment from either source fails it.
    if gap_samples is not None:
        gap_window = [s for s in gap_samples if begin + settle_seconds <= s[0] <= end]
        excursions = sorted(excursions + fb.find_excursions(
            gap_window, gap_threshold_ms, float("inf"), merge_gap_s))
    if not excursions:
        return True, f"{tag}: 0 excursions after the {settle_seconds:.0f}s settle window -- OK"
    spans = ", ".join(f"+{t0 - begin:.1f}s..+{t1 - begin:.1f}s" for t0, t1 in excursions)
    return False, (f"{tag}: {len(excursions)} excursion(s) after the {settle_seconds:.0f}s settle "
                    f"window ({spans}) -- impairment leaked into a phase that should be clean")


def verify(capture_dir, *, cycle_seconds, tolerance, jitter_threshold_ms, loss_threshold_pct,
           settle_seconds, merge_gap_s, dynamic_marker="blip", clean_marker="clean",
           gap_threshold_ms=GAP_THRESHOLD_MS):
    occurrences = load_phase_occurrences(capture_dir)
    if not occurrences:
        return False, ["no PHASE_BEGIN/PHASE_END pairs found in phases.txt"]
    samples = fb.load_raw_samples(capture_dir)
    ping_gaps = load_ping_gaps(capture_dir)
    gap_samples = load_link_gap_samples(capture_dir)
    ok = True
    lines = []
    for tag, begin, end in occurrences:
        profile = profile_of(tag)
        # The profile table decides what a phase is; the "blip" string match
        # is the fallback for a tag the table does not know (PLE-404 -- before
        # this, a dynamic profile whose name did not contain "blip" fell
        # through to "not checked", which is the exemption PLE-418 exists to
        # refuse).
        is_dynamic = profile.dynamic if profile is not None else dynamic_marker in tag
        is_clean = (profile is not None and not profile.netem and profile.rate is None) \
            if profile is not None else clean_marker in tag
        if is_dynamic:
            passed, line = check_dynamic_phase(
                tag, begin, end, samples,
                cycle_seconds=(profile.cycle_seconds if profile is not None
                                and profile.cycle_seconds else cycle_seconds),
                tolerance=tolerance,
                jitter_threshold_ms=jitter_threshold_ms, loss_threshold_pct=loss_threshold_pct,
                merge_gap_s=merge_gap_s, profile=profile, ping_gaps=ping_gaps,
                gap_samples=gap_samples, gap_threshold_ms=gap_threshold_ms)
        elif is_clean:
            passed, line = check_static_phase(
                tag, begin, end, samples, settle_seconds=settle_seconds,
                jitter_threshold_ms=jitter_threshold_ms, loss_threshold_pct=loss_threshold_pct,
                merge_gap_s=merge_gap_s, gap_samples=gap_samples,
                gap_threshold_ms=gap_threshold_ms)
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
    p.add_argument("--gap-threshold-ms", type=float, default=GAP_THRESHOLD_MS,
                   help="PLE-464: link silence, in ms, that makes a second an excursion")
    args = p.parse_args()

    ok, lines = verify(
        args.capture_dir, cycle_seconds=args.cycle_seconds, tolerance=args.tolerance,
        jitter_threshold_ms=args.jitter_threshold_ms, loss_threshold_pct=args.loss_threshold_pct,
        settle_seconds=args.settle_seconds, merge_gap_s=args.merge_gap_seconds,
        gap_threshold_ms=args.gap_threshold_ms)
    for line in lines:
        print(line)
    print("PASS" if ok else "FAIL: dynamic-profile pattern check failed, see above")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
