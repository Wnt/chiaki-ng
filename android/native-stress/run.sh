#!/usr/bin/env bash
# PLE-524: build a sanitizer-instrumented native stress test, push it with its runtime
# libraries to a device or emulator, run it from adb shell and keep the log.
# See android/native-stress/README.md.
#
#   android/native-stress/run.sh --sanitizer address -s emulator-5554 -- -n 500
#   android/native-stress/run.sh --sanitizer address -s emulator-5554 --self-test
#
# Exit status: 0 when the run is clean (with --self-test: when the pre-PLE-514 source
# is caught), 1 when it is not (a sanitizer report, a crash, or the harness watchdog's
# HANG), 2 on a usage or build error or a sanitizer runtime that fails its own CHECK.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$ANDROID_DIR/.." && pwd)"
TEST=chiaki-audio-output-stress
DEVICE_DIR=/data/local/tmp/chiaki-native-stress
# The last commit whose audio-output.cpp still has the Oboe error-thread vs free() race.
SELF_TEST_REV=9b767536^

usage() {
	sed -n 's/^#   //p' "$0"
	cat <<EOF
options:
  --sanitizer thread|address|hwaddress   required
  -s, --serial SERIAL        adb serial (default: \$ANDROID_SERIAL)
  --abi ABI                  default: the device's ro.product.cpu.abi
  --audio-output-src FILE    stress this audio-output.cpp instead of the tree's own
  --self-test                stress $SELF_TEST_REV's audio-output.cpp; passes only if the sanitizer reports it
  --no-build                 push and run what the last build left
  --out DIR                  log directory (default: android/app/build/native-stress/logs)
  -- ARGS                    passed to $TEST (-n iterations, -d max_delay_us, -s seed, -v)
env: CHIAKI_STRESS_GRADLE_ARGS  extra ./gradlew arguments (e.g. an init script)
EOF
}

sanitizer="" serial="${ANDROID_SERIAL:-}" abi="" source="" self_test=0 build=1
out_dir="$ANDROID_DIR/app/build/native-stress/logs"
test_args=()
while [ $# -gt 0 ]; do
	case "$1" in
		--sanitizer) sanitizer="$2"; shift 2 ;;
		-s|--serial) serial="$2"; shift 2 ;;
		--abi) abi="$2"; shift 2 ;;
		--audio-output-src) source="$(realpath "$2")"; shift 2 ;;
		--self-test) self_test=1; shift ;;
		--no-build) build=0; shift ;;
		--out) out_dir="$2"; shift 2 ;;
		-h|--help) usage; exit 0 ;;
		--) shift; test_args=("$@"); break ;;
		*) echo "run.sh: unknown argument $1" >&2; usage >&2; exit 2 ;;
	esac
done
runtime_env=""
case "$sanitizer" in
	thread) runtime_options=TSAN_OPTIONS ;;
	address) runtime_options=ASAN_OPTIONS ;;
	# The system's HWASan runtime (Android 14+), as the NDK's wrap.sh/hwasan.sh does it.
	hwaddress) runtime_options=HWASAN_OPTIONS runtime_env="LD_HWASAN=1 " ;;
	*) echo "run.sh: --sanitizer must be thread, address or hwaddress" >&2; exit 2 ;;
esac
if [ -z "$serial" ]; then
	echo "run.sh: no device: pass -s SERIAL or set ANDROID_SERIAL" >&2
	exit 2
fi
adb=(adb -s "$serial")
if [ -z "$abi" ]; then
	abi="$("${adb[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')"
fi

if [ "$self_test" = 1 ]; then
	if [ -n "$source" ]; then
		echo "run.sh: --self-test and --audio-output-src are exclusive" >&2
		exit 2
	fi
	source="$ANDROID_DIR/app/build/native-stress/self-test/audio-output.cpp"
	mkdir -p "$(dirname "$source")"
	git -C "$REPO" show "$SELF_TEST_REV:android/app/src/main/cpp/audio-output.cpp" > "$source"
fi

if [ "$build" = 1 ]; then
	gradle_args=(":app:buildCMakeDebug[$abi]" -q "-PchiakiNativeStress=$sanitizer" "-PchiakiAbiFilters=$abi")
	[ -n "$source" ] && gradle_args+=("-PchiakiNativeStressAudioOutputSource=$source")
	# shellcheck disable=SC2206 # word splitting of the extra arguments is the point
	extra=(${CHIAKI_STRESS_GRADLE_ARGS:-})
	echo "run.sh: building $TEST ($sanitizer, $abi)"
	(cd "$ANDROID_DIR" && ./gradlew "${extra[@]}" "${gradle_args[@]}") || exit 2
fi

bin_dir="$ANDROID_DIR/app/build/native-stress/$abi"
if [ ! -x "$bin_dir/$TEST" ]; then
	echo "run.sh: $bin_dir/$TEST not built" >&2
	exit 2
fi
built="$(cat "$bin_dir/SANITIZER" 2>/dev/null || true)"
built_source="$(cat "$bin_dir/SOURCE" 2>/dev/null || true)"
if [ "$built" != "$sanitizer" ] || [ "$built_source" != "$source" ]; then
	echo "run.sh: $bin_dir holds a '$built' build of '${built_source:-the tree}', not '$sanitizer' of '${source:-the tree}'; drop --no-build" >&2
	exit 2
fi

"${adb[@]}" shell rm -rf "$DEVICE_DIR"
"${adb[@]}" shell mkdir -p "$DEVICE_DIR"
for f in "$bin_dir"/*; do
	"${adb[@]}" push "$f" "$DEVICE_DIR/" > /dev/null
done
"${adb[@]}" shell chmod 755 "$DEVICE_DIR/$TEST"

mkdir -p "$out_dir"
# LeakSanitizer does not run on Android, so ASan's leak check is off rather than silently skipped.
options="halt_on_error=1"
[ "$sanitizer" = address ] && options="$options:detect_leaks=0"
quoted_args=""
for a in "${test_args[@]+"${test_args[@]}"}"; do
	quoted_args="$quoted_args '${a//\'/}'"
done
ndk_version="$(sed -n 's/^ *ndkVersion "\(.*\)"/\1/p' "$ANDROID_DIR/app/build.gradle")"
symbolizer="${ANDROID_HOME:-$HOME/android-sdk}/ndk/$ndk_version/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-symbolizer"

# One run on the device; sets log, status and sanitizer_report.
run_once() {
	local suffix=""
	[ "$self_test" = 1 ] && suffix=-self-test
	log="$out_dir/$(date -u +%Y%m%dT%H%M%SZ)-$sanitizer-$abi$suffix.log"
	{
		echo "# serial $serial abi $abi sanitizer $sanitizer source ${source:-tree} rev $(git -C "$REPO" rev-parse --short HEAD)"
		"${adb[@]}" shell "cd $DEVICE_DIR && $runtime_env$runtime_options=$options LD_LIBRARY_PATH=. ./$TEST$quoted_args; echo \"exit status \$?\"" 2>&1
	} | tee "$log"
	status="$(sed -n 's/^exit status \([0-9]*\).*/\1/p' "$log" | tr -d '\r' | tail -1)"
	sanitizer_report=0
	grep -qE '^(==[0-9]+==ERROR: |WARNING: ThreadSanitizer|SUMMARY: (Address|HWAddress|Thread)Sanitizer)' "$log" && sanitizer_report=1
	echo "run.sh: log $log"

	# The device has no symbolizer; resolve the frames in files we pushed with the NDK's, host side.
	if [ "$sanitizer_report" = 1 ] && [ -x "$symbolizer" ]; then
		python3 - "$log" "$bin_dir" "$DEVICE_DIR" "$symbolizer" > "$log.symbolized" <<'PY'
import re, subprocess, sys
log, bin_dir, device_dir, symbolizer = sys.argv[1:]
frame = re.compile(r'\(' + re.escape(device_dir) + r'/([^+)]+)\+(0x[0-9a-f]+)\)')
for line in open(log, errors='replace'):
    line = line.rstrip('\n')
    m = frame.search(line)
    if m:
        out = subprocess.run([symbolizer, '--obj=%s/%s' % (bin_dir, m.group(1)), '--demangle', m.group(2)],
                             capture_output=True, text=True).stdout.split('\n')
        line = '%s  %s %s' % (line[:m.start()].rstrip(), out[0], out[1] if len(out) > 1 else '')
    print(line)
PY
		echo "run.sh: symbolized $log.symbolized"
	fi
	# The sanitizer runtime itself failing is neither a pass nor a finding (README: TSan on API 36).
	if grep -qE 'Sanitizer: CHECK failed' "$log"; then
		echo "run.sh: ERROR: the $sanitizer runtime failed its own CHECK on this device; the run proves nothing"
		exit 2
	fi
}

if [ "$self_test" = 1 ]; then
	# The old race shows up either as a sanitizer report or, when the freed access happens inside
	# bionic or Oboe where nothing is instrumented, as a watchdog HANG (README, "Reading a
	# result"). A report is the stronger proof, so retry a few times for one.
	hung=""
	for attempt in 1 2 3 4 5; do
		run_once
		if [ "$sanitizer_report" = 1 ]; then
			echo "run.sh: SELF-TEST PASS: $sanitizer reported the pre-PLE-514 race (attempt $attempt)"
			exit 0
		fi
		[ "$status" = 3 ] && hung="$hung $log"
	done
	if [ -n "$hung" ]; then
		echo "run.sh: SELF-TEST PASS (weak): no sanitizer report in 5 attempts, but the pre-PLE-514 race hung on freed memory:$hung"
		exit 0
	fi
	echo "run.sh: SELF-TEST FAIL: the pre-PLE-514 race was not caught in 5 attempts (last exit status ${status:-?})"
	exit 1
fi
run_once
if [ "$status" = 0 ] && [ "$sanitizer_report" = 0 ]; then
	echo "run.sh: PASS"
	exit 0
fi
echo "run.sh: FAIL (exit status ${status:-?}, sanitizer report: $([ "$sanitizer_report" = 1 ] && echo yes || echo no))"
exit 1
