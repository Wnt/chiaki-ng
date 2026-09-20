#!/usr/bin/env bash
# Tests for docs/verification/lib/capture-guard.sh (PLE-410's require_current_apk,
# plus PLE-412's require_out_dir). No device needed: `adb` is stubbed to serve a
# synthetic APK whose dex carries a chosen PLE410_BUILD_GIT_SHA marker.
#
# Run directly: bash docs/verification/lib/test_capture_guard.sh
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
FAIL=0

pass() { echo "ok - $1"; }
fail() { echo "FAIL - $1"; FAIL=1; }

# --- require_out_dir (PLE-412), unchanged behaviour, quick regression check ---
(
  source "$HERE/capture-guard.sh"
  require_out_dir "" 0
) >/tmp/rog_out.$$ 2>&1
[ $? -ne 0 ] && grep -q "OUT_DIR is not set" /tmp/rog_out.$$ && pass "require_out_dir refuses empty OUT_DIR" \
  || fail "require_out_dir should refuse empty OUT_DIR"
rm -f /tmp/rog_out.$$

# --- require_current_apk (PLE-410) ---
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# A synthetic git worktree standing in for the chiaki-ng repo: capture-guard.sh
# resolves the repo from its own location, so it must live inside it.
REPO="$WORK/repo"
mkdir -p "$REPO/lib/include/chiaki" "$REPO/docs/verification/lib"
cp "$HERE/capture-guard.sh" "$HERE/check-apk-freshness.py" "$REPO/docs/verification/lib/"
git -C "$REPO" init -q
git -C "$REPO" config user.email test@example.com
git -C "$REPO" config user.name Test
# Linear history: a commit before the stats field (what a stale APK's build
# was compiled from), the stats-field change itself, then a commit after it
# (what a fresh APK's build was compiled from).
echo v0 > "$REPO/README.md"
git -C "$REPO" add README.md
git -C "$REPO" commit -q -m "v0 (before the stats field)"
STALE_BUILD_SHA=$(git -C "$REPO" rev-parse HEAD)

echo "// stats fields" > "$REPO/lib/include/chiaki/session.h"
git -C "$REPO" add lib/include/chiaki/session.h
git -C "$REPO" commit -q -m "add stats field"

echo v2 > "$REPO/README.md"
git -C "$REPO" add README.md
git -C "$REPO" commit -q -m "v2 (after the stats field)"
FRESH_BUILD_SHA=$(git -C "$REPO" rev-parse HEAD)

make_apk() {  # make_apk <out.apk> <build-sha>
  python3 - "$1" "$2" <<'PY'
import sys, zipfile
out, sha = sys.argv[1], sys.argv[2]
marker = f"PLE410_BUILD_GIT_SHA:{sha}".encode("ascii")
with zipfile.ZipFile(out, "w") as z:
    z.writestr("classes.dex", b"\x00" * 37 + marker + b"\x00" * 41)
PY
}

STALE_APK="$WORK/stale.apk"
FRESH_APK="$WORK/fresh.apk"
make_apk "$STALE_APK" "$STALE_BUILD_SHA"
make_apk "$FRESH_APK" "$FRESH_BUILD_SHA"

# Stub adb: `pm path` names a fixed on-device path, `pull` copies whichever
# APK this test run wants served.
make_adb_stub() {  # make_adb_stub <bin-path> <apk-to-serve>
  cat > "$1" <<EOF
#!/usr/bin/env bash
if [ "\$1" = shell ] && [ "\$2" = pm ] && [ "\$3" = path ]; then
  echo "package:/data/app/com.metallic.chiaki/base.apk"
  exit 0
fi
if [ "\$1" = pull ]; then
  cp "$2" "\$3"
  exit 0
fi
exit 1
EOF
  chmod +x "$1"
}

ADB_STALE="$WORK/adb-stale"
ADB_FRESH="$WORK/adb-fresh"
make_adb_stub "$ADB_STALE" "$STALE_APK"
make_adb_stub "$ADB_FRESH" "$FRESH_APK"

(
  source "$REPO/docs/verification/lib/capture-guard.sh"
  require_current_apk "$ADB_STALE" com.metallic.chiaki
) >/tmp/rca_stale.$$ 2>&1
rc=$?
if [ $rc -ne 0 ] && grep -q "STALE" /tmp/rca_stale.$$ && grep -q "gradlew assembleDebug" /tmp/rca_stale.$$; then
  pass "require_current_apk refuses a stale APK and names the rebuild command"
else
  fail "require_current_apk should refuse the stale APK (rc=$rc)"
  cat /tmp/rca_stale.$$
fi
rm -f /tmp/rca_stale.$$

(
  source "$REPO/docs/verification/lib/capture-guard.sh"
  require_current_apk "$ADB_FRESH" com.metallic.chiaki
) >/tmp/rca_fresh.$$ 2>&1
rc=$?
if [ $rc -eq 0 ] && grep -q "^OK" /tmp/rca_fresh.$$; then
  pass "require_current_apk passes a freshly built APK"
else
  fail "require_current_apk should pass the fresh APK (rc=$rc)"
  cat /tmp/rca_fresh.$$
fi
rm -f /tmp/rca_fresh.$$

# Not-installed case
cat > "$WORK/adb-notinstalled" <<'EOF'
#!/usr/bin/env bash
if [ "$1" = shell ] && [ "$2" = pm ] && [ "$3" = path ]; then
  exit 0   # empty output, package not found
fi
exit 1
EOF
chmod +x "$WORK/adb-notinstalled"
(
  source "$REPO/docs/verification/lib/capture-guard.sh"
  require_current_apk "$WORK/adb-notinstalled" com.metallic.chiaki
) >/tmp/rca_missing.$$ 2>&1
rc=$?
if [ $rc -ne 0 ] && grep -q "not installed" /tmp/rca_missing.$$; then
  pass "require_current_apk refuses when the package is not installed"
else
  fail "require_current_apk should refuse when pm path finds nothing (rc=$rc)"
fi
rm -f /tmp/rca_missing.$$

[ "$FAIL" = 0 ] && echo "PASS: all capture-guard.sh tests" || echo "FAIL: see above"
exit $FAIL
