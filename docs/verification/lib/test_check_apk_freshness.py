#!/usr/bin/env python3
"""Unit tests for check-apk-freshness.py (PLE-410).

Run directly: python3 docs/verification/lib/test_check_apk_freshness.py
No device or network needed -- everything is a synthetic git repo and a
synthetic APK (a zip with a classes.dex entry carrying a fake
PLE410_BUILD_GIT_SHA marker, the same way javac inlines the real one).
"""
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(__file__))
_spec = importlib.util.spec_from_file_location(
    "check_apk_freshness", os.path.join(os.path.dirname(__file__), "check-apk-freshness.py"))
caf = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(caf)


def run(*args, cwd=None):
    subprocess.run(args, cwd=cwd, check=True, capture_output=True)


def make_repo(root):
    run("git", "init", "-q", cwd=root)
    run("git", "-C", root, "config", "user.email", "test@example.com")
    run("git", "-C", root, "config", "user.name", "Test")


def commit_file(root, relpath, content):
    full = os.path.join(root, relpath)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w") as f:
        f.write(content)
    run("git", "-C", root, "add", relpath)
    run("git", "-C", root, "commit", "-q", "-m", f"touch {relpath}")
    return subprocess.run(
        ["git", "-C", root, "rev-parse", "HEAD"], capture_output=True, text=True, check=True,
    ).stdout.strip()


def make_apk(path, build_sha):
    marker = f"PLE410_BUILD_GIT_SHA:{build_sha}".encode("ascii")
    with zipfile.ZipFile(path, "w") as z:
        # Padding on both sides mirrors real dex layout, where the marker sits
        # among unrelated string-pool bytes rather than at an offset boundary.
        z.writestr("classes.dex", b"\x00" * 37 + marker + b"\x00" * 41)


class CheckApkFreshnessTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = os.path.join(self.tmp.name, "repo")
        os.makedirs(self.repo)
        make_repo(self.repo)
        self.header = "lib/include/chiaki/session.h"

    def test_stale_apk_refused_build_precedes_source_change(self):
        # APK built from an earlier commit; session.h changes afterwards.
        build_sha = commit_file(self.repo, "README.md", "v1")
        commit_file(self.repo, self.header, "// stats fields")
        apk = os.path.join(self.tmp.name, "stale.apk")
        make_apk(apk, build_sha)

        ok, line = caf.check(apk, self.repo, self.header)

        self.assertFalse(ok)
        self.assertIn("STALE", line)
        self.assertIn("rebuild", line.lower())

    def test_fresh_apk_passes_build_descends_from_source_change(self):
        commit_file(self.repo, self.header, "// stats fields")
        build_sha = commit_file(self.repo, "README.md", "v2")  # descends from the header commit
        apk = os.path.join(self.tmp.name, "fresh.apk")
        make_apk(apk, build_sha)

        ok, line = caf.check(apk, self.repo, self.header)

        self.assertTrue(ok)
        self.assertIn("OK", line)

    def test_apk_built_from_the_exact_change_commit_passes(self):
        build_sha = commit_file(self.repo, self.header, "// stats fields")
        apk = os.path.join(self.tmp.name, "exact.apk")
        make_apk(apk, build_sha)

        ok, _ = caf.check(apk, self.repo, self.header)

        self.assertTrue(ok)

    def test_unrelated_commit_not_in_history_refused(self):
        commit_file(self.repo, self.header, "// stats fields")
        apk = os.path.join(self.tmp.name, "unknown.apk")
        make_apk(apk, "a" * 40)  # a sha this repo has never seen

        ok, line = caf.check(apk, self.repo, self.header)

        self.assertFalse(ok)
        self.assertIn("STALE", line)

    def test_unknown_marker_refused(self):
        # Gradle's git lookup itself failed at build time -- BuildConfig carries
        # the literal string "unknown" rather than a sha.
        commit_file(self.repo, self.header, "// stats fields")
        apk = os.path.join(self.tmp.name, "noident.apk")
        make_apk(apk, "unknown")

        ok, line = caf.check(apk, self.repo, self.header)

        self.assertFalse(ok)
        self.assertIn("STALE", line)
        self.assertIn("could not determine", line)

    def test_missing_history_exits(self):
        build_sha = commit_file(self.repo, self.header, "// stats fields")
        apk = os.path.join(self.tmp.name, "fresh.apk")
        make_apk(apk, build_sha)

        with self.assertRaises(SystemExit):
            caf.check(apk, self.repo, "lib/include/chiaki/no-such-header.h")

    def test_apk_without_marker_exits(self):
        apk = os.path.join(self.tmp.name, "nomarker.apk")
        with zipfile.ZipFile(apk, "w") as z:
            z.writestr("classes.dex", b"no marker in here")

        with self.assertRaises(SystemExit):
            caf.extract_build_sha(apk)

    def test_apk_without_dex_exits(self):
        apk = os.path.join(self.tmp.name, "nodex.apk")
        with zipfile.ZipFile(apk, "w") as z:
            z.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")

        with self.assertRaises(SystemExit):
            caf.extract_build_sha(apk)


if __name__ == "__main__":
    unittest.main()
