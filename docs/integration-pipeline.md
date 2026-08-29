---
tags: [sih2026, integration, pipeline, plan]
ps_id: SIH26168
role: integrator (model ↔ app)
---

# Integration Pipeline — model → app, end to end (my plate)

Back to [[00-Overview]].
Related: [[13-Model-App-Integration-Contract]] (questions sent to the model team) ·
[[13-ANSWERS-Model-Team]] (their answers) · [[Model-Questions-Part-2]] (open follow-ups) ·
[[12-Model-Results]] (model status) ·
`Aneesh/SIH-IDR-architecture.md` + `Aneesh/SIH-IDR-android.md` (system + app architecture).

> **This is the integrator's execution doc** — my slice of the model→app integration,
> as a row-by-row plan with parity mechanics and code skeletons. The numbered **Rows** are
> the plan (what, who, when, done-when); the **Appendices** are build detail.
>
> **Architecture authority:** `Aneesh/SIH-IDR-architecture.md` (system) and
> `Aneesh/SIH-IDR-android.md` (app layer) are the authoritative architecture. Where this
> doc used to disagree with them, it has been reconciled — see the box below. Model-side
> facts still come from [[13-ANSWERS-Model-Team]] (what the model team actually built),
> which itself diverges from Aneesh's idealised model spec — those divergences are flagged
> as open forks.

> ### Reconciliation with Aneesh's architecture docs (2026-08-29)
> **Adopted from Aneesh (this doc changed to match):**
> - GNSS from the **raw `LocationManager` GPS provider**, NOT `FusedLocationProviderClient`
>   (Fused does its own dead reckoning in tunnels → corrupts the drift metric; its smoothed
>   output breaks the EKF white-noise assumption). — Row 4, Appendix C.6
> - Sensors sampled at the **highest reliable device rate**; decimation to the model rate
>   is an **explicit, anti-alias-filtered, tested** stage (adds parity gate **G2a**). — Rows 4, 6, 9
> - **No navigation constants in Kotlin source** — window length, stride, rates, thresholds
>   live in a versioned **config file**; a per-asset **manifest** (shapes, dtypes, channel
>   order, units, gravity handling, window/stride, rate, norm constants, self-test vector,
>   SHA-256, min engine version) is asserted at load. Replaces the earlier "version guard". — Row 6, Appendix C.7
> - **Module layout** follows Aneesh `SIH-IDR-android.md` §2 (`:app` / `:android-*` /
>   `:engine` / `:core-*`, with a mechanical `android.*` import ban below the line for the
>   edge-engine deliverable). — Row 4, Appendix B
> - `PositionState` (pose + covariance + mode) is **co-owned with the fusion owner (M2)**,
>   not frozen by me alone. `PositioningEngine.kt` is a **draft proposal**. — Row 2
> - `StubEngine` **holds last known GNSS speed** (doesn't diverge like double integration). — Appendix C.2
> - Extra Android caveats folded into Appendix G (accel saturation, timestamp-base
>   validation, sensor-event object reuse, interpreter warm-up, NaN rejection, thermal).
>
> **Open forks (need a team decision — flagged in the rows, not silently resolved):**
> - **F1 — feature computation location.** Aneesh prefers the 7 features + normalization
>   **baked into the `.tflite` graph** (only a raw window crosses the boundary); the model
>   team currently computes them in `preprocess.py` and this doc ports that to Kotlin with
>   golden-vector parity. → [[Model-Questions-Part-2]], decide with the model team.
> - **F2 — model output.** Aneesh's spec wants a **forward displacement increment + variance**
>   at a fast IMU rate; the model team built a **forward speed scalar at 10 Hz** ([[13-ANSWERS-Model-Team]] A12).
> - **F3 — gyro sensor.** Aneesh wants `TYPE_GYROSCOPE_UNCALIBRATED` (own bias estimation);
>   need to confirm what IO-VNBD training used. → [[Model-Questions-Part-2]] Q6.
> - **F4 — map library.** `SIH-IDR-android.md` §9 picks **Mapsforge**; [[App-Stack-and-Integration-Decisions]] §3 picks **osmdroid**. App-team call.

---

## Legend

| Mark | Meaning |
| --- | --- |
| ✅ | done |
| ⬅️ | current position |
| ⛔ | blocked on another team |
| — | milestone marker, not a task |

**Owners:** `ME` = the integrator (me) · `APP` = app team · `MODEL` = model team ·
`FUSION` / `M2` = dead-reckoning + heading + Kalman fusion teammate · `M3` = maps /
map-matching / dataset teammate · `JOINT` = me + another team together.

---

## The sequence (overview)

| # | Step | Owner | Status |
| --- | --- | --- | --- |
| 0 | Secure a physical Android phone (emulator has no sensors / GNSS) | ME / TEAM | ✅ confirmed (phone in hand) |
| 1 | Get Part A answers from the model team | ME → MODEL | ✅ done ([[13-ANSWERS-Model-Team]]) |
| 2 | Draft the `PositioningEngine` interface (`[1,50,7]` in, speed out, 10 Hz) | ME (+ M2 for `PositionState`) | ✅ draft — `tanmay/PositioningEngine.kt` (**not frozen**; needs M2 + F1) |
| 3 | Hand the interface + [[HANDOFF-to-App-Team]] to the app team | ME → APP | ⬅️ **here — next** |
| 4 | Agree the sensor / GNSS boundary (raw `LocationManager`, max sample rate, module tree) | JOINT | not started |
| 5 | Grab `engine_stub.tflite` + `preprocess.py` from `sih-26168-model` | ME | available, not grabbed |
| 6 | Build the Phase-0 skeleton against the stub (engine box = me; Android shell = app team) | ME + APP | not started |
| — | **Milestone: dot moving on a real phone at 10 Hz on the stub** | — | — |
| 7 | Port `preprocess.py` → Kotlin (feature math, decimate, window, normalize) — **subject to F1** | ME | not started (can start once F1 decided) |
| 8 | **[WAIT]** real `.tflite` + **manifest** + `testset.npz` (+ high-rate variant) + G1 | MODEL | ⛔ A8 resolved (TCN); packaging pending |
| 9 | Swap stub → real model, run parity gates **G0 / G2 / G2a / G3 / G4** | ME | can't start until #8 |
| 10 | **[WAIT]** heading + DR + non-holonomic + EKF + handover + map-matching (Part C) | M2 / M3 | ⛔ not started; heading + map-matching have no owner |
| 11 | Port Part C to Kotlin, wire in → parity gate **G5** | ME | can't start until #10 |
| 12a | Measure drift (**G6**), feed numbers back to model + M2 / M3 | ME | later |
| 12b | Build uncertainty ellipse + GNSS-mute toggle + compare view + record button (demo/data) | ME + APP | later |
| 13 | Performance tuning → steady 10 Hz (**G7**) | ME | later |
| 14 | Ongoing: re-run G2–G4 (+ drift) on every new model | ME | ongoing later |

**Integration is DONE when G0–G7 (+ G2a) are all green on the demo phone** (G0 export +
manifest sane · G1 model team's Python parity · G2 feature/preprocessing parity · G2a
decimation/anti-alias parity · G3 model-path parity · G4 full on-device parity · G5
DR+fusion trajectory parity · G6 drift < 10% · G7 steady 10 Hz).

**Open forks (F1–F4) — see the reconciliation box at the top.** F1 (features in-graph vs
Kotlin) gates Row 7's shape; F3 (gyro sensor) and F4 (map lib) are quick team calls.

**Current position:** rows 0, 1 done; row 2 drafted. **Next:** row 3 (hand over the
interface + handoff doc) + row 4 (boundary, incl. the raw-GNSS / max-rate / module-tree
changes), then rows 5 → 7 → 6 to the "dot on the stub" milestone. Parity gates (9) wait
on the real model; Part C (10–11) waits on M2 / M3 — and heading + map-matching still
need owners named.

---

## Row 0 — Secure a physical Android phone

- **Owner:** ME / TEAM
- **Status:** ✅ done — phone in hand
- **Depends on:** —
- **What it is:** the emulator has **no motion sensors and no GNSS** — the entire engine
  is untestable without a real device. This is a silent hard blocker on the Row 6 milestone.
- **How to do it:**
  1. Confirm a real Android phone is available now, with a USB cable.
  2. Enable Developer Options → USB debugging (Settings → About phone → tap Build number 7×).
  3. Prefer the phone from [[11-NavIC-Phone-Testing]] task 5 — one with confirmed NavIC
     (IRNSS) support, so the NavIC badge and the canyon story are demoable on the same device.
  4. Check it exposes `TYPE_GRAVITY` and a real `TYPE_GYROSCOPE` (Aneesh §12.1: some budget
     phones have no gyro — detect at startup and refuse clearly).
  5. **Aim for 3 phones eventually** (Aneesh §12.17): one budget, one OEM known for
     aggressive background-killing (common in India), one mid-range. One is enough to start.
- **Output / deliverable:** a phone on a desk, `adb devices` lists it.
- **Done when:** confirmed and debugging.

---

## Row 1 — Get Part A answers from the model team

- **Owner:** ME (asked) → MODEL (answered)
- **Status:** ✅ done
- **Depends on:** —
- **What it is:** send the model team the interface questionnaire and get authoritative
  answers, so the app↔model boundary is fully specified before anyone writes real code.
- **What was done:** sent [[13-Model-App-Integration-Contract]] Part A + Part B. Answers
  came back in [[13-ANSWERS-Model-Team]].
- **Answers now locked (build against these):**
  - Framework: **TensorFlow 2.20 / Keras 3**, exports to LiteRT/TFLite.
  - Input tensor: **`[1, 50, 7]` float32** — 7 engineered features, 50-sample (5 s) window.
  - Sample rate: **10 Hz** (IO-VNBD smartphone data is 10 Hz). App must downsample the
    phone's native rate to exactly 10 Hz.
  - Stride: **1 sample** → 10 Hz output.
  - Output tensor: **`[1, 1]` float32 = forward speed in m/s directly** (no de-normalization).
  - Statefulness: **stateless** — full 50×7 window every call.
  - Input normalization: **per-channel standardize `(x-mean)/std`**, applied by the app,
    constants in a `norm.json` (values change on every retrain — scheme is stable).
  - The 7 features (from `TYPE_ACCELEROMETER` and `TYPE_GRAVITY`, NOT `TYPE_LINEAR_ACCELERATION`):
    ```
    a_lin    = accelerometer - gravity           # per axis
    g_hat    = gravity / (|gravity| + 1e-6)
    a_vert   = dot(a_lin, g_hat)
    a_horiz  = |a_lin - a_vert * g_hat|
    a_lin_mag = |a_lin|
    gyro_mag  = |gyro|
    features  = [a_horiz, a_vert, a_lin_mag, gyro_x, gyro_y, gyro_z, gyro_mag]
    ```
- **Still open (tracked in [[Model-Questions-Part-2]], none block rows 2–7):**
  A8 GRU-vs-TCN decision, who downsamples to 10 Hz and how, whether `preprocess.py` is
  frozen, `testset.npz` timing, raw-gyro / mount-orientation clarification.
- **Done when:** interface numbers known → yes.

---

## Row 2 — Draft the `PositioningEngine` interface

- **Owner:** ME to draft · `PositionState` shape **co-owned with M2** (fusion owns pose +
  covariance + mode, per Aneesh architecture §"interfaces to freeze in week one")
- **Status:** ✅ draft written — `tanmay/PositioningEngine.kt` (**DRAFT PROPOSAL, not frozen**)
- **Depends on:** Row 1
- **What it is:** the Kotlin file defining the seam between *the engine* (raw samples in →
  pose out) and *the app* (UI + Android lifecycle). Both sides code to it so both build in
  parallel. Aneesh `SIH-IDR-android.md` §"The seam" calls for exactly this
  (`PositioningEngine`-shaped interface, real + stub impls from commit one).
- **What was written:**
  - `interface PositioningEngine` — `start()`, `stop()`, `onImuSample(tNanos, ax,ay,az,
    grx,gry,grz, gx,gy,gz)` (raw accel + gravity + gyro), `onGnssFix(...)`,
    `onGnssLost(tNanos)`, `val state: StateFlow<PositionState>`.
  - `enum class Mode { INIT, NAVIC, GNSS, DEGRADED, DEAD_RECKONING }`
    (`DEGRADED` added per Aneesh — bad fixes are worse than no fix).
  - `data class PositionState(lat, lon, speedMps, headingDeg, mode, satsInFix,
    irnssSatsInFix, uncertaintyM, engineTickMs)` — **needs M2 sign-off** (they may want
    full covariance, not a scalar; they own this struct).
  - Only **structural** constants stay in code (`WINDOW_SAMPLES`, `FEATURES`,
    `RAW_CHANNELS` — tensor shape). Rates / thresholds / window-in-seconds move to the
    **config file** (Aneesh rule 4: no navigation constants in Kotlin source).
- **Still to settle before it freezes:**
  - M2 confirms the `PositionState` fields (covariance representation).
  - F1 (features in-graph vs Kotlin) — changes whether `onImuSample` even needs the
    gravity channel, or just a raw accel/gyro window.
- **Where it lives:** staged at `sih-26168-notes/tanmay/PositioningEngine.kt`; moves to
  `:core-types` (Aneesh module layout) once the project exists.
- **Done when:** app team + M2 both agree, then it freezes as v1.
- **Reference:** Appendix C.1.

---

## Row 3 — Send the interface + integration expectations to the app team

- **Owner:** ME → APP
- **Status:** ⬅️ **next**
- **Depends on:** Row 2
- **What it is:** hand the app team the interface draft + what the integrator needs from
  them, so they build the app shell (from Aneesh `SIH-IDR-android.md`) against a stub.
- **How to do it:** hand over `PositioningEngine.kt` + [[HANDOFF-to-App-Team]] (the thin
  version — it defers to `SIH-IDR-android.md` for the app architecture and only covers the
  seam + the parity-gate fixtures + the open forks F1–F4).
- **Output / deliverable:** app team confirms the interface draft and the boundary (Row 4).
- **Done when:** confirmed.

---

## Row 4 — Agree the sensor / GNSS boundary with the app team

- **Owner:** JOINT (me + app team)
- **Status:** not started
- **Depends on:** Row 2
- **What it is:** pin down who does what at the sensor/GNSS layer. Aligns with Aneesh
  `SIH-IDR-android.md` §2 module layout (`:android-sensors`, `:android-model` = app;
  `:engine`, `:core-*` = mine).
- **The split:**
  | Piece | Owner | Notes |
  | --- | --- | --- |
  | Android project, Gradle module tree, manifest, permissions, foreground service | APP | module tree per `SIH-IDR-android.md` §2, incl. the `android.*` import ban below the line |
  | Register accel + gyro + gravity, **at the highest reliable rate**, explicit handler, `maxReportLatencyUs = 0` | APP | NOT a fixed 50 Hz — Aneesh §4 |
  | **Raw `LocationManager` GPS provider** + `GnssStatus` callback (+ GNSS measurements where available) | APP | **NOT `FusedLocationProviderClient`** — Aneesh §5 |
  | NavIC: count `CONSTELLATION_IRNSS` sats with `usedInFix` | APP | |
  | Timestamp validation vs `elapsedRealtimeNanos` at startup; copy sensor `values` immediately | APP | Aneesh §4 |
  | Push raw samples into `onImuSample` / `onGnssFix` off the main thread | APP | |
  | Ring buffer, **anti-alias decimation to model rate**, feature math (subject to F1), normalize, model, DR, fusion, map-match, output | ME | the decimation stage is explicit + tested (gate G2a) |
  | Map + dot + uncertainty ellipse + mode badge + GNSS-mute toggle + record button | APP | |
- **Why this split:** Android lifecycle with the app team; all model-sensitive math in the
  engine, in one place, JVM-parity-testable without a phone.
- **Also decide:** does the app do the anti-alias decimation, or hand the engine the full
  high-rate stream (preferred — keeps the tested stage on the engine side)? ; what the app
  does before `start()`.
- **Output / deliverable:** a short written boundary note both sides agree to.
- **Done when:** written and agreed.

---

## Row 5 — Grab the stub model + preprocessing reference

- **Owner:** ME
- **Status:** available in the `sih-26168-model` repo, not yet grabbed
- **Depends on:** Row 1
- **What it is:** get the two artifacts the model team already delivered so I can build
  and test the whole pipeline before the real model exists.
- **How to do it:**
  1. Clone / pull `sih-26168-model`.
  2. Get `engine_stub.tflite`, `make_stub.py`, `preprocess.py`.
  3. Verify the stub in a throwaway Python check: input `[1,50,7]` float32, output
     `[1,1]` float32 ≈ 8.33, builtin ops only (no `SELECT_TF_OPS`).
  4. Read `preprocess.py` line by line — it is the spec for the Row 7 Kotlin port. Note
     which stages it does (gravity subtract, features, downsample, window, normalize) and
     which it leaves to the caller — this answers open question Q2/Q3.
- **Output / deliverable:** stub `.tflite` in `app/src/main/assets/engine.tflite` (as the
  placeholder), `preprocess.py` understood and annotated.
- **Done when:** stub loads and runs; `preprocess.py` scope is clear.

---

## Row 6 — Build the Phase-0 skeleton against the stub

- **Owner:** ME (engine box) + APP (Android shell)
- **Status:** not started
- **Depends on:** Row 2 (interface), Row 5 (stub)
- **What it is:** a running app on a real phone, dot moving at 10 Hz, powered by the stub.
  Every wire in place; only the brain is fake.
- **My part (the engine box — `:engine` + `:core-*` modules):**
  1. `RingBuffer` — fixed circular store of raw rows `(tNanos, ax,ay,az, grx,gry,grz, gx,gy,gz)`,
     generously sized, at the **native** sensor rate. **IMU samples are never dropped**
     (Aneesh §3) — if the engine can't keep up, log loudly, don't add a drop policy.
     Sensor thread writes, engine thread reads.
  2. `ConditioningStage` — normalise timestamps, handle duplicate / out-of-order samples,
     detect gaps, reject outliers, flag accelerometer clipping (Aneesh §4).
  3. `Decimator` — from the native rate down to the model rate, with an **anti-alias
     low-pass** before decimation (Aneesh §4 / gate **G2a**). This is an explicit, tested
     stage — not "grab every Nth sample".
  4. `FeatureComputer` — the 7-feature math (Row 7). **Skipped if F1 resolves to
     features-in-graph** — then only a raw window crosses into the model.
  5. `Normalizer` — from the config/manifest, not hard-coded. Skipped if F1 → in-graph.
  6. `SpeedModel` — TFLite `Interpreter` wrapper; **one interpreter per thread** (not
     thread-safe); **warm up** with a dummy inference at startup (first call is slow);
     **reject non-finite** inputs/outputs loudly (a NaN poisons filter state permanently);
     direct `ByteBuffer`s allocated once and reused.
  7. **Manifest load + self-test** (replaces the old "version guard", per Aneesh §7): on
     startup, load the model's manifest (asset id + version, SHA-256, input/output shapes
     + dtypes, channel order, units, gravity handling, window/stride, expected rate,
     normalisation constants, a self-test vector, min engine version). **Refuse to start on
     any mismatch**, loudly. Run the self-test vector (canned input → expected output) —
     catches conversion error, quantisation drift, norm mismatch, wrong model loaded, in
     one cheap check.
  8. `EngineLoop` — fixed-rate scheduler at the model rate; each tick: window → decimate →
     (features → normalize | raw window) → model → (Row 11 heading / DR / NHC / EKF /
     map-match later) → publish `state`. Inference runs on its **own thread**; results
     come back as **timestamped delayed measurements** so slow inference causes no lag
     (Aneesh §3). Stale-output policy: reuse last, mark stale so the filter de-weights it.
  9. `StubEngine` — **holds last known GNSS speed** (safe; doesn't diverge like raw
     double-integration). `ReplayEngine` — reads a recorded drive and replays
     `onImuSample` / `onGnssFix` (desk + CI, no phone).
- **App team's part (the Android shell):** per Aneesh `SIH-IDR-android.md` §15 build order —
  project + module tree + `android.*` import ban; permissions + foreground service +
  engine-lifetime ownership; sensor layer (high rate, ring buffer, measured real rates);
  GNSS layer (**raw provider**, `GnssStatus`, NavIC count); the seam + stub; trip recorder
  (ships early — data collection has weeks of lead time); map view + mode badge + GNSS-mute
  toggle; replay harness; placeholder-model inference on device.
- **Output / deliverable:** installable debug APK; the engine as `:engine` + `:core-*`.
- **Milestone — done when:** dot moves on a real phone at 10 Hz on the stub; basement flips
  the badge to `DEAD_RECKONING` and the dot keeps moving smoothly.
- **Reference:** Appendices B (project setup), C (code skeletons), F (threading);
  Aneesh `SIH-IDR-android.md` §§2–5, 15.

---

## Row 7 — Port `preprocess.py` → Kotlin

- **Owner:** ME
- **Status:** not started — **can start now, against the stub**
- **Depends on:** Row 5
- **What it is:** rewrite the model team's Python preprocessing as Kotlin, stage for
  stage, so the phone prepares model input identically to how training did. Mismatch here
  = wrong speed with no error message.
- **⚠️ Blocked on fork F1.** If the model team bakes features + normalization **into the
  `.tflite` graph** (Aneesh's preferred fix, `SIH-IDR-android.md` §13.3), this whole row
  shrinks to "assemble a raw window" and the parity risk mostly disappears. Resolve F1
  before investing in the full port. What follows assumes F1 → Kotlin port.
- **Stages to port (confirm exact scope with Q2/Q3 first):**
  1. **Gravity split** — `a_lin = accelerometer - gravity`; `g_hat = gravity/(|gravity|+1e-6)`;
     `a_vert = dot(a_lin, g_hat)`; `a_horiz = |a_lin - a_vert*g_hat|`; `a_lin_mag = |a_lin|`;
     `gyro_mag = |gyro|`.
  2. **Feature vector** — `[a_horiz, a_vert, a_lin_mag, gyro_x, gyro_y, gyro_z, gyro_mag]`.
  3. **Anti-alias decimate** native rate → model rate (Row 6 `Decimator`; gate G2a).
  4. **Window** — `WINDOW_SAMPLES`, newest last, no padding, no per-window detrend.
  5. **Normalize** — `(x-mean)/std` per channel from the **manifest** (not a pasted
     constant). No output norm.
- **How to do it:** open `preprocess.py` beside the editor; port each line; keep every
  constant named, not magic. Write it as a pure function `preprocess(rawRows) -> Array<FloatArray>`
  so it runs in a plain JVM unit test.
- **Output / deliverable:** `Preprocessing.kt` + a JVM test scaffold (the real parity
  assertion is Row 9 / G2, once `testset.npz` exists).
- **Done when:** compiles, structurally matches `preprocess.py`, runs in a JVM test on
  dummy input producing a `[50,7]` array. (Numeric parity proven later in G2.)

---

## Row 8 — WAIT: real `.tflite` + `testset.npz` + final `norm.json` + G1

- **Owner:** MODEL
- **Status:** ⛔ blocked on the model team's A8 (GRU vs TCN) decision — [[Model-Questions-Part-2]] Q1
- **Depends on:** Row 1
- **Status update:** A8 resolved — **TCN chosen** (Round 4: drift 18%→12%, pass rate
  19%→43%, builtins-only, no `SELECT_TF_OPS`). See [[12-Model-Results]]. Still waiting on
  the packaged deliverables below.
- **What I receive:**
  - `engine_vX.Y_DATE_HASH.tflite` (TCN, builtins-only)
  - a **manifest** for it (Aneesh §7): asset id + version, SHA-256, input/output shapes +
    dtypes, channel order, units, gravity handling, window + stride, expected rate,
    normalisation constants, **a self-test vector**, min engine version
  - `testset.npz` — ~100 windows incl. hard cases (braking, turns, stop-go, stationary,
    high speed, a blackout window), with `raw_inputs`, `model_inputs`, `model_inputs_norm`,
    `keras_outputs`, `tflite_outputs`, **and a high-rate raw variant for gate G2a**
  - **G1** = written Python parity confirmation + script (Keras vs Python-TFLite ≤ 1e-4).
    Model team's gate; I file it so any on-device mismatch (G4) is provably app-side.
- **My action while waiting:** rows 0, 2–7; push F1–F4 to a decision.
- **Done when:** all files in hand, G1 confirmation received, manifest self-test passes,
  and G0 inspection passes (shapes/dtype/op set/tensor names match the stub).

---

## Row 9 — Swap stub → real model, run parity gates G0 / G2 / G2a / G3 / G4

- **Owner:** ME
- **Status:** blocked on Row 7 + Row 8
- **Depends on:** Row 7 (Kotlin preprocessing), Row 8 (real model + testset + manifest + G1)
- **The gates** (numbering per Aneesh `SIH-IDR-android.md` §11):
  | Gate | Feeds | Compares to | Tolerance | Proves |
  | --- | --- | --- | --- | --- |
  | **G0** | — | inspect `.tflite` + manifest | — | shapes / dtype / names / ops as promised |
  | **G1** | *(model team)* Keras → Python-TFLite | Keras outputs | 1e-4 | the export itself is clean (filed from Row 8) |
  | **G2** | `raw_inputs` (at model rate) → my Kotlin `preprocess()` | `model_inputs` | 1e-4 | the feature / preprocessing port is correct |
  | **G2a** | **high-rate raw** → my `Decimator` (anti-alias) | the fixture's model-rate grid | tolerance TBD | **the decimation stage is correct** — G2 can't see it because its fixture is already at model rate; aliasing here passes every other gate while corrupting the live input distribution |
  | **G3** | `model_inputs` → normalize → model | `keras_outputs` (m/s) | 1e-3 | normalization + model call + buffer layout correct |
  | **G4** | **high-rate raw** → **whole Kotlin pipeline, on the phone** | `keras_outputs` (m/s) | 1e-3 | the model is correctly integrated end-to-end on device |
- **How to do it:** G2 / G2a / G3 as JVM tests via the replay harness (fast, CI); G4 as an
  `androidTest` on the demo phone. Failure isolation: G2 red = feature math; G2a red =
  decimation / aliasing; G3 red + G2 green = normalize / buffer layout / dtype; G4 red +
  G2+G2a+G3 green = live window assembly / timestamping.
- **Output / deliverable:** a green parity suite in CI + the manifest self-test wired at startup.
- **Done when:** **G4 green on the demo phone.** The de-risking milestone ([[06-Challenges]] #8).

> If **F1 → features-in-graph**: G2 becomes "raw window assembly matches fixture", G3
> folds into G4, and the parity surface shrinks a lot. Revisit this table once F1 is decided.

---

## Row 10 — WAIT: heading + DR + map-matching + EKF + handover (Part C)

- **Owner:** FUSION (M2) for heading/DR/EKF/handover · M3 for map-matching
- **Status:** ⛔ not started by them
- **Depends on:** —
- **What I need — and who owns each (confirm ownership; the answers doc left gaps here):**

  **a. Heading estimator** — *not currently assigned anywhere.* The speed model gives
  speed only; DR needs a direction. Someone must own:
  - the algorithm: gyro-z integration? complementary filter (gyro + magnetometer)?
    game-rotation-vector yaw?
  - how initial heading is seeded from GNSS course, and the re-seed rule when GNSS is healthy
  - the phone→vehicle yaw offset estimation (the "align phone to car" the calibration
    screen implies but doesn't fully solve)
  - the Python reference function.
  **Raise this explicitly with M2 — the answers doc says "HEADING is a Part C concern"
  but Part C (C1–C6) never lists it.**

  **b. DR integrator** — `dr_step(state, speed, heading, dt) -> state` (position delta from
  speed + heading).

  **c. Non-holonomic constraint** — no sideways / vertical motion; how it's enforced
  (pseudo-measurement vs hard projection).

  **d. Map-matching** — *also missing from Part C (C1–C6) and doc 14.* It sits **inside my
  engine box**, between fusion and position output (architecture block 3, task 12, M3;
  ranked #3 hardest in [[06-Challenges]], the "safety net" against drift). I need:
  - the offline OSM road-graph file + its format, and who generates/bundles it (task 4)
  - the snapping algorithm: HMM map-matching, or Kalman + road graph
  - the Python reference
  - failure-mode notes (parallel roads, forks — when it confidently picks wrong)

  **e. EKF / GNSS+INS fusion** — state vector, process model + `Q`, GNSS measurement model
  + `R` (and how `R` scales with reported accuracy), which states are estimated only when
  GNSS is healthy vs frozen during blackout.

  **f. Handover rules** — exact "GNSS lost" / "GNSS recovered" conditions + debounce; snap
  vs smooth-blend on recovery.

  **g. NavIC fix logic** — does IRNSS contribution change `R`?

  **h. A recorded-drive parity fixture** — drive CSV (timestamped IMU + GNSS, with a
  real/simulated blackout) + the expected output trajectory from their Python, for G5.
- **My action:** chase M2 (heading/DR/EKF/handover) and M3 (map-matching) — the
  second-biggest external dependency after the model, and currently the least-defined.
- **Done when:** Python references for a–g + the parity fixture (h) in hand, and heading +
  map-matching have confirmed owners.

---

## Row 11 — Port heading + DR + map-matching + fusion to Kotlin, wire in (G5)

- **Owner:** ME (porting + wiring; algorithms are M2 / M3)
- **Status:** blocked on Row 9 + Row 10
- **Depends on:** Row 9 (working model path), Row 10 (all Part C references)
- **How to do it:**
  1. Port each piece to Kotlin next to its Python original: heading estimator,
     `dr_step`, non-holonomic constraint, map-matcher, `ekf_predict`, `ekf_update_gnss`.
  2. Wire the tick, in order:
     `model speed` + `heading estimator` → `dr_step` → non-holonomic → `ekf_predict`;
     on a GNSS fix → `ekf_update_gnss`; then `map_match(snap to road graph)` → position out;
     snap/blend on GNSS recovery per the handover rule.
  3. Implement the handover state machine + mode-badge logic
     (`satsInFix>0 && irnss>0 → NAVIC`; `satsInFix>0 → GNSS`; else `DEAD_RECKONING`).
  4. Feed `driftEstimateM` from the EKF position covariance (for the demo meter).
  5. Parity-test each ported piece against its Python reference the same way as G2/G3
     (golden vectors), not just the end-to-end G5.
- **G5:** run the recorded-drive fixture through the Kotlin `RealEngine`; the output
  trajectory must match the Python reference within tolerance, and final drift % within
  ~1–2 points.
- **Output / deliverable:** full `RealEngine` producing lat/lon + mode; G5 green.
- **Done when:** G5 green.

---

## Row 12a — Measure drift (G6)

- **Owner:** ME
- **Status:** later
- **Depends on:** Row 11
- **How to do it:**
  1. Run recorded drives with real / simulated GNSS-blackout segments through the engine
     via the replay harness.
  2. For each blackout: `drift = final_position_error / distance_travelled`. Target
     **< 10%** (< 100 m per 1 km).
  3. Report drift numbers back to the model team (speed error) and M2 / M3
     (heading / map-matching) — this is the feedback loop that gets the KPI met.
- **Reality check:** the model team's own first drift eval on the baseline is ~18% median,
  only 19% of segments under target ([[13-ANSWERS-Model-Team]] honest-status note). Hitting
  < 10% will take several model iterations — my job here is to *measure it cleanly and
  fast* so each new model is easy to evaluate.
- **Output / deliverable:** a repeatable drift report (one command, runs on every model bump).
- **Done when:** drift measured on the standard blackout set and reported.

---

## Row 12b — Demo features

- **Owner:** ME (engine hooks) + APP (UI)
- **Status:** later
- **Depends on:** Row 11
- **What it is:** the on-stage proof features from [[05-App-Features]].
  - **Blackout-simulator toggle** — engine ignores GNSS while on (my hook); a button (APP UI).
  - **Live drift meter** — engine exposes `driftEstimateM` (done in Row 11); APP renders it.
  - **Session logger/recorder** — engine writes sensor + position to a file (mine).
  - **Compare view** — plain-GPS-only track vs full-engine track side by side (APP UI, my
    engine can run a "GNSS passthrough" mode for the comparison baseline).
- **Done when:** all four work on the demo phone.

---

## Row 13 — Performance tuning → steady 10 Hz (G7)

- **Owner:** ME
- **Status:** later
- **Depends on:** Row 11 (but start measuring `engineTickMs` from Row 6 — don't leave perf to the end)
- **Target:** engine holds 10 Hz for 10 minutes, tick p95 < 50 ms, no visible dot stutter,
  on the **demo phone**.
- **How to do it:**
  - Log `engineTickMs` every tick; investigate any tick > 40 ms.
  - **Zero allocation in `tick()`** — pre-allocate every array / buffer, reuse them.
    GC pauses are the #1 stutter cause.
  - Sensor callbacks do nothing but append to the ring buffer.
  - `Process.setThreadPriority(THREAD_PRIORITY_MORE_FAVORABLE)` on the engine thread.
  - Benchmark CPU+XNNPACK vs `GpuDelegate` vs NNAPI; keep the fastest (for a small
    TCN, CPU usually wins).
  - Foreground service + partial wake lock so the OS doesn't throttle mid-demo.
- **Output / deliverable:** a perf trace showing steady 10 Hz on the demo phone.
- **Done when:** G7 green on the demo phone.

---

## Row 14 — Ongoing: re-run parity on every new model

- **Owner:** ME
- **Status:** ongoing, once the real model exists
- **What it is:** every time the model team ships a new `.tflite` **+ its manifest**
  (versioned), drop it in, re-run **G0 / G2 / G2a / G3 / G4**, and check drift (G6) didn't
  regress. Aneesh §7: automatic rollback — if a new model performs measurably worse than
  its predecessor over a session where GNSS was trusted, revert and report.
- **How to do it:** JVM parity tests (replay harness) in CI on every model bump; G4
  (`androidTest`) on the demo phone before each checkpoint; the manifest self-test runs at
  every app launch.
- **Done when:** continuous until the finale.

---

## What to do this week

1. **Row 3** — hand `PositioningEngine.kt` + [[HANDOFF-to-App-Team]] to the app team. *(now)*
2. **Row 4** — agree the boundary, incl. the reconciled points: **raw `LocationManager`**
   (not Fused), **max sample rate** (not fixed 50 Hz), the **module tree**. *(one conversation)*
3. **Force F1–F4 to a decision** — with the model team (F1 features-in-graph, F3 gyro
   sensor) and the app team (F4 map lib). **F1 gates Row 7.**
4. **Row 5** — pull `sih-26168-model`, grab the stub + `preprocess.py`, verify the stub. *(an hour)*
5. **Row 6 (my half, F1-independent parts)** — ring buffer + conditioning + `Decimator`
   + `EngineLoop` + `ReplayEngine` + `StubEngine` (hold-last-GNSS-speed), wired. *(this week)*
6. **Row 7** — port `preprocess.py` → Kotlin **once F1 says Kotlin**; otherwise just the
   raw-window assembly. *(1–2 days)*
7. Chase **heading + map-matching owners** (M2 / M3) — still the least-defined part of the
   whole pipeline.

Nothing here needs the real model or the Part C code.

---

## Open risks to keep visible

| Risk | Row | Why it matters |
| --- | --- | --- |
| **Heading estimator has no owner** | 10 | DR can't produce a position without it; Aneesh + the model answers both call it "Part C" but Part C never lists it |
| **Map-matching has no owner / no spec** | 10 | Inside the engine box, #3 hardest problem; buys back along-track info at turns/junctions where the speed model is weakest |
| **F1 unresolved** (features in-graph vs Kotlin) | 7, 9 | Changes the interface, Row 7's size, and most of the parity surface — decide before investing in the port |
| Baseline drift over the KPI | 12a | Round 4 TCN = **12%** (was 18%), pass rate 43%. Closer, not there. Model-capacity problem — I own *measuring* it fast, not fixing it |
| Aliasing on decimation | 9 (G2a) | Passes every other gate while making the live input distribution differ from training |
| `.tflite` shipped without / with a stale manifest | 6 | The manifest + self-test vector is the load-time guard — make sure the model team actually ships one |
| Two app-architecture docs (this + Aneesh) drift apart | — | Aneesh's is authoritative for app/arch; this one is execution + parity. Keep the split clean, don't re-fork |

---
---

# APPENDICES — reference material (build detail)

The rows above are the plan. These appendices are the "how", reconciled with Aneesh's
architecture docs: 10 Hz **output**, 50-sample window, 7 features (subject to F1),
`TYPE_ACCELEROMETER` + `TYPE_GRAVITY` + `TYPE_GYROSCOPE` at **max sample rate**, raw
`LocationManager` GPS provider, output in m/s, manifest + self-test at load.

| Appendix | Contents | Maps to row |
| --- | --- | --- |
| A | Tools to install + vocabulary | 0, 6 |
| B | Android project setup (module tree, Gradle, manifest) — *app team's, for reference* | 6 |
| C | Code skeletons: interface pointer, stub, sensor source, ring buffer + decimator, GNSS source, `SpeedModel`, replay harness | 2, 6 |
| D | The 7-feature preprocessing port (Kotlin) — *if F1 → Kotlin* | 7 |
| E | Parity-gate test code (G2 / G2a / G3 / G4) | 9 |
| F | Threading model | 6 |
| G | Bugs you will hit, and how they look | all |
| H | The pipeline stage diagram | — |

---

## Appendix A — Tools to install

| Tool | What it is | Where |
| --- | --- | --- |
| **Android Studio** | Where you write, build, and run the app. Bundles the Android SDK + a JDK + an emulator. | developer.android.com/studio |
| **A physical Android phone** | The emulator has **no motion sensors and no GNSS** — the engine is untestable on it. Real phone + USB cable + USB debugging on (Settings → About phone → tap Build number 7× → Developer options → USB debugging). Prefer one with confirmed NavIC/IRNSS ([[11-NavIC-Phone-Testing]] task 5). | — |
| **Python 3.11** | Only to run the model team's reference scripts / regenerate the stub. Never on the phone. | python.org |
| **Git** | The app gets its **own repo** (`sih-26168-app`), separate from this notes vault. | — |

**Vocabulary (first-use):**
- **Gradle** — the build system; you declare libraries in `app/build.gradle.kts`.
- **`AndroidManifest.xml`** — the app's table of contents: name, permissions, screens, services.
- **Activity** — one screen. **Service** — background code with no screen; a **foreground
  service** shows a notification and is allowed to keep running (needed for continuous sensors).
- **Coroutine / `StateFlow`** — Kotlin background work / an observable value the UI reacts to.
- **Interpreter** — the TensorFlow Lite object that loads `engine.tflite` and runs it.
- **`androidTest`** — tests that run on a real device (vs plain JVM unit tests on your laptop).

---

## Appendix B — Android project setup *(app team owns this; here for reference)*

> **Module layout follows Aneesh `SIH-IDR-android.md` §2, not a single `app` module.**
> `:app` (UI, service) → `:android-sensors`, `:android-model`, `:android-assets` →
> `─── no `android.*` below this line (build-check enforced) ───` → `:engine`, `:core-nav`,
> `:core-map`, `:core-model`, `:core-assets`, `:core-replay`, `:core-types`. The lower
> half is pure-Kotlin and is the edge engine (`:edge-cli` reuses it). The integrator owns
> `:engine` + `:core-*`; the app team owns `:app` + `:android-*`.

### B.1 Project
Android Studio → New Project → **Empty Activity** (Compose, per
[[App-Stack-and-Integration-Decisions]] §2). Kotlin. Min SDK **API 26** — but pick it
**deliberately** (Aneesh §12.14: raw GNSS measurements, FGS types, notification permission
each have different floors; record what degrades below which version).

### B.2 Gradle (dependencies — spread across the modules above)
```kotlin
// :android-model
implementation("org.tensorflow:tensorflow-lite:2.17.0")
implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
// A8 resolved: TCN, builtins-only -> NO tensorflow-lite-select-tf-ops needed.

// :app  — GNSS uses the platform LocationManager (no play-services-location, see C.6)
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

// map — F4 UNRESOLVED: SIH-IDR-android.md §9 says Mapsforge; App-Stack doc §3 says osmdroid.
// implementation("org.mapsforge:mapsforge-map-android:0.21.0")   // Aneesh's pick
// implementation("org.osmdroid:osmdroid-android:6.1.18")         // App-Stack doc's pick

testImplementation("junit:junit:4.13.2")                 // JVM parity tests (:engine, :core-*)
androidTestImplementation("androidx.test.ext:junit:1.2.1")
androidTestImplementation("androidx.test:runner:1.6.2")
```
```kotlin
android { androidResources { noCompress += "tflite" } }  // TFLite must memory-map
```

### B.3 Manifest
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.HIGH_SAMPLING_RATE_SENSORS"/>
```
```xml
<service android:name=".EngineService"
         android:foregroundServiceType="location" android:exported="false"/>
```
- `HIGH_SAMPLING_RATE_SENSORS` — **do** declare it. Aneesh §4: sample at the highest
  reliable rate (not a fixed 50 Hz); the engine's `Decimator` brings it to the model rate
  with anti-aliasing. The 10 Hz in the problem statement is the *output* rate, not a
  sampling requirement.
- Foreground service is essential — Android throttles/stops sensor + location in the
  background (Aneesh §8). Also request **battery-optimisation exemption** explicitly, with
  an explanation, or Doze throttles sensor delivery.
- No `INTERNET` needed for the engine; the map layer may need it for first tile fetch
  (F4-dependent) — dev only.
- Runtime permission prompt still required.

---

## Appendix C — Code skeletons

> `WINDOW_SAMPLES = 50`, `FEATURES = 7` (structural, from the manifest); raw channels per
> `onImuSample` = 9 (accel3 + gravity3 + gyro3), or 6 if F1 → features-in-graph. Rates and
> thresholds are **config**, not constants (Aneesh rule 4). Snippets are sketches — real
> code lives in `:engine` / `:core-*` / `:android-*`.

### C.1 `PositioningEngine.kt` — the seam (Row 2)
The current draft is the file **`tanmay/PositioningEngine.kt`** — read it there, don't
copy it here. Key points and pending changes:
- `onImuSample(tNanos, ax,ay,az, grx,gry,grz, gx,gy,gz)` — raw accel + gravity + gyro.
  **If F1 → features-in-graph**, this drops the gravity channel and becomes a raw
  accel+gyro window; wait on F1 before freezing.
- `enum class Mode { INIT, NAVIC, GNSS, DEGRADED, DEAD_RECKONING }` — `DEGRADED` per
  Aneesh §5 (fixes arriving but failing quality/innovation gating).
- `PositionState` — **M2 owns this struct.** They may replace `uncertaintyM: Float` with
  a covariance representation. Get their sign-off before freezing.
- Only structural constants (`WINDOW_SAMPLES`, `FEATURES`, `RAW_CHANNELS`) stay in code.
  Rates / thresholds / window-seconds live in the config file (Aneesh rule 4).

### C.2 `StubEngine.kt` — build the whole app against this first (Row 6)
Per Aneesh §15: the stub **holds last known GNSS speed** — safe, and it does not diverge
the way raw double-integration would.
```kotlin
class StubEngine : PositioningEngine {
    private val _state = MutableStateFlow(PositionState()); override val state = _state.asStateFlow()
    private var lat = 12.9716; private var lon = 77.5946
    private var heading = 0.0; private var gnssSpeed = 0f; private var lastGyroZ = 0f
    private var gnssGood = false; private var running = false
    private val sched = Executors.newSingleThreadScheduledExecutor()

    override fun start() { running = true; sched.scheduleAtFixedRate(::tick, 0, 100, MILLISECONDS) }
    override fun stop()  { running = false; sched.shutdown() }

    override fun onImuSample(t: Long, ax: Float, ay: Float, az: Float,
                             grx: Float, gry: Float, grz: Float,
                             gx: Float, gy: Float, gz: Float) { lastGyroZ = gz }
    override fun onGnssFix(t: Long, la: Double, lo: Double, sp: Float, br: Float,
                           acc: Float, sats: Int, irnss: Int) {
        lat = la; lon = lo; heading = br.toDouble(); gnssSpeed = sp; gnssGood = true
    }
    override fun onGnssLost(t: Long) { gnssGood = false }   // keep last gnssSpeed

    private fun tick() {
        if (!running) return
        val t0 = System.nanoTime(); val dt = 0.1
        heading += Math.toDegrees(lastGyroZ * dt)           // crude heading, stub only
        val d = gnssSpeed * dt; val R = 6_378_137.0
        lat += d * cos(Math.toRadians(heading)) / R * (180 / PI)
        lon += d * sin(Math.toRadians(heading)) / (R * cos(Math.toRadians(lat))) * (180 / PI)
        _state.value = PositionState(lat, lon, gnssSpeed, heading.toFloat(),
            if (gnssGood) Mode.GNSS else Mode.DEAD_RECKONING,
            engineTickMs = (System.nanoTime() - t0) / 1e6f)
    }
}
```

### C.3 `SensorSource.kt` — *(app team; the boundary from Row 4)*
```kotlin
class SensorSource(ctx: Context, private val engine: PositioningEngine) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel   = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gravity = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val gyro    = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)   // F3: UNCALIBRATED?
    private val thread  = HandlerThread("sensors").apply { start() }
    private val handler = Handler(thread.looper)

    private val lastGrav = FloatArray(3).also { it[2] = 9.81f }
    private val lastGyro = FloatArray(3)

    fun start() {
        require(gyro != null) { "no gyroscope on this device" }       // Aneesh §12.1
        // Highest reliable rate, NOT a fixed 50 Hz. SENSOR_DELAY_FASTEST, then measure the
        // real delivered rate per device (Aneesh §4, §12.2). Engine's Decimator -> model rate.
        val rate = SensorManager.SENSOR_DELAY_FASTEST
        sm.registerListener(this, accel,   rate, 0 /* maxReportLatencyUs = 0: no batching */, handler)
        sm.registerListener(this, gravity, rate, 0, handler)
        sm.registerListener(this, gyro,    rate, 0, handler)
    }
    fun stop() { sm.unregisterListener(this); thread.quitSafely() }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            // COPY immediately — SensorEvent.values is a pooled array, overwritten next event (Aneesh §12.6)
            Sensor.TYPE_GRAVITY   -> System.arraycopy(e.values, 0, lastGrav, 0, 3)
            Sensor.TYPE_GYROSCOPE -> System.arraycopy(e.values, 0, lastGyro, 0, 3)
            Sensor.TYPE_ACCELEROMETER -> engine.onImuSample(
                e.timestamp,                              // ns since boot — validate base at startup (Aneesh §12.4)
                e.values[0], e.values[1], e.values[2],
                lastGrav[0], lastGrav[1], lastGrav[2],
                lastGyro[0], lastGyro[1], lastGyro[2])
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
```
Sample-and-hold: each accelerometer event carries the most recent gravity + gyro. The
downsampler (C.5) fixes the timing by interpolating onto a clean 10 Hz grid.

### C.4 `RingBuffer` — raw sample store (Row 6, mine)
Fixed circular array of rows `(tNanos, ax, ay, az, grx, gry, grz, gx, gy, gz)`, capacity
~6 s × 50 Hz ≈ 300 rows. Sensor thread writes, engine thread reads, short lock (copy
indices, not data).

### C.5 `Downsampler.latestWindow` — 50 rows at exactly 10 Hz ending "now" (Row 6, mine)
```kotlin
// Confirm the method with Model-Questions-Part-2 Q2 (interpolate vs decimate).
fun latestWindow(tEndNanos: Long): Array<FloatArray> {
    val periodNs = 1_000_000_000L / RATE_HZ                // 100 ms
    val out = Array(WINDOW) { FloatArray(9) }
    for (k in 0 until WINDOW) {
        val t = tEndNanos - (WINDOW - 1 - k) * periodNs
        interpolateInto(t, out[k])   // binary-search straddling ring rows, lerp each channel
    }
    return out                        // [50][9] raw, evenly spaced -> Appendix D
}
```

### C.6 `GnssSource.kt` — *(app team; NavIC counting)*
```kotlin
// RAW LocationManager GPS provider — NOT FusedLocationProviderClient.
// Aneesh §5: Fused blends WiFi/cell/its-own-DR and smooths; in a tunnel it emits positions
// from ITS dead reckoning, so our engine would correct against Google's DR and the drift
// metric stops meaning anything. Its smoothed output also breaks the EKF's white-noise
// assumption -> silent overconfidence / divergence.
class GnssSource(ctx: Context, private val engine: PositioningEngine) {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var satsInFix = 0; private var irnssInFix = 0; private var lastFixNs = 0L
    // TODO: also expose the C/N0 distribution + sat count + fix geometry for the M2
    //       quality gate (Aneesh §5 mode determination), and register the GNSS
    //       measurements callback where available for Doppler speed labels.

    private val statusCb = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            var tot = 0; var irn = 0
            for (i in 0 until s.satelliteCount) {
                if (!s.usedInFix(i)) continue
                tot++
                if (s.getConstellationType(i) == GnssStatus.CONSTELLATION_IRNSS) irn++  // IRNSS == NavIC
            }
            satsInFix = tot; irnssInFix = irn
        }
    }
    @SuppressLint("MissingPermission")
    fun start() {
        lm.registerGnssStatusCallback(Executors.newSingleThreadExecutor(), statusCb)
        lm.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, 1000L, 0f,
            { loc ->
                lastFixNs = SystemClock.elapsedRealtimeNanos()
                engine.onGnssFix(lastFixNs, loc.latitude, loc.longitude,
                    if (loc.hasSpeed()) loc.speed else 0f,
                    if (loc.hasBearing()) loc.bearing else 0f,
                    if (loc.hasAccuracy()) loc.accuracy else 999f, satsInFix, irnssInFix)
            },
            Looper.getMainLooper())
    }
    // Placeholder only. M2 owns the real rule (Aneesh §5): gate on C/N0, sat count,
    // fix geometry AND filter innovation — never on "no fix" alone, because seconds of
    // degraded multipath enter the filter before fixes stop, and a DEGRADED mode
    // de-weights those rather than trusting them.
    fun checkBlackout(nowNs: Long) {
        if (satsInFix == 0 || (nowNs - lastFixNs) / 1_000_000 > 3000) engine.onGnssLost(nowNs)
    }
}
```
Mode badge: `NAVIC` (sats + IRNSS) · `GNSS` (sats, trusted) · `DEGRADED` (fixes failing
the gate) · `DEAD_RECKONING` (no trusted GNSS).

### C.7 `SpeedModel.kt` — the TFLite wrapper (Row 6, mine)
```kotlin
class SpeedModel(ctx: Context, private val manifest: ModelManifest) {
    private val interpreter: Interpreter
    private val inBuf: ByteBuffer
    private val outBuf: ByteBuffer

    init {
        val file = ctx.assets.open(manifest.file)           // path from the manifest
        // Aneesh §7: verify SHA-256, shapes, dtypes, channel order, units, window/stride,
        // rate, min-engine-version against the manifest. Refuse to start on ANY mismatch.
        manifest.verify(file)

        interpreter = Interpreter(loadMapped(file),
            Interpreter.Options().apply { numThreads = 2 })  // float32 CPU + XNNPACK (Aneesh §13.10)
        val i = interpreter.getInputTensor(0).shape()
        val o = interpreter.getOutputTensor(0).shape()
        require(i[1] == WINDOW_SAMPLES && i[2] == manifest.features) { "input ${i.toList()} != manifest" }
        inBuf  = ByteBuffer.allocateDirect(4 * i[1] * i[2]).order(ByteOrder.nativeOrder())
        outBuf = ByteBuffer.allocateDirect(4 * o[1]).order(ByteOrder.nativeOrder())

        warmUp()                                            // Aneesh §13.8: first inference is slow
        runSelfTest(manifest.selfTestVector)               // Aneesh §7: canned input -> expected output
    }

    /** window: [WINDOW][features], model-ready. Returns speed in m/s. */
    fun run(window: Array<FloatArray>): Float {
        inBuf.rewind(); for (row in window) for (v in row) inBuf.putFloat(v)
        outBuf.rewind(); interpreter.run(inBuf, outBuf); outBuf.rewind()
        val s = outBuf.float
        require(s.isFinite()) { "model produced non-finite output" }   // Aneesh §13.9: a NaN poisons the filter
        return s
    }
    // one interpreter per thread — Interpreter is NOT thread-safe (Aneesh §13.8)
}
```
**The manifest replaces the old "version guard"** (Aneesh §7). It travels with the
`.tflite`, is asserted at load, and its self-test vector catches conversion error,
quantisation drift, normalisation mismatch and wrong-model-loaded in one cheap check.

### C.8 Replay harness (Row 6, mine — build it, it saves you)
A `PositioningEngine` driver that reads a recorded-drive CSV (timestamped IMU + GNSS) and
replays `onImuSample` / `onGnssFix` in order. Lets you: test on IO-VNBD segments at your
desk; reproduce any bug deterministically; run the whole pipeline as a **JVM test** (the
`org.tensorflow:tensorflow-lite` jar also runs on desktop) — this is how G2/G3 run in CI.
Record real drives now so you have a corpus before the model lands.

### C.9 Minimal UI wiring *(app team)*
```kotlin
lifecycleScope.launch {
    engine.state.collect { s ->
        marker.position = GeoPoint(s.lat, s.lon)
        map.controller.setCenter(marker.position)
        badge.text = "${s.mode}  ${s.satsInFix} sats (NavIC ${s.irnssSatsInFix})"
        speed.text = "%.0f km/h".format(s.speedMps * 3.6f)
        map.invalidate()
    }
}
```

---

## Appendix D — The 7-feature preprocessing port (Row 7)

Port of the model team's `preprocess.py`. **Match it line for line** — confirm the exact
scope with [[Model-Questions-Part-2]] Q2/Q3 (is downsampling and normalization inside
`preprocess.py`, or the caller's job?).

Input: `[50][9]` raw rows `(ax,ay,az, grx,gry,grz, gx,gy,gz)` from `latestWindow` (C.5).
Output: `[50][7]` feature rows, **pre-normalization**.

```kotlin
object Preprocessing {
    // stage 1 — the 7 features, per sample (13-ANSWERS A4/A5)
    fun features(row: FloatArray, out: FloatArray) {
        val ax = row[0]; val ay = row[1]; val az = row[2]
        val grx = row[3]; val gry = row[4]; val grz = row[5]
        val gx = row[6]; val gy = row[7]; val gz = row[8]

        // linear acceleration = accelerometer - gravity  (NOT TYPE_LINEAR_ACCELERATION)
        val lx = ax - grx; val ly = ay - gry; val lz = az - grz

        val gMag = sqrt(grx*grx + gry*gry + grz*grz) + 1e-6f
        val ghx = grx / gMag; val ghy = gry / gMag; val ghz = grz / gMag

        val aVert = lx*ghx + ly*ghy + lz*ghz                 // dot(a_lin, g_hat)
        val hx = lx - aVert*ghx; val hy = ly - aVert*ghy; val hz = lz - aVert*ghz
        val aHoriz  = sqrt(hx*hx + hy*hy + hz*hz)
        val aLinMag = sqrt(lx*lx + ly*ly + lz*lz)
        val gyroMag = sqrt(gx*gx + gy*gy + gz*gz)

        out[0] = aHoriz; out[1] = aVert; out[2] = aLinMag
        out[3] = gx;     out[4] = gy;    out[5] = gz          // raw gyro axes (Q6: mount orientation)
        out[6] = gyroMag
    }

    fun featureWindow(raw: Array<FloatArray>): Array<FloatArray> {
        val w = Array(WINDOW) { FloatArray(FEATURES) }
        for (k in raw.indices) features(raw[k], w[k])
        return w                                             // [50][7], pre-norm
    }
}
```

Normalization — constants come from the **model manifest** (Aneesh §7), not a pasted
`norm.json`; the manifest also carries the channel order the assert checks against, and
its self-test vector is what actually catches a norm mismatch. If F1 → features-in-graph,
this class disappears entirely.
```kotlin
class Normalizer(manifest: ModelManifest) {
    private val mean = manifest.inputMean    // per-channel, from the manifest
    private val std  = manifest.inputStd
    init { require(manifest.channelOrder == EXPECTED_CHANNEL_ORDER) { "channel order mismatch" } }
    // manifest.outputNorm == NONE — model emits m/s directly, no de-normalization
    fun apply(w: Array<FloatArray>) {
        for (row in w) for (i in row.indices) row[i] = (row[i] - mean[i]) / std[i]
    }
}
```

Final tick (Row 6 / Row 11):
```kotlin
val raw   = downsampler.latestWindow(now)      // [50][9]
val feats = Preprocessing.featureWindow(raw)   // [50][7]
normalizer.apply(feats)                        // in place
val speedMps = speedModel.run(feats).coerceIn(cfg.speedMinMps, cfg.speedMaxMps)  // m/s
// ... Row 11: heading -> dr_step -> NHC -> ekf -> map-match -> slew -> publish
```

---

## Appendix E — Parity-gate test code (Row 9)

`testset` (from `testset.npz`, [[Model-Questions-Part-2]] Q4) has per row `k`:
`raw_inputs` (model-rate), `model_inputs` (features, pre-norm), `model_inputs_norm`,
`keras_outputs` (m/s), **plus a `raw_high_rate` variant for G2a**. No `*_denorm` column —
output norm is "none".

```kotlin
// G2 — feature / preprocessing parity  (JVM test, via replay harness)
@Test fun g2_features_match_python() {
    for (c in loadTestSet())
        assertArrayClose(c.modelInputs, Preprocessing.featureWindow(c.rawInputs), atol = 1e-4f)
}

// G2a — decimation / anti-alias parity  (the stage G2 can't see — its input is already
// at model rate). Feed high-rate raw, decimate, compare to the model-rate grid.
@Test fun g2a_decimation_no_aliasing() {
    for (c in loadTestSet())
        assertArrayClose(c.rawInputs, Decimator.toModelRate(c.rawHighRate), atol = /* TBD with model team */ 1e-3f)
}

// G3 — normalize + model parity  (output already m/s)
@Test fun g3_model_path_matches_python() {
    for (c in loadTestSet()) {
        val w = c.modelInputs.deepCopy(); normalizer.apply(w)
        assertClose(c.kerasOutputs, speedModel.run(w), atol = 1e-3f)
    }
}

// G4 — full pipeline from HIGH-RATE raw, ON THE PHONE  (androidTest)
@Test fun g4_full_pipeline_on_device() {
    for (c in loadTestSet()) {
        val dec  = Decimator.toModelRate(c.rawHighRate)
        val feat = Preprocessing.featureWindow(dec); normalizer.apply(feat)
        assertClose(c.kerasOutputs, speedModel.run(feat), atol = 1e-3f)
    }
}
```
- **G0** (before any of these): inspect the `.tflite` + manifest — shapes/dtype/op set,
  tensor names match the stub, SHA-256 checks.
- **G1**: filed from the model team (Keras vs Python-TFLite ≤ 1e-4) — not your code.
- Failure isolation: G2 red = feature math · G2a red = decimation/aliasing · G3 red +
  G2 green = norm / buffer layout / dtype · G4 red + others green = live window assembly /
  timestamp base.
- **G4 green = the model is correctly integrated** ([[06-Challenges]] #8).
- If **F1 → features-in-graph**: G2 shrinks to "raw window assembly matches fixture",
  G3 merges into G4.

---

## Appendix F — Threading model

| Thread | Runs | Must NOT |
| --- | --- | --- |
| `"sensors"` HandlerThread *(app team)* | `SensorEventListener` → `engine.onImuSample` → ring-buffer append | do math, touch UI, block |
| GNSS executor *(app team)* | `GnssStatus.Callback`, `LocationCallback` → engine GNSS state | block |
| `"engine"` single-thread scheduler *(mine)* | the 10 Hz `tick()`: window → features → normalize → model → DR/fusion → publish `state` | touch UI, **allocate** |
| Main thread *(app team)* | collect `state` → move dot, update badge | do engine work |

Ring buffer: engine reads / sensor thread writes under a short lock — copy indices, not data.
Loop: `ScheduledExecutorService` fixed-rate, **never `Thread.sleep`** (drifts, misses 10 Hz).
Give the engine thread `Process.THREAD_PRIORITY_MORE_FAVORABLE`.

---

## Appendix G — Bugs you will hit, and how they look

| Symptom | Likely cause | Where to look |
| --- | --- | --- |
| Dot drifts garbage from second 1, no crash | normalization mismatch / channel order wrong | G3; manifest channel_order |
| Kotlin features ≈ Python but off by a constant scale | units mismatch (g vs m/s², deg/s vs rad/s) | 13-ANSWERS A3 units / manifest |
| Parity green on JVM, red on phone | live window assembly, timestamp base, or float rounding | G4 vs G2/G2a/G3; §12.4 timestamp validation |
| **Live input distribution differs from training, all gates green** | **decimation without anti-alias → aliasing** | **G2a**; Aneesh §4 |
| Small constant along-track offset that ruins long outages | off-by-one in window stride/alignment train vs infer | Aneesh §13.5; manifest window/stride |
| Quantised model biased vs float | quantisation error shows up as bias — the worst kind here | Aneesh §13.2; evaluate the quantised model, not only float |
| Dot lags reality | inference on the engine thread, not async; or window too long | Aneesh §3 (async inference, delayed measurements); config window |
| Dot stutters / freezes 100–300 ms | allocation inside `tick()` → GC pause | Appendix F; profile allocations |
| Position jumps at tunnel exit | snapping on GNSS re-acquire instead of slewing | Aneesh §5; slew over 1–2 s, covariance-weighted |
| Engine state permanently corrupt after one bad sample | NaN propagation not rejected | C.7 `isFinite`; Aneesh §13.9 |
| `a_vert` / `a_horiz` wrong when phone tilts | gravity vector stale, or a budget phone synthesises `TYPE_GRAVITY` | C.3 `lastGrav` |
| Accelerometer readings implausible on potholes | sensor saturation (default range ~±2 g) — clipped samples look plausible | Aneesh §12.5; request widest range, detect clipping |
| Mode badge flickers | handover rule has no debounce / no DEGRADED state | Model-Questions-Part-2 §4; Aneesh §5 |
| Sensors stop when screen locks / after a while | no foreground service, or OEM battery manager killed it | Appendix B.3; §12.8; battery-optimisation exemption |
| Inference latency spikes with screen off | CPU downclock | Aneesh §12.10; measure in that state |
| Everything slows after ~10 min on a dashboard in sun | thermal throttling — a first-class runtime condition | Aneesh §12.11; monitor thermal headroom, degrade deliberately |
| NavIC count always 0 | phone lacks NavIC, or reading constellation wrong | [[11-NavIC-Phone-Testing]]; `CONSTELLATION_IRNSS` |
| Position keeps moving in a tunnel "for free" | still on `FusedLocationProviderClient` — it's doing its own DR | C.6; must be raw `GPS_PROVIDER` |
| Speed pinned at a constant | stub still wired in, or model output tensor misread | C.2 vs C.7; G0 |

---

## Appendix H — The pipeline, one diagram

```
 :android-sensors (app)   highest reliable rate, device frame, irregular, no batching
  accel + gravity + gyro  ──▶  onImuSample()
 :app (raw GPS_PROVIDER)  ──▶  onGnssFix() / onGnssLost()   + C/N0, sat count -> M2 gate
                                    │
 ──────── :engine + :core-* (mine — no android.*, = the edge engine) ────────
                                    ▼
   ring buffer (native rate, no drops)  →  conditioning (timestamps, gaps, clipping)
                                    ▼
   Decimator: native rate → model rate, ANTI-ALIAS low-pass first    [gate G2a]
                                    ▼   window = WINDOW_SAMPLES, newest last
   ┌─ F1 = Kotlin port:  7-feature math → normalize (from manifest)     [gate G2, G3]
   └─ F1 = in-graph:     assemble raw window only
                                    ▼
   engine.tflite (TCN, float32 CPU)  ──▶  forward speed m/s   [+ manifest self-test]
     (own thread; result returns as a timestamped delayed measurement)
                                    ▼
   heading estimator ─▶ dr_step ─▶ non-holonomic ─▶ error-state EKF
     (predict fast; update: speed 10 Hz, NHC 10 Hz, ZUPT event, GNSS 1 Hz if trusted,
      map-match 1 Hz if confident)                                     [gate G5]
                                    ▼
   HMM map-matcher (1 Hz, feeds correction back)  →  output smoothing / slew
                                    ▼
   PositionState { lat, lon, speed, heading, mode, covariance }  ──▶ state (StateFlow)
 ─────────────────────────────────────────────────────────────────────
                                    ▼
   map + icon + uncertainty ellipse + mode badge + GNSS-mute (app)
```
Stages that exist twice (Python reference + my Kotlin) and must match under parity:
**feature math, normalize, heading, dr_step, non-holonomic, ZUPT, EKF, map-match** —
plus the **decimation** stage (G2a), which has no Python twin but must not alias.
