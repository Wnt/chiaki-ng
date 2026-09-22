#!/usr/bin/env bash
# PLE-425: gather evidence on whether any non-config-change onPause() ends the
# stream. Runs a matrix of backgrounding scenarios against a live stream and
# records, for each: whether StreamActivity finished, whether it was silent or
# showed the error dialog, and the relevant logcat lines
# (Session quit / Stop JNI Session / Recreating the stream window).
#
# This ticket changes no behaviour -- capture.sh only observes.
set -uo pipefail
REPO=/home/wnt/gta6
WS=${WS:-/home/wnt/gta6}
HERE=$WS/scripts/dev/ab
ADB=$REPO/scripts/dev/device-bin/adb
PKG=fi.madekivi.pleikkari
OUT=${OUT_DIR:-}
FORCE=${FORCE:-0}
for arg in "$@"; do [ "$arg" = --force ] && FORCE=1; done
source "$(dirname "${BASH_SOURCE[0]}")/../lib/capture-guard.sh"
require_out_dir "$OUT" "$FORCE"
require_current_apk "$ADB" "$PKG" || exit 1
PS5_NAME=${PS5_NAME:-PS5-466}
export ANDROID_SERIAL=${ANDROID_SERIAL:-192.168.40.101:5555}
source "$HERE/ui.sh"

log(){ printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "$OUT/run.log"; }
mark(){ "$ADB" shell log -t PLE425 "$1"; log "MARK: $1"; }
streaming(){ "$ADB" shell dumpsys activity activities | grep -q "topResumedActivity.*StreamActivity"; }
resumed_component(){ "$ADB" shell dumpsys activity activities | grep -m1 "mResumedActivity" | sed -E 's/^[[:space:]]*//'; }

cleanup(){
  local rc=$?
  trap - EXIT
  [ -z "${LOGCAT_PID:-}" ] || kill "$LOGCAT_PID" 2>/dev/null
  log "leaving stream and clearing state"
  streaming && "$ADB" shell input keyevent 4 && sleep 8
  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
  exit $rc
}
trap cleanup EXIT

mkdir -p "$OUT"
: > "$OUT/run.log"

start_stream(){
  local tag=$1
  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
  "$ADB" shell input keyevent 224 >/dev/null 2>&1
  sleep 1
  "$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  "$ADB" shell am start -n "$PKG/com.metallic.chiaki.main.MainActivity" >/dev/null
  sleep 3
  "$ADB" exec-out screencap -p > "$OUT/${tag}_00_main.png"
  ui_tap_resource_id "$PKG:id/playButton" "$OUT/${tag}_00_main_ui.xml" "$PS5_NAME" || return 3
  local ok=0
  for _ in $(seq 1 25); do sleep 2; if streaming; then ok=1; break; fi; done
  [ "$ok" = 1 ] || return 3
  ok=0
  for _ in $(seq 1 30); do
    sleep 2
    tail -n 400 "$OUT/session_logcat.txt" | grep -q "Session has quit" && return 4
    [ "$(grep -c 'Feedback stats:' "$OUT/session_logcat.txt")" -ge 3 ] && { ok=1; break; }
  done
  [ "$ok" = 1 ] || return 4
  log "$tag: streaming with stats; settling 8s"
  sleep 8
  return 0
}

"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
"$ADB" logcat -c || true
"$ADB" logcat -v time > "$OUT/session_logcat.txt" 2>/dev/null &
LOGCAT_PID=$!
sleep 1

# result table, one row per case, written as we go
RESULT_TSV="$OUT/results.tsv"
echo -e "case\ttested_or_reasoned\tpause_called\tsession_ended\tsilent_or_dialog\tresumed_component_after" > "$RESULT_TSV"

record_state(){
  local tag=$1
  "$ADB" exec-out screencap -p > "$OUT/${tag}_after.png"
  resumed_component > "$OUT/${tag}_resumed.txt"
}

# ---------- Case: screen off / on ----------
log "=== case: screen off/on ==="
start_stream "A" || { log "A: could not reach a live stream, exit $?"; exit 3; }
mark "PLE425 case=screen_off BEGIN"
"$ADB" shell input keyevent 26   # POWER: screen off
sleep 6
mark "PLE425 case=screen_off screen_is_off"
"$ADB" shell input keyevent 26   # POWER: screen on
sleep 2
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
sleep 3
mark "PLE425 case=screen_off END"
record_state "A_screen_off_on"
sleep 2

# ---------- Case: notification shade pulldown ----------
log "=== case: notification shade ==="
start_stream "B" || { log "B: could not reach a live stream, exit $?"; exit 3; }
mark "PLE425 case=shade BEGIN"
"$ADB" shell cmd statusbar expand-notifications
sleep 4
mark "PLE425 case=shade expanded"
"$ADB" shell cmd statusbar collapse
sleep 3
mark "PLE425 case=shade END"
record_state "B_shade"
sleep 2

# ---------- Case: another app takes foreground briefly ----------
log "=== case: another app foreground ==="
start_stream "C" || { log "C: could not reach a live stream, exit $?"; exit 3; }
mark "PLE425 case=other_app BEGIN"
"$ADB" shell am start -n com.android.settings/.Settings >/dev/null
sleep 4
mark "PLE425 case=other_app settings_foreground"
"$ADB" exec-out screencap -p > "$OUT/C_during_settings.png"
"$ADB" shell input keyevent 4   # Back out of Settings
sleep 3
mark "PLE425 case=other_app END"
record_state "C_other_app"
sleep 2

# ---------- Case: recents switcher ----------
log "=== case: recents switcher ==="
start_stream "D" || { log "D: could not reach a live stream, exit $?"; exit 3; }
mark "PLE425 case=recents BEGIN"
"$ADB" shell input keyevent 187  # APP_SWITCH
sleep 4
mark "PLE425 case=recents shown"
"$ADB" exec-out screencap -p > "$OUT/D_during_recents.png"
"$ADB" shell input keyevent 4    # dismiss recents
sleep 3
mark "PLE425 case=recents END"
record_state "D_recents"
sleep 2

# ---------- Case: rotation (genuine config change, in-place per manifest) ----------
log "=== case: rotation ==="
start_stream "E" || { log "E: could not reach a live stream, exit $?"; exit 3; }
"$ADB" shell settings put system accelerometer_rotation 0
mark "PLE425 case=rotation BEGIN"
"$ADB" shell settings put system user_rotation 1   # 90 degrees
sleep 4
mark "PLE425 case=rotation rotated"
"$ADB" exec-out screencap -p > "$OUT/E_rotated.png"
"$ADB" shell settings put system user_rotation 0
sleep 4
mark "PLE425 case=rotation END"
record_state "E_rotation"
"$ADB" shell settings put system accelerometer_rotation 1
sleep 2

log "=== case: DeX attach (PLE-384 path) ==="
DEX_DISPLAYS=$("$ADB" shell dumpsys display | grep -c "^  Display Id=")
echo "PLE-425: DeX not exercised -- no external display / DeX dongle attached to this rig. dumpsys display shows $DEX_DISPLAYS display id line(s) (1 == internal panel only). Reasoned from 1acc5ab0's own diff instead of observed: onPause's isChangingConfigurations branch is what the DeX density step (docs verification PLE-384) relies on; this rig cannot exercise it." | tee "$OUT/F_dex_not_tested.txt"

log "capture complete; see run.log, results.tsv (manual verdicts filled from logcat), and per-case screenshots/xml"
