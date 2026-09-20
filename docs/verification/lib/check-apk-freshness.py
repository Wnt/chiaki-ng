#!/usr/bin/env python3
"""PLE-410: refuse a capture whose installed APK predates the stats fields it
claims to measure.

The incident: `build/captures/ple357` was captured four hours *after* PLE-356
landed in source, but the installed binary was built *before* PLE-356 --
nobody rebuilt between the source change and the install. A check that
compares the *install* timestamp (`dumpsys package`'s lastUpdateTime) against
a source-change timestamp would have said this was fine, because the install
happened after the source changed; the actual defect is that the *binary*
predates it.

First attempt at this check compared the installed APK's `classes*.dex`
mtime (Gradle's dexer writes those) against `lib/include/chiaki/session.h`'s
last git commit time. That does not work on this project: verified against a
real `assembleDebug` output (PLE-410), every entry in the APK -- all 1073 of
them, not just the dex files -- is stamped with the same fixed epoch
(1981-01-01 01:01:02), Android Gradle Plugin's reproducible-build
normalization. There is no build-time signal left in the zip's timestamps at
all.

What ships instead: `android/app/build.gradle` embeds the exact git commit
the build ran from as a `BuildConfig.BUILD_GIT_SHA_MARKER` string constant
(`PLE410_BUILD_GIT_SHA:<40 hex chars>`). javac inlines that literal, so it
lands verbatim, as plain ASCII bytes, somewhere in the installed APK's
`classes*.dex` string pool -- readable by a plain byte search, no dex-format
parsing needed, and it survives R8/minification because minification renames
identifiers, not string *literal* content. "Current" means: the commit this
APK was built from has the last commit that touched
`lib/include/chiaki/session.h` as an ancestor (`git merge-base
--is-ancestor`), i.e. the exact source tree that was compiled already
contained that change. That is an exact git-DAG fact, not a timestamp
heuristic, so it has no clock-skew or reproducible-build failure mode.

Usage: check-apk-freshness.py --apk <path-to-pulled-apk> --repo <worktree-root>
Exit 0 and an OK line if the APK's build commit descends from (or is)
session.h's last-change commit, exit 1 and a STALE line otherwise.

What this does NOT catch (state these limits, not just the code):
  * A stats-field change landed in a file other than session.h (e.g. a Kotlin-
    side field added without touching the C header) -- this only watches one
    file, chosen because every stats-field change so far has touched it, not
    because it is the only place a change could happen.
  * An uncommitted local edit to session.h, in either direction: if the
    worktree that ran the build had an uncommitted change to session.h not
    yet reflected by `git log`, or if HEAD moved locally after the build
    without a new commit, the embedded SHA and the ancestry check cannot see
    it -- git history is the only thing this reads.
  * A build whose embedded SHA is not in this repo's history at all (rebased
    away, or built from an unrelated checkout) is treated as stale and
    refused, which is the safe default, but it cannot distinguish that case
    from real staleness in its message.
  * Runtime staleness: a correct, fresh binary that is nonetheless serving
    stale state (a wedged service, a cached process) reads exactly the same
    as a truly fresh one to this check.
  * A build produced with `chiakiPsnMock` or any other property that does not
    touch `git rev-parse HEAD` still embeds the real commit, so this cannot
    catch APKs built from the right commit but with the wrong build flags for
    the capture at hand -- it only verifies source freshness.
"""
import argparse
import re
import subprocess
import sys
import zipfile

STATS_HEADER = "lib/include/chiaki/session.h"
MARKER_RE = re.compile(rb"PLE410_BUILD_GIT_SHA:([0-9a-f]{40}|unknown)")


def git_last_change_sha(repo, path):
    """Return the short-form commit that last touched `path` in `repo`."""
    out = subprocess.run(
        ["git", "-C", repo, "log", "-1", "--format=%H", "--", path],
        capture_output=True, text=True, check=True,
    ).stdout.strip()
    if not out:
        sys.exit(f"check-apk-freshness.py: git has no history for {path} in {repo}")
    return out


def extract_build_sha(apk_path):
    """Find BuildConfig.BUILD_GIT_SHA_MARKER's literal value in the APK's dex.

    javac inlines the `buildConfigField` string constant into every class
    that references it, so a plain byte search across the classes*.dex
    entries' raw bytes is enough -- no dex format parsing needed.
    """
    with zipfile.ZipFile(apk_path) as z:
        dex_names = [n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex")]
        if not dex_names:
            sys.exit(f"check-apk-freshness.py: {apk_path} has no classes*.dex entries -- not a valid APK?")
        for name in dex_names:
            m = MARKER_RE.search(z.read(name))
            if m:
                return m.group(1).decode("ascii")
    sys.exit(
        f"check-apk-freshness.py: no PLE410_BUILD_GIT_SHA marker found in {apk_path}'s dex -- "
        "this APK predates the marker itself (rebuild required) or BuildConfig was stripped"
    )


def check(apk_path, repo, stats_header=STATS_HEADER):
    build_sha = extract_build_sha(apk_path)
    src_sha = git_last_change_sha(repo, stats_header)
    if build_sha == "unknown":
        return False, (
            "STALE: the installed APK's build could not determine its own git commit "
            "(BuildConfig.BUILD_GIT_SHA_MARKER is 'unknown' -- built outside a git checkout?). "
            "Cannot confirm it is current. Rebuild and install: "
            "(cd android && ./gradlew assembleDebug) && "
            "adb install -r android/app/build/outputs/apk/debug/app-debug.apk"
        )
    is_ancestor = subprocess.run(
        ["git", "-C", repo, "merge-base", "--is-ancestor", src_sha, build_sha],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    ).returncode == 0
    if not is_ancestor:
        return False, (
            f"STALE: installed APK was built from commit {build_sha[:12]}, which does not "
            f"include {stats_header}'s last change {src_sha[:12]} (either that commit isn't "
            f"an ancestor of the build, or {build_sha[:12]} is not in this repo's history at "
            "all -- rebased away, or a different checkout). This capture would measure a "
            "stale metric and say nothing about it. Rebuild and install: "
            "(cd android && ./gradlew assembleDebug) && "
            "adb install -r android/app/build/outputs/apk/debug/app-debug.apk"
        )
    return True, (
        f"OK: installed APK was built from commit {build_sha[:12]}, which includes "
        f"{stats_header}'s last change {src_sha[:12]}"
    )


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--apk", required=True, help="path to the pulled installed APK")
    p.add_argument("--repo", required=True, help="worktree root to read git history from")
    p.add_argument("--stats-header", default=STATS_HEADER)
    args = p.parse_args()

    ok, line = check(args.apk, args.repo, args.stats_header)
    print(line)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
