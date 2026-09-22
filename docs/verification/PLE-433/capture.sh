#!/usr/bin/env bash
# PLE-433: does "Reconnect" after a genuine link-watchdog timeout (PLE-423's real
# network-cut quit, reason=stream_connection_timeout) bring video back, the way
# PLE-393 proved it does for an injected generic error quit?
#
# PLE-393 answered this for quit_reason=10 (STREAM_CONNECTION_UNKNOWN), injected
# via the PLE-422 devtools broadcast straight into StreamSession.remoteEvent().
# That is not the same code path as a real watchdog timeout: PLE-423 showed the
# watchdog quit originates natively (streamconnection.c's link-watchdog check ->
# chiaki_session_send_event(..., is_error=true) -> the real "Session has quit"/
# "Session quit: reason=stream_connection_timeout" lines), not from the injection
# hook. This ticket exists because that native path was never combined with a
# Reconnect tap on hardware.
#
# Procedure: connect for real, cut the network 100% at the rig (PLE-423's
# MODE=loss), wait for the native watchdog quit and its error dialog, restore a
# clean network (the console must be reachable again for Reconnect to have any
# chance), tap Reconnect, and use PLE-393's anchored-to-teardown method to decide
# whether the new session's decoder is really re-established (not just stale
# "Feedback stats:" lines from the session being left) -- including tolerating
# and logging a PLE-428 rp_in_use race if the console hasn't released the prior
# session yet.
set -uo pipefail
REPO=/home/wnt/gta6
HERE=$REPO/scripts/dev/ab
ADB=$REPO/scripts/dev/device-bin/adb
IMPAIR=$REPO/scripts/net/impairctl.py
PKG=fi.madekivi.pleikkari
OUT=${OUT_DIR:-}
FORCE=${FORCE:-0}
for arg in "$@"; do [ "$arg" = --force ] && FORCE=1; done
source "$(dirname "${BASH_SOURCE[0]}")/../lib/capture-guard.sh"
require_out_dir "$OUT" "$FORCE"
require_current_apk "$ADB" "$PKG" || exit 1
PS5=${PS5:-192.168.1.164}
PS5_NAME=${PS5_NAME:-PS5-466}
export ANDROID_SERIAL=${ANDROID_SERIAL:-192.168.40.101:5555}
QUIT_WAIT_SECONDS=${QUIT_WAIT_SECONDS:-90}
RECONNECT_WAIT_SECONDS=${RECONNECT_WAIT_SECONDS:-90}
source "$HERE/ui.sh"

log(){ printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
streaming(){ "$ADB" shell dumpsys activity activities | grep -q "topResumedActivity.*StreamActivity"; }
stats_count(){ grep -c "Feedback stats:" "$1" 2>/dev/null || true; }
decoder_init_count(){ grep -c "AMediaCodec_configure() succeeded" "$1" 2>/dev/null || true; }

mkdir -p "$OUT"

cleanup(){
  local rc=$?
  trap - EXIT
  [ -z "${LOGCAT_PID:-}" ] || kill "$LOGCAT_PID" 2>/dev/null
  log "clearing impairment first, so the stream/console can be left cleanly"
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

log "start clean"
"$IMPAIR" clean --commit > "$OUT/net_start_clean.txt" 2>&1
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
"$ADB" shell input keyevent 224 >/dev/null 2>&1   # WAKEUP
sleep 1
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true

LOG="$OUT/session_logcat.txt"
"$ADB" logcat -c || true
"$ADB" logcat -v time > "$LOG" 2>/dev/null &
LOGCAT_PID=$!

log "launch, tap $PS5_NAME to connect"
"$ADB" shell am start -n "$PKG/com.metallic.chiaki.main.MainActivity" >/dev/null
sleep 3
"$ADB" exec-out screencap -p > "$OUT/00_main.png"
ui_tap_resource_id "$PKG:id/playButton" "$OUT/00_main_ui.xml" "$PS5_NAME" || { log "could not find $PS5_NAME tile"; exit 3; }

ok=0
for _ in $(seq 1 25); do sleep 2; if streaming; then ok=1; break; fi; done
[ "$ok" = 1 ] || { "$ADB" exec-out screencap -p > "$OUT/fail_nostream.png"; log "StreamActivity never became topmost"; exit 3; }

ok=0
for _ in $(seq 1 30); do
  sleep 2
  grep -q "Session has quit" "$LOG" && { log "session quit during startup"; grep -m1 "Session quit:" "$LOG"; exit 4; }
  [ "$(stats_count "$LOG")" -ge 3 ] && { ok=1; break; }
done
[ "$ok" = 1 ] || { log "no Feedback stats line during startup: is stream_feedback_stats_log on?"; exit 4; }
log "streaming confirmed; settling 20s"
sleep 20
"$ADB" exec-out screencap -p > "$OUT/01_streaming.png"
INIT_BEFORE=$(decoder_init_count "$LOG")

### Induce a genuine watchdog timeout: total loss at the rig (PLE-423 MODE=loss) ##
log "cutting the network at the rig: 100% loss both directions"
CUT_EPOCH=$(date -u +%s.%N)
echo "CUT $CUT_EPOCH" >> "$OUT/phases.txt"
"$IMPAIR" profile custom --loss 100% --ttl $((QUIT_WAIT_SECONDS+300))s --commit > "$OUT/02_net_cut.txt" 2>&1 \
  || { log "applying total loss failed"; cat "$OUT/02_net_cut.txt"; exit 5; }

quit=0
for _ in $(seq 1 "$QUIT_WAIT_SECONDS"); do
  sleep 1
  if grep -q "Session has quit" "$LOG"; then quit=1; break; fi
done
QUIT_EPOCH=$(date -u +%s.%N)
echo "QUIT_OBSERVED $QUIT_EPOCH" >> "$OUT/phases.txt"
if [ "$quit" != 1 ]; then
  log "NO QUIT within ${QUIT_WAIT_SECONDS}s -- the watchdog did not fire"
  "$ADB" exec-out screencap -p > "$OUT/02_no_quit.png"
  exit 6
fi
log "session quit $(python3 -c "print(f'{$QUIT_EPOCH-$CUT_EPOCH:.1f}')")s after the cut (poll resolution 1 s)"
grep -m1 "link watchdog" "$LOG" | tee "$OUT/02_watchdog_line.txt"
QUIT_LINE_TEXT=$(grep -m1 "Session quit:" "$LOG" | tee "$OUT/02_quit_reason.txt")
case "$QUIT_LINE_TEXT" in
  *reason=stream_connection_timeout*) : ;;
  *) log "WARNING: quit reason was not stream_connection_timeout -- '$QUIT_LINE_TEXT'; this run is not exercising the watchdog path this ticket is about" ;;
esac
sleep 3
"$ADB" exec-out screencap -p > "$OUT/03_error_dialog.png"

log "restoring a clean network so the console is reachable for Reconnect"
"$IMPAIR" clean --commit > "$OUT/04_net_clean.txt" 2>&1
sleep 5
"$ADB" exec-out screencap -p > "$OUT/04_error_dialog_clean_net.png"

### Tap Reconnect, anchored to the most recent native teardown (PLE-393 method) ##
log "tap Reconnect (android:id/button1, the dialog's positive button)"
RECONNECT_LINE=$(wc -l < "$LOG")
"$ADB" shell uiautomator dump /data/local/tmp/chiaki-ple433-window.xml >/dev/null
"$ADB" exec-out cat /data/local/tmp/chiaki-ple433-window.xml > "$OUT/05_dialog_ui.xml"
"$ADB" shell rm /data/local/tmp/chiaki-ple433-window.xml >/dev/null
RECONNECT_COORDS=$(python3 "$HERE/ui_selector.py" "$OUT/05_dialog_ui.xml" "android:id/button1") \
  || { log "could not find Reconnect button in dialog dump"; exit 7; }
# shellcheck disable=SC2086
"$ADB" shell input tap $RECONNECT_COORDS

# The old (pre-Reconnect) session can keep emitting "Feedback stats:" for a moment
# after the tap, until its own teardown actually runs -- anchor success to stats
# seen only after the most recent "Shutting down JNI Session" line, which moves
# forward on this scenario's own teardown and again on any PLE-428 retry.
INUSE_SEEN=0
TAPPED_FOR_TEARDOWN_LINE=0
ok=0
RETRY_LOG="$OUT/retry_classifications.tsv"
printf 'utc\tteardown_line\tclass_code\tclassification\n' > "$RETRY_LOG"
for _ in $(seq 1 $((RECONNECT_WAIT_SECONDS/2))); do
  sleep 2
  CUR_TEARDOWN_LINE=$(tail -n +$((RECONNECT_LINE+1)) "$LOG" | grep -n "Shutting down JNI Session" | tail -1 | cut -d: -f1)
  if [ -n "$CUR_TEARDOWN_LINE" ]; then
    CUR_TEARDOWN_LINE=$((CUR_TEARDOWN_LINE + RECONNECT_LINE))
    tail -n +$((CUR_TEARDOWN_LINE+1)) "$LOG" > "$OUT/post_reconnect_tail.txt"
    [ "$(grep -c "Feedback stats:" "$OUT/post_reconnect_tail.txt")" -ge 3 ] && { ok=1; break; }
    if [ "$CUR_TEARDOWN_LINE" != "$TAPPED_FOR_TEARDOWN_LINE" ]; then
      class=$(classify_connect_failure "$OUT/post_reconnect_tail.txt"); class_rc=$?
      [ "$class_rc" = 11 ] && INUSE_SEEN=1
      printf '%s\t%s\t%s\t%s\n' "$(date -u +%H:%M:%SZ)" "$CUR_TEARDOWN_LINE" "$class_rc" "$class" >> "$RETRY_LOG"
      "$ADB" shell uiautomator dump /data/local/tmp/chiaki-ple433-window.xml >/dev/null 2>&1
      "$ADB" exec-out cat /data/local/tmp/chiaki-ple433-window.xml > "$OUT/retry_at_line${CUR_TEARDOWN_LINE}_ui.xml" 2>/dev/null
      "$ADB" shell rm /data/local/tmp/chiaki-ple433-window.xml >/dev/null 2>&1
      if RECONNECT_COORDS=$(python3 "$HERE/ui_selector.py" "$OUT/retry_at_line${CUR_TEARDOWN_LINE}_ui.xml" "android:id/button1" 2>/dev/null); then
        log "new teardown at log line $CUR_TEARDOWN_LINE, tapping Reconnect again ($class)"
        # shellcheck disable=SC2086
        "$ADB" shell input tap $RECONNECT_COORDS
      fi
      TAPPED_FOR_TEARDOWN_LINE=$CUR_TEARDOWN_LINE
    fi
  fi
done

"$ADB" exec-out screencap -p > "$OUT/06_final.png"
INIT_AFTER=$(decoder_init_count "$LOG")
{
  echo "already_in_use_seen=$INUSE_SEEN (PLE-428, not this ticket's fix)"
  echo "decoder_init_count_before=$INIT_BEFORE decoder_init_count_after=$INIT_AFTER"
} > "$OUT/decoder_counts.txt"
cat "$OUT/decoder_counts.txt"
if [ "$ok" = 1 ] && [ "$INIT_AFTER" -gt "$INIT_BEFORE" ]; then
  echo "OK: Feedback stats resumed after watchdog-timeout Reconnect, and the decoder was re-initialized ($INIT_BEFORE -> $INIT_AFTER inits)" > "$OUT/RESULT.txt"
elif [ "$ok" = 1 ]; then
  echo "PARTIAL: Feedback stats resumed but no new decoder init was logged -- check decoder_counts.txt" > "$OUT/RESULT.txt"
else
  echo "BLOCKED: no Feedback stats after watchdog-timeout Reconnect -- black screen reproduced" > "$OUT/RESULT.txt"
fi
cat "$OUT/RESULT.txt"

log "capture complete"
[ "$ok" = 1 ] && [ "$INIT_AFTER" -gt "$INIT_BEFORE" ]
