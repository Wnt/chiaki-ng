#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
"""Drive first-run onboarding on the emulator against the PSN mock (PLE-284).

    android/psn-mock/onboarding_test.py --build                  # verified app link, happy path
    android/psn-mock/onboarding_test.py --link nolink            # what production is: unverifiable link
    android/psn-mock/onboarding_test.py --link nolink --select-domain   # user enabled the link
    android/psn-mock/onboarding_test.py --scenario expired-code  # any scenario from psn_mock.SCENARIOS

It starts from a first-run state of com.metallic.chiaki.psnmock (a separate app from the
real one), taps Sign in, fills the mock's password form in the browser and waits. It FAILS if:
  * anything other than the app or the browser comes to the front (Android Settings above all),
  * the app shows an instruction paragraph,
  * the screen stops changing without reaching an end state (a dead end),
  * the app crashes,
and for the `ok` scenario, if the console list never shows the mock's PS5.
A failure scenario passes when the app ends on one of its own screens the user can act on.
Artifacts (a screenshot and UI dump per distinct screen, the mock's events, summary.json)
go to build/psn-mock/onboarding-<time>-<link>-<scenario>/ in the workspace.

Needs: the mock up (android/psn-mock/serve.sh start) and the emulator booted (emu.sh start).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
ANDROID = HERE.parent
WORKSPACE = Path(os.environ.get("PLEIKKARI_WORKSPACE_ROOT", "/home/wnt/gta6"))
PKG = "com.metallic.chiaki.psnmock"
HOSTS = {"verified": "pleikkari-psn.lab.madekivi.fi", "nolink": "pleikkari-psn-nolink.lab.madekivi.fi"}
BROWSERS = {"com.android.chrome", "com.chrome.beta", "com.chrome.dev", "org.mozilla.firefox", "com.sec.android.app.sbrowser"}
INSTRUCTION_WORDS = 14  # a TextView of the app with this many words is an instruction paragraph


def log(message: str) -> None:
    print(f"onboarding: {message}", flush=True)


@dataclass
class Node:
    package: str
    cls: str
    text: str
    desc: str
    rid: str
    clickable: bool
    bounds: tuple[int, int, int, int]

    @property
    def center(self) -> tuple[int, int]:
        left, top, right, bottom = self.bounds
        return (left + right) // 2, (top + bottom) // 2


def parse_nodes(xml: str) -> list[Node]:
    nodes = []
    for match in re.finditer(r"<node [^>]*>", xml):
        raw = match.group(0)
        get = lambda key: (re.search(key + r'="([^"]*)"', raw) or [None, ""])[1]
        box = [int(v) for v in re.findall(r"\d+", get("bounds"))] or [0, 0, 0, 0]
        text = get("text").replace("&amp;", "&").replace("&quot;", '"').replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        nodes.append(Node(get("package"), get("class"), text, get("content-desc"), get("resource-id"), get("clickable") == "true", tuple(box)))
    return nodes


class Device:
    def __init__(self, serial: str):
        self.serial = serial
        self.env = dict(os.environ)
        if serial.startswith("emulator-"):
            # As emu.sh does (it sources the SDK env.sh): the emulator is not the Samsung, so its adb
            # commands must not wait in the phone's FIFO. A phone serial keeps the queued wrapper.
            sdk = os.environ.get("ANDROID_HOME", "/home/wnt/android-sdk")
            self.env["PATH"] = f"{sdk}/platform-tools:{self.env.get('PATH', '')}"

    def adb(self, *args: str, check: bool = True, timeout: float = 60) -> str:
        result = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout, env=self.env)
        if check and result.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.strip()}")
        return result.stdout

    def shell(self, command: str, check: bool = True) -> str:
        return self.adb("shell", command, check=check)

    def dump(self) -> tuple[str, list[Node]] | None:
        out = self.shell("uiautomator dump /sdcard/pleikkari-onboarding.xml >/dev/null 2>&1 && cat /sdcard/pleikkari-onboarding.xml", check=False)
        return (out, parse_nodes(out)) if "<hierarchy" in out else None

    def tap(self, node: Node) -> None:
        x, y = node.center
        self.shell(f"input tap {x} {y}")

    def type_into(self, node: Node, text: str) -> None:
        self.tap(node)
        time.sleep(0.5)
        self.shell("input keyevent KEYCODE_MOVE_END")
        self.shell("input keyevent " + " ".join(["KEYCODE_DEL"] * 40))
        self.shell(f"input text '{text}'")

    def screenshot(self, path: Path) -> None:
        path.write_bytes(subprocess.run(["adb", "-s", self.serial, "exec-out", "screencap", "-p"], capture_output=True, timeout=60, env=self.env).stdout)


def http_json(url: str, timeout: float = 10):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return json.loads(response.read())


def build_apk(link: str) -> Path:
    env = dict(os.environ, PLEIKKARI_ABIS="arm64-v8a,x86_64",
               PLEIKKARI_OPENSSL_PREBUILT_INCLUDE=str(WORKSPACE / "scripts/dev/openssl-prebuilt.cmake"))
    init = WORKSPACE / "scripts/dev/openssl-prebuilt.gradle"
    command = f". /home/wnt/android-sdk/env.sh && ./gradlew -q --max-workers={env.get('GATE_JOBS', '4')} -I {init} -PchiakiPsnMock={link} assembleDebug"
    log(f"building the {link} mock APK")
    subprocess.run(["bash", "-c", command], cwd=ANDROID, env=env, check=True)
    target = WORKSPACE / "build/psn-mock" / f"psnmock-{link}.apk"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes((ANDROID / "app/build/outputs/apk/debug/app-debug.apk").read_bytes())
    return target


def reset_app(device: Device) -> None:
    """First-run state without uninstalling: the adb wrapper refuses any uninstall. Only the mock package."""
    assert PKG.endswith(".psnmock")
    device.shell(f"am force-stop {PKG}")
    device.shell(f"run-as {PKG} sh -c 'rm -rf shared_prefs databases files no_backup cache code_cache app_webview'", check=False)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--link", choices=sorted(HOSTS), default="verified")
    parser.add_argument("--scenario", default="ok")
    parser.add_argument("--select-domain", action="store_true", help="enable the link host for the app, as a user would in Settings")
    parser.add_argument("--apk", type=Path, help="default: build/psn-mock/psnmock-<link>.apk in the workspace")
    parser.add_argument("--build", action="store_true", help="build the mock APK first")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    parser.add_argument("--timeout", type=float, default=150)
    parser.add_argument("--stall", type=float, default=40, help="seconds without a screen change that count as a dead end")
    args = parser.parse_args(argv)

    host = HOSTS[args.link]
    run_id = time.strftime("%Y%m%dT%H%M%S")
    out = WORKSPACE / "build/psn-mock" / f"onboarding-{run_id}-{args.link}-{args.scenario}"
    out.mkdir(parents=True, exist_ok=True)
    account = f"{args.scenario}+{run_id.lower()}@mock"
    summary: dict = {"link": args.link, "scenario": args.scenario, "select_domain": args.select_domain, "account": account, "taps": 0, "screens": []}

    def finish(result: str, reason: str) -> int:
        summary.update(result=result, reason=reason)
        try:
            events = [e for e in http_json(f"https://{host}/__mock/events?since={since}")["events"] if e.get("account") in (account, None)]
        except Exception as exc:  # the verdict does not depend on it
            events = [{"error": str(exc)}]
        summary["mock_events"] = events
        (out / "summary.json").write_text(json.dumps(summary, indent=1))
        log(f"{result}: {reason} ({summary['taps']} taps); artifacts in {out}")
        print(result)
        return 0 if result == "PASS" else 1

    # The emulator is shared: hold its reservation (emu.sh keeps it until released).
    subprocess.run([str(WORKSPACE / "scripts/dev/emu.sh"), "shell", "true"], check=True, capture_output=True)
    try:
        if http_json(f"https://{host}/__mock/events?since=999999999", timeout=10) is None:
            raise RuntimeError
    except Exception:
        log(f"the mock is not reachable at https://{host}; run android/psn-mock/serve.sh start")
        return 2
    since = max([e["seq"] for e in http_json(f"https://{host}/__mock/events")["events"]] or [0])

    apk = build_apk(args.link) if args.build else (args.apk or WORKSPACE / "build/psn-mock" / f"psnmock-{args.link}.apk")
    if not apk.exists():
        log(f"no APK at {apk}; pass --build")
        return 2
    device = Device(args.serial)
    reset_app(device)
    log(f"installing {apk.name} on {args.serial}")
    device.adb("install", "-r", "-t", str(apk), timeout=300)
    reset_app(device)
    if args.select_domain:
        device.shell(f"pm set-app-links-user-selection --user 0 --package {PKG} true {host}")
    else:
        device.shell(f"pm set-app-links-user-selection --user 0 --package {PKG} false all", check=False)
    links = device.shell(f"pm get-app-links --user 0 {PKG}", check=False)
    summary["app_links"] = links
    if args.link == "verified":
        for _ in range(30):
            if f"{host}: verified" in links:
                break
            time.sleep(2)
            links = device.shell(f"pm get-app-links {PKG}", check=False)
        else:
            return finish("FAIL", f"{host} never verified on the device; is assetlinks.json served?")

    device.shell("logcat -c", check=False)
    device.shell(f"am start -W -n {PKG}/com.metallic.chiaki.main.MainActivity")
    started = last_change = time.monotonic()
    last_signature = ""
    signed_in_tapped = submitted = False
    seen_signatures: set[str] = set()

    while True:
        now = time.monotonic()
        if now - started > args.timeout:
            return finish("FAIL", f"no end state after {args.timeout:.0f} s")
        crash = device.shell("logcat -d -b crash", check=False)
        if PKG in crash:
            (out / "crash.txt").write_text(crash)
            return finish("FAIL", "the app crashed")
        snapshot = device.dump()
        if snapshot is None:  # uiautomator cannot dump while animating; try again
            time.sleep(1)
            continue
        xml, nodes = snapshot
        foreground = next((n.package for n in nodes if n.package), "")
        mine = [n for n in nodes if n.package == PKG]
        signature = hashlib.sha1("|".join(f"{n.package}{n.rid}{n.text}{n.bounds}" for n in nodes if n.text or n.rid or n.clickable).encode()).hexdigest()[:10]
        if signature != last_signature:
            last_signature, last_change = signature, now
            if signature not in seen_signatures:
                seen_signatures.add(signature)
                index = len(summary["screens"])
                (out / f"{index:02d}-{signature}.xml").write_text(xml)
                device.screenshot(out / f"{index:02d}-{signature}.png")
                summary["screens"].append({"at_s": round(now - started, 1), "package": foreground, "texts": [n.text for n in nodes if n.text][:12]})
                log(f"screen {index}: {foreground}: {[n.text for n in nodes if n.text][:6]}")

        if foreground and foreground not in (PKG, *BROWSERS):
            return finish("FAIL", f"left the app for {foreground}" + (" (Android Settings)" if "settings" in foreground else ""))
        paragraph = next((n.text for n in mine if len(n.text.split()) >= INSTRUCTION_WORDS), None)
        if paragraph:
            return finish("FAIL", f"instruction paragraph: {paragraph!r}")

        by_id = {n.rid.rsplit("/", 1)[-1]: n for n in nodes if n.rid}
        names = [n.text for n in mine if n.rid.endswith("nameTextView")]
        if foreground == PKG and "PS5 mock" in names:
            summary["seconds"] = round(now - started, 1)
            return finish("PASS", f"signed in; console list shows {names}")

        if foreground == PKG and "onboardingSignInButton" in by_id and not signed_in_tapped:
            device.tap(by_id["onboardingSignInButton"])
            summary["taps"] += 1
            signed_in_tapped = True
            continue
        if foreground in BROWSERS and {"account", "password", "sign-in"} <= by_id.keys() and not submitted:
            device.type_into(by_id["account"], account)
            device.type_into(by_id["password"], "mockpass")
            device.shell("input keyevent KEYCODE_BACK")  # close the keyboard so the button is on screen
            time.sleep(1)
            snapshot = device.dump()
            button = next((n for n in (snapshot[1] if snapshot else nodes) if n.rid.endswith("sign-in")), by_id["sign-in"])
            device.tap(button)
            summary["taps"] += 3
            submitted = True
            continue

        stalled = now - last_change
        if stalled > args.stall:
            if args.scenario != "ok" and foreground == PKG and any(n.clickable for n in mine):
                summary["seconds"] = round(now - started, 1)
                return finish("PASS", f"{args.scenario}: the app settled on its own actionable screen: {[n.text for n in mine if n.text][:6]}")
            where = "the browser" if foreground in BROWSERS else foreground or "an unknown window"
            return finish("FAIL", f"dead end: nothing changed for {stalled:.0f} s in {where}")
        time.sleep(1.5)


if __name__ == "__main__":
    sys.exit(main())
