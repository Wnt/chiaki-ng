#!/usr/bin/env bash
# Set up an arm64 (aarch64) Debian/Ubuntu host -- a Raspberry Pi, an arm64 VM, or Debian in
# Termux proot-distro on a phone -- to build the Android app with android/gradlew.
#
#   android/scripts/setup-arm64-linux-host.sh
#   android/scripts/setup-arm64-linux-host.sh --skip-apt
#
# Google ships the NDK, aapt2 and CMake for linux-x86_64 only, so this installs the normal SDK
# packages and then swaps in native substitutes:
#   - NDK: the x86_64 clang/lld/llvm binaries are moved to bin.x86_64-orig and replaced by the
#     distro's LLVM of the same major version. clang is a wrapper that keeps the NDK's own
#     sysroot and resource dir (Android compiler-rt, libunwind), so the NDK's headers, libraries
#     and CMake toolchain file are used unchanged.
#   - aapt2: Google's x86_64 aapt2 runs under box64 (the distro's aapt2 is too old to load
#     android-35's android.jar), set through android.aapt2FromMavenOverride.
#   - CMake: the exact version app/build.gradle pins, from the PyPI wheel (which has aarch64
#     builds), referenced by cmake.dir in android/local.properties.
# The SDK platform, NDK and CMake versions are read from app/build.gradle. Safe to re-run, and
# must be re-run after sdkmanager (re)installs the NDK, which brings the x86_64 binaries back.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$ANDROID_DIR/.." && pwd)"
BUILD_GRADLE="$ANDROID_DIR/app/build.gradle"
# Only its aapt2 is used (under box64); matches the build-tools CI installs.
BUILD_TOOLS=35.0.0
CMDLINE_TOOLS_URL=https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
WRAPPER_MARKER="chiaki-ng arm64 host wrapper"

usage() {
	sed -n 's/^#   //p' "$0"
	cat <<EOF
options:
  --skip-apt      do not install distro packages (they must already be present)
env: ANDROID_HOME         SDK location (default: ~/Android/Sdk)
     CHIAKI_HOST_TOOLS    where the aapt2 wrapper and CMake go (default: ~/Android)
EOF
}

skip_apt=0
while [ $# -gt 0 ]; do
	case "$1" in
		--skip-apt) skip_apt=1 ;;
		-h|--help) usage; exit 0 ;;
		*) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
	esac
	shift
done

log() { printf '\n==> %s\n' "$*"; }
die() { echo "error: $*" >&2; exit 1; }

if [ "$(uname -s)" != Linux ] || [ "$(uname -m)" != aarch64 ]; then
	echo "This host is $(uname -s) $(uname -m); the stock Android SDK already runs here, nothing to do."
	exit 0
fi

gradle_value() {
	local value
	value="$(sed -nE "s/^\s*$1\s+\"?([0-9.]+)\"?\s*$/\1/p" "$BUILD_GRADLE" | head -1)"
	[ -n "$value" ] || die "could not find '$1' in $BUILD_GRADLE"
	echo "$value"
}
COMPILE_SDK="$(gradle_value compileSdk)"
NDK_VERSION="$(gradle_value ndkVersion)"
CMAKE_VERSION="$(gradle_value version)"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
TOOLS="${CHIAKI_HOST_TOOLS:-$HOME/Android}"
NDK="$SDK/ndk/$NDK_VERSION"
NDK_PREBUILT="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
CMAKE_DIR="$TOOLS/cmake-$CMAKE_VERSION"
AAPT2_WRAPPER="$TOOLS/aapt2-box64/aapt2"
SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

echo "SDK $SDK: platform android-$COMPILE_SDK, NDK $NDK_VERSION, CMake $CMAKE_VERSION"

# Set key=value in a Java properties file, keeping every other line (signing settings etc.).
set_property() {
	local file="$1" key="$2" value="$3"
	mkdir -p "$(dirname "$file")"
	touch "$file"
	grep -v "^$key=" "$file" > "$SCRATCH/props" || true
	echo "$key=$value" >> "$SCRATCH/props"
	cat "$SCRATCH/props" > "$file"
}

# The NDK's LLVM major version decides which distro LLVM replaces it; the NDK is not installed
# yet on a first run, so this is only known after sdkmanager.
ndk_llvm_major() {
	sed -nE '1s/^([0-9]+)\..*/\1/p' "$NDK_PREBUILT/AndroidVersion.txt"
}

apt_install() {
	local sudo=""
	[ "$(id -u)" = 0 ] || sudo=sudo
	$sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "$@"
}

# Phase 1, before sdkmanager (which needs Java): everything but the LLVM, whose version comes
# from the NDK.
install_base_packages() {
	local jdk="" candidate policy
	command -v apt-get >/dev/null || die "no apt-get; install a JDK (17+), clang/lld/llvm matching the NDK, box64, ninja, protoc, python3-protobuf, python3-venv, curl, unzip, file by hand and re-run with --skip-apt"
	if [ "$(id -u)" = 0 ]; then apt-get update -qq; else sudo apt-get update -qq; fi
	for candidate in openjdk-21-jdk-headless openjdk-17-jdk-headless; do
		# Captured first: under pipefail, grep -q exiting early would fail the pipeline via SIGPIPE.
		policy="$(LC_ALL=C apt-cache policy "$candidate" 2>/dev/null)"
		if grep -qE '^ *Candidate: [0-9]' <<<"$policy"; then
			jdk="$candidate"
			break
		fi
	done
	[ -n "$jdk" ] || die "neither openjdk-21 nor openjdk-17 is available from apt"
	apt_install "$jdk" box64 ninja-build protobuf-compiler python3 python3-protobuf python3-venv \
		curl unzip file git adb
}

install_sdk() {
	local sdkmanager="$SDK/cmdline-tools/latest/bin/sdkmanager"
	if [ ! -x "$sdkmanager" ]; then
		log "Installing Android command-line tools"
		curl -fsSL -o "$SCRATCH/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"
		mkdir -p "$SDK/cmdline-tools"
		rm -rf "$SDK/cmdline-tools/latest" "$SDK/cmdline-tools/cmdline-tools"
		unzip -q "$SCRATCH/cmdline-tools.zip" -d "$SDK/cmdline-tools"
		mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
	fi
	log "Installing SDK packages"
	yes | "$sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
	"$sdkmanager" --sdk_root="$SDK" \
		"platforms;android-$COMPILE_SDK" "build-tools;$BUILD_TOOLS" "ndk;$NDK_VERSION" \
		| grep -v '^\[' || true
	[ -f "$NDK_PREBUILT/AndroidVersion.txt" ] || die "NDK $NDK_VERSION did not install to $NDK"
}

patch_ndk() {
	local llvm_major="$1" llvm_bin="/usr/lib/llvm-$1/bin" f
	log "Replacing the NDK's x86_64 toolchain with LLVM $llvm_major"
	[ -x "$llvm_bin/clang" ] || die "$llvm_bin/clang is missing; install clang-$llvm_major"
	[ -d "$NDK_PREBUILT/lib/clang/$llvm_major" ] || die "NDK has no lib/clang/$llvm_major resource dir"
	mkdir -p "$NDK_PREBUILT/bin.x86_64-orig"
	for f in "$NDK_PREBUILT"/bin/*; do
		[ -L "$f" ] && continue
		file -b "$f" | grep x86-64 >/dev/null || continue
		mv -f "$f" "$NDK_PREBUILT/bin.x86_64-orig/"
		f="$(basename "$f")"
		if [ "$f" = "clang-$llvm_major" ]; then
			continue # written below
		elif [ -e "$llvm_bin/$f" ]; then
			ln -s "$llvm_bin/$f" "$NDK_PREBUILT/bin/$f"
		else
			echo "  no native $f (not needed for the build)"
		fi
	done
	# The NDK's clang builds compiler-rt/libunwind in and defaults its sysroot; the distro's does
	# not. Config-file options are never reported as unused, so compile-only runs stay -Werror clean.
	printf -- '--rtlib=compiler-rt\n--unwindlib=libunwind\n' > "$NDK_PREBUILT/bin/ndk-host-aarch64.cfg"
	cat > "$NDK_PREBUILT/bin/clang-$llvm_major" <<EOF
#!/bin/sh
# $WRAPPER_MARKER (android/scripts/setup-arm64-linux-host.sh): the distro's clang-$llvm_major
# with the NDK's own resource dir and sysroot, in place of the NDK's x86_64 clang.
ndk_bin=\$(dirname "\$(readlink -f "\$0")")
case "\$(basename "\$0")" in *++) mode=--driver-mode=g++ ;; *) mode= ;; esac
exec $llvm_bin/clang \$mode -resource-dir "\$ndk_bin/../lib/clang/$llvm_major" --sysroot="\$ndk_bin/../sysroot" --config="\$ndk_bin/ndk-host-aarch64.cfg" "\$@"
EOF
	chmod +x "$NDK_PREBUILT/bin/clang-$llvm_major"
}

install_aapt2_wrapper() {
	log "Installing the box64 aapt2 wrapper"
	mkdir -p "$(dirname "$AAPT2_WRAPPER")"
	# AGP rejects an override whose file name is not exactly aapt2.
	cat > "$AAPT2_WRAPPER" <<EOF
#!/bin/sh
# $WRAPPER_MARKER (android/scripts/setup-arm64-linux-host.sh): Google's x86_64 aapt2 under box64.
# box64 prints a harmless "Symbol nftw not found" at startup.
export BOX64_NOBANNER=1 BOX64_LOG=0
exec box64 "$SDK/build-tools/$BUILD_TOOLS/aapt2" "\$@"
EOF
	chmod +x "$AAPT2_WRAPPER"
	set_property "$HOME/.gradle/gradle.properties" android.aapt2FromMavenOverride "$AAPT2_WRAPPER"
}

install_cmake() {
	if [ -x "$CMAKE_DIR/bin/cmake" ] && "$CMAKE_DIR/bin/cmake" --version | grep -x "cmake version $CMAKE_VERSION" >/dev/null; then
		log "CMake $CMAKE_VERSION already at $CMAKE_DIR"
	else
		log "Installing CMake $CMAKE_VERSION"
		# /usr/bin/python3 explicitly: under Termux proot, Termux's own python can be on PATH too.
		/usr/bin/python3 -m venv "$SCRATCH/cmake-venv"
		"$SCRATCH/cmake-venv/bin/pip" install -q "cmake==$CMAKE_VERSION"
		rm -rf "$CMAKE_DIR"
		cp -a "$(echo "$SCRATCH"/cmake-venv/lib/python3*/site-packages/cmake/data)" "$CMAKE_DIR"
	fi
	# AGP looks for ninja next to cmake.
	ln -sf "$(command -v ninja)" "$CMAKE_DIR/bin/ninja"
}

smoke_test() {
	log "Checking the toolchain"
	echo 'int main(void) { return 0; }' > "$SCRATCH/t.c"
	"$NDK_PREBUILT/bin/aarch64-linux-android24-clang++" -x c++ "$SCRATCH/t.c" -o "$SCRATCH/t" -shared -fPIC -lc++_shared \
		|| die "NDK clang wrapper cannot link an arm64-v8a library"
	"$NDK_PREBUILT/bin/armv7a-linux-androideabi24-clang" -Werror -c "$SCRATCH/t.c" -o "$SCRATCH/t.o" \
		|| die "NDK clang wrapper cannot compile for armeabi-v7a"
	"$AAPT2_WRAPPER" version 2>&1 | grep '^Android Asset Packaging Tool' >/dev/null || die "$AAPT2_WRAPPER does not run"
	"$CMAKE_DIR/bin/cmake" --version | head -1
	java -version 2>&1 | head -1
}

if [ "$skip_apt" = 0 ]; then
	log "Installing distro packages"
	install_base_packages
fi
command -v java >/dev/null || die "java not found"

install_sdk
llvm_major="$(ndk_llvm_major)"
[ -n "$llvm_major" ] || die "could not read the LLVM version from $NDK_PREBUILT/AndroidVersion.txt"
if [ "$skip_apt" = 0 ]; then
	log "Installing LLVM $llvm_major"
	apt_install "clang-$llvm_major" "lld-$llvm_major" "llvm-$llvm_major"
fi
patch_ndk "$llvm_major"
install_aapt2_wrapper
install_cmake

log "Writing android/local.properties"
set_property "$ANDROID_DIR/local.properties" sdk.dir "$SDK"
set_property "$ANDROID_DIR/local.properties" cmake.dir "$CMAKE_DIR"

log "Checking out submodules"
git -C "$REPO" submodule update --init --recursive

smoke_test

cat <<EOF

Done. Build with:
  cd android && ./gradlew --no-daemon assembleDebug -PchiakiAbiFilters=arm64-v8a
Add -PchiakiDebugKeystore=\$HOME/.android/debug.keystore (the project debug keystore) to build
an APK that installs over the CI-built app with adb install -r.
Under Termux, run termux-wake-lock (and on Android 12+ disable the phantom process killer) so
Android does not kill Gradle while Termux is in the background.
EOF
