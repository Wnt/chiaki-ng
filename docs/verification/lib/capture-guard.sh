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
