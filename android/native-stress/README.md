# Sanitizer builds of the native code (PLE-524)

Two build paths, both debug-only and off by default. Neither changes the normal
`assembleDebug`; a release build with either property set is refused.

| I want to… | Build with | Runs where |
|---|---|---|
| Stress a native race under TSan, ASan or HWASan from `adb shell` | `-PchiakiNativeStress=thread\|address\|hwaddress` (use `run.sh`) | any device or emulator with that runtime (see limits) |
| Run the whole app with ASan or HWASan | `-PchiakiSanitizer=address\|hwaddress` | ASan: API 27+, any ABI · HWASan: arm64, Android 14+ |

## Native stress tests: `run.sh`

`chiaki-audio-output-stress` is an executable for `adb shell`, not part of the
APK. It `#include`s `android/app/src/main/cpp/audio-output.cpp` directly (so
it can reach `AudioOutput`'s internals) and does what Oboe's detached error
thread does on a device change: `onErrorBeforeClose`, `AudioStream::close`,
`onErrorAfterClose(ErrorDisconnected)`, holding the same references Oboe
holds. It fires that concurrently with `android_chiaki_audio_output_free()` on a
real Oboe stream. The delays on both sides are random, drawn from a window that
grows to twice the longest `free()`/`close()` seen so far, so each side wins
some iterations. It is the stress test PLE-514 asked for.

```bash
# the emulator is shared: check `scripts/dev/emu.sh status` first
android/native-stress/run.sh --sanitizer address -s emulator-5554 -- -n 300
android/native-stress/run.sh --sanitizer address -s emulator-5554 -- -n 200 -E   # lifecycle only
android/native-stress/run.sh --sanitizer address -s emulator-5554 --self-test    # must be caught
# the phone: hold it for the whole run
scripts/dev/device.py run PLE-N -- android/native-stress/run.sh --sanitizer hwaddress -s <serial>
```

`run.sh` builds only `:app:buildCMakeDebug[<abi>]`, so it never replaces
`app-debug.apk`. It then pushes the binary, `libc++_shared.so`, `liboboe.so`
and the ASan/TSan runtime to `/data/local/tmp/chiaki-native-stress`, runs it,
and keeps the log (plus a host-symbolized `.symbolized` copy when there is a
report) under `android/app/build/native-stress/logs/`. Arguments after `--` go
to the binary: `-n` iterations, `-d` extra delay window in µs, `-s` seed (it
prints the seed it used, so a failure can be replayed), `-t` watchdog seconds,
`-E` lifecycle only (new → settings → free, no error thread), `-v`.

In this workspace, pass the gate's shared OpenSSL prefix, or the first build of
a fresh worktree compiles OpenSSL (minutes):

```bash
export PLEIKKARI_OPENSSL_PREBUILT_INCLUDE=/home/wnt/gta6/scripts/dev/openssl-prebuilt.cmake GATE_JOBS=4
export CHIAKI_STRESS_GRADLE_ARGS="-I /home/wnt/gta6/scripts/dev/openssl-prebuilt.gradle --offline"
```

`--audio-output-src FILE` stresses another `audio-output.cpp` (a candidate fix,
or an old revision from `git show`). `--self-test` stresses `9b767536^`, the
source from before PLE-514, and passes only if the race is caught. Run it
first when you change the harness: a harness that cannot catch the bug it was
built for proves nothing.

### Reading a result

- `run.sh: PASS` needs exit status 0 and no sanitizer report. The PASS line
  counts how the race went: `error callback reopened N` (the error thread won),
  `found AudioOutput gone N` (`free()` won), `raced in between N`. If either
  winner count is 0, or no stream ever opened (an emulator without audio), the
  binary prints a WARNING; that race was not exercised.
- `HANG iteration i … main thread in X, error thread in Y` (exit status 3) is
  the watchdog. A thread blocked on a mutex inside freed memory looks like
  this, because the access happens in bionic, which no sanitizer instruments.
  The pre-PLE-514 source hung this way in one of four emulator runs, and
  `--self-test` counts it as caught.
- `the … runtime failed its own CHECK` (exit 2) is the sanitizer runtime
  breaking, not a finding.
- To read a frame by hand: `llvm-symbolizer --obj=android/app/build/native-stress/<abi>/<file> <offset>`,
  from `$ANDROID_HOME/ndk/<ndkVersion>/toolchains/llvm/prebuilt/linux-x86_64/bin/`.

### Adding another stress test

Add an `add_executable` next to `chiaki-audio-output-stress` in
`android/app/CMakeLists.txt`, with the same sanitizer options, add it to the
collect step and to the `targets` line in `build.gradle`, and make `run.sh`'s
`TEST` selectable. AGP only builds targets that produce an artifact unless
`targets` names them, which is why the collect step is listed there.

## Sanitized APK: `-PchiakiSanitizer`

```bash
cd android
./gradlew assembleDebug -PchiakiSanitizer=address -PchiakiAbiFilters=x86_64      # emulator
./gradlew assembleDebug -PchiakiSanitizer=hwaddress                               # phone, arm64 only
```

This instruments the APK's whole native build (chiaki-lib, chiaki-jni and the
static third-party libraries; prebuilt Oboe and OpenSSL stay uninstrumented).
It packages the NDK's `wrap.sh` (`asan.sh` or `hwasan.sh`), plus
`libclang_rt.asan-*.so` for ASan, and turns on `useLegacyPackaging` so the
libraries are extracted, which `wrap.sh` needs. HWASan uses the runtime in the
system image, so it needs Android 14+. The versionName gets a `-address` or
`-hwaddress` suffix.

The applicationId is unchanged, so the APK installs over the registered app
and can stream to the PS5. That also means you must reinstall a normal build
afterwards. Check `dumpsys package com.metallic.chiaki | grep versionName` before
any A/B capture: a sanitized app is several times slower and would ruin the
measurement. Follow AGENTS.md rule 11 (`app-state.sh backup`, `install -r`
only). A sanitizer report lands in logcat under the `wrap.sh` tag and in the
tombstone. The build writes the usual `app-debug.apk`, so rebuild before
handing that path to `emu.sh install`.

Proof it took effect: `adb shell run-as com.metallic.chiaki cat /proc/<pid>/environ`
shows `LD_PRELOAD=…libclang_rt.asan…` for ASan.

## Limits found so far

- **TSan does not run on this NDK, on any API level we can test.** Every TSan
  binary from NDK r28 (clang 19) dies in `ThreadSanitizer: CHECK failed:
  tsan_rtl.cpp:1036 "((thr->ignore_reads_and_writes)) > ((0))"` as soon as a
  second thread starts, even a two-thread `x++` program. `run.sh` reports that
  as exit 2. Confirmed on the x86_64 emulator on both API 36 (Android 16) and
  API 35 (Android 15, `EMU_AVD=pleikkari-api35`), and on the S22 Ultra
  (Android 16) — so this is not an API-36 quirk, it's NDK r28 (PLE-544). Use
  ASan (emulator) or HWASan (phone). TSan could only ever run in an
  `adb shell` binary anyway, never inside the app process (no `wrap.sh` path).
  This is a known, still-open NDK limitation, not something specific to this
  project: [android/ndk#1041](https://github.com/android/ndk/issues/1041)
  tracks TSan-on-Android support since 2022, and as of the NDK team's most
  recent comments there (2025-10-13) it remains unfinished and low priority
  ("relatively low priority because pretty much everyone actually wants this
  for their apps... rather than for their stand-alone unit test binaries").
  No new upstream issue filed — #1041 already covers this exact failure mode
  for `adb shell` binaries, filing a duplicate would add nothing.
- **HWASan binaries use the system runtime.** On Android 14+ `run.sh` sets
  `LD_HWASAN=1`, as the NDK's `wrap.sh/hwasan.sh` does, and pushes no runtime.
  The NDK's own `libclang_rt.hwasan-aarch64-android.so` pushed next to the
  binary crashes in its pre-init constructor on the S22.
- LeakSanitizer does not run on Android; `run.sh` sets `detect_leaks=0`.
- Oboe, AAudio and bionic are uninstrumented. A race whose access falls inside
  them shows up as a crash in their frames, or as a HANG, not as a sanitizer
  report on our line.
- The emulator proves the lifetime logic, not the phone's AAudio MMAP path.
