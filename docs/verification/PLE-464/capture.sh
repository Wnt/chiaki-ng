#!/usr/bin/env bash
# PLE-464: one continuous LAN stream with `clean -> blip-200ms -> 4g -> wifi-slow ->
# roam-3000ms -> clean` stepped under it, and the badge's own 1 Hz "Quality badge:" line
# as the series. PLE-404's harness with the profile list widened; everything else is
# deliberately identical so this capture and PLE-366/PLE-411/PLE-404's are read the same
# way.
#
# Two questions in one run, because they need the same stream:
#   - does the badge stop reading GOOD through a total blackout (`roam-3000ms`)?
#   - does the new stall arm fire on anything that is merely slow or merely hitching
#     (`clean`, `blip-200ms`, `4g`, `wifi-slow`)? That is the bar PLE-423 had to clear.
#
# WS is /home/wnt/gta6 now: PLE-404 landed the roam-* profiles into the workspace repo,
# so there is no ticket worktree to point at. The rig's guest script must be in sync
# (`scripts/net/impairctl.py status --commit` prints IN SYNC / DRIFT) -- a stale one
# silently lacks the roam table.
set -uo pipefail
REPO=/home/wnt/gta6
WS=${WS:-/home/wnt/gta6}
HERE=$WS/scripts/dev/ab
ADB=$REPO/scripts/dev/device-bin/adb
IMPAIR=$WS/scripts/net/impairctl.py
PKG=fi.madekivi.pleikkari
OUT=${OUT_DIR:-}
FORCE=${FORCE:-0}
for arg in "$@"; do [ "$arg" = --force ] && FORCE=1; done
source "$(dirname "${BASH_SOURCE[0]}")/../lib/capture-guard.sh"
require_out_dir "$OUT" "$FORCE"
# PLE-410: refuse before spending any phone time if the installed APK predates the
# stats fields this capture measures.
require_current_apk "$ADB" "$PKG" || exit 1
[ -x "$IMPAIR" ] || { echo "capture.sh: no impairctl.py at $IMPAIR (set WS=)" >&2; exit 1; }
PS5=${PS5:-192.168.1.164}
PS5_NAME=${PS5_NAME:-PS5-466}
# The emulator is up on this box, so every adb call needs a serial (see LEARNINGS).
export ANDROID_SERIAL=${ANDROID_SERIAL:-192.168.40.101:5555}
# roam-1200ms fires once every 20 s, same cadence as blip-200ms, so a phase has to be
# several multiples of that to show the badge cycling rather than a single excursion.
PHASE_SECONDS=${PHASE_SECONDS:-120}
PROFILES=${PROFILES:-"clean blip-200ms 4g wifi-slow roam-3000ms clean"}
source "$HERE/ui.sh"

log(){ printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
streaming(){ "$ADB" shell dumpsys activity activities | grep -q "topResumedActivity.*StreamActivity"; }

cleanup(){
  local rc=$?
  trap - EXIT
  [ -z "${PING_PID:-}" ] || kill "$PING_PID" 2>/dev/null
  [ -z "${LOGCAT_PID:-}" ] || kill "$LOGCAT_PID" 2>/dev/null
  log "leaving stream and clearing impairment"
  # Back out of the stream and let the session close before force-stopping: force-stopping
  # mid-stream wedges the console's encoder for ~40 min (PS5 AvCap wedge).
  streaming && "$ADB" shell input keyevent 4 && sleep 8
  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
  "$IMPAIR" clean --commit > "$OUT/net_final_clean.txt" 2>&1
  exit $rc
}
trap cleanup EXIT

mkdir -p "$OUT"
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
if [ "$ok" != 1 ]; then
  "$ADB" exec-out screencap -p > "$OUT/fail_nostream.png"
  # PLE-450: name the failure instead of leaving "no StreamActivity" to be diagnosed
  # by hand later -- an AvCap wedge is console-side and means waiting, not retrying.
  class=$(classify_connect_failure "$OUT/session_logcat.txt")
  log "no StreamActivity: $class"
  exit 3
fi
# StreamActivity being topmost is not a live stream (PLE-356). Wait for the 1 Hz stats line
# the whole measurement is made of, and bail if the session quits.
ok=0
for _ in $(seq 1 30); do
  sleep 2
  grep -q "Session has quit" "$OUT/session_logcat.txt" && { log "session quit during startup"; grep -m1 "Session quit:" "$OUT/session_logcat.txt"; exit 4; }
  [ "$(grep -c 'Feedback stats:' "$OUT/session_logcat.txt")" -ge 3 ] && { ok=1; break; }
done
[ "$ok" = 1 ] || { log "no Feedback stats line: is stream_feedback_stats_log on?"; exit 4; }
[ "$(grep -c 'Quality badge:' "$OUT/session_logcat.txt")" -ge 1 ] || {
  log "stats line present but no 'Quality badge:' line -- old build installed?"; exit 4; }
log "streaming with stats; settling 20 s"
sleep 20

i=0
for p in $PROFILES; do
  i=$((i+1))
  tag=$(printf '%02d_%s' "$i" "$p")
  log "phase $tag: apply $p"
  echo "PHASE_BEGIN $tag $(date -u +%s.%N)" >> "$OUT/phases.txt"
  if [ "$p" = clean ]; then
    "$IMPAIR" clean --commit > "$OUT/${tag}_net.txt" 2>&1
  elif [ "$p" = blip-200ms ] || [ "$p" = roam-1200ms ] || [ "$p" = roam-3000ms ]; then
    # A dynamic profile's idle state is all-zero netem (`delay 0ms`, plus `loss 0%` for
    # roam-1200ms), and tc omits a zero clause from its own status output, so
    # verify_switch always reports it "missing" even while the loop is cycling
    # correctly. The loop's liveness is checked instead -- by impairctl's own
    # blip_loop_alive read-back at apply time, and by the pattern check below.
    "$IMPAIR" profile "$p" --ttl $((PHASE_SECONDS*3+300))s --no-verify --commit > "$OUT/${tag}_net.txt" 2>&1
  else
    "$IMPAIR" profile "$p" --ttl $((PHASE_SECONDS*3+300))s --commit > "$OUT/${tag}_net.txt" 2>&1
  fi || { log "apply $p failed"; cat "$OUT/${tag}_net.txt"; exit 5; }
  sleep "$PHASE_SECONDS"
  if ! streaming || grep -q "Session has quit" "$OUT/session_logcat.txt"; then
    log "stream dropped during $tag"; "$ADB" exec-out screencap -p > "$OUT/${tag}_fail.png"; break
  fi
  "$ADB" exec-out screencap -p > "$OUT/${tag}_overlay.png"
  echo "PHASE_END $tag $(date -u +%s.%N)" >> "$OUT/phases.txt"
  log "phase $tag done"
done
# PLE-418: a dynamic profile's remote cycling loop can die mid-capture with nothing
# reporting it; verify the raw samples actually show the pattern each phase claims --
# and, since PLE-404, that the excursions are the *width* this profile's pulse implies,
# so a capture claiming a 1.2 s outage cannot pass on a 200 ms hitch's data. The guard
# reads the profile table from WS for the same reason capture.sh does.
if ! REPO="$WS" verify_dynamic_profile_pattern "$OUT" > "$OUT/pattern_check.txt" 2>&1; then
  log "dynamic-profile pattern check failed"; cat "$OUT/pattern_check.txt"; exit 6
fi
log "capture complete"
