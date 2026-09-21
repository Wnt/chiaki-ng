#!/usr/bin/env python3
"""Tests for verify-blip-pattern.py's profile-driven checks (PLE-404).

No capture, no device: each case builds a synthetic capture directory -- a
phases.txt, a session_logcat.txt of `Feedback stats:` lines, and where the
case needs one a phone_ping.txt -- and runs the guard's own `verify()` over
it.

The profile table is monkeypatched with synthetic entries rather than read
from scripts/net/impair_profiles.py, so these tests say the same thing before
and after a new profile lands there.

Run directly: python3 docs/verification/lib/test_verify_blip_pattern.py
"""
import datetime
import importlib.util
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "verify_blip_pattern", os.path.join(HERE, "verify-blip-pattern.py"))
vbp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(vbp)

CLEAN_JITTER = 1.5
BAD_JITTER = 9.0
# feedback_stats.stamp() reads a logcat `MM-DD HH:MM:SS.mmm` and assumes the
# capture year, so the synthetic timestamps have to be real ones in that year
# and in this box's timezone, or nothing round-trips back into the phase span.
T0 = vbp.fb.stamp("03-04 12:00:00.000")
CYCLE = 20.0
PHASE_SECONDS = 120


def logcat_stamp(ts):
    return datetime.datetime.fromtimestamp(ts, vbp.fb.TZ).strftime("%m-%d %H:%M:%S.%f")[:-3]


def stats_line(ts, jitter_ms, loss_pct, gap_ms=None, silence_ms=None):
    """One `Feedback stats:` line in the shape feedback_stats.py parses.

    Loss is carried on `congestion_loss measured=` (a fraction), the field the
    real captures move; the takion counters stay equal, which is what they do
    on this box even through a total outage (PLE-404).
    """
    line = (f"{logcat_stamp(ts)} I/Chiaki  (1): Feedback stats: window 1000 ms "
            f"video received 60 decoded 60 packet_jitter_ms {jitter_ms:.3f} "
            f"packet_jitter_raw_ms {jitter_ms:.3f} | takion_raw expected_per_s 120.000 "
            f"received_per_s 120.000 fec_recovered 0 unrecoverable 0 | probe_rtt_ms 4.000 "
            f"congestion_loss measured={loss_pct / 100.0:.4f} reported=0.0000")
    # PLE-464: the link-silence fields, absent from every capture taken before it
    # landed -- which is what makes them the switch between the two sources.
    if gap_ms is not None or silence_ms is not None:
        line += (f" | takion_silence_ms {int(silence_ms or 0)} "
                 f"window_max_gap_ms {int(gap_ms or 0)}")
    return line


def ping_line(ts, seq):
    return f"[{ts:.6f}] 64 bytes from 192.168.1.164: icmp_seq={seq} ttl=63 time=4.00 ms"


class GuardTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = self.tmp.name
        self.saved = dict(vbp.impair_profiles.PROFILES)
        self.addCleanup(self._restore)
        # A 0.2 s pulse (one sample wide) and a 1.2 s one (two samples wide),
        # standing in for blip-200ms and roam-1200ms.
        vbp.impair_profiles.PROFILES["pulse-narrow"] = vbp.impair_profiles.Profile(
            netem=("delay", "0ms"), rate=None, rtt_ms=0.0, description="test",
            dynamic=True, on_netem=("delay", "200ms"), cycle_seconds=CYCLE, on_seconds=0.2)
        vbp.impair_profiles.PROFILES["pulse-wide"] = vbp.impair_profiles.Profile(
            netem=("delay", "0ms", "loss", "0%"), rate=None, rtt_ms=0.0, description="test",
            dynamic=True, on_netem=("delay", "0ms", "loss", "100%"),
            cycle_seconds=CYCLE, on_seconds=1.2)

    def _restore(self):
        vbp.impair_profiles.PROFILES.clear()
        vbp.impair_profiles.PROFILES.update(self.saved)

    def write(self, tag, *, bad_offsets=(), bad_width=1, ping_gaps=(),
              gap_offsets=None, gap_width=1, gap_ms=3000, with_gap_field=False):
        """One phase, 120 samples at 1 Hz, bad seconds at the given offsets.

        `gap_offsets` (PLE-464) writes the link-silence fields as well, with a
        silent run `gap_width` samples wide at each offset -- the shape a total
        outage makes, and the one the jitter/loss samples cannot show.
        """
        with open(os.path.join(self.dir, "phases.txt"), "w") as f:
            f.write(f"PHASE_BEGIN {tag} {T0:.6f}\n")
            f.write(f"PHASE_END {tag} {T0 + PHASE_SECONDS:.6f}\n")
        bad = {o + w for o in bad_offsets for w in range(bad_width)}
        silent = {o + w for o in (gap_offsets or ()) for w in range(gap_width)}
        emit_gap = with_gap_field or gap_offsets is not None
        with open(os.path.join(self.dir, "session_logcat.txt"), "w") as f:
            for i in range(PHASE_SECONDS + 1):
                is_bad = i in bad
                kwargs = {}
                if emit_gap:
                    kwargs = dict(gap_ms=gap_ms if i in silent else 18,
                                  silence_ms=gap_ms if i in silent else 3)
                f.write(stats_line(T0 + i, BAD_JITTER if is_bad else CLEAN_JITTER,
                                    20.0 if is_bad else 0.0, **kwargs) + "\n")
        if ping_gaps:
            missing = {o + w for o in ping_gaps for w in range(2)}
            with open(os.path.join(self.dir, "phone_ping.txt"), "w") as f:
                for i in range(PHASE_SECONDS + 1):
                    if i not in missing:
                        f.write(ping_line(T0 + i, i + 1) + "\n")

    def verify(self):
        return vbp.verify(self.dir, cycle_seconds=CYCLE, tolerance=1,
                           jitter_threshold_ms=3.0, loss_threshold_pct=2.0,
                           settle_seconds=5.0, merge_gap_s=2.0)

    def test_a_dynamic_phase_passes_on_its_own_cadence_and_width(self):
        self.write("02_pulse-narrow", bad_offsets=range(10, 120, 20), bad_width=1)
        ok, lines = self.verify()
        self.assertTrue(ok, lines)
        self.assertIn("widths", lines[0])

    def test_a_pulse_narrower_than_the_profile_claims_fails(self):
        """PLE-404: counting alone cannot tell a 1.2 s outage from a 200 ms
        hitch -- both fire once every 20 s. A capture labelled with the wide
        profile whose excursions are one sample wide is the narrow profile's
        data under the wide profile's name."""
        self.write("02_pulse-wide", bad_offsets=range(10, 120, 20), bad_width=1)
        ok, lines = self.verify()
        self.assertFalse(ok, lines)
        self.assertIn("widths", lines[0])
        self.assertIn("expected 2-4", lines[0])

    def test_the_wide_pulse_passes_at_its_own_width(self):
        self.write("02_pulse-wide", bad_offsets=range(10, 120, 20), bad_width=2)
        ok, lines = self.verify()
        self.assertTrue(ok, lines)

    def test_a_dead_loop_still_fails_with_no_witness(self):
        self.write("02_pulse-narrow", bad_offsets=(10, 30), bad_width=1)
        ok, lines = self.verify()
        self.assertFalse(ok, lines)
        self.assertIn("cyclic pattern is not present", lines[0])

    def test_a_dead_loop_fails_when_the_ping_agrees_nothing_happened(self):
        self.write("02_pulse-wide", bad_offsets=(10, 30), bad_width=2, ping_gaps=(10,))
        ok, lines = self.verify()
        self.assertFalse(ok, lines)
        self.assertIn("neither the metrics nor the wire", lines[0])

    def test_metrics_blind_to_an_outage_the_wire_shows_passes_and_says_so(self):
        """PLE-404's own capture: five 1.2 s total outages, two visible in the
        app's metrics, all five in the phone's ping. Failing that as a dead
        loop would have been wrong -- and no retry could ever fix it."""
        self.write("02_pulse-wide", bad_offsets=(30, 50), bad_width=2,
                    ping_gaps=range(10, 120, 20))
        ok, lines = self.verify()
        self.assertTrue(ok, lines)
        self.assertIn("the metrics this capture reads are blind to it", lines[0])

    def test_a_clean_phase_with_a_leak_fails(self):
        self.write("03_clean", bad_offsets=(60,), bad_width=1)
        ok, lines = self.verify()
        self.assertFalse(ok, lines)
        self.assertIn("leaked", lines[0])

    def test_an_unknown_profile_falls_back_to_the_name_match(self):
        """An old capture whose tag names a profile no longer in the table is
        still checked on its cadence -- just not on a width it cannot state."""
        self.write("02_blip-legacy", bad_offsets=range(10, 120, 20), bad_width=1)
        ok, lines = self.verify()
        self.assertTrue(ok, lines)
        self.assertIn("width not checked", lines[0])


    # ---- PLE-464: the link-silence source ---------------------------------------

    def test_an_outage_the_metrics_cannot_see_passes_on_the_silence_series(self):
        """The defect PLE-464 root-caused, as a capture: `takion_packets_lost` is only
        raised when a packet arrives, so a second in which nothing arrived shows clean
        jitter and clean loss. Before the silence field the guard read those and called
        a perfectly good capture a dead loop."""
        self.write("02_pulse-wide", bad_offsets=(), gap_offsets=range(10, 120, 20),
                    gap_width=2)
        ok, lines = self.verify()
        self.assertTrue(ok, lines)
        self.assertIn("from link silence", lines[0])

    def test_the_silence_series_is_preferred_over_the_metrics(self):
        """Both present and disagreeing: the wire wins. Here the metrics show the wrong
        cadence (two hits) and the silence series the right one."""
        self.write("02_pulse-wide", bad_offsets=(30, 50), bad_width=2,
                    gap_offsets=range(10, 120, 20), gap_width=2)
        ok, lines = self.verify()
        self.assertTrue(ok, lines)
        self.assertIn("from link silence", lines[0])

    def test_the_silence_series_still_checks_the_pulse_width(self):
        """PLE-404's width rule is not weakened by the new source: a one-sample silence
        under the wide profile's name is still the narrow profile's data."""
        self.write("02_pulse-wide", gap_offsets=range(10, 120, 20), gap_width=1)
        ok, lines = self.verify()
        self.assertFalse(ok, lines)
        self.assertIn("from link silence", lines[0])
        self.assertIn("expected 2-4", lines[0])

    def test_a_clean_phase_silent_on_the_wire_fails_even_with_clean_metrics(self):
        """A clean phase must be clean on the wire too. The metrics here are spotless."""
        self.write("03_clean", bad_offsets=(), gap_offsets=(60,), gap_width=2)
        ok, lines = self.verify()
        self.assertFalse(ok, lines)
        self.assertIn("leaked", lines[0])

    def test_a_quiet_link_below_the_gap_threshold_is_not_an_excursion(self):
        """The cut has to sit above what a healthy link does. 4g's and wifi-slow's worst
        seconds in build/captures/ple464 are 39 ms and 56 ms; neither may register."""
        self.write("03_clean", bad_offsets=(), gap_offsets=range(10, 120, 20),
                    gap_width=2, gap_ms=56)
        ok, lines = self.verify()
        self.assertTrue(ok, lines)

    def test_a_capture_without_the_field_reads_the_metrics_exactly_as_before(self):
        """Every capture taken before PLE-464 landed. Its verdict must not move."""
        self.write("02_pulse-wide", bad_offsets=range(10, 120, 20), bad_width=2)
        ok, lines = self.verify()
        self.assertTrue(ok, lines)
        self.assertNotIn("link silence", lines[0])


if __name__ == "__main__":
    unittest.main(verbosity=2)
