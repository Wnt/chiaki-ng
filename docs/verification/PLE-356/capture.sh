#!/usr/bin/env bash
# PLE-356: one continuous LAN stream, impairment profile stepped underneath it,
# phone-side ping as ground truth throughout. Everything else (senkusha rtt_ms,
# console_rtt_raw, probe_rtt_ms) comes from the 1 Hz "Feedback stats" line.
set -uo pipefail
REPO=/home/wnt/gta6
HERE=$REPO/scripts/dev/ab
ADB=$REPO/scripts/dev/device-bin/adb
IMPAIR=$REPO/scripts/net/impairctl.py
PKG=com.metallic.chiaki
OUT=${OUT_DIR:-$REPO/build/captures/ple356}
PS5=192.168.1.164
PHASE_SECONDS=${PHASE_SECONDS:-60}
PROFILES=${PROFILES:-"clean 5g wifi-slow clean blip-200ms clean"}
source "$HERE/ui.sh"

log(){ printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
streaming(){ "$ADB" shell dumpsys activity activities | grep -q "topResumedActivity.*StreamActivity"; }

cleanup(){
  local rc=$?
  trap - EXIT
  [ -z "${PING_PID:-}" ] || kill "$PING_PID" 2>/dev/null
  [ -z "${LOGCAT_PID:-}" ] || kill "$LOGCAT_PID" 2>/dev/null
  log "leaving stream and clearing impairment"
  streaming && "$ADB" shell input keyevent 4 && sleep 6
  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
  "$IMPAIR" clean --commit > "$OUT/net_final_clean.txt" 2>&1
  exit $rc
}
trap cleanup EXIT

mkdir -p "$OUT"
log "start clean"
"$IMPAIR" clean --commit > "$OUT/net_start_clean.txt" 2>&1

# fresh app, screen awake
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
"$ADB" shell input keyevent 224 >/dev/null 2>&1   # WAKEUP
sleep 1
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"$ADB" logcat -c || true
"$ADB" logcat -v time > "$OUT/session_logcat.txt" 2>/dev/null &
LOGCAT_PID=$!
"$ADB" shell "ping -D -i 1 -w 3000 $PS5" > "$OUT/phone_ping.txt" 2>&1 &
PING_PID=$!

log "launch"
"$ADB" shell am start -n "$PKG/.main.MainActivity" >/dev/null
sleep 3
PLEIKKARI_ALLOW_DANGEROUS=1 "$ADB" shell wm user-rotation lock 1
sleep 3
"$ADB" exec-out screencap -p > "$OUT/00_main.png"
log "tap play"
ui_tap_resource_id "$PKG:id/playButton" "$OUT/00_main_ui.xml" "PS5-466" || exit 3
ok=0
for _ in $(seq 1 25); do sleep 2; if streaming; then ok=1; break; fi; done
[ "$ok" = 1 ] || { "$ADB" exec-out screencap -p > "$OUT/fail_nostream.png"; log "no StreamActivity"; exit 3; }
for _ in $(seq 1 30); do
  [ "$(grep -c 'Takion received init ack' "$OUT/session_logcat.txt")" -ge 2 ] && break
  sleep 2
done
# PLE-356: StreamActivity being topmost is not a live stream. A run wasted seven
# minutes stepping profiles under a session the console had killed two seconds in
# ("Nagare did not init! AvCap failed to initialize video: [InitResult:-6]", the
# previous aborted session's encoder still held). Wait for the 1 Hz stats line the
# whole measurement is made of, and bail if the session quits.
ok=0
for _ in $(seq 1 30); do
  sleep 2
  grep -q "Session has quit" "$OUT/session_logcat.txt" && { log "session quit during startup"; grep -m1 "Session quit:" "$OUT/session_logcat.txt"; exit 4; }
  [ "$(grep -c 'Feedback stats:' "$OUT/session_logcat.txt")" -ge 3 ] && { ok=1; break; }
done
[ "$ok" = 1 ] || { log "no Feedback stats line: is stream_feedback_stats_log on?"; exit 4; }
log "streaming with stats; settling 20 s"
sleep 20

i=0
for p in $PROFILES; do
  i=$((i+1))
  tag=$(printf '%02d_%s' "$i" "$p")
  log "phase $tag: apply $p"
  echo "PHASE_BEGIN $tag $(date -u +%s.%N)" >> "$OUT/phases.txt"
  # `profile clean` verifies by reading back the ingress ifb, which the clean
  # profile has just deleted; the `clean` subcommand is the supported way there.
  if [ "$p" = clean ]; then
    "$IMPAIR" clean --commit > "$OUT/${tag}_net.txt" 2>&1
  elif [ "$p" = blip-200ms ]; then
    # blip-200ms's steady state is `delay 0ms`, which tc omits from its own status
    # output, so verify_switch always reports "delay: missing, expected 0ms" even
    # though the blip loop is running -- same class as PLE-354's false negative.
    # A phone ping under it reads 14 ms baseline with 206/207/409 ms spikes, so the
    # profile is genuinely in force. It is also entered from clean, because
    # switching into it from wifi-slow leaves that profile's 20mbit rate cap on.
    "$IMPAIR" profile "$p" --ttl $((PHASE_SECONDS*3+300))s --no-verify --commit > "$OUT/${tag}_net.txt" 2>&1
  else
    "$IMPAIR" profile "$p" --ttl $((PHASE_SECONDS*3+300))s --commit > "$OUT/${tag}_net.txt" 2>&1
  fi || { log "apply $p failed"; cat "$OUT/${tag}_net.txt"; exit 5; }
  # let the badge's 5 s fast window and 30 s slow window follow the step
  sleep "$PHASE_SECONDS"
  if ! streaming || grep -q "Session has quit" "$OUT/session_logcat.txt"; then
    log "stream dropped during $tag"; "$ADB" exec-out screencap -p > "$OUT/${tag}_fail.png"; break
  fi
  "$ADB" exec-out screencap -p > "$OUT/${tag}_overlay.png"
  echo "PHASE_END $tag $(date -u +%s.%N)" >> "$OUT/phases.txt"
  log "phase $tag done"
done
log "capture complete"
