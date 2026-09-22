#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
"""Prove the onboarding driver guards onboarding: it passes the real flow and fails each known defect (PLE-302).

    android/psn-mock/onboarding_suite.py --build        # build the nolink mock APK, then run every case
    android/psn-mock/onboarding_suite.py                 # reuse build/psn-mock/psnmock-nolink.apk
    android/psn-mock/onboarding_suite.py --only settings-redirect

Every case runs onboarding_test.py on the nolink mock host with the app's link selection explicitly
disabled, which is what production looks like to a user. The clean case must PASS, through the
browser tab, its Finish sign-in action and the console link. Each fault case turns on one defect
in the mock build (PsnMockFault.kt) and must FAIL for that defect's reason, not any other: a
guard that fails for the wrong reason would also pass the defect once the other problem is gone.

Exit 0 only if every case behaves. Artifacts: build/psn-mock/suite-<time>/<case>/ and suite.json.
Needs the mock up (serve.sh status) and the emulator booted; one full run takes about 12 minutes.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import onboarding_test  # noqa: E402

# case -> (driver arguments, expected result, pattern the driver's reason must match)
CASES = {
    "clean": (["--play"], "PASS", r"link failed honestly and Retry re-ran it"),
    "redirect-dead-end": (["--fault", "redirect-dead-end"], "FAIL", r"^dead end: nothing changed for \d+ s in the browser$"),
    "settings-redirect": (["--fault", "settings-redirect"], "FAIL", r"^left the app for \S*settings\S* \(Android Settings\)$"),
    "instruction-paragraph": (["--fault", "instruction-paragraph"], "FAIL", r"^instruction paragraph: "),
    # PLE-323: a first-time user leaves the redirect page without Finish; each way must still sign in.
    "exit-x": (["--exit", "x"], "PASS", r"^signed in; console list shows"),
    "exit-back": (["--exit", "back"], "PASS", r"^signed in; console list shows"),
    "exit-open-in-browser": (["--exit", "open-in-browser"], "PASS", r"^signed in; console list shows"),
    "exit-idle": (["--exit", "idle", "--timeout", "240"], "PASS", r"^signed in; console list shows"),
    "exit-loses-code": (["--exit", "x", "--fault", "exit-loses-code"], "FAIL", r"^dead end: nothing changed for \d+ s in fi\.madekivi\.pleikkari\.psnmock$"),
    # The dead-end fault disables a component, which outlives the app process: a clean run after the
    # faults proves no fault leaks into the next run.
    "clean-after-faults": (["--play"], "PASS", r"link failed honestly and Retry re-ran it"),
}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--build", action="store_true", help="build the nolink mock APK first")
    parser.add_argument("--only", choices=sorted(CASES), action="append")
    parser.add_argument("--serial", help="default: the driver's (emulator-5554)")
    args = parser.parse_args(argv)

    out = onboarding_test.WORKSPACE / "build/psn-mock" / f"suite-{time.strftime('%Y%m%dT%H%M%S')}"
    out.mkdir(parents=True, exist_ok=True)
    if args.build:
        onboarding_test.build_apk("nolink")
    results = []
    for name in args.only or CASES:
        extra, expected, pattern = CASES[name]
        command = [sys.executable, str(HERE / "onboarding_test.py"), "--link", "nolink", "--out", str(out / name), *extra]
        if args.serial:
            command += ["--serial", args.serial]
        print(f"suite: {name}: expecting {expected} /{pattern}/", flush=True)
        subprocess.run(command)
        try:
            summary = json.loads((out / name / "summary.json").read_text())
        except (OSError, ValueError):
            summary = {"result": "ERROR", "reason": "the driver wrote no summary.json (see its output above)"}
        ok = summary.get("result") == expected and re.search(pattern, summary.get("reason", "")) is not None
        results.append({"case": name, "ok": ok, "expected": expected, "pattern": pattern,
                        "result": summary.get("result"), "reason": summary.get("reason"), "taps": summary.get("taps")})
        print(f"suite: {name}: {'ok' if ok else 'WRONG'}: {summary.get('result')}: {summary.get('reason')}", flush=True)

    (out / "suite.json").write_text(json.dumps(results, indent=1))
    passed = all(r["ok"] for r in results)
    print(f"suite: {sum(r['ok'] for r in results)}/{len(results)} cases behaved; artifacts in {out}")
    print("PASS" if passed else "FAIL")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
