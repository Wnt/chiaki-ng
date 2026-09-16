#!/usr/bin/env bash
# SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
# Run the PSN mock behind forwarder-agent on its two public hosts (PLE-284).
#
#   serve.sh start     start mock + agent in the background (idempotent)
#   serve.sh stop      stop both
#   serve.sh status    show processes and probe both public hosts
#   serve.sh run       run both in the foreground (for a systemd unit)
#
# The agent token is read from $FORWARDER_AGENT_TOKEN, else the first existing
# env file in $PSN_MOCK_AGENT_ENV, ~/.config/pleikkari/forwarder-agent.env,
# ~/.config/locator-kiosk/forwarder-agent.env. It is passed through the
# environment, never on a command line, and never printed.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFIED_HOST="${PSN_MOCK_VERIFIED_HOST:-pleikkari-psn.lab.madekivi.fi}"
NOLINK_HOST="${PSN_MOCK_NOLINK_HOST:-pleikkari-psn-nolink.lab.madekivi.fi}"
PORT="${PSN_MOCK_PORT:-18284}"
CONTROL="${PSN_MOCK_CONTROL_HOST:-tunnel.lab.madekivi.fi}"
STATE="${PSN_MOCK_STATE:-${XDG_STATE_HOME:-$HOME/.local/state}/pleikkari-psn-mock}"
AGENT="${PSN_MOCK_AGENT:-$HOME/.cache/pleikkari/forwarder/forwarder-agent}"

die() { printf 'psn-mock: %s\n' "$*" >&2; exit 1; }
say() { printf 'psn-mock: %s\n' "$*"; }

load_token() {
	[ -n "${FORWARDER_AGENT_TOKEN:-}" ] && return
	local f
	for f in ${PSN_MOCK_AGENT_ENV:-} "$HOME/.config/pleikkari/forwarder-agent.env" "$HOME/.config/locator-kiosk/forwarder-agent.env"; do
		if [ -r "$f" ]; then
			FORWARDER_AGENT_TOKEN="$(sed -n 's/^FORWARDER_AGENT_TOKEN=//p' "$f" | tail -n1 | tr -d "\"'")"
			[ -n "$FORWARDER_AGENT_TOKEN" ] && export FORWARDER_AGENT_TOKEN && return
		fi
	done
	die "no forwarder agent token: set FORWARDER_AGENT_TOKEN or PSN_MOCK_AGENT_ENV"
}

ensure_agent() {
	[ -x "$AGENT" ] && return
	command -v gh >/dev/null || die "forwarder-agent missing at $AGENT and gh is not installed to fetch it"
	say "fetching forwarder-agent from the Wnt/forwarder CI artifact"
	local run tmp
	run="$(gh run list --repo Wnt/forwarder --workflow CI --branch main --status success --limit 1 --json databaseId --jq '.[0].databaseId')"
	tmp="$(mktemp -d)"
	gh run download --repo Wnt/forwarder -n forwarder-binaries --dir "$tmp" "$run"
	mkdir -p "$(dirname "$AGENT")"
	install -m 0755 "$tmp/forwarder-agent-linux-amd64" "$AGENT"
	rm -rf "$tmp"
}

pid_alive() { [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null; }

mock_cmd() {
	exec python3 "$HERE/psn_mock.py" --port "$PORT" --state-dir "$STATE" \
		--verified-host "$VERIFIED_HOST" --nolink-host "$NOLINK_HOST"
}

agent_cmd() {
	exec "$AGENT" --server "$CONTROL" \
		--tunnel "psnmock:http:$VERIFIED_HOST:$PORT,psnmocknolink:http:$NOLINK_HOST:$PORT"
}

cmd_start() {
	load_token
	ensure_agent
	mkdir -p "$STATE"
	if pid_alive "$STATE/mock.pid"; then say "mock already running"; else
		(mock_cmd) >>"$STATE/mock.log" 2>&1 &
		echo $! >"$STATE/mock.pid"
	fi
	if pid_alive "$STATE/agent.pid"; then say "agent already running"; else
		(agent_cmd) >>"$STATE/agent.log" 2>&1 &
		echo $! >"$STATE/agent.pid"
	fi
	local i
	for i in $(seq 1 30); do
		if ! pid_alive "$STATE/mock.pid"; then die "mock exited; see $STATE/mock.log"; fi
		if curl -fsS --max-time 5 "https://$VERIFIED_HOST/healthz" 2>/dev/null | grep -q "psn-mock ok"; then
			say "up: https://$VERIFIED_HOST (assetlinks) and https://$NOLINK_HOST (none); logs in $STATE"
			return 0
		fi
		sleep 1
	done
	die "not reachable at https://$VERIFIED_HOST after 30 s; see $STATE/agent.log and $STATE/mock.log"
}

cmd_stop() {
	local name
	for name in agent mock; do
		if pid_alive "$STATE/$name.pid"; then kill "$(cat "$STATE/$name.pid")" && say "stopped $name"; fi
		rm -f "$STATE/$name.pid"
	done
}

cmd_status() {
	local name host code
	for name in mock agent; do
		if pid_alive "$STATE/$name.pid"; then say "$name running (pid $(cat "$STATE/$name.pid"))"; else say "$name not running"; fi
	done
	for host in "$VERIFIED_HOST" "$NOLINK_HOST"; do
		code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "https://$host/.well-known/assetlinks.json" || true)"
		say "https://$host/.well-known/assetlinks.json -> HTTP $code"
	done
}

cmd_run() {
	load_token
	ensure_agent
	mkdir -p "$STATE"
	(mock_cmd) &
	local mock=$!
	(agent_cmd) &
	local agent=$!
	trap 'kill $mock $agent 2>/dev/null' EXIT INT TERM
	wait -n $mock $agent
}

case "${1:-}" in
	start) cmd_start ;;
	stop) cmd_stop ;;
	status) cmd_status ;;
	run) cmd_run ;;
	*) sed -n '3,13p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
