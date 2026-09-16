# Android UX audit and target information architecture

Status: baseline audit for PLE-217, 2026-09-16. This document describes the
UI before the UX 1–6 work. It is a design contract, not an implementation.

The audit is based on the API 36 phone emulator at 1080 × 2400, the current
layouts and activities, and the landed measurement results from PLE-116 and
PLE-172. The screenshots in [`ux-audit/`](ux-audit/) are current-app captures;
no proposed UI is mixed into them. Dynamic console and error states which
cannot safely be produced without a registered console are identified as such.

## Product rules

These rules are acceptance criteria for every follow-on phase.

1. No app control or content may be obscured by the status, navigation, or
   display-cutout insets.
2. A useful stream must not require a Settings visit. Defaults and named
   presets make the technical decisions.
3. Do not add instructional prose. Familiar labels, state, hierarchy, and one
   obvious next action must make the product discoverable.
4. One console has one primary action: **Play**. Wake, registration, and remote
   routing are state transitions behind that affordance, not competing buttons.
5. Runtime changes remain reversible settings/build flags and retain today's
   behaviour as the initial preset. Moving or relabelling UI is not a runtime
   behaviour change.

## Capture index

All files are API 36 emulator PNGs below 300 KB at its phone-size 1080 × 2400
framebuffer. The system bars are deliberately present in normal screens and
the stream preview records the app's immersive surface/overlays.

| File | State represented |
| --- | --- |
| [`01-home-empty.png`](ux-audit/01-home-empty.png) | `MainActivity`, discovery on, no saved/discovered consoles |
| [`02-home-add-actions.png`](ux-audit/02-home-add-actions.png) | Home speed dial: Register Console / Add Console Manually |
| [`03-register-console.png`](ux-audit/03-register-console.png) | `RegistActivity`, default PS5 form |
| [`04-manual-console.png`](ux-audit/04-manual-console.png) | `EditManualConsoleActivity` |
| [`05-home-console-list.png`](ux-audit/05-home-console-list.png) | Home with a saved manual console and overflow affordance |
| [`06-console-overflow.png`](ux-audit/06-console-overflow.png) | Console Wakeup / Edit / Delete popup menu |
| [`07-settings-general.png`](ux-audit/07-settings-general.png) | Settings, General category |
| [`08-settings-stream.png`](ux-audit/08-settings-stream.png) | Settings, start of Stream category |
| [`09-settings-experiments.png`](ux-audit/09-settings-experiments.png) | Settings, latency/network experiment rows |
| [`10-settings-controller.png`](ux-audit/10-settings-controller.png) | Controller Mapping category |
| [`11-settings-postprocessing.png`](ux-audit/11-settings-postprocessing.png) | Post-processing and Export categories |
| [`12-resolution-dialog.png`](ux-audit/12-resolution-dialog.png) | Representative list-preference dialog |
| [`13-key-mapping-dialog.png`](ux-audit/13-key-mapping-dialog.png) | Physical key-capture dialog |
| [`14-registered-consoles.png`](ux-audit/14-registered-consoles.png) | Registered Consoles sub-screen, empty state |
| [`15-session-logs.png`](ux-audit/15-session-logs.png) | Session Logs sub-screen, empty state |
| [`16-psn-login.png`](ux-audit/16-psn-login.png) | `PsnLoginActivity` embedded sign-in surface |
| [`17-registration-execution.png`](ux-audit/17-registration-execution.png) | `RegistExecuteActivity` progress/log surface |
| [`19-touch-controller.png`](ux-audit/19-touch-controller.png) | `StreamActivity`, full on-screen controller in the debug preview |
| [`20-stream-diagnostics.png`](ux-audit/20-stream-diagnostics.png) | Current opt-in diagnostics overlay (same preview, retained separately for the developer-feature inventory) |

The remaining dialogs are event variants, not distinct destinations: standby
(Wakeup / Connect / Cancel), delete manual console, delete registered console,
duplicate registration (Overwrite / Cancel), import summary/failure, PSN login
error (Retry / Cancel), stream create/remote/quit error, and login PIN. Their
messages and actions are inventoried below. Producing them requires destructive
data changes, an authenticated PSN response, or a live session, so the audit
uses the representative current-app dialog captures above rather than faking
those runtime conditions.

## Current screen inventory

### Home and console surfaces

| Surface | Entry and content | Actions / dialogs | Audit finding |
| --- | --- | --- | --- |
| `MainActivity` / `activity_main.xml` | Launcher. Toolbar, LAN/PSN lists, empty state, add FAB. | Discovery toolbar toggle; overflow → Settings; console row tap connects or registers. | Two console lists model the same object differently. Empty state instructs the user to enable Discovery even though discovery defaults on. |
| Add speed dial | Home `floatingActionButton`. | Register Console; Add Console Manually. | Two implementation paths are exposed before the user can express the goal (“play on this console”). |
| LAN/manual console row / `item_display_host.xml` | Name, address, registration ID, running title, state icon. | Row tap; overflow Wakeup/Edit/Delete; standby tap opens Wakeup/Connect/Cancel. | The primary action has no label. State is icon-heavy and Wakeup appears in two interaction models. |
| PSN account consoles / `item_psn_console.xml` | A second section above LAN consoles. | Per-row Register, Wake, and Connect; global Refresh. | Three equal-weight verbs violate the single-action model. “Registered” is not useful live status. |
| Manual console entry / `EditManualConsoleActivity` | Address plus an optional registered-console binding. | Save. | This is a valid expert/LAN fallback, but currently has equal prominence to guided registration. |
| Delete manual dialog | `MainActivity.deleteHost`. | Delete / Keep. | Keep as confirmation; use console name, not only address. |
| Standby dialog | `MainActivity.hostTriggered`. | Wakeup / Connect / Cancel. | Target flow should make Play wake automatically and show progress. |

### Registration and PSN

| Surface | Content and decisions | Audit finding |
| --- | --- | --- |
| `RegistActivity` | Host, broadcast, four console-version choices, PSN sign-in/manual account ID, external help URL, PIN instructions, PIN, Register. | It combines discovery, platform/version selection, account identity, and PIN in one long form. It contains instructional prose and exposes protocol-era choices most users cannot answer. |
| `PsnLoginActivity` | Close toolbar, progress bar, embedded WebView; error dialog Retry / Cancel. | Authentication is visually disconnected from onboarding. PLE-214 separately owns the Custom Tab/passkey fix. |
| `RegistExecuteActivity` | Raw monospaced registration log, progress, result text, Share Log. Duplicate dialog Overwrite / Cancel. | Debug output is the dominant content in a user task. Success has no explicit next-step action. |
| PSN console list on home | Account devices load only when both PSN switches are enabled in Settings. | A first-time user cannot discover the intended PSN path without visiting Settings. |

### Settings and sub-screens

`SettingsActivity` hosts one `PreferenceFragmentCompat`. There is no second
level separating everyday choices from experiments.

| Current category | Current contents | Target disposition |
| --- | --- | --- |
| General | Registered consoles; PSN sign-in; experimental PSN remote play; face-button swap; rumble; touchscreen-as-touchpad; touch redraw experiment; motion; touch haptics; verbose logging; session logs. | Keep account, consoles, controller/touch, audio/haptics, and About in user Settings. Move logging and experimental toggles to Developer. |
| Stream | Resolution, FPS, refresh policy, bitrate, codec, timestamps, operating-rate controls, decoder/input/thread/performance/Wi-Fi/recovery/pacing/network/input/diagnostic/audio flags. | Replace the user-facing portion with a preset chosen on Home. Move individual knobs to Developer without changing keys/defaults. |
| Controller Mapping | 13 Android key-code capture rows. | Keep under Controller as an advanced sub-screen. |
| Post-processing | Debanding; render-when-dirty. | Keep a simple visual-quality choice only if it can explain its latency cost through the preset; raw flags go to Developer. |
| Export | Export and import including secret registration keys. | Move to Developer/Support and retain the warning. |
| Registered Consoles | Dialog-fragment list; add FAB; swipe/delete confirmation. | Keep as account/device management, not a prerequisite to Play. |
| Session Logs | Dialog-fragment list with share buttons. | Developer/Support. |
| Preference dialogs | List selectors, numeric text inputs, key mapping capture. | Presets remove normal-user exposure to codec/rate/timing numeric inputs. |

### Stream

| Surface | Content and actions | Audit finding |
| --- | --- | --- |
| Connecting | Black video surface plus centered indeterminate progress. | Needs console identity and cancellable connection state, without tutorial text. |
| Playback | Immersive `SurfaceView`/optional GL view. Tap or transient system bars reveal overlay. | Correctly prioritises content, but all edge controls need explicit inset handling. |
| Bottom overlay | On-Screen Controls switch plus Normal/Zoom/Stretch icon group. | Useful choices, but the bottom container is constrained to the physical parent edge. |
| Touch controller | D-pad, sticks, face/shoulder/system buttons and touchpad over video. | Several controls are constrained directly to left/right/bottom parent edges and collide with gesture/navigation/cutout regions. |
| Diagnostics | Popup overlay showing decoder/network/display/pacing values at 1 Hz. | Developer feature. Replace normal-user diagnostics with a small network-quality chip in UX 5. |
| Login PIN dialog | PIN field; Connect / Quit. | Keep as an exceptional authentication request. |
| Error dialogs | session quit: Reconnect/Quit; create and remote errors: Quit. | Errors should name the failed step and offer the one recovery action that can fix it. |

## Current flows

Counts below treat a tap as a deliberate activation and a decision as a choice
requiring product/protocol knowledge. Text entry is listed separately.

### First launch to first LAN stream

1. Home opens empty with discovery active.
2. Tap the `+` FAB. **1 tap, 1 decision** (the user must choose registration
   versus manual entry).
3. Tap **Register Console**. **1 tap**.
4. Registration form: decide host/broadcast, console generation/software era,
   how to obtain an account ID, and obtain the PIN. **At least 4 decisions;
   2–3 text entries**.
5. Tap **Register**. **1 tap**.
6. Read a raw execution log; on success, Back returns home. **1 tap**.
7. Find and tap the registered console row. **1 tap**. If it is in standby,
   choose Wakeup versus immediate Connect and later tap again. **1–2 extra
   taps, 1 extra decision**.

Best case: **5 taps, at least 5 decisions, 2–3 text entries**. Standby path:
**7 taps and 6 decisions**. This excludes navigating the console's own menus
to obtain its PIN.

### First launch to first PSN/remote stream

1. Home → overflow → Settings. **2 taps**.
2. Enable **PSN Sign-In Registration**, then enable **PSN remote consoles**.
   **2 taps, 2 technical decisions**.
3. Back to Home, open `+`, choose Register Console. **3 taps, 1 decision**.
4. Choose console version and tap **Sign in with PSN**. **2 taps, 1 decision**.
5. Complete web authentication, return, obtain/enter console PIN, tap Register.
   **At least 2 taps plus authentication, 1 text entry**.
6. Return home; in the separate PSN list choose Register, then Connect (or
   Wake then Connect). **2–3 taps, 1 decision**.

App chrome alone is **at least 11 taps and 5 decisions**, plus provider auth
and PIN entry. The requirement to discover two Settings switches is the main
failure.

### Add a second console

Home → `+` → choose Register or Manual → repeat the corresponding form → Save
or Register → Back → select the new row: **5 taps minimum**, **2+ decisions**,
plus address/account/PIN entry. The flow does not reuse the signed-in account
as the obvious starting point.

### Change quality

Home overflow → Settings → scroll to Stream → choose Resolution → choose value
→ choose FPS → choose value → optionally choose codec, bitrate, display refresh,
and many latency toggles → Back → reconnect: **7 taps for resolution + FPS,
10+ taps when codec/refresh are included, and at least 2 technical decisions**.
There is no coherent “quality” choice and reconnect requirements are scattered
through summaries.

## Target information architecture

```text
Home
├── Account/avatar → Account
├── Quality preset: Low latency | Balanced | Data saver
├── Console card(s)
│   └── Play → wake if needed → register/PIN only if needed → stream
├── Add console
│   ├── Sign in with PSN → account consoles → choose console
│   └── Add by address (secondary LAN-only path)
└── Settings
    ├── Controller & touch
    ├── Audio & haptics
    ├── Account & registered consoles
    └── About
        └── Developer (unlocked by five version taps)
            ├── Stream experiments
            ├── Diagnostics & logs
            └── Import / export
```

### Home

- Merge LAN, saved manual, and PSN devices into one deduplicated console list.
- Each card shows name and one explicit status: **On**, **Standby**, **Remote**,
  or **Registration required**. Secondary metadata may show the running game or
  route, but never becomes another primary action.
- A single **Play** button performs the expected transition: connect when on,
  wake then connect from standby, establish the PSN route when remote, or enter
  onboarding at the minimum missing registration step.
- Put the current preset beside the consoles. It is visible and changeable
  without opening Settings; the shipped selection already gives a good stream.
- **Add console** is secondary. Signed-in account discovery is first; **Add by
  address** remains available for LAN-only/expert setups.

### Guided onboarding

1. First launch shows one primary action, **Sign in with PSN**, and secondary
   **Add by address**.
2. Provider authentication returns to a full-screen list of account consoles.
3. Tap a console. If existing credentials match, it is ready. Otherwise show a
   focused PIN entry using the console platform's familiar “Link Device” label.
4. Registration runs behind a determinate task surface; raw logs are available
   only through Developer/Support.
5. Land on Home with that console selected and one **Play** action.

No screen explains the whole process in prose. State, labels, and available
actions reveal the next step.

### Quality presets and existing flags

PLE-116 found operating-rate 960 materially better than the default codec path,
with no benefit above 960; PLE-172 confirmed it and promoted
`stream_decoder_operating_rate_default=true`. PLE-65 promoted the decoupled
decoder input thread. Those landed defaults must not be accidentally undone by
the preset layer.

The initial mapping for UX 3 should be:

| Preset | User promise | User-visible media choices | Underlying flags |
| --- | --- | --- | --- |
| **Low latency** (initial/default) | Fast response with current proven behaviour. | 720p, 60 fps, HEVC, automatic bitrate. | `stream_decoder_operating_rate_default=true` (960) and `stream_decoder_input_thread=true`; no debanding. Preserve every other current default. This is today's effective default and therefore satisfies the runtime-default rule. |
| **Balanced** | Higher image stability/quality with modest buffering. | 1080p, 60 fps, HEVC, automatic bitrate. | Keep the two proven decoder defaults; pacing `balanced` may be included only after its promotion evidence is accepted. Do not silently enable unproven network/recovery experiments. |
| **Data saver** | Lower bandwidth and heat. | 540p, 30 fps, H.264, automatic bitrate. | Keep the proven decoder defaults; no post-processing. Resolution/FPS/codec are the intended behaviour change. |

The preset setting must write the existing underlying keys so the A/B harness
continues to observe them. A “Custom” state may be shown only on Developer when
an individual key diverges; normal Home never exposes Custom as a decision.

Keep in user Settings: controller/touch layout, button mapping, motion, rumble
and touch haptics, audio choice, PSN account, registered consoles, About. Keep
quality presets on Home, not duplicated in Settings.

Move to the hidden Developer page: resolution/FPS/bitrate/codec raw controls;
display refresh policy; timestamps and timestamp rate; low-latency decoder;
operating-rate default/auto/override; decoder priority/input/thread affinity;
performance mode and Wi-Fi lock; late-frame recovery; every pacing mode/lead/
age/nonblocking knob; packet-loss cap/adaptive reporting/reorder bypass;
controller coalescing/unbuffered/trigger fallback/feedback interval; touch
redraw coalescing; stream window optimisation; diagnostics overlay and stats;
audio buffer/FIFO depths; deband render mode; verbose logs; import/export.
Existing preference keys, summaries, and defaults remain intact there.

## System-bar and cutout audit (input to UX 1)

API 36 enforces edge-to-edge for target-SDK apps. Most normal activities use a
`match_parent` root without applying `WindowInsets`; `StreamActivity` explicitly
calls `WindowCompat.setDecorFitsSystemWindows(window, false)`. The captures show
content drawing into bar regions. These are the concrete repair targets:

| Screen | View id / layout | Edge at risk or observed | Required invariant |
| --- | --- | --- | --- |
| Home | `appBarLayout`, `toolbar` (`activity_main`) | Top toolbar occupies the status-bar region. | App bar top padding equals status/cutout inset. |
| Home | `floatingActionButton`, `floatingActionButtonDial` | FAB/dial are anchored to the physical bottom/end; gesture/nav bar can overlap. | Bottom/end margins include navigation and gesture insets. |
| Home | `hostsRecyclerView` | Fixed 96 dp bottom padding does not model the navigation inset. | Recycler content padding includes FAB and dynamic bottom inset. |
| Registration | `rootLayout` and `registButton` | Scroll content begins/ends at physical bounds; the final button can sit under the bottom bar. | Scroll padding consumes top and bottom insets and remains scrollable with IME. |
| Manual entry | `rootLayout` and `saveButton` | Same pattern as registration. | Same invariant. |
| PSN login | `toolbar`, `webView` | Toolbar reaches status area; WebView bottom reaches navigation area. | Apply top inset to toolbar, bottom/IME inset to WebView. |
| Registration execution | `iconImageView`, `shareLogButton`, `infoTextView`, `progressBar` | Root has only 8 dp; bottom actions are constrained to physical parent bottom. | Top and bottom constraints terminate inside safe content bounds. |
| Settings | `toolbar`, `settingsFragment` | Toolbar/content root has no inset listener; last preference can be obscured. | Toolbar consumes top; preference RecyclerView consumes bottom. |
| Registered consoles | `floatingActionButton`, `hostsRecyclerView` | Fragment FAB and list anchor to dialog/activity content bounds without explicit inset padding. | Host container supplies safe insets; scrolling content remains reachable. |
| Session logs | `logsRecyclerView` | Bottom list row/share action can meet the navigation region. | Recycler padding includes bottom inset. |
| Stream overlay | `overlay` and its unnamed bottom `ConstraintLayout` | `fitsSystemWindows=true` conflicts with an explicitly edge-to-edge window and is not a reliable per-edge contract; bottom bar can cover switch/toggle. | Use current bar insets to offset the control tray while video remains edge-to-edge. |
| Touch controller | `leftAnalogStickView`, `rightAnalogStickView`, `l3ButtonView`, `r3ButtonView`, `psButtonView` | All are constrained directly to parent bottom. | Control safe area excludes bottom gesture/nav inset. |
| Touch controller | `dpadView`, `l2ButtonView`, `l1ButtonView`, `shareButtonView`, `r2ButtonView`, `r1ButtonView`, `optionsButtonView`, `faceButtonsLayout` | Left/right/top controls are placed against physical edges; landscape cutout/gesture regions can cover hit targets. | Apply safe drawing + tappable-element insets to the controller constraint container. |
| Stream dialogs | platform dialog window; `pinEditText` in `dialog_login_pin` | IME and navigation bar can crowd the PIN action row. | Dialog content/actions stay above IME and navigation inset. |

UX 1 should test gesture and three-button navigation in portrait and landscape,
including an open keyboard. A screenshot alone is insufficient: assert view
bounds against `WindowInsetsCompat.Type.systemBars() | displayCutout()` for the
named controls.

## Success measures for later phases

- First launch to a console card: provider authentication plus one console
  selection; no Settings visit and no protocol-version decision.
- Ready console to stream: one Play tap. Standby console to stream: the same one
  Play tap, with visible waking progress.
- Add a second account console: Add console → choose console; manual address is
  present but secondary.
- Change quality: open the Home preset chooser and choose once: **2 taps, 1
  plain-language decision**.
- At every step, the primary action and every touch target are fully inside the
  safe window bounds under gesture and three-button navigation.
