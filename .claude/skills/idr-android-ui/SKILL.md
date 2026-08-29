---
name: idr-android-ui
description: >-
  UI/UX standards for the IDR app — a single-screen driver HUD painted over an
  osmdroid map. Covers Material 3 (MDC-Android, XML Views — NOT Compose),
  glanceable driver-first design, the Mode→colour/label contract, the
  GNSS-vs-dead-reckoning trail rules, and edge-to-edge insets. Use whenever
  editing activity_main.xml, OsmdroidMapRenderer.kt, MainActivity's view code,
  or anything under app/src/main/res (themes, colors, dimens, drawables).
---

# IDR Android UI Standards

The app is **one screen**: a full-bleed osmdroid `MapView` with a small set of
overlay chips and controls on top of it. There is no list, no text input, no
toolbar, no second screen. Optimise for that — do not add navigation scaffolding,
RecyclerViews, or CoordinatorLayout patterns the app has no use for.

> **Invoking this skill:** it is a project skill loaded at session start, not
> callable via the `Skill` tool mid-session. "Use the skill" means read this
> file and follow it. Work one stage at a time — theme, then tokens+layout,
> then renderer — building and installing to the device between each so a
> regression is bisectable (§3 step 5, §8).

## 1. Who the user is (from `docs/architecture-android.md` §14 — this is binding)

A driver, often on a two-wheeler, glancing **< 1 second** at a hot phone in
bright sunlight. Every UI decision serves that.

- **Never freeze, never jump.** The position icon moves continuously with an
  honest uncertainty circle. Corrections are slewed by `PositionInterpolator`,
  never snapped — do not add code that teleports the marker.
- **Be honest without alarming.** A quietly growing uncertainty circle is how
  degradation is shown. Never display a confident position we don't have; never
  raise a warning the driver can't act on.
- **No jargon on the driver-facing surface.** Not "EKF", "dead reckoning",
  "drift", "NHC". The whole message is *"GPS lost — still tracking you"*
  (already `R.string.mode_dead_reckoning`). "Drift" text is acceptable only
  because it lives behind the evaluator-facing compare view context.
- **Glanceability.** Large type, high contrast, few elements. Automatic
  day/night.
- **Silence while driving.** No dialogs, no toasts, no notifications mid-drive.
  Permission prompts happen once, before the engine starts.
- **Colours must survive sunlight and colour blindness.** Trail segments and
  mode states must differ in **lightness or pattern**, not hue alone. Never
  red-against-green.
- **Separate evaluator from driver.** Live numbers, the two-trace overlay and
  the GNSS-mute toggle are evaluator tools — they stay behind the "Compare
  view" switch, not in the driver's default view.

## 2. Stack & hard constraints

| Thing | Value | Implication |
| --- | --- | --- |
| UI toolkit | **XML Views** (`AppCompatActivity` + `findViewById`) | Not Compose. Do not introduce Compose or ViewBinding-vs-Compose debates. ViewBinding is fine to adopt; Compose is out of scope. |
| `com.google.android.material` | **1.12.0** | Full Material 3 is available (`Theme.Material3.*`, `MaterialCardView`, `MaterialButton`, `FloatingActionButton`, M3 colour roles). |
| `minSdk` | **26** | No `WindowInsetsController` shims needed below 26; `androidx.core` `WindowCompat`/`ViewCompat` insets APIs are the target. |
| `constraintlayout` | 2.1.4 | Keep the hierarchy flat — one `ConstraintLayout` root, no nested weight-`LinearLayout`s. Use a `Flow` or chains for the button row. |
| Map | `osmdroid-android:6.1.20` | Overlay chips sit on top of arbitrary map imagery — see the HUD-chip rule in §5. |
| Theme today | `Theme.MaterialComponents.DayNight.NoActionBar` (**M2**) | Must migrate to M3 first — see §3. |

## 3. Prerequisite: migrate the theme M2 → M3 (do this before touching layouts)

The current `res/values/themes.xml` is Material 2. M3 component styles and
`?attr/colorSurfaceContainer*` / `?attr/textAppearance*` tokens will not resolve
under it. Migration steps:

1. `themes.xml` parent → `Theme.Material3.DayNight.NoActionBar`.
2. Map the existing brand colours in `res/values/colors.xml` onto M3 roles:
   - `idr_primary` (#1565C0) → `colorPrimary`; keep `idr_primary_dark` for the
     status-bar scrim if needed.
   - Add `colorSurface`, `colorOnSurface`, `colorSurfaceContainerHigh`,
     `colorOutline`, `colorError` for light; add a `res/values-night/` variant.
   - `idr_navic` (#2E7D32 green), `idr_dead_reckoning` (#FF8F00 amber) stay as
     **named semantic colours** — they are the mode palette (§4), not theme
     roles, and must stay stable across day/night.
3. Add `res/values/dimens.xml` with a spacing scale
   (`spacing_small`=8dp, `spacing_medium`=16dp, `spacing_large`=24dp) and
   `hud_corner_radius`, `hud_elevation`.
4. Remove hardcoded `#FFFFFF` / `#CC000000` / raw `16dp` from
   `activity_main.xml` — reference tokens instead. **Exception:** HUD chips over
   the map — see §5.
5. Build (`./gradlew :app:assembleDebug`) after the theme change alone, before
   layout edits, so a regression is bisectable.

## 4. The Mode → label + colour contract

`PositionState.mode` is a `Mode` enum with exactly these values. `MainActivity`
already maps them to labels via `modeLabel()`; keep that, and add a colour so the
mode badge's accent (a left bar or its container tint) tracks state:

| `Mode` | Label string | Accent colour | Meaning to driver |
| --- | --- | --- | --- |
| `INIT` | `mode_init` "Starting…" | `colorOutline` (neutral grey) | warming up |
| `GNSS` | `mode_gnss` "GNSS" | `idr_primary` (blue) | normal satellite fix |
| `NAVIC` | `mode_navic` "NavIC" | `idr_navic` (green) | fix includes IRNSS sats |
| `DEGRADED` | `mode_degraded` "Degraded fix" | `idr_dead_reckoning` (amber) | weak geometry |
| `DEAD_RECKONING` | `mode_dead_reckoning` "GPS lost — still tracking you" | `idr_dead_reckoning` (amber) | no fix, model + IMU only |

Rules:
- Amber and green must also differ in **shape or icon**, not just hue (colour
  blindness). Give `DEAD_RECKONING` a distinct icon (e.g. a pulsing dot) as well
  as the amber accent.
- `DEAD_RECKONING` is the one state that gets **prominence** — a slide-down
  banner or an expanded chip, because it's the state the product exists for.
  Every other state stays a quiet single chip.
- The `"N sats"` / `"(NavIC N)"` suffix that `MainActivity` appends is
  evaluator-ish detail; keep it small / secondary within the chip, not the
  headline.

## 5. Component standards

- **HUD chips over the map** (`mode_badge`, `speed_text`, `drift_text`): these
  sit on unpredictable map imagery. They **keep a fixed high-contrast treatment**
  — solid dark translucent container, white text — because `?attr/colorSurface`
  would be invisible over a dark tile and vice versa. Wrap each in a
  `MaterialCardView` with `app:cardBackgroundColor` set to a dedicated
  `@color/hud_scrim` (≈ `#CC1C1B1F`), `app:cardCornerRadius="@dimen/hud_corner_radius"`,
  `app:cardElevation="@dimen/hud_elevation"`. This is the deliberate exception to
  the "no hardcoded colours" rule — document it with an XML comment.
- **Speed readout**: it is the single most-glanced element. Make it the largest
  type on screen (`?attr/textAppearanceDisplaySmall` or bigger), its own chip,
  bottom-leading or top-centre. `R.string.speed_format` ("%.0f km/h") stays.
- **Action buttons** (`mute_toggle`, `compare_toggle`, `record_button`): today
  they're three full-width framework `Button`s at 11sp — they dominate the
  screen. Restyle **in place** — do not hide or relocate them behind a gesture.
  `docs/architecture-android.md` §14 is explicit that judges want the mute
  toggle and the two-trace overlay reachable; this is an SIH demo app and those
  are first-class controls, not clutter. The fix is visual weight, not
  discoverability:
  - `MaterialButton` with `style="@style/Widget.Material3.Button.OutlinedButton"`
    (or `...TonalButton` for `record_button` as the primary), in a single
    `Flow` / horizontal chain pinned to the bottom, with icons so the labels can
    shrink.
  - `record_button` while recording: red dot + elapsed timer, not just a text
    swap.
- **Recenter**: today an `ImageButton` with a framework icon. Make it a
  `com.google.android.material.floatingactionbutton.FloatingActionButton`
  (`app:fabSize="mini"`). Leave it **always visible** for now — auto-hiding it
  while following needs a new callback on the `MapRenderer` interface, which is
  the osmdroid↔Mapsforge seam (F4 still open) and was just edited in `d90f984`.
  Defer that to a follow-up once F4 is settled.
- **Permission overlay** (`permission_overlay`): this one is NOT over live map
  imagery in practice (it's shown pre-engine) — use real M3 tokens here,
  `colorSurface` container, `Widget.Material3.Button` for the CTA. Give it a
  `ShapeableImageView` or vector illustration so it doesn't read as an error.
- **Touch targets** ≥ 48dp (`android:minWidth`/`minHeight` or FAB sizing).
  `contentDescription` on every `ImageButton`/`ImageView`; decorative art gets
  `android:importantForAccessibility="no"`.

## 6. Map renderer (`OsmdroidMapRenderer.kt`) rules

- Replace the deprecated `Polygon.fillColor` / `strokeColor` / `strokeWidth`
  setters (they generated build warnings) with
  `fillPaint`/`outlinePaint` on the `Polygon`.
- Trail segment widths and marker sizes: derive from `dp` via
  `context.resources.displayMetrics.density`, don't hardcode pixel floats
  (`8f`, `10f`).
- **Trail colours** (`GNSS_TRAIL_COLOR` blue, `DEAD_RECKONING_TRAIL_COLOR`
  amber): must also differ in **width or dash** — make the dead-reckoning
  segment dashed (`outlinePaint.pathEffect = DashPathEffect(...)`) so it reads
  without colour. The "plain GPS" compare trail stays grey and thin.
- **Uncertainty circle**: tint it to match the mode — blue-ish under GNSS,
  amber under `DEGRADED`/`DEAD_RECKONING` — so its meaning ("how sure are we")
  is reinforced by colour. Keep it semi-transparent; never a hard edge.
- **Vehicle marker**: `ic_position_dot.xml` already has an up-arrow + ring + dot
  and `updatePosition()` already calls `vehicleMarker.setRotation(headingDeg)`,
  so heading *is* shown — refine the arrow's legibility rather than adding one
  from scratch. Add a distinct silhouette for `DEAD_RECKONING` (hollow / pulsing)
  vs the solid dot shown with a fix; this needs the renderer to know the current
  `Mode` (it's on every `appendTrailPoint` call — cache it).
- Never animate the marker faster or slower than the 16 ms interpolation tick
  already in `MainActivity.observeEngineState()`; the renderer just draws what
  it's given.

## 7. Edge-to-edge insets

- In `MainActivity.onCreate`, before `setContentView` results are used:
  `WindowCompat.setDecorFitsSystemWindows(window, false)`.
- Apply insets with `ViewCompat.setOnApplyWindowInsetsListener` on the overlay
  containers — pad the **top** HUD chips down by
  `systemBars().top`, pad the **bottom** control row up by
  `max(systemBars().bottom, ime().bottom)`. Do **not** use
  `android:fitsSystemWindows="true"` in XML.
- The `MapView` itself should draw full-bleed **under** the bars (that's
  desirable — more map). Only the chrome gets inset.
- Status-bar icon contrast: set `WindowInsetsControllerCompat.isAppearanceLightStatusBars`
  based on day/night so icons stay visible over the map.

## 8. Checklist before you call a UI change done

- [ ] Theme is `Theme.Material3.*`; `./gradlew :app:assembleDebug` green.
- [ ] No raw hex or raw `dp` in `activity_main.xml` except the documented
      `@color/hud_scrim` HUD exception.
- [ ] Every `Mode` value has a visible label **and** a non-hue-only
      distinction; verified by forcing each mode (StubEngine or a test hook).
- [ ] `DEAD_RECKONING` is visually prominent; all other modes are quiet.
- [ ] Speed is the largest element on screen.
- [ ] Recenter FAB hides while following, appears after a pan.
- [ ] Insets: nothing under the notch or nav bar; map still full-bleed.
- [ ] Trails distinguishable in greyscale (screenshot, desaturate, check).
- [ ] Installed and eyeballed on the real device
      (`./gradlew :app:installDebug` — device V2253 is the test phone), in
      both light and dark, ideally outdoors for sunlight legibility.
- [ ] No new dialog/toast/notification on the driving path.

## 9. Anti-patterns (do not do these here)

- Adding Jetpack Compose, a nav graph, bottom navigation, or a Toolbar.
- Theming the over-map HUD chips with `?attr/colorSurface` (they vanish over
  matching tiles).
- Making every mode loud — only `DEAD_RECKONING` is loud.
- Distinguishing trail/mode state by hue alone.
- Snapping/teleporting the marker, or bypassing `PositionInterpolator`.
- Permission prompts, dialogs or toasts once the engine is running.
- Hardcoding pixel sizes in `OsmdroidMapRenderer`.
