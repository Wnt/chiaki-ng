# Touch overlay: what the two reference clients do, and what we do (PLE-342)

Both reference clients are kept decompiled on the workspace box for exactly this
kind of question:

- `android-low-latency-examples/psremoteplay-apk/` — Sony, `com.playstation.remoteplay` 9.1.0
- `android-low-latency-examples/geforcenow-apk/` — NVIDIA GeForce NOW, plus `runtime-capture/`

This is a study of **geometry and affordance**, not of artwork. None of their
drawables were copied; every shape we ship is drawn from the numbers below.

A note on reading Sony's APK: its resource names are stripped and apktool leaves
the two overlay layouts as raw binary XML
(`apktool-out/res/layout/APKTOOL_RENAMED_0x7f0b00bd` and `…00be`). They have to
be read out of `base.apk` with `aapt dump xmltree`. `aapt2` prints negative dp as
huge positives (`16777209.000000dp` is `-7dp`), which is worth knowing before
trusting any margin from it.

---

## 1. How a thumbstick gets its affordance

### GFN

`apktool-out/res/drawable-anydpi-v24/ic_gamepad_joystick_background.xml` — a
140 dp vector of **three concentric circles**, and that is the entire trick:

| ring | Ø | fill | stroke |
|---|---|---|---|
| outer rim | 139 dp | `#000000` @ 15 % | `#ffffff` w=1 @ **50 %** |
| travel ring | 95 dp | none | `#ffffff` w=1 @ 15 % |
| home socket | 58 dp | `#ffffff` @ 10 % | none |

The socket is *exactly* the knob diameter
(`ic_gamepad_joystick_thumb.xml`, 58 dp, `#ffffff` @ 60 % plus a 21-dot knurl at
40 %), so an untouched stick looks like a knob **seated in a socket**.

Behaviour (`jadx-out/sources/com/nvidia/uilibrary/gamepad/widget/OSGJoystick.java:130-146`):
the assembly is drawn at its layout home when idle, and the whole thing
translates under the finger on `ACTION_DOWN` — a floating origin with a *fixed*
activation circle (`W2/a.java:25` measures from the layout centre). Travel is
`touchRadius − thumbWidth/2` = 49 dp (`OSGJoystick.java:152`).

### PS Remote Play — and this is the load-bearing finding

Sony ships **two different stick designs, chosen by orientation**, and they
disagree about visibility:

- **Landscape** (`layout/0x7f0b00bd`): base `0x7f080058` and knob `0x7f080059`
  both carry `android:visibility=1` (INVISIBLE). Nothing is drawn until a finger
  lands (`registerActivityLifecycleCallbacks.java:475-575` flips both to visible
  and moves them to the touch point, then back to INVISIBLE on UP). The hit
  region is **half the screen width × the lower 60 % of its height** per side.
  The only affordance is a one-time coach mark, string `0x7f0f0074`: *"Touch here
  to operate the controller sticks."*
- **Portrait** (`layout/0x7f0b00be`): base `0x7f080058` is a **115 dp view,
  `android:visibility=0` — always drawn** — showing a 110 dp thin outline ring at
  alpha 102/255 (40 %), fixed position, `marginTop 11.5dp`,
  `marginRight 40.5dp`. Travel radius `0.35 × 115` = 40.25 dp.

Sony needing a coach mark in landscape, and abandoning the invisible stick
entirely in portrait, is the direct evidence for our defect 1.

### What we do

`res/drawable/control_analog_stick_base.xml` — our own three-ring gate at
128 dp: rim r=62 (15 % black fill, 50 % white hairline), travel ring r=40 (= the
`control_analog_stick_radius` the code actually clamps to), home socket r=24
(= `control_analog_stick_handle_radius`, so the resting knob looks seated).
Knob `control_analog_stick_handle.xml`, 48 dp, 36 % white fill with a 65 % rim.

`AnalogStickView` previously left `center` null until first touch and drew
**nothing at all** — the stick had no position to discover. It now draws the gate
and a seated knob at the view centre when idle (alpha 178) and follows the finger
when engaged (alpha 255), keeping chiaki's relative/floating activation.

---

## 2. Face and system buttons

| | GFN | PS Remote Play | ours (after PLE-342) |
|---|---|---|---|
| face button box | 64 × 64 dp | 79 × 79 dp landscape, 61 × 61 dp portrait | 62 dp half-cells in a 124 dp diamond |
| drawn shape | 63.1 dp circle | 61 dp disc | glyph, 44 dp |
| fill / stroke | 15 % black, 50 % white w=1 | alpha 230 rim, glyph 239-251 | glyph at 35 % white (`control_primary`) |
| label | **24 dp glyph vectors, 70 % white, never text** — `ic_gamepad_a/b/x/y.xml`; no `TextView`, no `drawText` in `OSGButton` | pre-rendered PNG glyphs | vector glyphs |

Our `control_primary` was `#22ffffff` — **13 % white**, against GFN's 50 % rim and
Sony's 40 % portrait outline. That is the "graphics need work" complaint in one
number; it is now `#59ffffff` (35 %), pressed `#CCffffff`.

### Create and Options specifically

Sony's PS5 assets are **rasterized words** — the 144 px PNGs behind
`0x7f080216` / `0x7f0801a8` contain a ~10 × 21 dp capsule outline with the word
`CREATE` or `OPTIONS` baked in above it. Their **PS4** assets are glyphs: a share
arrow, and *three horizontal bars* for Options.

So the glyph precedent is Sony's own. We draw our own geometry:
`control_button_share.xml` is a rounded frame split by a vertical rule,
`control_button_options.xml` is three stacked rules — both in a square 48 dp
viewport, no letters.

The *stretching* was ours alone: `ButtonView.onDraw` called
`setBounds(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)`,
so a square vector in a 48 × 64 dp view was stretched 1:1.33. `ButtonView` now
supports `app:drawableFitCenter`, which caches a centred, aspect-correct rect in
`onSizeChanged`, so the hit target can be any shape without deforming the mark.

---

## 3. Hit target versus drawn shape

Both clients make the touchable area **much** larger than the art, and neither
uses `TouchDelegate`.

- **GFN** hit-tests a circle about each view's layout centre with radius
  `(touch_area_extra + 1) × max(w,h)/2` (`W2/a.java:34-36`), default extra 0.8
  (`res/values/dimens.xml:204`). A 63 dp face button gets a **115 dp hit circle,
  ≈ 3.3× the drawn area**. Overlaps resolve **nearest-centre-wins** at the root
  listener (`V2/a.java:37-72`).
- **Sony** bakes **9 dp of transparent margin per side** into every bitmap
  (158 px canvas, 122 px art → 79 dp view, 61 dp drawn ≈ 1.68×), and hit-tests
  the d-pad as **one 222 dp rotated container** split into 8 angular sectors
  (60° cardinal, 30° diagonal, no centre dead zone) — the arrows are inert
  `ImageView`s (`registerActivityLifecycleCallbacks.java:641-758`). Misses inside
  the cluster are swallowed rather than falling through to the video.

We already had the nearest-centre tie-break (`ButtonView.bestFittingTouchView`).
PLE-342 keeps every hit target at ≥ 48 dp while drawing smaller marks inside
them, which is the same relationship both clients rely on.

---

## 4. Portrait versus landscape

- **GFN has no portrait.** `AndroidManifest.xml:185` pins the stream activity to
  `screenOrientation="sensorLandscape"`. There is exactly one overlay layout,
  `res/layout/controller_flex_layout.xml`, and **no** `layout-land/`,
  `values-land/` or `values-sw*dp/` variant carries a single gamepad dimension.
  It adapts by one scalar: each hand is a pane aspect-locked to 295.68 : 400.63
  dp, capped at 400.63 dp tall, bottom-anchored (`vertical_bias="1.0"`) and
  pinned to its screen edge. Everything inside is a guideline percentage of that
  pane.
- **Sony has two hand-authored layouts**, picked in *code* by aspect ratio, not
  by resource qualifier (`onPrepareNavigateUpTaskStack.java:1101-1103`, w/h >
  0.825 → landscape; inflated at `registerActivityLifecycleCallbacks.java:977-981`).
  Portrait is not a scaled landscape: different stick design (above), outline art
  instead of filled discs, backdrop rings behind the d-pad and face cluster, and
  `paddingBottom 56dp` pulling both columns down.

**What we take from this.** GFN's single bottom-anchored, edge-pinned, height-capped
pane is the cheaper idea and it is the one that answers the user's complaint:
the pad must be **bounded and bottom-anchored**, not spread evenly down the
window. We use one layout for both orientations (no second inflation, no
orientation-specific resource), bounded to a 324 dp band — see
`res/layout/fragment_controls.xml`.

---

## 5. Two behaviours deliberately *not* taken

- **Auto-hide.** Sony fades the whole overlay out after 3000 ms idle
  (`registerActivityLifecycleCallbacks.java:1364`, 150 ms alpha anim). That is an
  animation on the in-stream surface and a behaviour change rather than a defect
  fix, so it belongs behind a setting in its own ticket, not here.
- **Backdrop rings** behind the d-pad and face cluster (Sony portrait,
  drawables `l` and `gJ`, 229 dp). Pure added overdraw over the video for
  grouping we get from position alone.

---

## 6. Why none of this costs a frame

`runtime-capture/sf_full_gamepad.txt:1472` (S22 Ultra, GFN with the pad up) is
the useful measurement: the overlay is a separate top-level window composited
`Comp Type DEVICE` on its own HWC plane, and the video `SurfaceView` **stays**
`DEVICE` in both the pad-up and pad-down dumps. An overlay of this kind does not
demote the video layer to client composition.

Ours is the same shape of thing: a `View` hierarchy over a `SurfaceView`,
invalidated by input events, not by video frames. Drawing an idle stick gate adds
two vector fills to a layer that is already composited every frame whether it is
blank or not — it adds no per-frame work, only a one-time raster.
