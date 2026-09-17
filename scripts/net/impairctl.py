#!/usr/bin/env python3
"""Control the netem guest from CT950 over SSH."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys


DEFAULT_ENV = Path("~/.config/pleikkari/impair.env").expanduser()
PROFILES = ("clean", "5g", "4g", "wifi-slow", "blip-200ms", "loss-2", "custom")
TTL_RE = re.compile(r"^[1-9][0-9]*[smhd]$")


class ConfigError(ValueError):
    pass


def read_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        if "=" not in line:
            raise ConfigError(f"{path}:{number}: expected KEY=VALUE")
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if value[:1] in ("'", '"'):
            if len(value) < 2 or value[-1] != value[0]:
                raise ConfigError(f"{path}:{number}: unmatched quote")
            value = value[1:-1]
        if key == "IMPAIR_HOST":
            values[key] = value
    return values


def ttl(value: str) -> str:
    if not TTL_RE.fullmatch(value):
        raise argparse.ArgumentTypeError("use a positive integer plus s, m, h, or d (for example 30m)")
    return value


def safe_host(value: str) -> str:
    if not value or value.startswith("-") or any(char.isspace() for char in value):
        raise ConfigError("IMPAIR_HOST must be a non-empty SSH host without whitespace or leading '-'")
    return value


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--env-file", type=Path, default=DEFAULT_ENV)
    subparsers = result.add_subparsers(dest="command", required=True)

    apply_parser = subparsers.add_parser("apply", help="apply a profile")
    apply_parser.add_argument("profile", choices=PROFILES)
    apply_parser.add_argument("--ttl", type=ttl)
    apply_parser.add_argument("--delay")
    apply_parser.add_argument("--jitter")
    apply_parser.add_argument("--loss")
    apply_parser.add_argument("--reorder")
    apply_parser.add_argument("--rate")
    subparsers.add_parser("status", help="show the active profile and tc tree")
    subparsers.add_parser("clear", help="clear all impairment")
    return result


def remote_arguments(args: argparse.Namespace) -> list[str]:
    if args.command != "apply":
        return [args.command]
    if args.profile == "clean":
        return ["clean"]
    if args.ttl is None:
        raise ConfigError("--ttl is required for apply unless the profile is clean")
    command = ["profile", args.profile, "--ttl", args.ttl]
    for option in ("delay", "jitter", "loss", "reorder", "rate"):
        value = getattr(args, option)
        if value is not None:
            command.extend((f"--{option}", value))
    return command


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        file_values = read_env_file(args.env_file.expanduser())
        host = safe_host(os.environ.get("IMPAIR_HOST", file_values.get("IMPAIR_HOST", "")))
        remote = remote_arguments(args)
    except (ConfigError, argparse.ArgumentTypeError, OSError) as exc:
        print(f"impairctl.py: {exc}", file=sys.stderr)
        return 2

    command = [
        "ssh",
        "-o",
        "BatchMode=yes",
        "-o",
        "ConnectTimeout=10",
        host,
        shlex.join(["impair.sh", *remote]),
    ]
    try:
        return subprocess.run(command, check=False).returncode
    except FileNotFoundError:
        print("impairctl.py: ssh was not found", file=sys.stderr)
        return 127


if __name__ == "__main__":
    raise SystemExit(main())
