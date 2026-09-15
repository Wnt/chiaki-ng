# PLE-28 — Samsung Wi-Fi ADB verification (Astra)

## Run identity

- Worker: Codex, `gpt-6-astra`, medium effort; CT950.
- Dispatcher run: `/home/wnt/gta6/build/dispatch/ple-28`.
- Worktree: `/home/wnt/gta6/wt/ple-28`.
- Branch: `jonni/ple-28-verify-android-build-launch-and-debugger-on-wi-fi-adb`.
- Starting HEAD / APK source: `23eeeaa596cffb65e9d39ff63dbe8681c51f90fb`.
- Evidence root: `/home/wnt/gta6/wt/ple-28/build/ple-28-evidence` (ignored build artifacts; retained locally).
- Wi-Fi ADB transport: `192.168.1.105:36437`; model `SM-S908B` (`SM_S908B` in device list); hardware serial `R3CT30WLVFV`; Android 16 / API 36.
- Identity evidence: `identity.txt`; exact device commands and exit codes: `commands.log`.
- No PLE-27 worker was active during this run. No branch was pushed or merged.
- Fresh worktree required submodule initialization and native builds. PLE-25's gate was also active early in this run; it had finished by 20:28:03 UTC. These timings include shared-host contention and are not an isolated build-speed measurement.

## Gate

```sh
/home/wnt/gta6/scripts/dev/gate.sh /home/wnt/gta6/wt/ple-28
```

Full output: `gate.log`. Structured timestamps: `timings.json`.

## Scope

This report changes documentation only. Emulator smoke is not required for this documentation-only change and was not run. The device session concerns launch, UI responsiveness, and managed-code debugging only. It cannot establish PS5 registration/streaming, hardware decoding, composition, vsync, refresh behavior, input-to-photon latency, or Galaxy S25 behavior. No PS5 session was attempted.

## Results and timings

- Gate exited 0, `GATE: PASS`: host configure 56 s, host build 22 s, host ctest 0 s (actual ctest 0.38 s), Android `assembleDebug` 399 s. Gate-reported total 477 s; externally measured 477.675 s.
- Host ctest: 1/1 test passed, 100%, zero failed.
- Gate interval: 2026-09-15 20:23:28.184–20:31:25.859 UTC.
- Install/launch/debug/evidence interval: 20:31:40.324–20:33:44.055 UTC, **123.732 s**. Includes interaction, screenshots, debugger inspection, cleanup, and log collection.
- Dispatcher start: **20:23:01 UTC**. Final handoff timestamp and dispatcher-to-handoff wall time are recorded in `timings.json` after committing, and in the final dispatcher report; dispatcher terminal-state bookkeeping happens afterward.
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`, 31,584,062 bytes.
- APK SHA-256: `a9dc54eaeb3313f0038f5c5c38ad7068b04bd75c3d24919640a149067496ee14`.
- `adb install -r` returned `Success`. Cold launch returned `Status: ok`, `TotalTime: 571`, `WaitTime: 574` (milliseconds). PID **28516**.
- JDB 17.0 (Java SE 17.0.20) attached, hit app code at `MainActivity.kt:95`, inspected `expand = true`, cleared the breakpoint, and continued. Full terminal transcript: `jdb.txt`.
- Resumed UI showed **Register Console** and **Add Console Manually**; Back collapsed the menu. Overflow → Settings rendered preferences; Back returned to MainActivity. Final PID remained 28516 and `activities-after.txt` showed MainActivity as top resumed activity.
- Captured app logcat has no `FATAL EXCEPTION`; captured lifecycle logcat has no `ANR in com.metallic.chiaki`. This observation is limited to the captured session.
- One gate attempt, one install, one launch, one debugger attachment; no install/launch/debugger errors. The first button tap at (956,2192) did not hit the breakpoint. The screenshot showed the button overlapping the navigation bar; retrying its upper region at (950,2140) hit immediately. This existing layout issue was not changed.
- A discovered-console card was visible on the main screen; it was not selected. Settings showed zero registered consoles. Discovery visibility does not prove registration or streaming.

## Repeatable device/debugger procedure

Run from this worktree with the Samsung unlocked. Coordinates below apply to this session's 1080×2316 display; inspect the UI dump before reusing them on another configuration. Always select the explicit Wi-Fi serial, since the same phone also appears under an mDNS ADB alias.

```sh
adb -s 192.168.1.105:36437 devices -l
adb -s 192.168.1.105:36437 shell getprop ro.product.model
adb -s 192.168.1.105:36437 shell getprop ro.serialno
sha256sum android/app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.1.105:36437 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.1.105:36437 shell am force-stop com.metallic.chiaki
adb -s 192.168.1.105:36437 shell am start -W -n com.metallic.chiaki/.main.MainActivity
adb -s 192.168.1.105:36437 shell pidof com.metallic.chiaki
# Substitute the returned PID; this run used 28516.
adb -s 192.168.1.105:36437 forward tcp:5028 jdwp:28516
jdb -attach localhost:5028 -sourcepath android/app/src/main/java
```

In JDB:

```text
stop in com.metallic.chiaki.main.MainActivity.expandFloatingActionButton
```

In a second terminal:

```sh
adb -s 192.168.1.105:36437 shell input tap 950 2140
```

Wait for JDB's breakpoint event, then inspect and resume:

```text
locals
print expand
where
clear com.metallic.chiaki.main.MainActivity.expandFloatingActionButton
cont
```

Observed output (full transcript preserves command/output interleaving):

```text
Breakpoint hit: "thread=main", com.metallic.chiaki.main.MainActivity.expandFloatingActionButton(), line=95 bci=0
95            binding.floatingActionButton.isExpanded = expand
Local variables:
expand = true
[1] com.metallic.chiaki.main.MainActivity.expandFloatingActionButton (MainActivity.kt:95)
[2] com.metallic.chiaki.main.MainActivity.onCreate$lambda$0 (MainActivity.kt:46)
Removed: breakpoint com.metallic.chiaki.main.MainActivity.expandFloatingActionButton
```

After confirming the expanded menu, use Back, open overflow, and open Settings:

```sh
adb -s 192.168.1.105:36437 shell input keyevent KEYCODE_BACK
adb -s 192.168.1.105:36437 shell input tap 1020 80
adb -s 192.168.1.105:36437 shell input tap 790 165
adb -s 192.168.1.105:36437 shell input keyevent KEYCODE_BACK
adb -s 192.168.1.105:36437 shell pidof com.metallic.chiaki
```

Use `exit` in JDB to detach, then remove only this session's forward:

```sh
adb -s 192.168.1.105:36437 forward --remove tcp:5028
```

No persistent wait-for-debugger setting was used. App data was retained by `install -r`; no settings were changed. The debug app remains installed and running.

## Evidence capture and inventory

All paths in this section are relative to the evidence root above. `commands.log` records timestamped commands, outputs, and exit codes for the install/UI flow. `record.py` is the local command-output recorder. JDB was recorded with:

```sh
script -q -f -c 'jdb -attach localhost:5028 -sourcepath android/app/src/main/java' build/ple-28-evidence/jdb.txt
```

UI dumps used `adb -s 192.168.1.105:36437 shell uiautomator dump /sdcard/ple-28-NAME.xml` followed by `adb -s 192.168.1.105:36437 pull /sdcard/ple-28-NAME.xml build/ple-28-evidence/NAME.xml`. Names: `main`, `expanded`, `overflow`, `settings`, `return`. Screenshots `main.png`, `expanded.png`, `settings.png` used `adb -s 192.168.1.105:36437 exec-out screencap -p > build/ple-28-evidence/NAME.png`.

```sh
adb -s 192.168.1.105:36437 logcat -d -v threadtime --pid=28516 > build/ple-28-evidence/app-logcat.txt
adb -s 192.168.1.105:36437 logcat -d -v threadtime -s ActivityTaskManager ActivityManager AndroidRuntime > build/ple-28-evidence/lifecycle-logcat.txt
adb -s 192.168.1.105:36437 shell dumpsys activity activities > build/ple-28-evidence/activities-after.txt
adb -s 192.168.1.105:36437 shell dumpsys package com.metallic.chiaki > build/ple-28-evidence/package.txt
```

`relevant-logcat.txt` extracts the app's launch/Settings records. Device log timestamps are UTC+3, whereas host command timestamps are UTC. Relevant output:

```text
09-15 23:31:46.778  1452  1551 I ActivityManager: Start proc 28516:com.metallic.chiaki/u0a182 for next-top-activity {com.metallic.chiaki/com.metallic.chiaki.main.MainActivity}
09-15 23:31:47.320  1452  1540 I ActivityTaskManager: Displayed com.metallic.chiaki/.main.MainActivity for user 0: +571ms
09-15 23:33:36.266  1452  1540 I ActivityTaskManager: Displayed com.metallic.chiaki/.settings.SettingsActivity for user 0: +207ms
```

Additional context: `power-before.txt`, `window-before.txt`, `build-context.txt`. Evidence and APK are local artifacts, not committed binaries. Preserve the evidence directory and APK before removing this worktree. This report is the only committed change; its commit does not change the APK source from the starting HEAD.
