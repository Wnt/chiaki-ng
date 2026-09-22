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
# Scenario A (the real thing): PLE-422 added a devtools-only broadcast,
# ACTION_INJECT_QUIT_REASON, that feeds a QuitEvent straight into the live
# StreamSession through StreamSession.remoteEvent() -- the same entry point the
# PSN remote-session path already uses for events that did not originate in this
# process. From there it is indistinguishable from a real error: StreamSession
# posts StreamStateQuit, StreamActivity shows the real error dialog, and its
# Reconnect button calls the real reconnect() (viewModel.pause() + resume()).
# We inject quit_reason=10 (STREAM_CONNECTION_UNKNOWN, isError=true), tap
# Reconnect, and watch logcat for the decoder being torn down
# (AMediaCodec_delete / "Video decoder final output stats") and re-established
# (Initializing decoder / AMediaCodec_configure() succeeded), not merely for
# pixels to reappear. PLE-422's own run hit a known separate race (PLE-428):
# the first Reconnect can land while the console hasn't released the prior
# session yet ("Remote Play on Console is already in use"), which is not this
# ticket's fix -- retry Reconnect past it and record whether it reproduces.
#
# Scenario B (clean quit -> reconnect, the control): the in-app Quit flow (and the
# hardware/software Back key, which StreamActivity does not intercept -- it falls
# through dispatchKeyEvent to the default Activity back behavior, finish()) ends
# the session with QuitReason STOPPED (isError == false) and finish()es the whole
# Activity rather than offering a Reconnect button. The nearest available control
# is a fresh connect afterwards, which builds a brand-new Activity/ViewModel/
# StreamSession and so exercises the decoder's normal first-time init path, not
# the Reconnect-surface-reuse path -- useful as a "connecting still works" sanity
# check, not as evidence for the fix itself.
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
PS5_NAME=${PS5_NAME:-PS5-466}
source "$HERE/ui.sh"

log(){ printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
streaming(){ "$ADB" shell dumpsys activity activities | grep -q "topResumedActivity.*StreamActivity"; }
stats_count(){ grep -c "Feedback stats:" "$1" 2>/dev/null || true; }
decoder_init_count(){ grep -c "AMediaCodec_configure() succeeded" "$1" 2>/dev/null || true; }
decoder_teardown_count(){ grep -c "Video decoder final output stats" "$1" 2>/dev/null || true; }

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
"$ADB" shell am start -n "$PKG/com.metallic.chiaki.main.MainActivity" >/dev/null
sleep 3
"$ADB" exec-out screencap -p > "$OUT/00_main.png"

LOG="$OUT/session_logcat.txt"
"$ADB" logcat -c || true
"$ADB" logcat -v time > "$LOG" 2>/dev/null &
LOGCAT_PID=$!

log "tap $PS5_NAME to connect"
ui_tap_resource_id "$PKG:id/playButton" "$OUT/01_main_ui.xml" "$PS5_NAME" || { log "could not find $PS5_NAME tile"; exit 3; }

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
A_INIT_BEFORE=$(decoder_init_count "$LOG")

### Scenario A: inject a genuine error QuitReason, then Reconnect ############
log "scenario A: inject quit_reason=10 (STREAM_CONNECTION_UNKNOWN, isError=true)"
A_INJECT_LINE=$(wc -l < "$LOG")
"$ADB" shell am broadcast -a com.metallic.chiaki.debug.INJECT_QUIT_REASON --ei quit_reason 10 >/dev/null

# This injected path goes straight into StreamSession.remoteEvent() and posts
# StreamStateQuit at the Kotlin layer -- it does not run libchiaki's native
# session-teardown code, so the native "Session has quit"/"Session quit:" log
# lines (which the clean-quit control below does see) never appear here. The
# receiver's own log line is the correct signal that the injection was
# delivered and accepted.
ok=0
for _ in $(seq 1 20); do sleep 1; grep -q "PLE-422 devtools: injecting QuitEvent" "$LOG" && { ok=1; break; }; done
if [ "$ok" != 1 ]; then
  "$ADB" exec-out screencap -p > "$OUT/A_fail_noquit.png"
  echo "BLOCKED: injected quit_reason broadcast was never logged as received" > "$OUT/A_RESULT.txt"
  cat "$OUT/A_RESULT.txt"
  exit 4
fi
tail -n +$((A_INJECT_LINE+1)) "$LOG" | grep -m1 "PLE-422 devtools: injecting QuitEvent" > "$OUT/A_quit_reason.txt" || true
sleep 1
"$ADB" exec-out screencap -p > "$OUT/A_01_error_dialog.png"

log "scenario A: tap Reconnect (android:id/button1, the dialog's positive button)"
A_RECONNECT_LINE=$(wc -l < "$LOG")
"$ADB" shell uiautomator dump /data/local/tmp/chiaki-ab-window.xml >/dev/null
"$ADB" exec-out cat /data/local/tmp/chiaki-ab-window.xml > "$OUT/A_02_dialog_ui.xml"
"$ADB" shell rm /data/local/tmp/chiaki-ab-window.xml >/dev/null
RECONNECT_COORDS=$(python3 "$HERE/ui_selector.py" "$OUT/A_02_dialog_ui.xml" "android:id/button1")
# shellcheck disable=SC2086
"$ADB" shell input tap $RECONNECT_COORDS

# The old (pre-Reconnect) session keeps emitting "Feedback stats:" lines for a
# moment after the tap, until its own teardown actually runs -- counting stats
# from right after the tap is a false positive from the SESSION WE JUST LEFT,
# not evidence the new one is decoding. Anchor success to stats seen only
# after the most recent "Shutting down JNI Session" (full native teardown of
# whichever session most recently ended), which moves forward on this scenario's
# own teardown and again on any subsequent one.
#
# PLE-428: the first Reconnect can also race the console's own teardown and
# land on "Remote Play on Console is already in use"
# (ChiakiQuitReason SESSION_REQUEST_RP_IN_USE, logged as
# "reason=session_request_rp_in_use", isError=true) -- a separate, already-filed
# defect, not this ticket's fix. That raises its own error dialog with its own
# Reconnect button; detect and tap through it rather than mistaking it for a
# hang.
A_INUSE_SEEN=0
TAPPED_FOR_TEARDOWN_LINE=0
ok=0
# PLE-450: this loop retries the Reconnect tap past whatever the previous
# attempt hit, silently -- classify each retried-past failure with the same
# AvCap-window-aware classifier capture.sh's other retry loops use, and leave
# a durable record naming what was retried past instead of only the terminal
# already_in_use_seen flag in A_decoder_counts.txt.
RETRY_LOG="$OUT/A_retry_classifications.tsv"
[ -s "$RETRY_LOG" ] || printf 'utc\tteardown_line\tclass_code\tclassification\n' > "$RETRY_LOG"
for _ in $(seq 1 45); do
  sleep 2
  CUR_TEARDOWN_LINE=$(tail -n +$((A_RECONNECT_LINE+1)) "$LOG" | grep -n "Shutting down JNI Session" | tail -1 | cut -d: -f1)
  if [ -n "$CUR_TEARDOWN_LINE" ]; then
    CUR_TEARDOWN_LINE=$((CUR_TEARDOWN_LINE + A_RECONNECT_LINE))
    tail -n +$((CUR_TEARDOWN_LINE+1)) "$LOG" > "$OUT/A_post_reconnect_tail.txt"
    [ "$(grep -c "Feedback stats:" "$OUT/A_post_reconnect_tail.txt")" -ge 3 ] && { ok=1; break; }
    if [ "$CUR_TEARDOWN_LINE" != "$TAPPED_FOR_TEARDOWN_LINE" ]; then
      class=$(classify_connect_failure "$OUT/A_post_reconnect_tail.txt"); class_rc=$?
      [ "$class_rc" = 11 ] && A_INUSE_SEEN=1
      printf '%s\t%s\t%s\t%s\n' "$(date -u +%H:%M:%SZ)" "$CUR_TEARDOWN_LINE" "$class_rc" "$class" >> "$RETRY_LOG"
      "$ADB" shell uiautomator dump /data/local/tmp/chiaki-ab-window.xml >/dev/null 2>&1
      "$ADB" exec-out cat /data/local/tmp/chiaki-ab-window.xml > "$OUT/A_retry_at_line${CUR_TEARDOWN_LINE}_ui.xml" 2>/dev/null
      "$ADB" shell rm /data/local/tmp/chiaki-ab-window.xml >/dev/null 2>&1
      if RECONNECT_COORDS=$(python3 "$HERE/ui_selector.py" "$OUT/A_retry_at_line${CUR_TEARDOWN_LINE}_ui.xml" "android:id/button1" 2>/dev/null); then
        log "scenario A: new teardown at log line $CUR_TEARDOWN_LINE, tapping Reconnect again ($class)"
        # shellcheck disable=SC2086
        "$ADB" shell input tap $RECONNECT_COORDS
      fi
      TAPPED_FOR_TEARDOWN_LINE=$CUR_TEARDOWN_LINE
    fi
  fi
done

"$ADB" exec-out screencap -p > "$OUT/A_04_final.png"
A_INIT_AFTER=$(decoder_init_count "$LOG")
A_TEARDOWN_AFTER=$(decoder_teardown_count "$LOG")
{
  echo "already_in_use_seen=$A_INUSE_SEEN (PLE-428, not this ticket's fix)"
  echo "decoder_init_count_before=$A_INIT_BEFORE decoder_init_count_after=$A_INIT_AFTER"
  echo "decoder_teardown_count_total=$A_TEARDOWN_AFTER"
} > "$OUT/A_decoder_counts.txt"
cat "$OUT/A_decoder_counts.txt"
if [ "$ok" = 1 ] && [ "$A_INIT_AFTER" -gt "$A_INIT_BEFORE" ]; then
  echo "OK: Feedback stats resumed after injected-error Reconnect, and the decoder was re-initialized ($A_INIT_BEFORE -> $A_INIT_AFTER inits)" > "$OUT/A_RESULT.txt"
elif [ "$ok" = 1 ]; then
  echo "PARTIAL: Feedback stats resumed but no new decoder init was logged -- check A_decoder_counts.txt" > "$OUT/A_RESULT.txt"
else
  echo "BLOCKED: no Feedback stats after injected-error Reconnect -- black screen reproduced" > "$OUT/A_RESULT.txt"
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
"$ADB" shell am start -n "$PKG/com.metallic.chiaki.main.MainActivity" >/dev/null
sleep 2
BEFORE_B=$(stats_count "$LOG")
ui_tap_resource_id "$PKG:id/playButton" "$OUT/B_02_main_ui.xml" "$PS5_NAME" || { log "could not find $PS5_NAME tile for control connect"; exit 3; }
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
