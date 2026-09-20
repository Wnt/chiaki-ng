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
