#!/usr/bin/env bash
# PLE-483: adapted from PLE-473's capture.sh -- same roam-1200ms recipe to populate
# the stall row, but the popup's container is now PLE-483's custom PopupWindow
# (popup_stream_menu.xml, a ScrollView id=streamMenuScroll) instead of PopupMenu's
# internal ListView, so the scroll-search below looks for a ScrollView.
set -uo pipefail
REPO=/home/wnt/gta6
WS=${WS:-/home/wnt/gta6}
HERE=$WS/scripts/dev/ab
ADB=$REPO/scripts/dev/device-bin/adb
IMPAIR=$WS/scripts/net/impairctl.py
PKG=com.metallic.chiaki
OUT=${OUT_DIR:-}
FORCE=${FORCE:-0}
for arg in "$@"; do [ "$arg" = --force ] && FORCE=1; done
source "$(dirname "${BASH_SOURCE[0]}")/../lib/capture-guard.sh"
require_out_dir "$OUT" "$FORCE"
require_current_apk "$ADB" "$PKG" || exit 1
[ -x "$IMPAIR" ] || { echo "capture.sh: no impairctl.py at $IMPAIR (set WS=)" >&2; exit 1; }
PS5=${PS5:-192.168.1.164}
PS5_NAME=${PS5_NAME:-PS5-466}
export ANDROID_SERIAL=${ANDROID_SERIAL:-192.168.40.101:5555}
PHASE_SECONDS=${PHASE_SECONDS:-90}
source "$HERE/ui.sh"

log(){ printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
streaming(){ "$ADB" shell dumpsys activity activities | grep -q "topResumedActivity.*StreamActivity"; }
dump_win(){
  local remote=/data/local/tmp/chiaki-ple473-window.xml
  "$ADB" shell uiautomator dump "$remote" >/dev/null 2>&1
  "$ADB" exec-out cat "$remote" > "$1" 2>/dev/null
  "$ADB" shell rm -f "$remote" >/dev/null 2>&1
}
# PLE-483: if the popup's ScrollView (id=streamMenuScroll) still needs to scroll to
# reach the stall row, extract its bounds from a dump and print a swipe-up gesture
# inside it, or nothing if it isn't present / doesn't need scrolling.
scroll_popup_up(){
  python3 - "$1" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
m = re.search(r'resource-id="[^"]*id/streamMenuScroll"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', text)
if not m:
    sys.exit(1)
l, t, r, b = map(int, m.groups())
cx = (l + r) // 2
print(f"{cx} {b - 40} {cx} {t + 40}")
PY
}

safe_video_tap(){
  python3 - "$1" "$PKG" <<'PY'
import re, sys
xml_path, pkg = sys.argv[1], sys.argv[2]
text = open(xml_path, encoding="utf-8", errors="replace").read()
m = re.search(rf'resource-id="{re.escape(pkg)}:id/aspectRatioLayout"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', text)
if not m:
    sys.exit(1)
l, t, r, b = map(int, m.groups())
print(f"{(l + r) // 2} {t + 80}")
PY
}

cleanup(){
  local rc=$?
  trap - EXIT
  [ -z "${LOGCAT_PID:-}" ] || kill "$LOGCAT_PID" 2>/dev/null
  log "leaving stream and clearing impairment"
  streaming && "$ADB" shell input keyevent 4 && sleep 8
  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
  "$IMPAIR" clean --commit > "$OUT/net_final_clean.txt" 2>&1
  PLEIKKARI_ALLOW_DANGEROUS=1 "$ADB" shell wm user-rotation lock 0 >/dev/null 2>&1 || true
  "$ADB" shell settings put system accelerometer_rotation 1 >/dev/null 2>&1 || true
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

log "launch"
"$ADB" shell am start -n "$PKG/.main.MainActivity" >/dev/null
sleep 3
"$ADB" exec-out screencap -p > "$OUT/00_main.png"
log "tap play"
dump_win "$OUT/00_main_ui.xml"
coords=$(python3 "$HERE/ui_selector.py" "$OUT/00_main_ui.xml" "$PKG:id/playButton" "$PS5_NAME") || { log "playButton not found"; exit 3; }
# shellcheck disable=SC2086
"$ADB" shell input tap $coords

ok=0
for _ in $(seq 1 25); do sleep 2; if streaming; then ok=1; break; fi; done
if [ "$ok" != 1 ]; then
  "$ADB" exec-out screencap -p > "$OUT/fail_nostream.png"
  class=$(classify_connect_failure "$OUT/session_logcat.txt")
  log "no StreamActivity: $class"
  exit 3
fi
ok=0
for _ in $(seq 1 30); do
  sleep 2
  grep -q "Session has quit" "$OUT/session_logcat.txt" && { log "session quit during startup"; grep -m1 "Session quit:" "$OUT/session_logcat.txt"; exit 4; }
  [ "$(grep -c 'Feedback stats:' "$OUT/session_logcat.txt")" -ge 3 ] && { ok=1; break; }
done
[ "$ok" = 1 ] || { log "no Feedback stats line: is stream_feedback_stats_log on?"; exit 4; }
[ "$(grep -c 'Quality badge:' "$OUT/session_logcat.txt")" -ge 1 ] || {
  log "stats line present but no 'Quality badge:' line -- old build installed?"; exit 4; }
log "streaming with stats; settling 15 s"
sleep 15

log "apply roam-1200ms"
echo "PHASE_BEGIN 01_roam-1200ms $(date -u +%s.%N)" >> "$OUT/phases.txt"
"$IMPAIR" profile roam-1200ms --ttl $((PHASE_SECONDS*3+300))s --no-verify --commit > "$OUT/01_roam-1200ms_net.txt" 2>&1 || {
  log "apply roam-1200ms failed"; cat "$OUT/01_roam-1200ms_net.txt"; exit 5; }
# roam-1200ms pulses once every 20 s (PLE-404's dynamic-profile cadence); wait for two
# pulses before the first screenshot so the row has a real value to show.
sleep 45

# $1 = 0 (portrait) or 1 (landscape-left), $2 = tag
capture_orientation(){
  local rot=$1 tag=$2
  local dump_tag="${OUT}/${tag}"
  log "orientation $tag: lock rotation $rot"
  PLEIKKARI_ALLOW_DANGEROUS=1 "$ADB" shell wm user-rotation lock "$rot"
  sleep 3
  streaming || { log "stream dropped before $tag"; return 1; }

  dump_win "${dump_tag}_video.xml"
  local tap
  tap=$(safe_video_tap "${dump_tag}_video.xml") || { log "$tag: aspectRatioLayout not found"; return 2; }
  log "$tag: safe video tap point is '$tap'"

  local learned="" attempt
  for attempt in $(seq 1 8); do
    # shellcheck disable=SC2086
    "$ADB" shell input tap $tap >/dev/null 2>&1  # showOverlay()
    if dump_win "${dump_tag}_learn.xml" && coords=$(python3 "$HERE/ui_selector.py" "${dump_tag}_learn.xml" "$PKG:id/streamMenuButton" 2>/dev/null); then
      learned=$coords
      log "$tag: learned streamMenuButton coords '$learned' on attempt $attempt"
      break
    fi
    log "$tag: learn attempt $attempt missed, retrying"
  done
  [ -n "$learned" ] || { log "$tag: never learned streamMenuButton coords"; return 2; }

  sleep 4

  local opened=0
  for attempt in $(seq 1 10); do
    # shellcheck disable=SC2086
    "$ADB" shell input tap $tap >/dev/null 2>&1      # showOverlay()
    # shellcheck disable=SC2086
    "$ADB" shell input tap $learned >/dev/null 2>&1  # streamMenuButton, from cache
    sleep 1
    dump_win "${dump_tag}_ui.xml"
    if grep -q "Last stall: " "${dump_tag}_ui.xml" 2>/dev/null; then
      opened=1
      log "$tag: popup opened with a stall row on attempt $attempt"
      break
    fi
    if grep -q "Connection: " "${dump_tag}_ui.xml" 2>/dev/null; then
      log "$tag: open attempt $attempt has the popup but no stall row visible, trying a scroll"
      local swipe
      if swipe=$(scroll_popup_up "${dump_tag}_ui.xml"); then
        # shellcheck disable=SC2086
        "$ADB" shell input swipe $swipe 200 >/dev/null 2>&1
        sleep 1
        dump_win "${dump_tag}_ui.xml"
        if grep -q "Last stall: " "${dump_tag}_ui.xml" 2>/dev/null; then
          opened=1
          log "$tag: popup's stall row reached by scrolling on attempt $attempt"
          break
        fi
      fi
    else
      log "$tag: open attempt $attempt missed the popup entirely, retrying"
    fi
    "$ADB" shell input keyevent 4 >/dev/null 2>&1
    sleep 2
  done
  [ "$opened" = 1 ] || { log "$tag: never got a stall row, scrolled or not"; return 3; }

  "$ADB" exec-out screencap -p > "${dump_tag}.png"
  log "$tag: captured ${dump_tag}.png and ${dump_tag}_ui.xml"
  "$ADB" shell input keyevent 4 >/dev/null 2>&1
  sleep 1
  return 0
}

rc=0
capture_orientation 0 portrait || rc=$?
if [ $rc -eq 0 ]; then
  capture_orientation 1 landscape || rc=$?
fi
echo "PHASE_END 01_roam-1200ms $(date -u +%s.%N)" >> "$OUT/phases.txt"

[ -z "${LOGCAT_PID:-}" ] || kill "$LOGCAT_PID" 2>/dev/null

if [ $rc -eq 0 ]; then
  if ! REPO="$WS" verify_dynamic_profile_pattern "$OUT" > "$OUT/pattern_check.txt" 2>&1; then
    log "dynamic-profile pattern check failed"; cat "$OUT/pattern_check.txt"; rc=6
  fi
fi

log "capture complete rc=$rc"
exit $rc
