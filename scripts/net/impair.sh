#!/usr/bin/env bash
# Apply PS5-side network impairment to a transparent bridge.

set -euo pipefail

readonly STATE_DIR="${IMPAIR_STATE_DIR:-/run/pleikkari-impair}"
readonly STATE_FILE="${STATE_DIR}/active"
readonly LOCK_FILE="${STATE_DIR}/lock"
readonly WATCHDOG_UNIT="pleikkari-impair-watchdog"
readonly DEFAULT_LAN_IFACE="${IMPAIR_LAN_IFACE:-eth0}"
readonly DEFAULT_PS5_IFACE="${IMPAIR_PS5_IFACE:-eth1}"
SELF=$(readlink -f "$0")
readonly SELF
DRY_RUN=0

usage() {
	cat <<'EOF'
Usage:
  impair.sh profile PROFILE --ttl DURATION
                  [--lan-iface IFACE] [--ps5-iface IFACE]
  impair.sh profile custom --ttl DURATION
                  [--lan-iface IFACE] [--ps5-iface IFACE]
                  [--delay TIME] [--jitter TIME] [--loss PERCENT]
                  [--reorder PERCENT] [--rate RATE]
  impair.sh clean [--lan-iface IFACE] [--ps5-iface IFACE]
  impair.sh status

Profiles: 5g, 4g, wifi-slow, blip-200ms, loss-2, custom
Durations use a positive integer plus s, m, h, or d (for example 30m).
TIME uses us, ms, or s; RATE uses kbit, mbit, or gbit.

The two interfaces must already be forwarding ports of the transparent bridge.
Each profile switch deletes both root qdiscs before reinstalling one with
`tc qdisc replace`, so entering any profile from any other establishes exactly
that profile's parameters -- `tc qdisc replace` on an existing netem qdisc
merges unspecified fields (rate, reorder, ...) with the qdisc's previous
values instead of resetting them, so skipping the delete would leave a
switched-from profile's parameters in force. `clean` removes both root
qdiscs. The legacy `apply` and `clear` verbs remain aliases for existing
harness callers.

Set IMPAIR_DRY_RUN=1 to print commands without changing the host.
EOF
}

die() {
	echo "impair.sh: $*" >&2
	exit 2
}

run() {
	if ((DRY_RUN)); then
		printf '+ '
		printf '%q ' "$@"
		printf '\n'
	else
		"$@"
	fi
}

try_run() {
	if ((DRY_RUN)); then
		run "$@"
	else
		"$@" >/dev/null 2>&1 || true
	fi
}

valid_iface() { [[ $1 =~ ^[a-zA-Z0-9_.:-]{1,15}$ ]]; }
valid_time() { [[ $1 =~ ^[0-9]+([.][0-9]+)?(us|ms|s)$ ]]; }
valid_rate() { [[ $1 =~ ^[0-9]+([.][0-9]+)?(kbit|mbit|gbit)$ ]]; }
valid_ttl() { [[ $1 =~ ^[1-9][0-9]*(s|m|h|d)$ ]]; }

valid_percent() {
	[[ $1 =~ ^[0-9]+([.][0-9]+)?%$ ]] || return 1
	awk -v value="${1%%%}" 'BEGIN { exit !(value >= 0 && value <= 100) }'
}

ttl_seconds() {
	local ttl=$1 number=${1%?}
	case ${ttl: -1} in
	s) echo "$number" ;;
	m) echo $((number * 60)) ;;
	h) echo $((number * 3600)) ;;
	d) echo $((number * 86400)) ;;
	esac
}

state_value() {
	local key=$1
	[[ -r $STATE_FILE ]] || return 1
	sed -n "s/^${key}=//p" "$STATE_FILE" | head -n 1
}

terminate_pid() {
	local pid=$1 attempt
	kill "$pid" 2>/dev/null || return 0
	for ((attempt = 0; attempt < 20; attempt++)); do
		kill -0 "$pid" 2>/dev/null || return 0
		sleep 0.05
	done
	kill -KILL "$pid" 2>/dev/null || true
}

stop_blip() {
	local pid cmdline
	pid=$(state_value blip_pid 2>/dev/null || true)
	[[ $pid =~ ^[0-9]+$ && -r /proc/$pid/cmdline ]] || return 0
	cmdline=$(tr '\0' ' ' <"/proc/$pid/cmdline")
	if [[ $cmdline == *"$SELF"* && $cmdline == *"__blip"* ]]; then
		terminate_pid "$pid"
	fi
}

cancel_watchdog() {
	local pid cmdline
	if command -v systemctl >/dev/null 2>&1; then
		systemctl stop "${WATCHDOG_UNIT}.timer" >/dev/null 2>&1 || true
		# A timer-fired clean runs inside this service. Do not terminate it before
		# it has removed the qdiscs and state file.
		if [[ -z ${INVOCATION_ID:-} ]]; then
			systemctl stop "${WATCHDOG_UNIT}.service" >/dev/null 2>&1 || true
			systemctl reset-failed "${WATCHDOG_UNIT}.service" >/dev/null 2>&1 || true
		fi
		systemctl reset-failed "${WATCHDOG_UNIT}.timer" >/dev/null 2>&1 || true
	fi
	pid=$(state_value watchdog_pid 2>/dev/null || true)
	[[ $pid =~ ^[0-9]+$ && -r /proc/$pid/cmdline ]] || return 0
	cmdline=$(tr '\0' ' ' <"/proc/$pid/cmdline")
	if [[ $cmdline == *"$SELF"* && $cmdline == *"__watchdog"* ]]; then
		terminate_pid "$pid"
	fi
}

old_or_default_iface() {
	local key=$1 fallback=$2 value
	value=$(state_value "$key" 2>/dev/null || true)
	[[ -z $value ]] && echo "$fallback" || echo "$value"
}

clean_impl() {
	local lan_iface=${1:-} ps5_iface=${2:-}
	[[ -n $lan_iface ]] || lan_iface=$(old_or_default_iface lan_iface "$DEFAULT_LAN_IFACE")
	[[ -n $ps5_iface ]] || ps5_iface=$(old_or_default_iface ps5_iface "$DEFAULT_PS5_IFACE")
	valid_iface "$lan_iface" || die "invalid LAN interface: $lan_iface"
	valid_iface "$ps5_iface" || die "invalid PS5 interface: $ps5_iface"
	[[ $lan_iface != "$ps5_iface" ]] || die "LAN and PS5 bridge interfaces must differ"
	if ((!DRY_RUN)); then
		stop_blip
		cancel_watchdog
	fi
	try_run tc qdisc del dev "$lan_iface" root
	try_run tc qdisc del dev "$ps5_iface" root
	if ((!DRY_RUN)); then
		rm -f "$STATE_FILE"
	fi
}

write_state() {
	local profile=$1 lan_iface=$2 ps5_iface=$3 ttl=$4 rate=$5 netem_string=$6 tmp
	tmp="${STATE_FILE}.tmp.$$"
	umask 077
	{
		echo "profile=$profile"
		echo "mode=transparent-bridge"
		echo "lan_iface=$lan_iface"
		echo "ps5_iface=$ps5_iface"
		echo "ttl=$ttl"
		[[ -z $rate ]] || echo "rate=$rate"
		echo "netem=$netem_string"
	} >"$tmp"
	mv "$tmp" "$STATE_FILE"
}

arm_watchdog() {
	local ttl=$1 seconds pid
	if command -v systemd-run >/dev/null 2>&1 &&
		systemd-run --quiet --unit "$WATCHDOG_UNIT" --on-active "$ttl" \
			--collect "$SELF" clean >/dev/null 2>&1; then
		echo "watchdog=systemd:${WATCHDOG_UNIT}.timer" >>"$STATE_FILE"
		return 0
	fi
	seconds=$(ttl_seconds "$ttl")
	nohup "$SELF" __watchdog "$seconds" >/dev/null 2>&1 9>&- &
	pid=$!
	echo "watchdog=process" >>"$STATE_FILE"
	echo "watchdog_pid=$pid" >>"$STATE_FILE"
}

install_profile() {
	local lan_iface=$1 ps5_iface=$2 rate=$3
	shift 3
	local -a netem=("$@")
	local -a rate_args=()
	[[ -z $rate ]] || rate_args=(rate "$rate")
	# eth0 egress carries PS5 -> LAN and eth1 egress carries LAN -> PS5.
	run tc qdisc replace dev "$lan_iface" root handle 10: netem "${netem[@]}" "${rate_args[@]}"
	run tc qdisc replace dev "$ps5_iface" root handle 20: netem "${netem[@]}" "${rate_args[@]}"
}

start_blip() {
	local pid
	nohup "$SELF" __blip >/dev/null 2>&1 9>&- &
	pid=$!
	echo "blip_pid=$pid" >>"$STATE_FILE"
}

profile_impl() {
	local profile=$1
	shift
	local ttl='' lan_iface=$DEFAULT_LAN_IFACE ps5_iface=$DEFAULT_PS5_IFACE
	local delay='' jitter='' loss='' reorder='' rate=''
	local custom_seen=0
	local -a netem=()

	while (($#)); do
		case $1 in
		--ttl) (($# >= 2)) || die "--ttl needs a value"; ttl=$2; shift 2 ;;
		--lan-iface) (($# >= 2)) || die "--lan-iface needs a value"; lan_iface=$2; shift 2 ;;
		--ps5-iface) (($# >= 2)) || die "--ps5-iface needs a value"; ps5_iface=$2; shift 2 ;;
		--delay) (($# >= 2)) || die "--delay needs a value"; delay=$2; custom_seen=1; shift 2 ;;
		--jitter) (($# >= 2)) || die "--jitter needs a value"; jitter=$2; custom_seen=1; shift 2 ;;
		--loss) (($# >= 2)) || die "--loss needs a value"; loss=$2; custom_seen=1; shift 2 ;;
		--reorder) (($# >= 2)) || die "--reorder needs a value"; reorder=$2; custom_seen=1; shift 2 ;;
		--rate) (($# >= 2)) || die "--rate needs a value"; rate=$2; custom_seen=1; shift 2 ;;
		--dry-run) DRY_RUN=1; shift ;;
		*) die "unknown profile option: $1" ;;
		esac
	done

	valid_ttl "$ttl" || die "profile requires a valid --ttl"
	valid_iface "$lan_iface" || die "invalid LAN interface: $lan_iface"
	valid_iface "$ps5_iface" || die "invalid PS5 interface: $ps5_iface"
	[[ $lan_iface != "$ps5_iface" ]] || die "LAN and PS5 bridge interfaces must differ"
	if [[ $profile != custom && $custom_seen == 1 ]]; then
		die "custom netem options require the custom profile"
	fi

	case $profile in
	5g) netem=(delay 10ms 2.5ms distribution normal loss 0.3%) ;;
	4g) netem=(delay 22.5ms 7.5ms distribution normal loss 1% reorder 0.5%); rate=30mbit ;;
	wifi-slow) netem=(delay 5ms 12.5ms 25% distribution normal loss 2% 25% reorder 2% 25%); rate=20mbit ;;
	blip-200ms) netem=(delay 0ms) ;;
	loss-2) netem=(loss 2%) ;;
	custom)
		[[ -z $delay ]] || valid_time "$delay" || die "invalid delay: $delay"
		[[ -z $jitter ]] || valid_time "$jitter" || die "invalid jitter: $jitter"
		[[ -z $loss ]] || valid_percent "$loss" || die "invalid loss: $loss"
		[[ -z $reorder ]] || valid_percent "$reorder" || die "invalid reorder: $reorder"
		[[ -z $rate ]] || valid_rate "$rate" || die "invalid rate: $rate"
		[[ -z $jitter || -n $delay ]] || die "--jitter requires --delay"
		if [[ -n $delay ]]; then
			netem+=(delay "$delay")
			[[ -z $jitter ]] || netem+=("$jitter")
		elif [[ -n $reorder ]]; then
			netem+=(delay 0ms)
		fi
		[[ -z $loss ]] || netem+=(loss "$loss")
		[[ -z $reorder ]] || netem+=(reorder "$reorder")
		if ((${#netem[@]} == 0)) && [[ -z $rate ]]; then
			die "custom requires at least one netem option"
		fi
		((${#netem[@]} > 0)) || netem=(delay 0ms)
		;;
	*) die "unknown profile: $profile" ;;
	esac

	# A profile switch must establish exactly the requested parameters. `tc
	# qdisc replace` on an existing netem handle merges unspecified fields
	# (rate, reorder, ...) with whatever the previous profile left rather than
	# resetting them, so delete each root qdisc -- wherever the previous
	# profile's parameters actually live -- before installing the new one.
	# Entering any profile from any other then matches entering it from clean.
	if ((DRY_RUN)); then
		try_run tc qdisc del dev "$lan_iface" root
		try_run tc qdisc del dev "$ps5_iface" root
		install_profile "$lan_iface" "$ps5_iface" "$rate" "${netem[@]}"
		return 0
	fi

	stop_blip
	cancel_watchdog
	try_run tc qdisc del dev "$lan_iface" root
	try_run tc qdisc del dev "$ps5_iface" root
	write_state "$profile" "$lan_iface" "$ps5_iface" "$ttl" "$rate" "${netem[*]}"
	trap 'clean_impl "$lan_iface" "$ps5_iface"' ERR
	arm_watchdog "$ttl"
	install_profile "$lan_iface" "$ps5_iface" "$rate" "${netem[@]}"
	[[ $profile != blip-200ms ]] || start_blip
	trap - ERR
	echo "active profile: $profile (bridge ports $lan_iface/$ps5_iface, ttl $ttl)"
}

show_status() {
	local profile lan_iface ps5_iface
	profile=$(state_value profile 2>/dev/null || echo clean)
	echo "active_profile=$profile"
	lan_iface=$(old_or_default_iface lan_iface "$DEFAULT_LAN_IFACE")
	ps5_iface=$(old_or_default_iface ps5_iface "$DEFAULT_PS5_IFACE")
	[[ $profile == clean ]] || cat "$STATE_FILE"
	for dev in "$lan_iface" "$ps5_iface"; do
		echo "--- qdisc: $dev"
		tc -s qdisc show dev "$dev"
	done
}

set_blip_delay() {
	local value=$1 lan_iface ps5_iface
	lan_iface=$(state_value lan_iface) || return 1
	ps5_iface=$(state_value ps5_iface) || return 1
	tc qdisc replace dev "$lan_iface" root handle 10: netem delay "$value"
	tc qdisc replace dev "$ps5_iface" root handle 20: netem delay "$value"
}

blip_loop() {
	while [[ $(state_value profile 2>/dev/null || true) == blip-200ms ]]; do
		sleep 20
		[[ $(state_value profile 2>/dev/null || true) == blip-200ms ]] || break
		set_blip_delay 200ms
		sleep 0.2
		[[ $(state_value profile 2>/dev/null || true) == blip-200ms ]] || break
		set_blip_delay 0ms
	done
}

watchdog_process() {
	local seconds=$1
	[[ $seconds =~ ^[1-9][0-9]*$ ]] || exit 2
	sleep "$seconds"
	exec "$SELF" clean
}

main() {
	local command=${1:-}
	[[ $command == __blip || $command == __watchdog ]] || {
		[[ ${IMPAIR_DRY_RUN:-0} == 1 ]] && DRY_RUN=1
		if ((!DRY_RUN && EUID != 0)); then
			die "must run as root"
		fi
		if ((!DRY_RUN)); then
			mkdir -p "$STATE_DIR"
			chmod 0700 "$STATE_DIR"
			exec 9>"$LOCK_FILE"
			flock 9
		fi
	}

	case $command in
	profile | apply)
		(($# >= 2)) || die "$command requires a profile"
		shift
		if [[ ${1:-} == clean ]]; then
			shift
			(($# == 0)) || die "clean profile takes no options"
			clean_impl
			echo "active profile: clean"
		else
			profile_impl "$@"
		fi
		;;
	status)
		(($# == 1)) || die "status takes no options"
		show_status
		;;
	clean | clear)
		shift
		local lan_iface='' ps5_iface=''
		while (($#)); do
			case $1 in
			--lan-iface) (($# >= 2)) || die "--lan-iface needs a value"; lan_iface=$2; shift 2 ;;
			--ps5-iface) (($# >= 2)) || die "--ps5-iface needs a value"; ps5_iface=$2; shift 2 ;;
			*) die "unknown clean option: $1" ;;
			esac
		done
		clean_impl "$lan_iface" "$ps5_iface"
		echo "active profile: clean"
		;;
	__blip) (($# == 1)) || exit 2; blip_loop ;;
	__watchdog) (($# == 2)) || exit 2; watchdog_process "$2" ;;
	-h | --help | help) usage ;;
	*) usage >&2; exit 2 ;;
	esac
}

main "$@"
