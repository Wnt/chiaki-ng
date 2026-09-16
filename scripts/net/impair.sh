#!/usr/bin/env bash
# Apply PS5-only network impairment on a routed netem guest.

set -euo pipefail

readonly STATE_DIR="${IMPAIR_STATE_DIR:-/run/pleikkari-impair}"
readonly STATE_FILE="${STATE_DIR}/active"
readonly LOCK_FILE="${STATE_DIR}/lock"
readonly IFB_IMPAIR="${IMPAIR_IFB_IMPAIR:-ifb-impair-rx}"
readonly IFB_LAN="${IMPAIR_IFB_LAN:-ifb-lan-rx}"
readonly WATCHDOG_UNIT="pleikkari-impair-watchdog"
SELF=$(readlink -f "$0")
readonly SELF

DRY_RUN=0

usage() {
	cat <<'EOF'
Usage:
  impair.sh apply PROFILE --peer IPv4 [--phone IPv4] [--ttl DURATION]
                  [--iface IFACE] [--phone-iface IFACE]
  impair.sh apply custom --peer IPv4 [--phone IPv4] [--ttl DURATION]
                  [--delay TIME] [--jitter TIME] [--loss PERCENT]
                  [--reorder PERCENT] [--rate RATE]
  impair.sh status
  impair.sh clear

Profiles: clean, 5g, 4g, wifi-slow, blip-200ms, loss-2, custom
Durations use a positive integer plus s, m, h, or d (for example 30m).
TIME uses us, ms, or s; RATE uses kbit, mbit, or gbit.

Interfaces are discovered with `ip route get`. --iface and --phone-iface are
available for unusual routing and deterministic tests. Set IMPAIR_DRY_RUN=1
to print commands without changing the host.
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

valid_ipv4() {
	local ip=$1 octet
	local -a octets
	[[ $ip =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || return 1
	IFS=. read -r -a octets <<<"$ip"
	((${#octets[@]} == 4)) || return 1
	for octet in "${octets[@]}"; do
		((${#octet} <= 3 && 10#$octet <= 255)) || return 1
	done
}

valid_iface() {
	[[ $1 =~ ^[a-zA-Z0-9_.:-]{1,15}$ ]]
}

valid_time() {
	[[ $1 =~ ^[0-9]+([.][0-9]+)?(us|ms|s)$ ]]
}

valid_percent() {
	[[ $1 =~ ^[0-9]+([.][0-9]+)?%$ ]] || return 1
	awk -v value="${1%%%}" 'BEGIN { exit !(value >= 0 && value <= 100) }'
}

valid_rate() {
	[[ $1 =~ ^[0-9]+([.][0-9]+)?(kbit|mbit|gbit)$ ]]
}

valid_ttl() {
	[[ $1 =~ ^[1-9][0-9]*(s|m|h|d)$ ]]
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

route_iface() {
	local address=$1 route iface
	route=$(ip -4 route get "$address") || die "no route to $address"
	iface=$(awk '{ for (i=1; i<=NF; i++) if ($i == "dev") { print $(i+1); exit } }' <<<"$route")
	[[ -n $iface ]] || die "could not find the interface for $address"
	valid_iface "$iface" || die "route returned an invalid interface: $iface"
	echo "$iface"
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
		# A timer-fired clear runs inside this service. Do not terminate it before
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

cleanup_iface() {
	local iface=$1
	valid_iface "$iface" || return 0
	try_run tc qdisc del dev "$iface" root
	try_run tc qdisc del dev "$iface" clsact
}

cleanup_ifb() {
	local ifb=$1
	try_run tc qdisc del dev "$ifb" root
	try_run ip link set dev "$ifb" down
	try_run ip link del dev "$ifb" type ifb
}

clear_impl() {
	local requested_peer=${1:-} requested_phone=${2:-} old_peer old_phone
	old_peer=$(state_value peer_iface 2>/dev/null || true)
	old_phone=$(state_value phone_iface 2>/dev/null || true)
	if ((!DRY_RUN)); then
		stop_blip
		cancel_watchdog
	fi
	[[ -z $old_peer ]] || cleanup_iface "$old_peer"
	[[ -z $old_phone || $old_phone == "$old_peer" ]] || cleanup_iface "$old_phone"
	[[ -z $requested_peer || $requested_peer == "$old_peer" || $requested_peer == "$old_phone" ]] || cleanup_iface "$requested_peer"
	[[ -z $requested_phone || $requested_phone == "$requested_peer" || $requested_phone == "$old_peer" || $requested_phone == "$old_phone" ]] || cleanup_iface "$requested_phone"
	cleanup_ifb "$IFB_IMPAIR"
	cleanup_ifb "$IFB_LAN"
	if ((!DRY_RUN)); then
		rm -f "$STATE_FILE"
	fi
}

ensure_ifb() {
	local ifb=$1
	if ((DRY_RUN)); then
		run ip link add "$ifb" type ifb
	elif ! ip link show dev "$ifb" >/dev/null 2>&1; then
		run ip link add "$ifb" type ifb
	fi
	run ip link set dev "$ifb" up
}

setup_redirect() {
	local iface=$1 ifb=$2
	ensure_ifb "$ifb"
	run tc qdisc replace dev "$iface" clsact
	run tc filter replace dev "$iface" ingress protocol ip prio 10 flower \
		action mirred egress redirect dev "$ifb"
}

setup_shaper() {
	local dev=$1 root_handle=$2 tbf_handle=$3 netem_handle=$4 source=$5 destination=$6 rate=$7
	shift 7
	local -a netem=("$@")
	local parent="${root_handle}:2"
	local -a tuple=()
	[[ -z $source ]] || tuple+=(src_ip "$source")
	[[ -z $destination ]] || tuple+=(dst_ip "$destination")

	run tc qdisc replace dev "$dev" root handle "${root_handle}:" prio bands 2 \
		priomap 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
	if [[ -n $rate ]]; then
		run tc qdisc replace dev "$dev" parent "$parent" handle "${tbf_handle}:" \
			tbf rate "$rate" burst 64kbit latency 400ms
		parent="${tbf_handle}:1"
	fi
	run tc qdisc replace dev "$dev" parent "$parent" handle "${netem_handle}:" netem "${netem[@]}"
	run tc filter replace dev "$dev" protocol ip parent "${root_handle}:" prio 10 \
		flower "${tuple[@]}" classid "${root_handle}:2"
}

install_tree() {
	local peer_iface=$1 phone_iface=$2 peer=$3 phone=$4 rate=$5
	shift 5
	local -a netem=("$@")

	if [[ -n $phone_iface && $phone_iface != "$peer_iface" ]]; then
		# Each direction enters a different routed interface. Redirect all IPv4
		# into a two-band IFB so unmatched ADB/SSH/mDNS stays in the clean band.
		setup_redirect "$phone_iface" "$IFB_IMPAIR"
		setup_shaper "$IFB_IMPAIR" 1 20 30 "$phone" "$peer" "$rate" "${netem[@]}"
		setup_redirect "$peer_iface" "$IFB_LAN"
		setup_shaper "$IFB_LAN" 2 21 40 "$peer" "$phone" "$rate" "${netem[@]}"
	else
		# --phone is optional. On a one-interface topology shape peer-bound
		# egress directly and peer-sourced ingress through one IFB.
		setup_shaper "$peer_iface" 1 20 30 "$phone" "$peer" "$rate" "${netem[@]}"
		setup_redirect "$peer_iface" "$IFB_LAN"
		setup_shaper "$IFB_LAN" 2 21 40 "$peer" "$phone" "$rate" "${netem[@]}"
	fi
}

write_state() {
	local profile=$1 peer_iface=$2 phone_iface=$3 peer=$4 phone=$5 ttl=$6 rate=$7 netem_string=$8 tmp mode
	if [[ -n $phone_iface && $phone_iface != "$peer_iface" ]]; then
		mode=dual-ingress
	else
		mode=single-interface
	fi
	tmp="${STATE_FILE}.tmp.$$"
	umask 077
	{
		echo "profile=$profile"
		echo "mode=$mode"
		echo "peer_iface=$peer_iface"
		[[ -z $phone_iface ]] || echo "phone_iface=$phone_iface"
		echo "ifb_impair=$IFB_IMPAIR"
		echo "ifb_lan=$IFB_LAN"
		echo "peer=$peer"
		[[ -z $phone ]] || echo "phone=$phone"
		[[ -z $ttl ]] || echo "ttl=$ttl"
		[[ -z $rate ]] || echo "rate=$rate"
		echo "netem=$netem_string"
		if [[ $mode == dual-ingress ]]; then
			echo "leg_a=$IFB_IMPAIR 1:2 30"
		else
			echo "leg_a=$peer_iface 1:2 30"
		fi
		echo "leg_b=$IFB_LAN 2:2 40"
	} >"$tmp"
	mv "$tmp" "$STATE_FILE"
}

arm_watchdog() {
	local ttl=$1 seconds pid
	[[ -n $ttl ]] || return 0
	if command -v systemd-run >/dev/null 2>&1 &&
		systemd-run --quiet --unit "$WATCHDOG_UNIT" --on-active "$ttl" \
			--collect "$SELF" clear >/dev/null 2>&1; then
		echo "watchdog=systemd:${WATCHDOG_UNIT}.timer" >>"$STATE_FILE"
		return 0
	fi
	seconds=$(ttl_seconds "$ttl")
	nohup "$SELF" __watchdog "$seconds" >/dev/null 2>&1 9>&- &
	pid=$!
	echo "watchdog=process" >>"$STATE_FILE"
	echo "watchdog_pid=$pid" >>"$STATE_FILE"
}

start_blip() {
	local pid
	nohup "$SELF" __blip >/dev/null 2>&1 9>&- &
	pid=$!
	echo "blip_pid=$pid" >>"$STATE_FILE"
}

apply_profile() {
	local profile=$1
	shift
	local peer='' phone='' ttl='' peer_iface='' phone_iface=''
	local delay='' jitter='' loss='' reorder='' rate=''
	local custom_seen=0
	local -a netem=()

	while (($#)); do
		case $1 in
		--peer)
			(($# >= 2)) || die "--peer needs a value"
			peer=$2
			shift 2
			;;
		--phone)
			(($# >= 2)) || die "--phone needs a value"
			phone=$2
			shift 2
			;;
		--ttl)
			(($# >= 2)) || die "--ttl needs a value"
			ttl=$2
			shift 2
			;;
		--iface)
			(($# >= 2)) || die "--iface needs a value"
			peer_iface=$2
			shift 2
			;;
		--phone-iface)
			(($# >= 2)) || die "--phone-iface needs a value"
			phone_iface=$2
			shift 2
			;;
		--delay)
			(($# >= 2)) || die "--delay needs a value"
			delay=$2
			custom_seen=1
			shift 2
			;;
		--jitter)
			(($# >= 2)) || die "--jitter needs a value"
			jitter=$2
			custom_seen=1
			shift 2
			;;
		--loss)
			(($# >= 2)) || die "--loss needs a value"
			loss=$2
			custom_seen=1
			shift 2
			;;
		--reorder)
			(($# >= 2)) || die "--reorder needs a value"
			reorder=$2
			custom_seen=1
			shift 2
			;;
		--rate)
			(($# >= 2)) || die "--rate needs a value"
			rate=$2
			custom_seen=1
			shift 2
			;;
		--dry-run)
			DRY_RUN=1
			shift
			;;
		*) die "unknown apply option: $1" ;;
		esac
	done

	[[ -n $peer ]] || die "apply requires --peer"
	valid_ipv4 "$peer" || die "invalid peer IPv4 address: $peer"
	[[ -z $phone ]] || valid_ipv4 "$phone" || die "invalid phone IPv4 address: $phone"
	[[ -z $ttl ]] || valid_ttl "$ttl" || die "invalid TTL: $ttl"
	[[ -z $peer_iface ]] || valid_iface "$peer_iface" || die "invalid interface: $peer_iface"
	[[ -z $phone_iface ]] || valid_iface "$phone_iface" || die "invalid phone interface: $phone_iface"
	[[ -z $phone_iface || -n $phone ]] || die "--phone-iface requires --phone"
	if [[ $profile != custom && $custom_seen == 1 ]]; then
		die "custom netem options require the custom profile"
	fi

	case $profile in
	clean) ;;
	5g) netem=(delay 10ms 2.5ms distribution normal loss 0.3%) ;;
	4g)
		netem=(delay 22.5ms 7.5ms distribution normal loss 1% reorder 0.5%)
		rate=30mbit
		;;
	wifi-slow)
		netem=(delay 5ms 12.5ms 25% distribution normal loss 2% 25% reorder 2% 25%)
		rate=20mbit
		;;
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
		# A rate-only custom profile still needs a child qdisc for a stable tree.
		((${#netem[@]} > 0)) || netem=(delay 0ms)
		;;
	*) die "unknown profile: $profile" ;;
	esac

	[[ -n $peer_iface ]] || peer_iface=$(route_iface "$peer")
	if [[ -n $phone && -z $phone_iface ]]; then
		phone_iface=$(route_iface "$phone")
	fi

	clear_impl "$peer_iface" "$phone_iface"
	if [[ $profile == clean ]]; then
		echo "active profile: clean"
		return 0
	fi
	if ((DRY_RUN)); then
		install_tree "$peer_iface" "$phone_iface" "$peer" "$phone" "$rate" "${netem[@]}"
		return 0
	fi

	# Persist and arm the fail-safe before changing packet flow. The watchdog's
	# clear waits on this process's lock if a very short TTL expires mid-apply.
	write_state "$profile" "$peer_iface" "$phone_iface" "$peer" "$phone" "$ttl" "$rate" "${netem[*]}"
	trap 'clear_impl "$peer_iface" "$phone_iface"' ERR
	arm_watchdog "$ttl"
	install_tree "$peer_iface" "$phone_iface" "$peer" "$phone" "$rate" "${netem[@]}"
	[[ $profile != blip-200ms ]] || start_blip
	trap - ERR
	echo "active profile: $profile (peer $peer, interface $peer_iface${phone_iface:+/$phone_iface}${ttl:+, ttl $ttl})"
}

show_physical_tree() {
	local dev=$1 root_parent=${2:-}
	echo "--- qdisc: $dev"
	tc -s qdisc show dev "$dev"
	echo "--- class: $dev"
	tc -s class show dev "$dev"
	echo "--- ingress filter: $dev"
	tc -s filter show dev "$dev" ingress
	if [[ -n $root_parent ]]; then
		echo "--- root filter: $dev"
		tc -s filter show dev "$dev" parent "$root_parent"
	fi
}

show_ifb_tree() {
	local dev=$1 parent=$2
	echo "--- qdisc: $dev"
	tc -s qdisc show dev "$dev"
	echo "--- class: $dev"
	tc -s class show dev "$dev"
	echo "--- filter: $dev"
	tc -s filter show dev "$dev" parent "$parent"
}

show_status() {
	local profile mode peer_iface phone_iface
	profile=$(state_value profile 2>/dev/null || echo clean)
	echo "active_profile=$profile"
	[[ $profile != clean ]] || return 0
	cat "$STATE_FILE"
	mode=$(state_value mode)
	peer_iface=$(state_value peer_iface)
	phone_iface=$(state_value phone_iface 2>/dev/null || true)
	if [[ $mode == single-interface ]]; then
		show_physical_tree "$peer_iface" 1:
	else
		show_physical_tree "$peer_iface"
		show_physical_tree "$phone_iface"
	fi
	if [[ $mode == dual-ingress ]]; then
		show_ifb_tree "$IFB_IMPAIR" 1:
	fi
	show_ifb_tree "$IFB_LAN" 2:
}

set_blip_delay() {
	local value=$1 key leg dev parent handle
	for key in leg_a leg_b; do
		leg=$(state_value "$key") || return 1
		read -r dev parent handle <<<"$leg"
		tc qdisc replace dev "$dev" parent "$parent" handle "${handle}:" netem delay "$value"
	done
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
	exec "$SELF" clear
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
	apply)
		(($# >= 2)) || die "apply requires a profile"
		shift
		apply_profile "$@"
		;;
	status)
		(($# == 1)) || die "status takes no options"
		show_status
		;;
	clear)
		(($# == 1)) || die "clear takes no options"
		clear_impl
		echo "active profile: clean"
		;;
	__blip)
		(($# == 1)) || exit 2
		blip_loop
		;;
	__watchdog)
		(($# == 2)) || exit 2
		watchdog_process "$2"
		;;
	-h | --help | help) usage ;;
	*)
		usage >&2
		exit 2
		;;
	esac
}

main "$@"
