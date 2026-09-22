#!/usr/bin/env bash
# PLE-198: wait until the SKIN sensor is back at or below a target temperature with
# mStatus=0, so every session starts from a matched baseline. Bounded to fit a Bash call.
#
#   device.py run PLE-198 -- cooldown.sh <target_celsius> [max_seconds]
#
# Exit 0 when cool, 1 when max_seconds ran out (call again).
set -euo pipefail
target=$1; max=${2:-540}
ADB=/home/wnt/gta6/scripts/dev/device-bin/adb
: "${ANDROID_SERIAL:?}"
end=$(( $(date +%s) + max ))
while :; do
	line=$("$ADB" shell dumpsys thermalservice | grep -m1 'mName=SKIN')
	t=$(sed -E 's/.*mValue=([0-9.]+).*/\1/' <<<"$line")
	s=$(sed -E 's/.*mStatus=([0-9]+).*/\1/' <<<"$line")
	printf '[%s] SKIN %s C status %s (target <= %s)\n' "$(date -u +%H:%M:%S)" "$t" "$s" "$target"
	if [ "$s" = 0 ] && awk -v t="$t" -v g="$target" 'BEGIN{exit !(t <= g)}'; then exit 0; fi
	[ "$(date +%s)" -lt "$end" ] || exit 1
	sleep 30
done
