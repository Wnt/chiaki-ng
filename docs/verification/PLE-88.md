# PLE-88 — Wi-Fi low-latency lock during a stream

## Implementation

- Added the `stream_wifi_low_latency_lock` setting, default off.
- While `StreamActivity` is resumed and the setting is on, the app holds a non-reference-counted
  `WifiManager.WifiLock` tagged `com.metallic.chiaki:StreamWifiLowLatency`.
- Android 10 / API 29 and newer use `WIFI_MODE_FULL_LOW_LATENCY`; API 24–28 use
  `WIFI_MODE_FULL_HIGH_PERF`.
- `onPause()` releases the lock; `onDestroy()` repeats the release as a lifecycle safety net.
- The manifest declares the normal `android.permission.WAKE_LOCK` permission required to acquire
  a Wi-Fi lock.

## Verified on CT950

- `WifiLockModePolicyTest`: 2/2 tests passed, covering the API 28 fallback and API 29+ low-latency
  path. Result: `android/app/build/test-results/testDebugUnitTest/TEST-com.metallic.chiaki.stream.WifiLockModePolicyTest.xml`.
- `/home/wnt/gta6/scripts/dev/gate.sh`: `GATE: PASS`; host build and ctest passed, and
  `assembleDebug` produced a 20,555,436-byte APK. Log:
  `/home/wnt/gta6/wt/ple-88/build/ple-88-evidence/gate.log`.
- The built APK declares `android.permission.WAKE_LOCK`; extracted permissions:
  `/home/wnt/gta6/wt/ple-88/build/ple-88-evidence/apk-permissions.txt`.
- API 36 emulator smoke cold-launched `MainActivity`, kept the process alive, and found no fatal
  exception. Log and screenshot:
  `/home/wnt/gta6/wt/ple-88/build/ple-88-evidence/emu-smoke.log` and
  `/home/wnt/gta6/wt/ple-88/build/ple-88-evidence/emu/smoke-20260916-044123.png`.
- Normal UI navigation to Settings showed **Wi-Fi low-latency lock** present and initially off.
  Evidence: `/home/wnt/gta6/wt/ple-88/build/ple-88-evidence/settings-wifi.xml` and
  `/home/wnt/gta6/wt/ple-88/build/ple-88-evidence/settings-wifi-lock.png`.
- Emulator evidence is limited to build, launch, UI, and crash detection. It cannot prove that
  Android's Wi-Fi service granted or retained either lock mode.

## Not proven

- No phone test or A/B capture is part of this implementation ticket.
- The shared Galaxy S22 Ultra was reserved by PLE-74 during this run, and was not used.
- On the Galaxy S22 Ultra, verify both setting arms during a live PS5 stream with
  `adb shell dumpsys wifi | grep -i lock`: off must show no app lock; on must show this app's
  `LOW_LATENCY` lock only while `StreamActivity` is resumed, disappearing when it is paused.
- The `HIGH_PERF` fallback needs an API 24–28 device; the project emulator is API 35/36.
- Network jitter, latency, power, and thermal effects need a later controlled phone A/B capture.
