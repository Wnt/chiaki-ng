#!/usr/bin/env python3
"""Rootless unit tests for the network impairment tools."""

from __future__ import annotations

import contextlib
import importlib.util
import io
from pathlib import Path
import shlex
import subprocess
import tempfile
import types
import unittest
from unittest import mock


HERE = Path(__file__).resolve().parent
IMPAIR = HERE / "impair.sh"
IMPAIRCTL = HERE / "impairctl.py"
PEER = "192.168.1.164"
PHONE = "192.168.40.100"
IFACE = "eth-lan"
PHONE_IFACE = "eth-impair"
IFB_IMPAIR = "ifb-impair-rx"
IFB_LAN = "ifb-lan-rx"


def load_impairctl() -> types.ModuleType:
    spec = importlib.util.spec_from_file_location("impairctl", IMPAIRCTL)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ImpairCommandTests(unittest.TestCase):
    maxDiff = None

    def dry_run(self, profile: str, *extra: str, phone: bool = True) -> subprocess.CompletedProcess[str]:
        arguments = [str(IMPAIR), "apply", profile, "--peer", PEER, "--iface", IFACE]
        if phone:
            arguments.extend(("--phone", PHONE, "--phone-iface", PHONE_IFACE))
        arguments.extend(extra)
        with tempfile.TemporaryDirectory() as state_dir:
            return subprocess.run(
                arguments,
                text=True,
                capture_output=True,
                check=False,
                env={
                    "PATH": "/usr/sbin:/usr/bin:/sbin:/bin",
                    "IMPAIR_DRY_RUN": "1",
                    "IMPAIR_STATE_DIR": state_dir,
                },
            )

    @staticmethod
    def tc_commands(result: subprocess.CompletedProcess[str]) -> list[list[str]]:
        return [
            shlex.split(line[2:])
            for line in result.stdout.splitlines()
            if line.startswith("+ tc ")
        ]

    @staticmethod
    def expected(netem: list[str] | None, rate: str | None = None) -> list[list[str]]:
        commands = [
            ["tc", "qdisc", "del", "dev", IFACE, "root"],
            ["tc", "qdisc", "del", "dev", IFACE, "clsact"],
            ["tc", "qdisc", "del", "dev", PHONE_IFACE, "root"],
            ["tc", "qdisc", "del", "dev", PHONE_IFACE, "clsact"],
            ["tc", "qdisc", "del", "dev", IFB_IMPAIR, "root"],
            ["tc", "qdisc", "del", "dev", IFB_LAN, "root"],
        ]
        if netem is None:
            return commands

        priomap = ["priomap", *("0" for _ in range(16))]
        first_parent = "1:2"
        second_parent = "2:2"
        first_rate: list[list[str]] = []
        second_rate: list[list[str]] = []
        if rate is not None:
            first_rate = [[
                "tc", "qdisc", "replace", "dev", IFB_IMPAIR, "parent", "1:2",
                "handle", "20:", "tbf", "rate", rate, "burst", "64kbit", "latency", "400ms",
            ]]
            second_rate = [[
                "tc", "qdisc", "replace", "dev", IFB_LAN, "parent", "2:2",
                "handle", "21:", "tbf", "rate", rate, "burst", "64kbit", "latency", "400ms",
            ]]
            first_parent = "20:1"
            second_parent = "21:1"

        return commands + [
            ["tc", "qdisc", "replace", "dev", PHONE_IFACE, "clsact"],
            [
                "tc", "filter", "replace", "dev", PHONE_IFACE, "ingress", "protocol", "ip",
                "prio", "10", "flower", "action", "mirred", "egress", "redirect", "dev", IFB_IMPAIR,
            ],
            [
                "tc", "qdisc", "replace", "dev", IFB_IMPAIR, "root", "handle", "1:",
                "prio", "bands", "2", *priomap,
            ],
            *first_rate,
            [
                "tc", "qdisc", "replace", "dev", IFB_IMPAIR, "parent", first_parent,
                "handle", "30:", "netem", *netem,
            ],
            [
                "tc", "filter", "replace", "dev", IFB_IMPAIR, "protocol", "ip", "parent", "1:",
                "prio", "10", "flower", "src_ip", PHONE, "dst_ip", PEER, "classid", "1:2",
            ],
            ["tc", "qdisc", "replace", "dev", IFACE, "clsact"],
            [
                "tc", "filter", "replace", "dev", IFACE, "ingress", "protocol", "ip", "prio", "10",
                "flower", "action", "mirred", "egress", "redirect", "dev", IFB_LAN,
            ],
            [
                "tc", "qdisc", "replace", "dev", IFB_LAN, "root", "handle", "2:",
                "prio", "bands", "2", *priomap,
            ],
            *second_rate,
            [
                "tc", "qdisc", "replace", "dev", IFB_LAN, "parent", second_parent,
                "handle", "40:", "netem", *netem,
            ],
            [
                "tc", "filter", "replace", "dev", IFB_LAN, "protocol", "ip", "parent", "2:",
                "prio", "10", "flower", "src_ip", PEER, "dst_ip", PHONE, "classid", "2:2",
            ],
        ]

    def assert_profile(
        self,
        profile: str,
        netem: list[str] | None,
        *extra: str,
        rate: str | None = None,
    ) -> None:
        result = self.dry_run(profile, *extra)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.tc_commands(result), self.expected(netem, rate))

    def test_clean_commands(self) -> None:
        self.assert_profile("clean", None)

    def test_5g_commands(self) -> None:
        self.assert_profile(
            "5g",
            ["delay", "10ms", "2.5ms", "distribution", "normal", "loss", "0.3%"],
        )

    def test_4g_commands(self) -> None:
        self.assert_profile(
            "4g",
            ["delay", "22.5ms", "7.5ms", "distribution", "normal", "loss", "1%", "reorder", "0.5%"],
            rate="30mbit",
        )

    def test_wifi_slow_commands(self) -> None:
        self.assert_profile(
            "wifi-slow",
            [
                "delay", "5ms", "12.5ms", "25%", "distribution", "normal", "loss", "2%",
                "25%", "reorder", "2%", "25%",
            ],
            rate="20mbit",
        )

    def test_blip_commands(self) -> None:
        self.assert_profile("blip-200ms", ["delay", "0ms"])

    def test_loss_2_commands(self) -> None:
        self.assert_profile("loss-2", ["loss", "2%"])

    def test_custom_commands(self) -> None:
        self.assert_profile(
            "custom",
            ["delay", "31ms", "7ms", "loss", "1.5%", "reorder", "0.4%"],
            "--delay", "31ms", "--jitter", "7ms", "--loss", "1.5%",
            "--reorder", "0.4%", "--rate", "12mbit",
            rate="12mbit",
        )

    def test_custom_reorder_adds_required_delay(self) -> None:
        self.assert_profile("custom", ["delay", "0ms", "reorder", "3%"], "--reorder", "3%")

    def test_optional_phone_uses_peer_only_filters(self) -> None:
        result = self.dry_run("loss-2", phone=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        commands = self.tc_commands(result)
        filters = [command for command in commands if command[1] == "filter"]
        self.assertEqual(len(filters), 3)
        self.assertEqual(sum(PEER in command for command in filters), 2)
        self.assertTrue(all(PHONE not in command for command in commands))

    def test_invalid_custom_value_fails_before_commands(self) -> None:
        result = self.dry_run("custom", "--loss", "101%")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(self.tc_commands(result), [])


class ImpairctlTests(unittest.TestCase):
    def setUp(self) -> None:
        self.module = load_impairctl()

    @staticmethod
    def env_file(directory: str) -> Path:
        path = Path(directory) / "impair.env"
        path.write_text("IMPAIR_HOST=netem.example\nIMPAIR_PEER=192.168.1.164\n", encoding="utf-8")
        return path

    def test_apply_builds_quoted_ssh_command(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_file = self.env_file(directory)
            completed = subprocess.CompletedProcess([], 0)
            with mock.patch.dict(self.module.os.environ, {}, clear=True), mock.patch.object(
                self.module.subprocess, "run", return_value=completed
            ) as run:
                result = self.module.main(
                    ["--env-file", str(env_file), "apply", "custom", "--ttl", "30m", "--delay", "20ms"]
                )
        self.assertEqual(result, 0)
        run.assert_called_once_with(
            [
                "ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "netem.example",
                "impair.sh apply custom --peer 192.168.1.164 --ttl 30m --delay 20ms",
            ],
            check=False,
        )

    def test_apply_requires_ttl(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            self.module.main(["apply", "4g"])
        self.assertEqual(raised.exception.code, 2)

    def test_ssh_failure_is_propagated(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_file = self.env_file(directory)
            completed = subprocess.CompletedProcess([], 255)
            with mock.patch.dict(self.module.os.environ, {}, clear=True), mock.patch.object(
                self.module.subprocess, "run", return_value=completed
            ):
                result = self.module.main(["--env-file", str(env_file), "status"])
        self.assertEqual(result, 255)


if __name__ == "__main__":
    unittest.main()
