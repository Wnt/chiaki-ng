#!/usr/bin/env bash
# PLE-382: PLE-371's acceptance criterion 4 was never taken (phone queue was busy).
# This starts one real stream and captures the streamMenuButton popup's disabled
# connection-info rows -- Connection mode/peer/MTU/RTT -- in both orientations.
#
# The rows live in a PopupMenu (StreamActivity.kt:938 showDisplayModeMenu), not
# the auto-hiding chip PLE-352 dealt with: once the popup is open it does not
# auto-dismiss on its own. The hazard is entirely in *opening* it -- the
# streamMenuButton itself sits in the overlay dock, which auto-hides
# HIDE_UI_TIMEOUT_MS (3.5 s) after last shown, and a bare `uiautomator dump`
# takes ~2 s. So: learn the button's coordinates once per orientation with a
# dump while the dock happens to still be visible, cache them, then drive the
# real open from the cached coordinates with no dump in between, retrying with
# a check that the popup's connection-info text actually landed.
#
# A second, undocumented hazard found live on the S22: in landscape the
# DefaultTouchControlsFragment's touch-capture layer covers the *entire*
# video area (aspectRatioLayout spans the full screen there), so a tap
# anywhere in the screen's vertical middle is consumed as gamepad/touchpad
# input to the console instead of reaching aspectRatioLayout's click ->
# showOverlay(). One stray tap at screen-centre during exploration actually
# navigated the PS5's own home-screen focus (harmless, but a real side
# effect against shared hardware). In portrait the video occupies only the
# top ~26% of the screen with no controls drawn over it, so any point in it
# is safe. The fix used here: always tap near aspectRatioLayout's *top* edge
# (top + 80px, horizontally centred) -- clear of the control graphics in
# both orientations on this device -- rather than the layout's vertical
# centre.
set -uo pipefail
REPO=/home/wnt/gta6
HERE=$REPO/scripts/dev/ab
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

log(){ printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
streaming(){ "$ADB" shell dumpsys activity activities | grep -q "topResumedActivity.*StreamActivity"; }
dump_win(){ # $1 = local path to save the uiautomator dump to
  local remote=/data/local/tmp/chiaki-ple382-window.xml
  "$ADB" shell uiautomator dump "$remote" >/dev/null 2>&1
  "$ADB" exec-out cat "$remote" > "$1" 2>/dev/null
  "$ADB" shell rm -f "$remote" >/dev/null 2>&1
}
# Extracts "cx top+80" for the aspectRatioLayout node in a dump, or nothing.
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
  # Back out of the stream before force-stopping: force-stopping mid-stream
  # wedges the console's encoder (PS5 AvCap wedge, PLE-385).
  streaming && "$ADB" shell input keyevent 4 && sleep 8
  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
  PLEIKKARI_ALLOW_DANGEROUS=1 "$ADB" shell wm user-rotation lock 0 >/dev/null 2>&1 || true
  "$ADB" shell settings put system accelerometer_rotation 1 >/dev/null 2>&1 || true
  exit $rc
}
trap cleanup EXIT

mkdir -p "$OUT"
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
"$ADB" shell input keyevent 224 >/dev/null 2>&1   # WAKEUP
sleep 1
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"$ADB" logcat -c || true
"$ADB" logcat -v time > "$OUT/session_logcat.txt" 2>/dev/null &
LOGCAT_PID=$!

log "launch"
"$ADB" shell am start -n "$PKG/com.metallic.chiaki.main.MainActivity" >/dev/null
sleep 3
"$ADB" exec-out screencap -p > "$OUT/00_main.png"
log "tap play"
dump_win "$OUT/00_main_ui.xml"
coords=$(python3 "$HERE/ui_selector.py" "$OUT/00_main_ui.xml" "$PKG:id/playButton" "$PS5_NAME") || { log "playButton not found"; exit 3; }
# shellcheck disable=SC2086
"$ADB" shell input tap $coords

ok=0
for _ in $(seq 1 25); do sleep 2; if streaming; then ok=1; break; fi; done
[ "$ok" = 1 ] || { "$ADB" exec-out screencap -p > "$OUT/fail_nostream.png"; log "no StreamActivity"; exit 3; }
ok=0
for _ in $(seq 1 20); do
  sleep 2
  grep -q "Session has quit" "$OUT/session_logcat.txt" && { log "session quit during startup"; grep -m1 "Session quit:" "$OUT/session_logcat.txt"; exit 4; }
  "$ADB" shell dumpsys activity activities | grep -q "topResumedActivity.*StreamActivity" && { ok=1; break; }
done
[ "$ok" = 1 ] || { log "StreamActivity never became top"; exit 4; }
log "streaming; settling 25 s for connection-mode/RTT to populate"
sleep 25

# $1 = 0 (portrait) or 1 (landscape-left), $2 = tag
capture_orientation(){
  local rot=$1 tag=$2
  local dump_tag="${OUT}/${tag}"
  log "orientation $tag: lock rotation $rot"
  PLEIKKARI_ALLOW_DANGEROUS=1 "$ADB" shell wm user-rotation lock "$rot"
  sleep 3
  streaming || { log "stream dropped before $tag"; return 1; }

  # video bounds don't disappear on their own, so this dump isn't time-critical
  dump_win "${dump_tag}_video.xml"
  local tap
  tap=$(safe_video_tap "${dump_tag}_video.xml") || { log "$tag: aspectRatioLayout not found"; return 2; }
  log "$tag: safe video tap point is '$tap'"

  # --- learn the streamMenuButton's coordinates once for this orientation ---
  local learned="" attempt
  for attempt in $(seq 1 8); do
    # shellcheck disable=SC2086
    "$ADB" shell input tap $tap >/dev/null 2>&1  # showOverlay()
    if dump_win "${dump_tag}_learn.xml" && coords=$(python3 "$HERE/ui_selector.py" "${dump_tag}_learn.xml" "$PKG:id/streamMenuButton" 2>/dev/null); then
      learned=$coords
      log "$tag: learned streamMenuButton coords '$learned' on attempt $attempt"
      break
    fi
    log "$tag: learn attempt $attempt missed (overlay likely hidden by dump time), retrying"
  done
  [ -n "$learned" ] || { log "$tag: never learned streamMenuButton coords"; return 2; }

  # let the overlay auto-hide once so the real pass starts from the same
  # "just tapped video" state the learn loop needed, not a stale open popup
  sleep 4

  # --- drive the real open from cached coordinates, no dump in between ---
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
      log "$tag: popup opened with connection-info rows on attempt $attempt"
      break
    fi
    log "$tag: open attempt $attempt missed, retrying"
    "$ADB" shell input keyevent 4 >/dev/null 2>&1  # back, in case a stale popup/menu is up
    sleep 1
  done
  [ "$opened" = 1 ] || { log "$tag: never got the popup with connection-info text"; return 3; }

  "$ADB" exec-out screencap -p > "${dump_tag}.png"
  log "$tag: captured ${dump_tag}.png and ${dump_tag}_ui.xml"
  "$ADB" shell input keyevent 4 >/dev/null 2>&1  # dismiss popup
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
