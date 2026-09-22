#!/usr/bin/env bash
# PLE-423: prove the link watchdog both ways on real hardware.
#
#   MODE=impairment  one continuous LAN stream stepped through clean -> blip-200ms ->
#                    4g -> wifi-slow -> clean. The watchdog must never fire, and the
#                    1 Hz "StreamConnection link:" line gives the worst inbound gap
#                    each profile actually produced -- the evidence that sets
#                    CHIAKI_LINK_WATCHDOG_TIMEOUT_MS.
#   MODE=loss        one stream on a clean network, then total symmetric loss applied
#                    at the rig. The session must quit with an error inside the bound.
#
# The network is cut at the rig, never on the phone: impair.sh exempts tcp/5555 in both
# directions, so adb and logcat keep working through a 100% loss profile and the phone's
# Wi-Fi settings are never touched.
set -uo pipefail
REPO=/home/wnt/gta6
HERE=$REPO/scripts/dev/ab
ADB=$REPO/scripts/dev/device-bin/adb
IMPAIR=$REPO/scripts/net/impairctl.py
PKG=fi.madekivi.pleikkari
OUT=${OUT_DIR:-}
MODE=${MODE:-impairment}
FORCE=${FORCE:-0}
for arg in "$@"; do [ "$arg" = --force ] && FORCE=1; done
source "$(dirname "${BASH_SOURCE[0]}")/../lib/capture-guard.sh"
require_out_dir "$OUT" "$FORCE"
require_current_apk "$ADB" "$PKG" || exit 1
PS5=${PS5:-192.168.1.164}
PS5_NAME=${PS5_NAME:-PS5-466}
export ANDROID_SERIAL=${ANDROID_SERIAL:-192.168.40.101:5555}
PHASE_SECONDS=${PHASE_SECONDS:-120}
PROFILES=${PROFILES:-"clean blip-200ms 4g wifi-slow clean"}
# How long to wait for the quit after the cut. The watchdog's own limit is 10 s and the
# poll is 1 Hz, so anything past ~15 s is a failure, not a slow pass; 90 s is generous
# enough to record how long a genuine hang lasts instead of just timing out on it.
QUIT_WAIT_SECONDS=${QUIT_WAIT_SECONDS:-90}
source "$HERE/ui.sh"

log(){ printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
streaming(){ "$ADB" shell dumpsys activity activities | grep -q "topResumedActivity.*StreamActivity"; }

cleanup(){
  local rc=$?
  trap - EXIT
  [ -z "${PING_PID:-}" ] || kill "$PING_PID" 2>/dev/null
  [ -z "${LOGCAT_PID:-}" ] || kill "$LOGCAT_PID" 2>/dev/null
  log "clearing impairment first, so the stream can be left cleanly"
  "$IMPAIR" clean --commit > "$OUT/net_final_clean.txt" 2>&1
  sleep 3
  # Back out of the stream and let the session close before force-stopping: force-stopping
  # mid-stream wedges the console's encoder for ~40 min (PS5 AvCap wedge).
  streaming && "$ADB" shell input keyevent 4 && sleep 8
  streaming && "$ADB" shell input keyevent 4 && sleep 8
  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
  exit $rc
}
trap cleanup EXIT

mkdir -p "$OUT"
echo "mode=$MODE profiles=$PROFILES phase_seconds=$PHASE_SECONDS" > "$OUT/params.txt"
log "start clean"
"$IMPAIR" clean --commit > "$OUT/net_start_clean.txt" 2>&1

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
"$ADB" shell am start -n "$PKG/com.metallic.chiaki.main.MainActivity" >/dev/null
sleep 3
PLEIKKARI_ALLOW_DANGEROUS=1 "$ADB" shell wm user-rotation lock 1
sleep 3
"$ADB" exec-out screencap -p > "$OUT/00_main.png"
log "tap play"
ui_tap_resource_id "$PKG:id/playButton" "$OUT/00_main_ui.xml" "$PS5_NAME" || exit 3
ok=0
for _ in $(seq 1 25); do sleep 2; if streaming; then ok=1; break; fi; done
[ "$ok" = 1 ] || { "$ADB" exec-out screencap -p > "$OUT/fail_nostream.png"; log "no StreamActivity"; exit 3; }
# StreamActivity being topmost is not a live stream (PLE-356). Wait for the 1 Hz stats line
# the whole measurement is made of, and bail if the session quits.
ok=0
for _ in $(seq 1 30); do
  sleep 2
  grep -q "Session has quit" "$OUT/session_logcat.txt" && { log "session quit during startup"; grep -m1 "Session quit:" "$OUT/session_logcat.txt"; exit 4; }
  [ "$(grep -c 'Feedback stats:' "$OUT/session_logcat.txt")" -ge 3 ] && { ok=1; break; }
done
[ "$ok" = 1 ] || { log "no Feedback stats line: is stream_feedback_stats_log on?"; exit 4; }
# The watchdog's own 1 Hz diagnostics line rides the same pref. Without it there is
# nothing to measure and the capture would look complete while proving nothing.
[ "$(grep -c 'StreamConnection link:' "$OUT/session_logcat.txt")" -ge 1 ] || {
  log "stats line present but no 'StreamConnection link:' line -- pre-PLE-423 build installed?"; exit 4; }
log "streaming with stats; settling 20 s"
sleep 20

if [ "$MODE" = loss ]; then
  echo "PHASE_BEGIN 01_clean $(date -u +%s.%N)" >> "$OUT/phases.txt"
  sleep 30
  echo "PHASE_END 01_clean $(date -u +%s.%N)" >> "$OUT/phases.txt"
  "$ADB" exec-out screencap -p > "$OUT/01_streaming.png"
  log "cutting the network at the rig: 100% loss both directions"
  CUT_EPOCH=$(date -u +%s.%N)
  echo "CUT $CUT_EPOCH" >> "$OUT/phases.txt"
  "$IMPAIR" profile custom --loss 100% --ttl $((QUIT_WAIT_SECONDS+300))s --commit > "$OUT/02_net_cut.txt" 2>&1 \
    || { log "applying total loss failed"; cat "$OUT/02_net_cut.txt"; exit 5; }
  quit=0
  for _ in $(seq 1 "$QUIT_WAIT_SECONDS"); do
    sleep 1
    if grep -q "Session has quit" "$OUT/session_logcat.txt"; then quit=1; break; fi
  done
  QUIT_EPOCH=$(date -u +%s.%N)
  echo "QUIT_OBSERVED $QUIT_EPOCH" >> "$OUT/phases.txt"
  if [ "$quit" != 1 ]; then
    log "NO QUIT within ${QUIT_WAIT_SECONDS}s -- the wedge is still there"
    "$ADB" exec-out screencap -p > "$OUT/02_no_quit.png"
    exit 6
  fi
  log "session quit $(python3 -c "print(f'{$QUIT_EPOCH-$CUT_EPOCH:.1f}')")s after the cut (poll resolution 1 s)"
  grep -m1 "link watchdog" "$OUT/session_logcat.txt" | tee "$OUT/02_watchdog_line.txt"
  grep -m1 "Session quit:" "$OUT/session_logcat.txt" | tee "$OUT/02_quit_reason.txt"
  sleep 3
  "$ADB" exec-out screencap -p > "$OUT/03_error_dialog.png"
  log "restoring a clean network so the error dialog's Reconnect is usable"
  "$IMPAIR" clean --commit > "$OUT/04_net_clean.txt" 2>&1
  sleep 3
  "$ADB" exec-out screencap -p > "$OUT/04_error_dialog_clean_net.png"
  log "capture complete"
  exit 0
fi

i=0
for p in $PROFILES; do
  i=$((i+1))
  tag=$(printf '%02d_%s' "$i" "$p")
  log "phase $tag: apply $p"
  echo "PHASE_BEGIN $tag $(date -u +%s.%N)" >> "$OUT/phases.txt"
  if [ "$p" = clean ]; then
    "$IMPAIR" clean --commit > "$OUT/${tag}_net.txt" 2>&1
  elif [ "$p" = blip-200ms ]; then
    # blip-200ms's steady state is `delay 0ms`, which tc omits from its own status output,
    # so verify_switch always reports "delay: missing, expected 0ms" even though the blip
    # loop is running (PLE-356's note).
    "$IMPAIR" profile "$p" --ttl $((PHASE_SECONDS*6+600))s --no-verify --commit > "$OUT/${tag}_net.txt" 2>&1
  else
    "$IMPAIR" profile "$p" --ttl $((PHASE_SECONDS*6+600))s --commit > "$OUT/${tag}_net.txt" 2>&1
  fi || { log "apply $p failed"; cat "$OUT/${tag}_net.txt"; exit 5; }
  sleep "$PHASE_SECONDS"
  if ! streaming || grep -q "Session has quit" "$OUT/session_logcat.txt"; then
    log "STREAM DROPPED during $tag -- this is the regression this capture exists to catch"
    "$ADB" exec-out screencap -p > "$OUT/${tag}_fail.png"
    grep -m1 "Session quit:" "$OUT/session_logcat.txt" | tee "$OUT/${tag}_quit_reason.txt"
    echo "PHASE_END $tag $(date -u +%s.%N)" >> "$OUT/phases.txt"
    exit 7
  fi
  "$ADB" exec-out screencap -p > "$OUT/${tag}_overlay.png"
  echo "PHASE_END $tag $(date -u +%s.%N)" >> "$OUT/phases.txt"
  log "phase $tag done"
done
# PLE-418: a dynamic profile's remote cycling loop can die mid-capture with nothing
# reporting it; verify the raw samples show the pattern each phase claims.
if ! verify_dynamic_profile_pattern "$OUT" > "$OUT/pattern_check.txt" 2>&1; then
  log "dynamic-profile pattern check failed"; cat "$OUT/pattern_check.txt"; exit 6
fi
log "capture complete"
