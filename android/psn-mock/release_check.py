#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
"""Prove a release APK cannot reach the PSN mock (PLE-302).

    android/psn-mock/release_check.py --build            # build assembleRelease (arm64-v8a), then check
    android/psn-mock/release_check.py --apk some.apk     # check an APK you already have
    android/psn-mock/release_check.py --apk rel.apk --control build/psn-mock/psnmock-verified.apk

It checks three things, and fails if any does not hold:
  1. `-PchiakiPsnMock=... assembleRelease` is refused by Gradle (only with --build);
  2. the release APK's package is com.metallic.chiaki, not the .psnmock twin;
  3. no entry of the release APK (dex, manifest, resources, native libs) contains a mock marker,
     searched as UTF-8 and UTF-16LE (binary XML), while Sony's production sign-in host is found,
     so the scan is known to be reading the code at all.
--control runs the same scan over a mock debug APK and requires it to find the markers:
proof that a mock build would have been caught.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ANDROID = HERE.parent
WORKSPACE = Path(os.environ.get("PLEIKKARI_WORKSPACE_ROOT", "/home/wnt/gta6"))
MARKERS = ["madekivi", "pleikkari-psn", "psnmock", "PSN MOCK", "__mock"]
PRODUCTION = "auth.api.sonyentertainmentnetwork.com"


def say(message: str) -> None:
    print(f"release-check: {message}", flush=True)


def gradle(*tasks: str) -> subprocess.CompletedProcess:
    init = WORKSPACE / "scripts/dev/openssl-prebuilt.gradle"
    env = dict(os.environ, PLEIKKARI_ABIS=os.environ.get("PLEIKKARI_ABIS", "arm64-v8a"),
               PLEIKKARI_OPENSSL_PREBUILT_INCLUDE=str(WORKSPACE / "scripts/dev/openssl-prebuilt.cmake"))
    command = f". /home/wnt/android-sdk/env.sh && ./gradlew -q --max-workers={env.get('GATE_JOBS', '4')} -I {init} " + " ".join(tasks)
    return subprocess.run(["bash", "-c", command], cwd=ANDROID, env=env, capture_output=True, text=True)


def scan(apk: Path) -> tuple[dict[str, list[str]], bool]:
    """Marker -> APK entries containing it, and whether the production host was seen."""
    hits: dict[str, list[str]] = {}
    production = False
    needles = {m: (m.encode(), m.encode("utf-16le")) for m in MARKERS}
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            data = archive.read(name)
            production = production or PRODUCTION.encode() in data or PRODUCTION.encode("utf-16le") in data
            for marker, forms in needles.items():
                if any(form in data for form in forms):
                    hits.setdefault(marker, []).append(name)
    return hits, production


def package_of(apk: Path) -> str:
    sdk = Path(os.environ.get("ANDROID_HOME", "/home/wnt/android-sdk"))
    aapt2 = sorted(sdk.glob("build-tools/*/aapt2"))
    if not aapt2:
        return "?"
    out = subprocess.run([str(aapt2[-1]), "dump", "badging", str(apk)], capture_output=True, text=True).stdout
    return out.split("name='", 1)[1].split("'", 1)[0] if "name='" in out else "?"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--control", type=Path, help="a -PchiakiPsnMock debug APK the scan must flag")
    args = parser.parse_args(argv)
    failures = []

    if args.build:
        refused = gradle("-PchiakiPsnMock=verified", "assembleRelease")
        if refused.returncode != 0 and "chiakiPsnMock is debug-only" in refused.stdout + refused.stderr:
            say("PASS: Gradle refuses -PchiakiPsnMock for a release variant")
        else:
            failures.append("Gradle did not refuse -PchiakiPsnMock=verified assembleRelease")
        say("building assembleRelease")
        built = gradle("assembleRelease")
        if built.returncode != 0:
            say(built.stdout[-3000:] + built.stderr[-3000:])
            say("FAIL: assembleRelease did not build")
            return 1
        args.apk = args.apk or next((ANDROID / "app/build/outputs/apk/release").glob("*.apk"))
    if not args.apk:
        parser.error("pass --build or --apk")

    package = package_of(args.apk)
    say(f"{args.apk}: package {package}")
    if package != "com.metallic.chiaki":
        failures.append(f"release package is {package}")
    hits, production = scan(args.apk)
    if hits:
        failures.append(f"mock markers in the release APK: {hits}")
    else:
        say(f"PASS: none of {MARKERS} in any entry")
    if production:
        say(f"PASS: {PRODUCTION} found, so the scan reads the code")
    else:
        failures.append(f"{PRODUCTION} not found either: the scan is not reading the code")
    if args.control:
        control_hits, _ = scan(args.control)
        if control_hits:
            say(f"PASS: the control APK is flagged: {sorted(control_hits)}")
        else:
            failures.append(f"the control APK {args.control} shows no marker: the scan proves nothing")

    for failure in failures:
        say(f"FAIL: {failure}")
    print("PASS" if not failures else "FAIL")
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
