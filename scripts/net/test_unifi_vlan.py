#!/usr/bin/env python3
"""Unit tests for unifi-vlan.py."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any


SCRIPT = Path(__file__).with_name("unifi-vlan.py")
SPEC = importlib.util.spec_from_file_location("unifi_vlan", SCRIPT)
assert SPEC and SPEC.loader
unifi_vlan = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = unifi_vlan
SPEC.loader.exec_module(unifi_vlan)


PHONE = "aa:bb:cc:dd:ee:ff"


class FakeController:
    def __init__(self) -> None:
        self.client_value: dict[str, Any] = {
            "user_id": "phone-id",
            "mac": PHONE,
            "connected": True,
            "ip": "192.0.2.2",
            "network_id": "default-id",
            "network_name": "Default",
            "override_network_id": None,
            "ap_mac": "00:11:22:33:44:55",
            "ap_name": "Test AP",
        }
        self.networks = {
            "default-id": {"_id": "default-id", "name": "Default", "vlan": None},
            "Impair": {"_id": "impair-id", "name": "Impair", "vlan": "40"},
        }
        self.calls: list[tuple[str, str | None]] = []

    def client(self, mac: str) -> dict[str, Any]:
        return dict(self.client_value)

    def network(self, value: str) -> dict[str, Any]:
        return dict(self.networks[value])

    def set_network_override(self, user_id: str, network_id: str | None) -> None:
        self.calls.append((user_id, network_id))


class Clock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.now += seconds


class VlanServiceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.controller = FakeController()
        self.store = unifi_vlan.StateStore(Path(self.temporary.name))
        self.service = unifi_vlan.VlanService(self.controller, self.store, PHONE)

    def test_refuses_every_other_client_before_controller_access(self) -> None:
        with self.assertRaisesRegex(unifi_vlan.VlanError, "only configured PHONE_MAC"):
            self.service.status("00:00:00:00:00:01")
        self.assertEqual([], self.controller.calls)

    def test_status_reports_network_vlan_ip_and_ap(self) -> None:
        status = self.service.status(PHONE.upper())
        self.assertEqual("Default", status["network_name"])
        self.assertEqual(1, status["vlan"])
        self.assertEqual("192.0.2.2", status["ip"])
        self.assertEqual("Test AP", status["ap_name"])

    def test_successful_move_keeps_original_state_for_manual_revert(self) -> None:
        result = self.service.move(PHONE, "Impair", 10, lambda _remaining: True)
        self.assertEqual([("phone-id", "impair-id")], self.controller.calls)
        self.assertEqual(1, result["confirm_attempts"])
        self.assertIsNone(self.store.load(PHONE)["previous_network_id"])

    def test_move_does_not_overwrite_pending_revert(self) -> None:
        self.service.move(PHONE, "Impair", 10, lambda _remaining: True)
        with self.assertRaisesRegex(unifi_vlan.VlanError, "already saved"):
            self.service.move(PHONE, "Impair", 10, lambda _remaining: True)

    def test_confirmation_retries_then_succeeds(self) -> None:
        clock = Clock()
        answers = iter([False, False, True])
        result = self.service.move(
            PHONE,
            "Impair",
            10,
            lambda _remaining: next(answers),
            interval=2,
            clock=clock,
            sleep=clock.sleep,
        )
        self.assertEqual(3, result["confirm_attempts"])
        self.assertEqual(4, clock.now)
        self.assertEqual([("phone-id", "impair-id")], self.controller.calls)

    def test_timeout_automatically_restores_and_removes_state(self) -> None:
        clock = Clock()
        with self.assertRaisesRegex(unifi_vlan.VlanError, "confirmation failed"):
            self.service.move(
                PHONE,
                "Impair",
                5,
                lambda _remaining: False,
                interval=2,
                clock=clock,
                sleep=clock.sleep,
            )
        self.assertEqual(
            [("phone-id", "impair-id"), ("phone-id", None)], self.controller.calls
        )
        self.assertFalse(self.store.path(PHONE).exists())
        self.assertEqual(5, clock.now)

    def test_revert_restores_saved_override(self) -> None:
        self.controller.client_value["override_network_id"] = "old-id"
        self.service.move(PHONE, "Impair", 10, lambda _remaining: True)
        result = self.service.revert(PHONE)
        self.assertEqual("old-id", result["previous_network_id"])
        self.assertEqual(
            [("phone-id", "impair-id"), ("phone-id", "old-id")], self.controller.calls
        )
        self.assertFalse(self.store.path(PHONE).exists())

    def test_revert_requires_saved_state(self) -> None:
        with self.assertRaisesRegex(unifi_vlan.VlanError, "no saved move"):
            self.service.revert(PHONE)

    def test_dry_run_does_not_write_state(self) -> None:
        self.service.move(PHONE, "Impair", 10, lambda _remaining: True, dry_run=True)
        self.assertFalse(self.store.path(PHONE).exists())

    def test_dry_run_revert_keeps_saved_state(self) -> None:
        self.service.move(PHONE, "Impair", 10, lambda _remaining: True)
        self.service.revert(PHONE, dry_run=True)
        self.assertTrue(self.store.path(PHONE).exists())


class ParsingTest(unittest.TestCase):
    def test_mac_parser_rejects_extra_characters(self) -> None:
        with self.assertRaisesRegex(unifi_vlan.VlanError, "invalid MAC"):
            unifi_vlan.normalize_mac("xxaa:bb:cc:dd:ee:ff")

    def test_duration_units(self) -> None:
        self.assertEqual(120, unifi_vlan.parse_duration("2m"))
        self.assertEqual(3600, unifi_vlan.parse_duration("1h"))
        self.assertEqual(2.5, unifi_vlan.parse_duration("2.5s"))

    def test_invalid_duration(self) -> None:
        with self.assertRaises(Exception):
            unifi_vlan.parse_duration("0")


if __name__ == "__main__":
    unittest.main()
