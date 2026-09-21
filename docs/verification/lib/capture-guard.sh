# Shared guard for docs/verification/*/capture.sh scripts.
#
# PLE-412: every capture.sh used to default OUT_DIR to its own ticket's
# directory and write into it unconditionally. PLE-403's run reused PLE-366's
# capture.sh, inherited that default, and overwrote PLE-366's raw capture --
# unrecoverable, because build/captures/ is gitignored. OUT_DIR now has no
# ticket-specific default anywhere: the caller must state where output goes,
# and a non-empty target is refused unless the caller opts in.
#
# Usage from a capture.sh:
#   FORCE=0
#   for arg in "$@"; do [ "$arg" = --force ] && FORCE=1; done
#   source "$(dirname "${BASH_SOURCE[0]}")/../lib/capture-guard.sh"
#   require_out_dir "$OUT" "$FORCE"

require_out_dir() {
  local out=$1 force=$2
  if [ -z "$out" ]; then
    echo "capture.sh: OUT_DIR is not set. State an output directory explicitly, e.g. OUT_DIR=build/captures/<ticket> $0" >&2
    exit 1
  fi
  if [ -d "$out" ] && [ -n "$(ls -A "$out" 2>/dev/null)" ] && [ "$force" != 1 ]; then
    echo "capture.sh: refusing to write into non-empty directory '$out'. Pass --force (or FORCE=1) to overwrite its contents." >&2
    exit 1
  fi
}

# PLE-418: a dynamic profile (blip-200ms) cycles on a shared remote host that
# is not scoped to the device reservation (scripts/net/impair.sh's
# `blip_loop`), and that loop can die -- or leave a stray pulse applied --
# without anything reporting it: capture.sh used to exit 0 regardless, with
# phases.txt well-formed and the artifacts looking complete while the raw
# jitter/loss samples silently show a clean network. Call this once, after
# the phase loop, before the caller's own final "capture complete" log line.
# On failure it prints the offending phase(s) and returns non-zero -- the
# caller must exit non-zero itself rather than fall through to exit 0.
verify_dynamic_profile_pattern() {
  local out=$1 repo="${REPO:-/home/wnt/gta6}" lib
  lib=$(dirname "${BASH_SOURCE[0]}")
  REPO="$repo" python3 "$lib/verify-blip-pattern.py" "$out"
}

# PLE-410: a capture with a stale installed APK ran to completion, exited 0,
# and produced a well-formed directory measuring the *old* packet-gap EWMA
# jitter estimator instead of PLE-356's per-frame one -- nothing caught it
# until analysis noticed grep -c packet_jitter_raw_ms returned 0. Call this
# right after confirming the package is what you expect and before spending
# any phone time on the capture itself: a re-run is cheap now, impossible
# once the phone has moved on. See check-apk-freshness.py's docstring for
# what "current" means here and what it does not catch.
#
# Usage: require_current_apk "$ADB" "$PKG"
# $ADB must be the adb wrapper/binary this capture.sh already uses. Exits
# non-zero with a message naming the mismatch and the rebuild command on
# failure; the caller should exit non-zero itself rather than continue.
# PLE-450: capture.sh retry loops used to treat every failed connect
# identically -- retry, succeed, and leave nothing in the capture's own
# artifacts saying a wedge happened, so PLE-385's prior-occurrence survey had
# to be assembled by reading old logcats by hand. PLE-385 established the
# AvCap wedge's signature precisely: it is console-side and occupies exactly
# one window, between "StreamConnection successfully received bang" and
# streaminfo. A failure before that point is something else (rp_in_use, a
# dead link) and must not be classified as the wedge just because an
# "InitResult" string happens to appear somewhere in the log. This follows
# wedge-probe.sh's exit-status contract (0 streaming / 10 AvCap wedge / 11
# rp_in_use / 12 other quit / 13 harness/unknown) so every capture.sh
# classifies a failed connect the same way instead of writing a second
# classifier.
#
# Usage: class=$(classify_connect_failure "$logfile"); rc=$?
# $logfile is one attempt's own logcat capture (or a tail of it since the
# last teardown) -- not a log spanning multiple attempts, or an AvCap wedge
# from an earlier attempt would be misattributed to this one.
classify_connect_failure() {
  local log=$1 bang_line
  if grep -q "rp_in_use" "$log" 2>/dev/null; then
    echo "RP_IN_USE"
    return 11
  fi
  bang_line=$(grep -n "StreamConnection successfully received bang" "$log" 2>/dev/null | tail -1 | cut -d: -f1)
  if [ -n "$bang_line" ] && tail -n "+$bang_line" "$log" | grep -q "AvCap failed to initialize video"; then
    echo "AVCAP_WEDGE:$(tail -n "+$bang_line" "$log" | grep -o 'InitResult:-[0-9]*' | tail -1)"
    return 10
  fi
  if grep -q "Session quit:" "$log" 2>/dev/null; then
    echo "OTHER_QUIT:$(grep -m1 -o 'reason=[a-z_]*' "$log" 2>/dev/null)"
    return 12
  fi
  echo "UNKNOWN"
  return 13
}

require_current_apk() {
  local adb=$1 pkg=$2 lib apk_repo device_path tmp rc
  lib=$(dirname "${BASH_SOURCE[0]}")
  apk_repo=$(git -C "$lib" rev-parse --show-toplevel) || {
    echo "capture.sh: could not resolve the git worktree containing $lib" >&2
    return 1
  }
  device_path=$("$adb" shell pm path "$pkg" 2>/dev/null | head -1 | tr -d '\r')
  device_path=${device_path#package:}
  if [ -z "$device_path" ]; then
    echo "capture.sh: package '$pkg' is not installed on the device (pm path returned nothing)." >&2
    return 1
  fi
  tmp=$(mktemp /tmp/capture-guard-apk.XXXXXX) || return 1
  if ! "$adb" pull "$device_path" "$tmp" >/dev/null 2>&1; then
    rm -f "$tmp"
    echo "capture.sh: could not pull the installed APK from the device ($device_path)." >&2
    return 1
  fi
  python3 "$lib/check-apk-freshness.py" --apk "$tmp" --repo "$apk_repo"
  rc=$?
  rm -f "$tmp"
  return $rc
}
