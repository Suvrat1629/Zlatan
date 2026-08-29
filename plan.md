# IDR App — Build Plan

**Project:** Intelligent Dead Reckoning with GNSS Fusion (SIH 26168, ISRO). Read this
fully before writing any code — it corrects two mistaken assumptions from an earlier
draft of this file (see "Superseded decisions" below).

## Build status (2026-08-29): Phase 0 implemented

The codebase now exists — see `README.md` for the module-by-module status. Short version:
all 7 pure-Kotlin modules (`:core-*`, `:engine`) are built **and test-verified** via
`./gradlew ... test` (28 passing tests). The 4 Android modules (`:android-*`, `:app`) are
written but **not compiler-verified** — no Android SDK was available in the build
environment, so treat them as reviewed-but-untested until `./gradlew assembleDebug` runs
for real in Android Studio. `docs/PositioningEngine.kt.draft` was ported into
`:core-types` essentially as-is. Read `README.md` before re-reading the rest of this file
line by line — it says what's real right now.

## Source of truth — read `docs/` before inventing anything

This repo's `docs/` folder is a **verbatim copy** of the team's current authoritative
design docs (copied from the `sih-26168-notes` vault; three people — Aneesh, Tanmay, and
the model team — wrote them, most recently 2026-08-29). Do not re-derive architecture
decisions from this file alone; this file is a **short index + Phase 0 checklist**, not a
replacement. When this file and `docs/` disagree, **`docs/` wins** — come back and fix
this file, don't silently follow the stale copy.

| File | What it is | Read it for |
| --- | --- | --- |
| `docs/architecture-android.md` | **Authoritative Android app architecture** (Aneesh) | Module structure, threading, sensor/GNSS acquisition, storage, asset versioning, UI, parity gates, Android/model caveats, build order — this is the main spec |
| `docs/architecture-system.md` | System-wide architecture (Aneesh) | Algorithms, maps, full system design — reference for context, not needed line-by-line to start the app skeleton |
| `docs/integration-pipeline.md` | Integrator's execution plan (Tanmay) | Row-by-row status of the model↔app integration, code skeletons, current blockers, what's decided vs still open |
| `docs/handoff-to-app-team.md` | The short version of the above, addressed to you | Start here if you only read one thing — it's 90 lines |
| `docs/model-app-integration-contract.md` | The full interface questionnaire sent to the model team | Every input/output/format question, with recommendations |
| `docs/model-app-integration-answers.md` | The model team's answers | The authoritative numbers: tensor shape, feature math, normalization |
| `docs/model-questions-part-2.md` | Open follow-up questions to the model team | What's still unconfirmed, and what's a hard blocker vs a clarification |
| `docs/model-results.md` | Model accuracy history | Context on how good the current model actually is |
| `docs/PositioningEngine.kt.draft` | The draft interface (not frozen) | The actual seam you code against — copy into `:core-types`, adapt as F1/F3 resolve |

## Non-negotiable facts (stable across all the docs above)

- **Language:** Kotlin, native Android. Not Flutter/React Native — cross-platform bridge
  latency becomes position error, not just UI lag, because dead reckoning integrates
  sensor data over time.
- **The app holds no navigation mathematics.** It reads sensors, keeps the phone awake,
  draws the map, handles buttons. All positioning math lives in `:engine` + `:core-*`
  (pure Kotlin, no `android.*` import — this is enforced by a build check, not
  convention, because it's also the edge-engine deliverable the problem statement
  requires). See `docs/architecture-android.md` §1–2.
- **Model:** TensorFlow/Keras → TFLite (LiteRT). Architecture decision is **settled: TCN**
  (not GRU) — builtins-only, no `tensorflow-lite-select-tf-ops` needed. Input
  `[1, 50, 7]` float32 (5 s window at a 10 Hz model rate), output `[1, 1]` float32 =
  forward speed in m/s directly, no de-normalization.
- **GNSS: raw `LocationManager` `GPS_PROVIDER`, not `FusedLocationProviderClient`.**
  Fused does its own dead reckoning in tunnels, which corrupts the drift metric (the
  entire competition score) and breaks the Kalman filter's white-noise assumption. See
  `docs/architecture-android.md` §5 for the full reasoning — don't add
  `play-services-location` for GNSS.
- **Sensors: sample at the highest rate the device reliably delivers, not a fixed 10 Hz
  or 100 Hz.** The 10 Hz in the problem statement is the *output* rate. Decimation down
  to the model's rate is an explicit, tested, anti-alias-filtered pipeline stage (gate
  G2a) — never "grab every Nth sample" naively. See `docs/architecture-android.md` §4.
- **Module structure is fixed** (`docs/architecture-android.md` §2):
  ```
  :app  :android-sensors  :android-assets  :android-model      ← Android, yours
  ─────────────── no android.* import below this line ───────────────
  :engine  :core-nav  :core-map  :core-model  :core-assets
  :core-replay  :core-types                                     ← pure Kotlin, integrator's
  ```
  Build this from commit one — retrofitting the split later is expensive.
- **Assets (model, map, config) are versioned data resolved through an `AssetProvider`,
  never opened by a literal path**, with a manifest asserted at load (shape/dtype,
  channel order, units, checksum, a self-test vector) — the engine refuses to start on a
  mismatch rather than producing silently wrong positions. See §7.
- **Offline posture:** zero internet, not just zero GNSS — the two are different physical
  phenomena. Only map tile/region imagery is network-dependent; everything else
  (sensors, inference, dead reckoning, fusion, map-matching) is on-device.

## Superseded decisions — do not follow an older note that still says this

An earlier version of this plan (and a decisions note in the vault) assumed two things
that are now **wrong**:
1. ~~`FusedLocationProviderClient` / `play-services-location` for GNSS~~ → raw
   `LocationManager` `GPS_PROVIDER` (see above).
2. ~~osmdroid, decided~~ → **still an open decision (F4)**, not settled. Aneesh's
   architecture doc recommends **Mapsforge** instead — bulk-downloading raster tiles from
   the public OSM servers osmdroid uses violates their usage policy (a real legal/ToS
   risk, not just a technical one), and osmdroid's opportunistic caching leaves a blank
   map on a cold start into a dead zone, which is exactly the tunnel/blackout scenario
   this app targets. See `docs/architecture-android.md` §9 "Map rendering choice" for
   the full comparison. **This needs a team decision before the map screen is built** —
   see "Open decisions" below. Whichever is picked, keep the renderer behind a thin
   map-view interface (the engine only emits a position; it never talks to the map
   directly), so switching later costs about a day, not a rewrite.

## Open decisions — need a team answer before certain rows can proceed

| # | Decision | Blocks | Current lean |
| --- | --- | --- | --- |
| **F1** | Are the 7 features + normalization computed in Kotlin (current plan), or baked into the `.tflite` graph? | Row 7 (preprocessing port), most of the parity-gate surface | Aneesh prefers in-graph (removes the #1 silent-failure risk — duplicated feature math drifting between Python and Kotlin); model team currently computes them in `preprocess.py`. Ask the model team. |
| **F3** | Gyro sensor: `TYPE_GYROSCOPE` (what the model was trained on, probably) or `TYPE_GYROSCOPE_UNCALIBRATED` (Aneesh's preference, own bias estimation)? | `onImuSample`'s parameter list | Confirm what IO-VNBD training used before switching. |
| **F4** | Map library: Mapsforge or osmdroid? | The map screen | See "Superseded decisions" above — Mapsforge is the better-argued choice, osmdroid is faster to a demo. App team's call. |
| — | Heading estimator — **currently has no owner.** Speed model gives speed only; dead reckoning needs a direction. Not listed anywhere in Part C. | Row 10/11 (dead reckoning can't produce a position without it) | Needs to be assigned to the fusion seat (M2) explicitly, today. |
| — | Map-matching — **currently has no owner or spec.** Lives inside the engine box; ranked the #3 hardest problem in the project. | Row 10/11 | Needs an owner (M3) named explicitly. |

None of these block starting Phase 0 (see below) — they block later rows. But the two
"no owner" items are the biggest live risk in the whole project right now; raise them
with the team today, don't wait for them to surface during integration.

## What's already delivered, ready to use

- `docs/PositioningEngine.kt.draft` — the interface to build against. Not frozen (depends
  on F1/F3, and `PositionState`'s shape needs the fusion owner's sign-off), but stable
  enough to start.
- A stub model interface exists in spec form (`engine_stub.tflite`, real
  `[1,50,7]→[1,1]` shape, constant output, confirmed builtins-only) in the
  `sih-26168-model` repo — pull it in for Row 5/6 below.
- The exact 7-feature math (gravity split → horizontal/vertical/magnitude + gyro
  magnitude) is in `docs/model-app-integration-answers.md` A4/A5 and reproduced in
  `docs/integration-pipeline.md` Appendix D.

## Phase 0 build order (start here — needs no model, no filter, nothing from anyone else)

From `docs/architecture-android.md` §15, this is the actual order to work in:

1. [x] Project skeleton with the module structure above, plus the `android.*` import-ban
       build check wired in from the start. `verifyNoAndroidImports` runs as part of
       `check` on every `:core-*`/`:engine` module (root `build.gradle.kts`).
2. [x] Permissions flow, foreground service, engine-lifetime ownership — `MainActivity`
       requests permissions then binds; `EngineService` is the sole owner of `engine.start()`/`stop()`.
3. [x] Sensor layer (`:android-sensors` `SensorSource`) — highest-rate acquisition on a
       dedicated handler thread, `maxReportLatencyUs = 0`; ring buffer + conditioning
       stage live in `:engine` (`RingBuffer.kt`, `ConditioningStage.kt`). **Written, not
       compiler-verified** — see README.md.
4. [x] GNSS layer (`GnssSource` in `:app`) — raw `GPS_PROVIDER`, `GnssStatus` callback,
       NavIC counting via `CONSTELLATION_IRNSS` + `usedInFix`. Full C/N₀/geometry quality
       gate is still the placeholder `ModeArbiter` describes, not the real Aneesh §5 gate.
5. [x] The `PositioningEngine` seam (ported from `docs/PositioningEngine.kt.draft` into
       `:core-types`) plus `StubEngine` (`:engine`) — **holds last known GNSS speed**, matches
       the draft's behaviour, has passing tests.
6. [x] Trip recorder (`TripRecordingEngine` in `:app`, canonical format in `:core-replay`)
       — start/stop toggle wired to the record button, writes to app-external storage.
7. [x] Map view (`OsmdroidMapRenderer` behind `MapRenderer`), mode badge, GNSS-mute toggle
       — **F4 not actually decided**, osmdroid used as the pragmatic default; swapping is
       meant to be a one-class change, per the interface.
8. [x] Replay harness (`:core-replay` `ReplayEngine`) — this is what let `:engine`'s tests
       run without a phone; see `engine/src/test/.../RealEngineTest.kt`.
9. [ ] Load a placeholder model file and run inference on-device — **blocked on having an
       actual `engine.tflite` binary**, which this build doesn't include (no network
       access to the model repo when this was built). `TfliteSpeedEstimator` is written
       and `EngineFactory` falls back to `StubEngine` automatically if the file's absent —
       but the actual "does TFLite load and run on a real device" proof is still open.
       **This is the single highest-value next step** — see `app/src/main/assets/README.md`.

**Checkpoint:** not yet reached — needs a real device and step 9. Everything up to that
point is written; nothing has run on an actual phone yet (emulators have no real sensors
or GNSS, so this genuinely can't be checked off from this build environment either way).

**Phase 1 onward** (real model arrives, parity gates G0–G7, dead reckoning + fusion
wiring, drift measurement, performance tuning) — full detail in
`docs/architecture-android.md` §11 and `docs/integration-pipeline.md` rows 8–14. Don't
plan that far ahead in this file; it'll drift out of sync with the real integration
status faster than this file gets updated. Read `docs/integration-pipeline.md` directly
when you get there — it's the live-updated one.

## A few things worth internalizing before writing code

- **Never freeze and never jump.** The whole product promise is a continuously moving
  icon with honest uncertainty. Every correction is slewed over 1-2 seconds, never
  snapped — including on GNSS re-acquisition after a tunnel.
- **No jargon where the driver can see it.** Not "EKF", "dead reckoning", "drift". "GPS
  lost — still tracking you" is the whole message. (Full UX principles:
  `docs/architecture-android.md` §14.)
- **Test the engine on a laptop, not a phone.** Everything in `:engine` and below is
  pure Kotlin and runs on the JVM by replaying recorded traces — no device, no vehicle,
  seconds per run. Only the Android-specific layers (sensor rates, service lifetime,
  permission flow) need on-device testing. This split is the actual payoff of the
  module structure above — don't skip it and end up needing a phone for every test.
