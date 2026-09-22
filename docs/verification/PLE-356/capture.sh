#!/usr/bin/env bash
# PLE-356: one continuous LAN stream, impairment profile stepped underneath it,
# phone-side ping as ground truth throughout. Everything else (senkusha rtt_ms,
# console_rtt_raw, probe_rtt_ms) comes from the 1 Hz "Feedback stats" line.
#
# PLE-367: cleanup leaves the stream through the in-app Quit dialog instead of
# force-stopping it. A force-stop kills the session without a Disconnect, and
# PLE-356 then hit "AvCap failed to initialize video: [InitResult:-6]" on
# every following attempt. PLE-357 later hit the same wedge with NO force-stop
# involved -- a clean back-button exit still wedged the next connect, and a
# bare retry ~60s later cleared it. So force-stop is not the sole cause, and
# this script does not claim to have found the trigger; it removes force-stop
# from the path (defect fix, always on) and adds a retry (the mitigation the
# evidence actually supports).
set -uo pipefail
REPO=/home/wnt/gta6
HERE=$REPO/scripts/dev/ab
ADB=$REPO/scripts/dev/device-bin/adb
# PLE-378: the fork used to carry its own dead scripts/net/impairctl.py (120
# lines, no profile/clean/--commit/--no-verify/verify_switch); this must
# resolve the workspace's live 327-line one, never fall back into the fork.
IMPAIR=${IMPAIR:-$REPO/scripts/net/impairctl.py}
[ -x "$IMPAIR" ] || { echo "capture.sh: impairctl.py not found or not executable at $IMPAIR (set IMPAIR= to override)" >&2; exit 1; }
PKG=fi.madekivi.pleikkari
OUT=${OUT_DIR:-}
FORCE=${FORCE:-0}
for arg in "$@"; do [ "$arg" = --force ] && FORCE=1; done
source "$(dirname "${BASH_SOURCE[0]}")/../lib/capture-guard.sh"
require_out_dir "$OUT" "$FORCE"
# PLE-410: refuse before spending any phone time if the installed APK
# predates the stats fields this capture measures.
require_current_apk "$ADB" "$PKG" || exit 1
PS5=${PS5:-192.168.1.164}
PS5_NAME=${PS5_NAME:-PS5-466}
PHASE_SECONDS=${PHASE_SECONDS:-60}
PROFILES=${PROFILES:-"clean 5g wifi-slow clean blip-200ms clean"}
source "$HERE/ui.sh"

log(){ printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
streaming(){ "$ADB" shell dumpsys activity activities | grep -q "topResumedActivity.*StreamActivity"; }

# PLE-367: leave through the in-app Quit dialog (quitButton -> confirm), never
# `keyevent 4` + force-stop. StreamActivity runs edge-to-edge with
# BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE, and a `streamTouchpadView` sits on top
# of the video surface to capture touchpad gestures -- a plain tap on the video
# area is consumed as touchpad input, not a click, and never reaches
# aspectRatioLayout's listener. The overlay (and quitButton on it) only
# appears via the same edge-swipe a real user would use to reveal the system
# bars, and it auto-hides again after 3.5 s (HIDE_UI_TIMEOUT_MS in
# StreamActivity.kt), so the swipe and the quitButton tap must happen back to
# back. No force-stop anywhere below.
exit_stream_gracefully(){
  streaming || return 0
  local tries cur
  for tries in 1 2; do
    # A session error (e.g. AvCap init failure) raises its own dialog first
    # ("Session has quit: ...", Reconnect=button1 / Quit=button2), which sits
    # on top of and hides the normal overlay/quitButton. Its Quit button calls
    # the same finish() the manual path does, so prefer it when present
    # instead of trying to reach a quitButton the dialog is covering.
    if ui_tap_resource_id "android:id/button2" "$OUT/exit_${tries}_autodialog.xml" "Quit" 2>/dev/null; then
      :
    else
      cur=$("$ADB" shell dumpsys window displays | grep -m1 -o 'cur=[0-9]*x[0-9]*' | cut -d= -f2)
      "$ADB" shell input swipe $((${cur%x*}/2)) 5 $((${cur%x*}/2)) $((${cur#*x}/3)) 200
      if ui_tap_resource_id "$PKG:id/quitButton" "$OUT/exit_${tries}_quit.xml" 2>/dev/null; then
        sleep 1
        ui_tap_resource_id "android:id/button1" "$OUT/exit_${tries}_confirm.xml" "Quit" 2>/dev/null || true
      fi
    fi
    for _ in $(seq 1 10); do sleep 1; streaming || return 0; done
    streaming || return 0
    log "in-app exit attempt $tries did not clear StreamActivity; retrying"
  done
  return 1
}

cleanup(){
  local rc=$?
  trap - EXIT
  [ -z "${PING_PID:-}" ] || kill "$PING_PID" 2>/dev/null
  [ -z "${LOGCAT_PID:-}" ] || kill "$LOGCAT_PID" 2>/dev/null
  log "leaving stream via in-app exit (no force-stop)"
  if ! exit_stream_gracefully; then
    log "WARNING: could not confirm the in-app exit cleared StreamActivity; leaving the app as-is rather than force-stopping"
  fi
  "$IMPAIR" clean --commit > "$OUT/net_final_clean.txt" 2>&1
  exit $rc
}
trap cleanup EXIT

mkdir -p "$OUT"
log "start clean"
"$IMPAIR" clean --commit > "$OUT/net_start_clean.txt" 2>&1

# fresh app, screen awake. This force-stop targets an idle app before the
# first launch, not a live session -- unrelated to the cleanup force-stop
# PLE-367 removed above.
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
"$ADB" shell input keyevent 224 >/dev/null 2>&1   # WAKEUP
sleep 1
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"$ADB" shell "ping -D -i 1 -w 3000 $PS5" > "$OUT/phone_ping.txt" 2>&1 &
PING_PID=$!

# PLE-367: retry the connect step once. PLE-357's evidence is that the same
# InitResult:-6 wedge cleared on a bare retry ~60s later, which turns a dead
# run into a delayed one. PLE-356's guard is reused unchanged as the pass/fail
# gate for each attempt: three `Feedback stats:` lines to declare success,
# `Session has quit` to abort early instead of waiting out the full timeout.
connect_attempt(){
  local n=$1 out=$2
  "$ADB" logcat -c || true
  "$ADB" logcat -v time > "$out" 2>/dev/null &
  LOGCAT_PID=$!
  log "launch (attempt $n)"
  "$ADB" shell am start -n "$PKG/com.metallic.chiaki.main.MainActivity" >/dev/null
  sleep 3
  PLEIKKARI_ALLOW_DANGEROUS=1 "$ADB" shell wm user-rotation lock 1
  sleep 3
  "$ADB" exec-out screencap -p > "$OUT/00_main_attempt${n}.png"
  log "tap play (attempt $n)"
  ui_tap_resource_id "$PKG:id/playButton" "$OUT/00_main_ui_attempt${n}.xml" "$PS5_NAME" || return 3
  local ok=0
  for _ in $(seq 1 25); do sleep 2; if streaming; then ok=1; break; fi; done
  [ "$ok" = 1 ] || { "$ADB" exec-out screencap -p > "$OUT/fail_nostream_attempt${n}.png"; log "no StreamActivity (attempt $n)"; return 3; }
  for _ in $(seq 1 30); do
    [ "$(grep -c 'Takion received init ack' "$out")" -ge 2 ] && break
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
    grep -q "Session has quit" "$out" && { log "session quit during startup (attempt $n)"; grep -m1 "Session quit:" "$out"; return 4; }
    [ "$(grep -c 'Feedback stats:' "$out")" -ge 3 ] && { ok=1; break; }
  done
  [ "$ok" = 1 ] || { log "no Feedback stats line (attempt $n): is stream_feedback_stats_log on?"; return 4; }
  return 0
}

# PLE-450: retrying past a failed connect must not be silent. Classify the
# failed attempt's own log (wedge-probe.sh's contract via capture-guard.sh)
# and append it to a durable record before retrying, so an AvCap wedge that
# clears on retry still leaves evidence it happened.
RETRY_LOG="$OUT/connect_retries.tsv"
[ -s "$RETRY_LOG" ] || printf 'utc\tfailed_attempt\tclass_code\tclassification\n' > "$RETRY_LOG"

SESSION_LOG="$OUT/session_logcat.txt"
connected=0
prev_attempt_log=""
for attempt in 1 2; do
  if [ "$attempt" -gt 1 ]; then
    class=$(classify_connect_failure "$prev_attempt_log"); class_rc=$?
    printf '%s\t%s\t%s\t%s\n' "$(date -u +%H:%M:%SZ)" "$((attempt-1))" "$class_rc" "$class" >> "$RETRY_LOG"
    log "connect attempt $((attempt-1)) failed ($class); waiting 60s before retry (PLE-357 evidence: a bare retry ~60s later cleared the same wedge)"
    sleep 60
    exit_stream_gracefully || true
    "$ADB" shell am start -n "$PKG/com.metallic.chiaki.main.MainActivity" >/dev/null 2>&1 || true
    sleep 2
  fi
  attempt_log="$OUT/session_logcat_attempt${attempt}.txt"
  prev_attempt_log="$attempt_log"
  if connect_attempt "$attempt" "$attempt_log"; then
    connected=1
    SESSION_LOG="$attempt_log"
    break
  fi
done
if [ "$connected" != 1 ]; then
  class=$(classify_connect_failure "$prev_attempt_log"); class_rc=$?
  printf '%s\t%s\t%s\t%s\n' "$(date -u +%H:%M:%SZ)" 2 "$class_rc" "$class" >> "$RETRY_LOG"
  log "connect attempt 2 also failed ($class)"
  exit 4
fi
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
  if ! streaming || grep -q "Session has quit" "$SESSION_LOG"; then
    log "stream dropped during $tag"; "$ADB" exec-out screencap -p > "$OUT/${tag}_fail.png"; break
  fi
  "$ADB" exec-out screencap -p > "$OUT/${tag}_overlay.png"
  echo "PHASE_END $tag $(date -u +%s.%N)" >> "$OUT/phases.txt"
  log "phase $tag done"
done
# PLE-418: a dynamic profile's remote cycling loop can die mid-capture with
# nothing reporting it; verify the raw samples actually show the pattern each
# phase claims before this exits 0.
if ! verify_dynamic_profile_pattern "$OUT" > "$OUT/pattern_check.txt" 2>&1; then
  log "dynamic-profile pattern check failed"; cat "$OUT/pattern_check.txt"; exit 6
fi
log "capture complete"
