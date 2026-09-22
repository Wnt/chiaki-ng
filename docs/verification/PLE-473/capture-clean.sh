#!/usr/bin/env bash
# PLE-473: one real stream under roam-1200ms and a screenshot of the connection-info
# popup's new "Last stall" row, in both orientations. PLE-464/476 established that
# roam-1200ms's 1.2 s outage reaches the badge's own stall arm (measured worst gap
# 1130 ms, comfortably above STALL_MS=500); this reuses that profile to put a real
# value on the row this ticket adds, following PLE-382's popup-opening recipe
# (streamMenuButton's PopupMenu does not auto-hide, only opening it races the
# overlay's auto-hide timeout) and PLE-464's impairment-under-stream harness.
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
# PLE-473: landscape's 1440px window fits six read-only rows with almost no margin
# (PLE-383's fixture already measured the six-row popup at 1430.667 of 1440 px) --
# this ticket's seventh row overflows it. The list is a scrollable ListView
# (confirmed live: scrollable="true" in the uiautomator dump), so the row is
# reachable, just not visible without a swipe in landscape. Extracts the popup
# ListView's bounds from a dump and prints a swipe-up gesture inside it, or
# nothing if no scrollable list is present.
scroll_popup_up(){
  python3 - "$1" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
m = re.search(r'class="android\.widget\.ListView"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', text)
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
"$ADB" shell am start -n "$PKG/fi.madekivi.pleikkari.main.MainActivity" >/dev/null
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

log "staying clean -- no impairment applied"
sleep 20

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
    if grep -q "Connection: " "${dump_tag}_ui.xml" 2>/dev/null; then
      opened=1
      log "$tag: popup opened (clean-session check) on attempt $attempt"
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
  [ "$opened" = 1 ] || { log "$tag: never got the popup"; return 3; }

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
[ -z "${LOGCAT_PID:-}" ] || kill "$LOGCAT_PID" 2>/dev/null

log "capture complete rc=$rc"
exit $rc
