#!/usr/bin/env bash
# PLE-393: verify on the Samsung S22 Ultra that commit 1acc5ab0 (PLE-384) actually
# fixes "Reconnect after an error quit shows a black screen".
#
# What 1acc5ab0 changed in StreamSession.kt (the part this ticket verifies):
#   - shutdown() used to set `surface = null` unconditionally. Every onPause()
#     (unless isChangingConfigurations) and every "Reconnect" button press calls
#     pause() -> shutdown(), so the very next resume() found surface == null and
#     never called session.setSurface(), decoding into nothing.
#   - shutdown() now leaves `surface` alone -- it belongs to the SurfaceView, whose
#     own holder callbacks (surfaceDestroyed) are the only thing that should clear
#     it -- and resume()/attachRemoteSession() guard with
#     `surface?.takeIf { it.isValid }` so a genuinely dead surface is still not
#     reused blindly.
#   - The video-decoder.c side of the same commit (surface_lost_idr_pending) is a
#     different code path: it fires when the SAME decoder survives a real
#     surfaceDestroyed/surfaceCreated cycle (a DeX display move), not when Reconnect
#     tears down the whole Session and builds a fresh decoder. It does not apply to
#     the scenario this ticket verifies and no log line from it is expected below.
#
# Scenario A (substitute for "error quit -> Reconnect"): a genuine error QuitReason
# turned out to be impractical to induce on this fork within a reasonable test
# window -- see docs/verification/PLE-393/README.md for the network-loss attempt
# and why it wedges the session (Takion "Bad file descriptor", heartbeat send
# failures forever) rather than raising a QuitEvent. The substitute used instead is
# a screen-off/screen-on pause/resume cycle: StreamActivity.onPause() (unless
# isChangingConfigurations) calls viewModel.pause() -> StreamSession.shutdown(),
# and onResume() calls viewModel.resume() -- the exact same two functions the
# error-quit dialog's Reconnect button calls (reconnect() = viewModel.pause();
# viewModel.resume()), on the exact same live SurfaceView/surface, same Activity
# instance. It does not produce a StreamStateQuit or show the Reconnect dialog,
# but it exercises the regressed and fixed lines directly.
#
# Scenario B (clean quit -> reconnect, the control): the in-app Quit flow (and the
# hardware/software Back key, which StreamActivity does not intercept -- it falls
# through dispatchKeyEvent to the default Activity back behavior, finish()) ends
# the session with QuitReason STOPPED (isError == false) and finish()es the whole
# Activity rather than offering a Reconnect button. There is no in-Activity control
# equivalent to Scenario A's pause/resume; the nearest available control is a fresh
# connect afterwards, which builds a brand-new Activity/ViewModel/StreamSession and
# so does not exercise the fixed lines at all -- it only confirms connecting still
# works.
set -uo pipefail
REPO=/home/wnt/gta6
HERE=$REPO/scripts/dev/ab
ADB=$REPO/scripts/dev/device-bin/adb
PKG=com.metallic.chiaki
OUT=${OUT_DIR:-}
FORCE=${FORCE:-0}
for arg in "$@"; do [ "$arg" = --force ] && FORCE=1; done
source "$(dirname "${BASH_SOURCE[0]}")/../lib/capture-guard.sh"
require_out_dir "$OUT" "$FORCE"
source "$HERE/ui.sh"

log(){ printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
streaming(){ "$ADB" shell dumpsys activity activities | grep -q "topResumedActivity.*StreamActivity"; }
stats_count(){ grep -c "Feedback stats:" "$1" 2>/dev/null || true; }

mkdir -p "$OUT"

cleanup(){
  local rc=$?
  trap - EXIT
  [ -z "${LOGCAT_PID:-}" ] || kill "$LOGCAT_PID" 2>/dev/null
  exit $rc
}
trap cleanup EXIT

log "wake, dismiss keyguard, launch"
"$ADB" shell input keyevent 224 >/dev/null 2>&1   # WAKEUP
sleep 1
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"$ADB" shell am start -n "$PKG/.main.MainActivity" >/dev/null
sleep 3
"$ADB" exec-out screencap -p > "$OUT/00_main.png"

LOG="$OUT/session_logcat.txt"
"$ADB" logcat -c || true
"$ADB" logcat -v time > "$LOG" 2>/dev/null &
LOGCAT_PID=$!

log "tap PS5-466 to connect"
ui_tap_resource_id "$PKG:id/playButton" "$OUT/01_main_ui.xml" "PS5-466" || { log "could not find PS5-466 tile"; exit 3; }

ok=0
for _ in $(seq 1 25); do sleep 2; if streaming; then ok=1; break; fi; done
[ "$ok" = 1 ] || { "$ADB" exec-out screencap -p > "$OUT/fail_nostream.png"; log "StreamActivity never became topmost"; exit 3; }

ok=0
for _ in $(seq 1 30); do
  sleep 2
  grep -q "Session has quit" "$LOG" && { log "session quit during startup"; grep -m1 "Session quit:" "$LOG"; exit 4; }
  [ "$(stats_count "$LOG")" -ge 3 ] && { ok=1; break; }
done
[ "$ok" = 1 ] || { log "no Feedback stats line during startup"; exit 4; }
log "streaming confirmed; settling 10s"
sleep 10
"$ADB" exec-out screencap -p > "$OUT/02_streaming_before_A.png"

### Scenario A: screen-off/on pause/resume (substitute; see header) ###########
# Same Activity instance, same Intent extras -- no risk of StreamActivity being
# recreated without its ConnectInfo (which a Home-button + `am start` relaunch
# would risk, since StreamActivity has no singleTask/singleTop launch mode).
# Turning the screen off takes the window out of focus and not visible, which
# drives onPause() (isChangingConfigurations is false) exactly as backgrounding
# the app would; turning it back on and dismissing the keyguard drives onResume().
log "scenario A: screen off (StreamActivity.onPause -> viewModel.pause -> shutdown)"
A_PAUSE_LINE=$(wc -l < "$LOG")
"$ADB" shell input keyevent 26   # POWER (screen off)
sleep 5
"$ADB" shell dumpsys activity activities | grep -m1 topResumedActivity > "$OUT/A_01_topactivity.txt"

log "scenario A: screen on, dismiss keyguard (onResume -> viewModel.resume)"
"$ADB" shell input keyevent 224   # WAKEUP
sleep 1
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
ok=0
for _ in $(seq 1 20); do sleep 1; if streaming; then ok=1; break; fi; done
if [ "$ok" != 1 ]; then
  "$ADB" exec-out screencap -p > "$OUT/A_fail_nostream.png"
  echo "BLOCKED: StreamActivity did not return to foreground after Home+relaunch" > "$OUT/A_RESULT.txt"
else
  ok=0
  for _ in $(seq 1 30); do
    sleep 2
    tail -n +$((A_PAUSE_LINE+1)) "$LOG" > "$OUT/A_post_resume_tail.txt"
    [ "$(grep -c "Feedback stats:" "$OUT/A_post_resume_tail.txt")" -ge 3 ] && { ok=1; break; }
  done
  "$ADB" exec-out screencap -p > "$OUT/A_02_after_resume.png"
  if [ "$ok" = 1 ]; then
    echo "OK: Feedback stats resumed after Home+relaunch (post-pause tail: $OUT/A_post_resume_tail.txt)" > "$OUT/A_RESULT.txt"
  else
    echo "BLOCKED: no Feedback stats after Home+relaunch -- black screen reproduced" > "$OUT/A_RESULT.txt"
  fi
fi
cat "$OUT/A_RESULT.txt"

### Scenario B: clean quit -> fresh connect (control) #########################
log "scenario B: leaving via the hardware Back key (StreamActivity does not intercept it; falls through to finish())"
B_QUIT_LINE=$(wc -l < "$LOG")
if streaming; then
  "$ADB" shell input keyevent 4   # BACK
fi
ok=0
for _ in $(seq 1 15); do sleep 1; streaming || { ok=1; break; }; done
[ "$ok" = 1 ] || log "WARNING: StreamActivity still topmost after Back"
"$ADB" exec-out screencap -p > "$OUT/B_01_back_at_list.png"
tail -n +$((B_QUIT_LINE+1)) "$LOG" | grep -m1 "Session quit:" > "$OUT/B_quit_reason.txt" || true

log "scenario B control: fresh connect from the console list"
"$ADB" shell am start -n "$PKG/.main.MainActivity" >/dev/null
sleep 2
BEFORE_B=$(stats_count "$LOG")
ui_tap_resource_id "$PKG:id/playButton" "$OUT/B_02_main_ui.xml" "PS5-466" || { log "could not find PS5-466 tile for control connect"; exit 3; }
ok=0
for _ in $(seq 1 25); do sleep 2; if streaming; then ok=1; break; fi; done
[ "$ok" = 1 ] || { "$ADB" exec-out screencap -p > "$OUT/B_fail_nostream.png"; echo "BLOCKED: control connect never reached StreamActivity" > "$OUT/B_RESULT.txt"; }
ok=0
for _ in $(seq 1 30); do
  sleep 2
  NOW=$(stats_count "$LOG")
  [ "$((NOW-BEFORE_B))" -ge 3 ] && { ok=1; break; }
done
"$ADB" exec-out screencap -p > "$OUT/B_03_control_streaming.png"
if [ "$ok" = 1 ]; then
  echo "OK: Feedback stats present on the fresh connect (control)" > "$OUT/B_RESULT.txt"
else
  echo "BLOCKED: no Feedback stats on the fresh connect (control)" > "$OUT/B_RESULT.txt"
fi
cat "$OUT/B_RESULT.txt"

log "leaving the stream cleanly (Back key)"
if streaming; then
  "$ADB" shell input keyevent 4
  sleep 2
fi

log "capture complete"
