#!/usr/bin/env python3
"""Rootless unit tests for the network impairment tools."""

from __future__ import annotations

import contextlib
import importlib.util
import io
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import types
import unittest
from unittest import mock


HERE = Path(__file__).resolve().parent
IMPAIR = HERE / "impair.sh"
IMPAIRCTL = HERE / "impairctl.py"
LAN_IFACE = "eth-lan"
PS5_IFACE = "eth-ps5"

PROFILE_NETEM = {
    "5g": ["delay", "10ms", "2.5ms", "distribution", "normal", "loss", "0.3%"],
    "4g": ["delay", "22.5ms", "7.5ms", "distribution", "normal", "loss", "1%", "reorder", "0.5%"],
    "wifi-slow": [
        "delay", "5ms", "12.5ms", "25%", "distribution", "normal", "loss", "2%",
        "25%", "reorder", "2%", "25%",
    ],
    "blip-200ms": ["delay", "0ms"],
    "loss-2": ["loss", "2%"],
}
PROFILE_RATE = {"4g": "30mbit", "wifi-slow": "20mbit"}
SWITCHING_PROFILES = ("5g", "4g", "wifi-slow", "loss-2")


def load_impairctl() -> types.ModuleType:
    spec = importlib.util.spec_from_file_location("impairctl", IMPAIRCTL)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run_impair(state_dir: str, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(IMPAIR), *args],
        text=True,
        capture_output=True,
        check=False,
        env={
            "PATH": "/usr/sbin:/usr/bin:/sbin:/bin",
            "IMPAIR_DRY_RUN": "1",
            "IMPAIR_STATE_DIR": state_dir,
        },
    )


def tc_commands(result: subprocess.CompletedProcess[str]) -> list[list[str]]:
    return [
        shlex.split(line[2:])
        for line in result.stdout.splitlines()
        if line.startswith("+ tc ")
    ]


def parse_netem(command: list[str]) -> dict[str, str] | None:
    """Parse a `tc qdisc replace ... netem <opts>` command into an option dict.

    Deliberately parses the *command we would issue*, not string-matches it,
    so a test comparing two parsed dicts is insensitive to option ordering
    within a single flag's value and catches a leftover field a display-only
    string comparison could miss.
    """
    if "netem" not in command:
        return None
    opts = command[command.index("netem") + 1 :]
    fields: dict[str, str] = {}
    i = 0
    keys = {"delay", "loss", "reorder", "rate", "distribution"}
    while i < len(opts):
        key = opts[i]
        if key in keys:
            values = []
            i += 1
            while i < len(opts) and opts[i] not in keys:
                values.append(opts[i])
                i += 1
            fields[key] = " ".join(values)
        else:
            i += 1
    return fields


def seed_state(state_dir: str, profile: str, rate: str | None, netem: list[str]) -> None:
    """Write the state file impair.sh's own non-dry-run apply would leave.

    Dry-run mode never calls write_state (it returns before touching state),
    so a chained-switch test has to seed the file by hand -- this mirrors
    exactly the format `write_state()` produces.
    """
    lines = [
        f"profile={profile}",
        "mode=transparent-bridge",
        f"lan_iface={LAN_IFACE}",
        f"ps5_iface={PS5_IFACE}",
        "ttl=5m",
    ]
    if rate:
        lines.append(f"rate={rate}")
    lines.append(f"netem={' '.join(netem)}")
    Path(state_dir, "active").write_text("\n".join(lines) + "\n", encoding="utf-8")


class ImpairCommandTests(unittest.TestCase):
    maxDiff = None

    def dry_run(self, profile: str, *extra: str, ttl: str = "5m") -> subprocess.CompletedProcess[str]:
        if profile == "clean":
            args = ["clean", "--lan-iface", LAN_IFACE, "--ps5-iface", PS5_IFACE]
        else:
            args = ["profile", profile, "--lan-iface", LAN_IFACE, "--ps5-iface", PS5_IFACE, "--ttl", ttl]
        args.extend(extra)
        with tempfile.TemporaryDirectory() as state_dir:
            return run_impair(state_dir, *args)

    def expected(self, netem: list[str] | None, rate: str | None = None) -> list[list[str]]:
        commands = [
            ["tc", "qdisc", "del", "dev", LAN_IFACE, "root"],
            ["tc", "qdisc", "del", "dev", PS5_IFACE, "root"],
        ]
        if netem is None:
            return commands
        rate_args = ["rate", rate] if rate else []
        return commands + [
            ["tc", "qdisc", "replace", "dev", LAN_IFACE, "root", "handle", "10:", "netem", *netem, *rate_args],
            ["tc", "qdisc", "replace", "dev", PS5_IFACE, "root", "handle", "20:", "netem", *netem, *rate_args],
        ]

    def assert_profile(self, profile: str, netem: list[str] | None, *extra: str, rate: str | None = None) -> None:
        result = self.dry_run(profile, *extra)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(tc_commands(result), self.expected(netem, rate))

    def test_clean_commands(self) -> None:
        self.assert_profile("clean", None)

    def test_5g_commands(self) -> None:
        self.assert_profile("5g", PROFILE_NETEM["5g"])

    def test_4g_commands(self) -> None:
        self.assert_profile("4g", PROFILE_NETEM["4g"], rate=PROFILE_RATE["4g"])

    def test_wifi_slow_commands(self) -> None:
        self.assert_profile("wifi-slow", PROFILE_NETEM["wifi-slow"], rate=PROFILE_RATE["wifi-slow"])

    def test_blip_commands(self) -> None:
        self.assert_profile("blip-200ms", PROFILE_NETEM["blip-200ms"])

    def test_loss_2_commands(self) -> None:
        self.assert_profile("loss-2", PROFILE_NETEM["loss-2"])

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

    def test_invalid_custom_value_fails_before_commands(self) -> None:
        result = self.dry_run("custom", "--loss", "101%")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(tc_commands(result), [])

    def test_apply_alias_matches_profile(self) -> None:
        with tempfile.TemporaryDirectory() as state_dir:
            profile_result = run_impair(
                state_dir, "profile", "loss-2", "--ttl", "5m", "--lan-iface", LAN_IFACE, "--ps5-iface", PS5_IFACE
            )
            apply_result = run_impair(
                state_dir, "apply", "loss-2", "--ttl", "5m", "--lan-iface", LAN_IFACE, "--ps5-iface", PS5_IFACE
            )
        self.assertEqual(tc_commands(profile_result), tc_commands(apply_result))

    # --- PLE-363: a profile switch must fully replace the previous profile's
    # parameters, not merge with them. `tc qdisc replace` on an existing netem
    # handle keeps any field the new invocation omits (rate, reorder, ...), so
    # `install_profile()` must never run against a qdisc a previous profile
    # left standing -- every switch deletes both root qdiscs first.

    def test_switch_deletes_old_qdiscs_before_installing(self) -> None:
        with tempfile.TemporaryDirectory() as state_dir:
            seed_state(state_dir, "wifi-slow", PROFILE_RATE["wifi-slow"], PROFILE_NETEM["wifi-slow"])
            result = run_impair(
                state_dir, "profile", "blip-200ms", "--ttl", "5m",
                "--lan-iface", LAN_IFACE, "--ps5-iface", PS5_IFACE,
            )
        self.assertEqual(result.returncode, 0, result.stderr)
        commands = tc_commands(result)
        self.assertEqual(commands, self.expected(PROFILE_NETEM["blip-200ms"]))
        # In particular: no leftover `rate` from wifi-slow anywhere in the output.
        self.assertTrue(all("rate" not in command for command in commands))

    def test_every_ordered_pair_matches_entry_from_clean(self) -> None:
        profiles = ("clean",) + SWITCHING_PROFILES
        for previous in profiles:
            for target in profiles:
                if previous == target:
                    continue
                with self.subTest(previous=previous, target=target):
                    with tempfile.TemporaryDirectory() as state_dir:
                        if previous != "clean":
                            seed_state(
                                state_dir, previous, PROFILE_RATE.get(previous), PROFILE_NETEM[previous]
                            )
                        if target == "clean":
                            args = ["clean", "--lan-iface", LAN_IFACE, "--ps5-iface", PS5_IFACE]
                        else:
                            args = ["profile", target, "--lan-iface", LAN_IFACE, "--ps5-iface", PS5_IFACE, "--ttl", "5m"]
                        switched = run_impair(state_dir, *args)
                    self.assertEqual(switched.returncode, 0, switched.stderr)

                    fresh = self.dry_run(target)
                    self.assertEqual(fresh.returncode, 0, fresh.stderr)

                    switched_replaces = [c for c in tc_commands(switched) if "replace" in c]
                    fresh_replaces = [c for c in tc_commands(fresh) if "replace" in c]
                    switched_netem = [parse_netem(c) for c in switched_replaces]
                    fresh_netem = [parse_netem(c) for c in fresh_replaces]
                    self.assertEqual(switched_netem, fresh_netem)

    def test_regression_returns_if_switch_stops_clearing_first(self) -> None:
        """Reproduces PLE-363 by skipping the pre-install delete, proving the
        test above actually catches the merge bug rather than passing vacuously."""
        source = IMPAIR.read_text(encoding="utf-8")
        pattern = re.compile(
            r'\tif \(\(DRY_RUN\)\); then\n'
            r'\t\ttry_run tc qdisc del dev "\$lan_iface" root\n'
            r'\t\ttry_run tc qdisc del dev "\$ps5_iface" root\n'
            r'\t\tinstall_profile'
        )
        self.assertRegex(source, pattern, "expected the pre-install teardown in profile_impl's dry-run branch to be intact")
        broken = pattern.sub('\tif ((DRY_RUN)); then\n\t\tinstall_profile', source, count=1)
        self.assertNotEqual(broken, source)

        with tempfile.TemporaryDirectory() as scratch:
            broken_impair = Path(scratch, "impair.sh")
            broken_impair.write_text(broken, encoding="utf-8")
            broken_impair.chmod(0o755)
            with tempfile.TemporaryDirectory() as state_dir:
                seed_state(state_dir, "wifi-slow", PROFILE_RATE["wifi-slow"], PROFILE_NETEM["wifi-slow"])
                result = subprocess.run(
                    [str(broken_impair), "profile", "blip-200ms", "--ttl", "5m",
                     "--lan-iface", LAN_IFACE, "--ps5-iface", PS5_IFACE],
                    text=True,
                    capture_output=True,
                    check=False,
                    env={
                        "PATH": "/usr/sbin:/usr/bin:/sbin:/bin",
                        "IMPAIR_DRY_RUN": "1",
                        "IMPAIR_STATE_DIR": state_dir,
                    },
                )
        self.assertEqual(result.returncode, 0, result.stderr)
        broken_commands = tc_commands(result)
        # The regressed script has no `tc qdisc del ... root` at all before the
        # replace, so this is exactly the additive bug the fixed script avoids.
        self.assertFalse(any(c[:4] == ["tc", "qdisc", "del", "dev"] for c in broken_commands))


class ImpairctlTests(unittest.TestCase):
    def setUp(self) -> None:
        self.module = load_impairctl()

    @staticmethod
    def env_file(directory: str) -> Path:
        path = Path(directory) / "impair.env"
        path.write_text("IMPAIR_HOST=netem.example\n", encoding="utf-8")
        return path

    def run_apply(self, *args: str) -> tuple[int, list[str]]:
        with tempfile.TemporaryDirectory() as directory:
            env_file = self.env_file(directory)
            completed = subprocess.CompletedProcess([], 0)
            with mock.patch.dict(self.module.os.environ, {}, clear=True), mock.patch.object(
                self.module.subprocess, "run", return_value=completed
            ) as run:
                result = self.module.main(["--env-file", str(env_file), "apply", *args])
        self.assertEqual(result, 0)
        ((command,), kwargs) = run.call_args
        self.assertEqual(kwargs, {"check": False})
        self.assertEqual(command[:5], ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10"])
        self.assertEqual(command[5], "netem.example")
        return result, shlex.split(command[6])

    def test_apply_builds_profile_command(self) -> None:
        _, remote = self.run_apply("custom", "--ttl", "30m", "--delay", "20ms")
        self.assertEqual(remote, ["impair.sh", "profile", "custom", "--ttl", "30m", "--delay", "20ms"])

    def test_apply_clean_ignores_ttl_and_sends_bare_clean(self) -> None:
        _, remote = self.run_apply("clean")
        self.assertEqual(remote, ["impair.sh", "clean"])

    def test_apply_requires_ttl_unless_clean(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()) as captured:
            with tempfile.TemporaryDirectory() as directory:
                env_file = self.env_file(directory)
                result = self.module.main(["--env-file", str(env_file), "apply", "4g"])
        self.assertEqual(result, 2)
        self.assertIn("--ttl is required", captured.getvalue())

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
