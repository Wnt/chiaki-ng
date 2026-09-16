#!/usr/bin/env python3
"""Move the configured test phone between UniFi networks, with rollback."""

from __future__ import annotations

import argparse
import json
import os
import shlex
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Mapping, Protocol, Sequence


DEFAULT_UNIFI_CONFIG = Path("~/.config/pleikkari/unifi.env").expanduser()
FALLBACK_UNIFI_CONFIG = Path("~/.config/unifi/config.env").expanduser()
DEFAULT_IMPAIR_CONFIG = Path("~/.config/pleikkari/impair.env").expanduser()
DEFAULT_STATE_DIR = Path("~/.local/state/pleikkari/unifi-vlan").expanduser()


class VlanError(RuntimeError):
    """An expected, user-facing VLAN tool failure."""


class Controller(Protocol):
    def client(self, mac: str) -> dict[str, Any]: ...

    def network(self, value: str) -> dict[str, Any]: ...

    def set_network_override(self, user_id: str, network_id: str | None) -> None: ...


def normalize_mac(value: str) -> str:
    text = value.strip().lower()
    if any(char not in "0123456789abcdef:-" for char in text):
        raise VlanError(f"invalid MAC address: {value!r}")
    compact = text.replace(":", "").replace("-", "")
    if len(compact) != 12 or any(char not in "0123456789abcdef" for char in compact):
        raise VlanError(f"invalid MAC address: {value!r}")
    return ":".join(compact[index : index + 2] for index in range(0, 12, 2))


def parse_duration(value: str) -> float:
    units = {"s": 1, "m": 60, "h": 3600}
    text = value.strip().lower()
    try:
        multiplier = units[text[-1]] if text[-1] in units else 1
        number = text[:-1] if text[-1] in units else text
        seconds = float(number) * multiplier
    except (IndexError, ValueError) as exc:
        raise argparse.ArgumentTypeError(f"invalid duration: {value!r}") from exc
    if seconds <= 0:
        raise argparse.ArgumentTypeError("duration must be greater than zero")
    return seconds


def read_env(path: Path) -> dict[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise VlanError(f"cannot read config {path}: {exc}") from exc
    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(lines, 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        if "=" not in line:
            raise VlanError(f"{path}:{line_number}: expected KEY=VALUE")
        key, raw_value = line.split("=", 1)
        key = key.strip()
        try:
            parts = shlex.split(raw_value, comments=True, posix=True)
        except ValueError as exc:
            raise VlanError(f"{path}:{line_number}: {exc}") from exc
        if len(parts) > 1:
            raise VlanError(f"{path}:{line_number}: quote values containing spaces")
        values[key] = parts[0] if parts else ""
    return values


def load_config(requested: Path) -> tuple[dict[str, str], Path]:
    actual = requested
    if (
        requested == DEFAULT_UNIFI_CONFIG
        and not requested.exists()
        and FALLBACK_UNIFI_CONFIG.exists()
    ):
        actual = FALLBACK_UNIFI_CONFIG
    return read_env(actual), actual


class UnifiController:
    """Small client for the local controller's legacy Network API."""

    def __init__(self, config: Mapping[str, str], *, dry_run: bool = False):
        missing = [
            key
            for key in ("UNIFI_HOST", "UNIFI_SITE_NAME", "UNIFI_TOKEN_FILE")
            if not config.get(key)
        ]
        if missing:
            raise VlanError(f"UniFi config is missing: {', '.join(missing)}")
        self.base_url = (
            config["UNIFI_HOST"].rstrip("/")
            + "/proxy/network/api/s/"
            + urllib.parse.quote(config["UNIFI_SITE_NAME"], safe="")
            + "/"
        )
        token_path = Path(os.path.expandvars(config["UNIFI_TOKEN_FILE"])).expanduser()
        try:
            self.api_key = token_path.read_text(encoding="utf-8").strip()
        except OSError as exc:
            raise VlanError(
                f"cannot read UniFi token file {token_path}: {exc}"
            ) from exc
        if not self.api_key:
            raise VlanError(f"UniFi token file is empty: {token_path}")
        self.ssl_context = ssl.create_default_context()
        if config.get("UNIFI_CURL_INSECURE", "0").lower() in {"1", "true", "yes"}:
            self.ssl_context = ssl._create_unverified_context()  # noqa: SLF001
        self.dry_run = dry_run

    def _request(
        self, method: str, path: str, payload: Mapping[str, Any] | None = None
    ) -> dict[str, Any]:
        url = urllib.parse.urljoin(self.base_url, path)
        body = (
            json.dumps(payload, sort_keys=True).encode()
            if payload is not None
            else None
        )
        if self.dry_run and method != "GET":
            print(f"DRY-RUN {method} {url} {body.decode() if body else ''}".rstrip())
            return {"meta": {"rc": "ok"}, "data": []}
        request = urllib.request.Request(
            url,
            data=body,
            method=method,
            headers={
                "Accept": "application/json",
                "Content-Type": "application/json",
                "X-API-KEY": self.api_key,
            },
        )
        try:
            with urllib.request.urlopen(
                request, context=self.ssl_context, timeout=15
            ) as response:
                result = json.load(response)
        except (urllib.error.URLError, json.JSONDecodeError, TimeoutError) as exc:
            raise VlanError(f"UniFi {method} {path} failed: {exc}") from exc
        if result.get("meta", {}).get("rc") not in (None, "ok"):
            message = result.get("meta", {}).get("msg", "controller rejected request")
            raise VlanError(f"UniFi {method} {path} failed: {message}")
        return result

    def _data(self, path: str) -> list[dict[str, Any]]:
        result = self._request("GET", path)
        data = result.get("data")
        if not isinstance(data, list):
            raise VlanError(f"UniFi GET {path} returned no data list")
        return data

    def client(self, mac: str) -> dict[str, Any]:
        requested = normalize_mac(mac)
        users = [
            item
            for item in self._data("rest/user")
            if item.get("mac", "").lower() == requested
        ]
        if len(users) != 1:
            raise VlanError(
                f"expected one saved client for {requested}, found {len(users)}"
            )
        stations = [
            item
            for item in self._data("stat/sta")
            if item.get("mac", "").lower() == requested
        ]
        station = stations[0] if stations else {}
        devices = self._data("stat/device") if station else []
        ap_mac = station.get("ap_mac")
        access_point = next(
            (
                item
                for item in devices
                if item.get("mac", "").lower() == str(ap_mac).lower()
            ),
            {},
        )
        user = users[0]
        return {
            "user_id": user["_id"],
            "mac": requested,
            "name": station.get("hostname") or user.get("hostname") or user.get("name"),
            "connected": bool(station),
            "ip": station.get("ip") or user.get("last_ip"),
            "network_id": station.get("network_id")
            or user.get("last_connection_network_id"),
            "network_name": station.get("network")
            or user.get("last_connection_network_name"),
            "override_network_id": user.get("network_id"),
            "ap_mac": ap_mac or user.get("last_uplink_mac"),
            "ap_name": access_point.get("name") or user.get("last_uplink_name"),
        }

    def networks(self) -> list[dict[str, Any]]:
        return [
            item
            for item in self._data("rest/networkconf")
            if item.get("purpose") != "wan"
        ]

    def network(self, value: str) -> dict[str, Any]:
        needle = value.casefold()

        def identifiers(network: Mapping[str, Any]) -> set[str]:
            vlan = network.get("vlan")
            if network.get("purpose") == "corporate" and not network.get(
                "vlan_enabled"
            ):
                vlan = 1
            return {
                str(network.get("_id", "")).casefold(),
                str(network.get("name", "")).casefold(),
                str(vlan if vlan is not None else "").casefold(),
            }

        matches = [
            network for network in self.networks() if needle in identifiers(network)
        ]
        if len(matches) != 1:
            raise VlanError(
                f"expected one network matching {value!r}, found {len(matches)}"
            )
        return matches[0]

    def set_network_override(self, user_id: str, network_id: str | None) -> None:
        # The controller represents "use the SSID/default network" as an empty override.
        self._request(
            "PUT",
            f"rest/user/{urllib.parse.quote(user_id, safe='')}",
            {"network_id": network_id or ""},
        )


@dataclass
class StateStore:
    directory: Path

    def path(self, mac: str) -> Path:
        return self.directory / f"{normalize_mac(mac).replace(':', '')}.json"

    def load(self, mac: str) -> dict[str, Any]:
        path = self.path(mac)
        try:
            state = json.loads(path.read_text(encoding="utf-8"))
        except FileNotFoundError as exc:
            raise VlanError(f"no saved move for {normalize_mac(mac)}") from exc
        except (OSError, json.JSONDecodeError) as exc:
            raise VlanError(f"cannot read saved state {path}: {exc}") from exc
        if state.get("version") != 1 or state.get("mac") != normalize_mac(mac):
            raise VlanError(f"invalid saved state: {path}")
        return state

    def save(self, mac: str, state: Mapping[str, Any]) -> None:
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        path = self.path(mac)
        if path.exists():
            raise VlanError(
                f"a move is already saved for {normalize_mac(mac)}; revert it first"
            )
        fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=self.directory)
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(dict(state), handle, indent=2, sort_keys=True)
                handle.write("\n")
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, path)
        except BaseException:
            try:
                os.unlink(temporary)
            except FileNotFoundError:
                pass
            raise

    def remove(self, mac: str) -> None:
        try:
            self.path(mac).unlink()
        except OSError as exc:
            raise VlanError(
                f"network restored but saved state could not be removed: {exc}"
            ) from exc


class VlanService:
    def __init__(self, controller: Controller, state: StateStore, allowed_mac: str):
        self.controller = controller
        self.state = state
        self.allowed_mac = normalize_mac(allowed_mac)

    def authorize(self, mac: str) -> str:
        requested = normalize_mac(mac)
        if requested != self.allowed_mac:
            raise VlanError(
                f"refusing client {requested}: only configured PHONE_MAC {self.allowed_mac} is allowed"
            )
        return requested

    def status(self, mac: str) -> dict[str, Any]:
        requested = self.authorize(mac)
        client = self.controller.client(requested)
        network = self.controller.network(str(client["network_id"]))
        return {
            **client,
            "vlan": network.get("vlan") or 1,
            "network_name": network.get("name") or client.get("network_name"),
            "saved_move": self.state.path(requested).exists(),
        }

    def move(
        self,
        mac: str,
        network_value: str,
        timeout: float,
        confirm: Callable[[float], bool],
        *,
        interval: float = 5.0,
        clock: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], None] = time.sleep,
        dry_run: bool = False,
    ) -> dict[str, Any]:
        requested = self.authorize(mac)
        client = self.controller.client(requested)
        target = self.controller.network(network_value)
        state = {
            "version": 1,
            "mac": requested,
            "user_id": client["user_id"],
            "previous_network_id": client.get("override_network_id"),
            "previous_network_name": client.get("network_name"),
            "target_network_id": target["_id"],
            "target_network_name": target.get("name"),
        }
        if self.state.path(requested).exists():
            raise VlanError(f"a move is already saved for {requested}; revert it first")
        if not dry_run:
            self.state.save(requested, state)
        self.controller.set_network_override(client["user_id"], target["_id"])
        deadline = clock() + timeout
        attempts = 0
        while True:
            remaining = max(0.0, deadline - clock())
            attempts += 1
            if confirm(remaining):
                return {**state, "confirm_attempts": attempts}
            remaining = deadline - clock()
            if remaining <= 0:
                break
            sleep(min(interval, remaining))
        if dry_run:
            self.controller.set_network_override(
                client["user_id"], state["previous_network_id"]
            )
        else:
            self._restore(requested, state)
        raise VlanError(
            f"confirmation failed for {timeout:g}s; restored {state.get('previous_network_name') or 'previous network'}"
        )

    def _restore(self, mac: str, state: Mapping[str, Any]) -> None:
        self.controller.set_network_override(
            str(state["user_id"]), state.get("previous_network_id")
        )
        self.state.remove(mac)

    def revert(self, mac: str, *, dry_run: bool = False) -> dict[str, Any]:
        requested = self.authorize(mac)
        state = self.state.load(requested)
        self.controller.set_network_override(
            str(state["user_id"]), state.get("previous_network_id")
        )
        if not dry_run:
            self.state.remove(requested)
        return state


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=DEFAULT_UNIFI_CONFIG)
    parser.add_argument("--impair-config", type=Path, default=DEFAULT_IMPAIR_CONFIG)
    parser.add_argument("--state-dir", type=Path, default=DEFAULT_STATE_DIR)
    parser.add_argument(
        "--dry-run", action="store_true", help="print writes without sending them"
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    status = subparsers.add_parser("status", help="show the phone's current network")
    status.add_argument("mac")

    move = subparsers.add_parser("move", help="set a client network override")
    move.add_argument("mac")
    move.add_argument("network", help="network name, VLAN, or controller ID")
    move.add_argument(
        "--revert-after", type=parse_duration, default=parse_duration("2m")
    )
    move.add_argument("--confirm-cmd", required=True)
    move.add_argument(
        "--poll-interval", type=parse_duration, default=parse_duration("5s")
    )
    move.add_argument("--dry-run", action="store_true", default=argparse.SUPPRESS)

    revert = subparsers.add_parser("revert", help="restore the saved network override")
    revert.add_argument("mac")
    revert.add_argument("--dry-run", action="store_true", default=argparse.SUPPRESS)
    return parser


def print_status(status: Mapping[str, Any]) -> None:
    print(f"MAC:       {status['mac']}")
    print(f"Connected: {'yes' if status['connected'] else 'no'}")
    print(f"Network:   {status.get('network_name') or '-'}")
    print(f"VLAN:      {status.get('vlan') or '-'}")
    print(f"IP:        {status.get('ip') or '-'}")
    ap = status.get("ap_name") or "-"
    if status.get("ap_mac"):
        ap += f" ({status['ap_mac']})"
    print(f"AP:        {ap}")
    print(f"Saved move: {'yes' if status['saved_move'] else 'no'}")


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        unifi_config, config_path = load_config(args.config.expanduser())
        impair_config = read_env(args.impair_config.expanduser())
        if not impair_config.get("PHONE_MAC"):
            raise VlanError(f"{args.impair_config}: PHONE_MAC is missing")
        controller = UnifiController(unifi_config, dry_run=args.dry_run)
        service = VlanService(
            controller,
            StateStore(args.state_dir.expanduser()),
            impair_config["PHONE_MAC"],
        )
        if config_path != args.config.expanduser():
            print(f"NOTE: using fallback UniFi config {config_path}", file=sys.stderr)

        if args.command == "status":
            print_status(service.status(args.mac))
        elif args.command == "move":
            command = shlex.split(args.confirm_cmd)
            if not command:
                raise VlanError("--confirm-cmd must not be empty")

            def confirm(remaining: float) -> bool:
                try:
                    return (
                        subprocess.run(
                            command, check=False, timeout=max(0.001, remaining)
                        ).returncode
                        == 0
                    )
                except (OSError, subprocess.TimeoutExpired):
                    return False

            result = service.move(
                args.mac,
                args.network,
                args.revert_after,
                confirm,
                interval=args.poll_interval,
                dry_run=args.dry_run,
            )
            print(
                f"Moved {result['mac']} to {result['target_network_name']}; "
                f"confirmation succeeded after {result['confirm_attempts']} attempt(s)."
            )
        else:
            result = service.revert(args.mac, dry_run=args.dry_run)
            print(
                f"Restored {result['mac']} to {result.get('previous_network_name') or 'its default network'}."
            )
        return 0
    except VlanError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
