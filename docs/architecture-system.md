# SIH — AI-ML Intelligent Dead Reckoning
## Consolidated architecture and planning document

**Problem statement owner:** Indian Space Research Organisation (ISRO), Department of Space
**Category:** Software · **Theme:** Smart Vehicles
**Mandated dataset:** IO-VNBD — https://github.com/onyekpeu/IO-VNBD
**Compiled:** 2026-08-29

This document consolidates the system-level architecture and planning for the project. **The Android
application architecture is a separate companion document: `SIH-IDR-android.md`.** The living,
individually maintained versions of these notes are in the wiki at `wiki/` — see `wiki/projects/sih.md`
as the hub. These two files are the single-document snapshot.

Team context: the team's own vault is `sih-26168-notes` (problem ID SIH26168). Section 17 reviews it
against this document; NavIC and the parity gates were adopted from it.

---

## Table of contents

1. What the problem actually is
2. End users and success criteria
3. Requirements
4. The central design decision and the error budget
5. Runtime architecture
6. Component inventory and modularity rules
7. Android application — summary and pointer
8. Asset lifecycle — shipping and updating models and maps
9. Map stack — rendering versus matching
10. Map data pipeline
11. Tooling and the open-source landscape
12. Caveats and failure modes
13. UX principles
14. Workstreams, critical path and gates
15. Assumptions, spikes and open questions
16. Decisions register
17. Requirements coverage and gap closures
18. Team repo alignment
19. Glossary

---

## 1. What the problem actually is

Stripped of jargon: **phone navigation dies when GPS dies.** The system must keep the position moving
accurately using only the phone's own motion sensors plus a map, then hand back to GNSS cleanly when
it returns.

It looks like one problem but it is four:

| Sub-problem | Question | Difficulty |
|---|---|---|
| Odometry | How fast am I going, how far have I gone? | **Hard.** No speedometer. This is the whole game. |
| Attitude | Which way am I pointing? | Medium. Gyro drifts slowly; solvable. |
| Constraint | Where on the road grid does that put me? | Medium. The map does the work. |
| Trust | Is the GNSS I am receiving actually good? | Underrated. Bad fixes are worse than none. |

The honest reframe: **this is not a navigation problem, it is an odometry problem on a device that is
not bolted to the wheels.** Everything else is a fight against integration drift. When a design choice
is unclear, ask whether it reduces error growth; if not, it is decoration.

One-sentence pitch: *we turn any phone into a wheel-speed sensor it does not have.*

---

## 2. End users and success criteria

### Four distinct audiences

**A. The evaluators (ISRO / SIH judges) — the immediate user.** Until the competition is won, this is
*the* user. They need evidence, numbers against the stated thresholds, a live demo that does not
embarrass, and defensible methodology. They will probe: was the test set leaked into training, does it
work off our own routes, what happens at re-acquisition, and is the "AI" doing real work or is it
decoration bolted onto a Kalman filter.
*Implication:* instrumented UI, reproducible evaluation, a demo mode we control.

**B. The driver — the real end user, and what the finale demo simulates.** Delivery rider on a
two-wheeler, cab driver, truck driver, ambulance driver, ordinary car owner. Real context: a
budget-to-midrange Android in a cheap holder, engine vibration, sun glare, one glance at a time, a hot
phone, 20% battery, patchy data.

The thing to internalise: **they do not care about metres of error, they care about missing a turn.**
Error at a decision point is the only error with a cost. Twenty metres of along-track error on an empty
highway is invisible; five metres at a flyover fork sends them the wrong way.
*Implication:* optimise accuracy at junctions and exits, not average error over the route.

**C. The fleet or logistics operator — the buyer, not the user.** Continuous track for ETA and
proof-of-delivery, no dead zones, works across a heterogeneous fleet.
*Implication:* eventually an SDK and per-device calibration profiles. Not now.

**D. The integrator using the edge engine** — external or FOG IMU at 200 Hz, no phone. Clean library
API, no Android dependencies, deterministic behaviour, documented frames and units.
*Implication:* the core must be a portable library from day one. Explicitly required by the problem
statement, and the reason architecture matters early.

Optimise for A through screening and the finale, but do not sacrifice B — the finale demo *is* a
driver scenario.

### Two definitions of success, both held

- **SIH success:** pass screening (plots plus preliminary models on IO-VNBD), then win the finale
  (working app, defensible story, robust live demo).
- **Engineering success:** under 10% drift, 10 Hz output, generalises to phones and routes never seen.

They mostly align but diverge — a polished UI serves A far more than B; rigorous route-level
evaluation serves both. Where they conflict, choose deliberately. Avoid work that serves neither.

---

## 3. Requirements

### 3.1 Stated functional requirements — a checklist judges will tick

1. In-vehicle alignment and calibration engine (phone pitch, roll, yaw relative to driving direction).
2. AI speed and vibration filter — local, filters road noise and potholes, estimates forward velocity.
3. Map matching plus kinematic constraints (non-holonomic constraints) against an offline map database.
4. AI-based GNSS+INS fusion engine.
5. Seamless GNSS deficit handler — millisecond transition, in **both** directions.
6. Real-time navigation UI with a smooth, uninterrupted vehicle icon.
7. Works with external IMU data, not only phone sensors.
8. Offline map database.
9. Training offline or in the cloud; inference on device.

### 3.2 Stated performance requirements

- Drift under 10% of distance travelled during blackout. Under 5 m over 50 m in under a minute;
  under 100 m over 1 km at 60 kmph.
- 10 Hz position updates on the phone; around 200 Hz on the edge engine with FOG IMU data.

**Read the 10 Hz carefully.** It is a *position output* rate, not a sensor sampling rate. The two are
independent, and sampling at the highest rate the device delivers while emitting at 10 Hz is strictly
better. See section 16, decision 1.

### 3.3 Derived requirements — unstated, but these separate a working demo from a broken one

- **Fully offline.** A tunnel has no mobile data either. Zero network calls in the critical path.
- Survives screen-off and app backgrounding.
- Handles sensor rate and API variation across phone vendors.
- Battery cost low enough for a one-hour drive to be viable.
- Recovers from the phone being picked up, re-seated or knocked mid-drive.
- **No calibration ritual.** A delivery rider will not perform a figure-of-eight before starting.
  Auto-alignment or the product is dead on arrival.
  - Deliberate, scoped exception: an optional compass (magnetometer) screen exists, reached from
    a button on the map — never on the startup path, never blocking a trip. §3.3 forbids gating
    a trip on a calibration ritual, not offering one. See `MagnetometerCalibrationActivity` in
    `:app`. An earlier version computed its own hard/soft-iron correction (see git history) —
    dropped after real-device testing showed it was correct but impractical for a person to
    actually finish by hand. It now just surfaces the vendor's own continuous calibration via
    `TYPE_MAGNETIC_FIELD` + `onAccuracyChanged`, the same signal every nav app already relies on.
    Nothing is persisted for fusion to consume. The fusion filter is now `ErrorStateEkf`
    (`use_error_state_ekf: true`), whose heading measurements are GNSS bearing above
    `ekf_min_bearing_trust_speed_mps`, the zero-velocity gyro-bias observation, the matched road's
    bearing (`use_road_bearing_heading`, on), and — behind `use_mag_heading`, **off** — the
    compass. Wiring the compass in was never a call site: `getOrientation` yields the *phone's*
    azimuth while the filter tracks the *vehicle's* heading. Rather than estimate that mount
    offset separately, the EKF carries it as a fifth state with a deliberately wide prior, so an
    early reading corrects the offset while a post-blackout reading corrects heading and the
    covariance decides which; declination is passed in per location, since the filter's frame is
    true north. It stays off by default because `docs/model-app-integration-contract.md` (A3.4)
    excludes the magnetometer from the model for vehicle distortion, and that call is only
    overturned by a drive showing `mag_heading_deg - heading` steady per mount at
    `mag_accuracy = 3` — which is what the compass telemetry columns exist to answer.
- Degrades gracefully *and says so* — visible uncertainty beats a confident wrong dot.
- Location never leaves the device by default.

### 3.4 Non-functional targets — fixed numbers, so they are testable

| Target | Value |
|---|---|
| Inference latency per 100 ms tick | under 10 ms on a mid-range phone |
| Model size | under 5 MB |
| Peak RAM | under 150 MB |
| Additional battery drain | under 10% per hour |
| Cold start to dead-reckoning-capable | under 10 s |

### 3.5 Out of scope — stated explicitly so nobody burns a week

Indoor pedestrian navigation · route planning and turn-by-turn guidance (this is a *positioning*
engine; use an existing router) · a map rendering stack of our own · iOS · a cloud fleet dashboard ·
horizontal accuracy guarantees inside multi-level parking (barometer floor detection only) ·
destination search and geocoding (polish, not deliverable).

### 3.6 Constraints and givens

Fixed hackathon calendar, a team of six, whatever phones the team already owns. The dataset is 10 Hz
with GPS-quality ground truth on foreign roads. No OBD-II access assumed at runtime, though it is
valuable for training labels. The team must arrive at the finale with models and offline maps already
on the device — assume no usable internet at the venue. Finale conditions are unknown and may not
involve a real tunnel, so a **GNSS-mute toggle** is required to demonstrate a blackout anywhere; it
must be visibly honest, not faked.

---

## 4. The central design decision and the error budget

### Do not double-integrate the accelerometer

Position error from double integration grows as roughly t³ with accelerometer bias, and a 1° tilt error
leaks 0.17 m/s² of gravity into the forward axis, which is about 6 m of position error in 8 seconds.
The benchmark is unreachable that way.

Restructure so that machine learning predicts a **velocity or displacement increment**, making error
grow as roughly t¹:

```
heading  <- gyro integration + tilt-from-gravity + (weak) magnetometer + map/GNSS anchoring
speed    <- ML model over an IMU window   (this is the whole trick)
position <- integrate speed along heading (2D bicycle model)
```

This mirrors the approach in Onyekpe's own work (WhONet and related INS papers), which regresses
displacement over roughly one-second windows rather than integrating raw acceleration. Our novelty
belongs on top of that, not in replacing it.

### Error budget

Benchmark: 100 m of drift over 1 km at 60 kmph, which is 60 seconds of free inertial navigation.

| Source | Requirement to stay in budget |
|---|---|
| Speed scale-factor bias | **under 3–5%**, giving 30–50 m along-track over 1 km. Dominant term. |
| Gyroscope z-axis bias | 0.01°/s gives 0.6° over 60 s, about 10 m cross-track. Manageable. |
| Heading error at outage entry | 1° gives 17 m cross-track over 1 km. Must be good at handover. |

**Along-track error is dominated by speed-model bias; cross-track by heading.** Map matching fixes
cross-track brilliantly and along-track barely at all — a tunnel is a straight corridor, so snapping to
it says nothing about how far along it you are. Effort therefore belongs on the speed model, not the
map matcher. Turns and junctions are the only places map matching buys along-track information back.

---

## 5. Runtime architecture

### Three tiers

**Offline / desktop, a priori, never at runtime:** dataset curation, model training and validation,
hyperparameter search, model export and quantisation, OSM extraction and routing-graph build, map
packaging. Everything expensive happens here and ships as a file.

**Mobile app layer (Android, Kotlin):** sensor acquisition, timestamping, resampling, lifecycle and
foreground service, model runtime hosting, map rendering, UI, the GNSS-mute toggle, data logging. The
app is plumbing and presentation — it holds no navigation mathematics.

**Navigation core (portable library, shared with the edge engine):** alignment, strapdown attitude
propagation, the error-state EKF, ZUPT and NHC, GNSS quality gating, mode arbitration, map-matcher
interface, output smoothing. No Android dependencies. This is the piece that must also accept a 200 Hz
external FOG IMU, which is why it is separated from day one.

### Data flow

```
GNSS ─────────────────────────────┐
                                  v
accel/gyro/mag/baro               [GNSS quality gate]   C/N0, sat count, HDOP, innovation
  |                                       |
  v                                       | mode: GNSS-aided INS | DEGRADED | DEAD RECKONING
[ingest + resample to 100 Hz]             |
  |                                       |
  ├──> [alignment engine] ── R_phone->vehicle, confidence
  ├──> [attitude propagation 100 Hz] ─────┤
  ├──> [motion context classifier 10 Hz] ─┤  stationary / idle / cruise / turn / shock
  └──> [speed model 10 Hz] ── delta_s, sigma^2
                                          v
                              [error-state EKF]
                    predict 100 Hz; update on each measurement:
                      speed model (10 Hz, R from sigma^2)
                      NHC lateral+vertical = 0 (10 Hz, gated on alignment confidence)
                      ZUPT (event-driven, gated on motion context)
                      GNSS position/velocity (1 Hz, only when the gate says trusted)
                      map-matched position (1 Hz, only when the matcher is confident)
                                          v
                              [pose @ 10 Hz + covariance]
                                          |
                     ┌────────────────────┴──────────────────┐
                     v                                       v
            [map matcher 1 Hz]                    [output smoothing / slew]
            HMM over road segments                          |
            feeds correction back up                        v
                                                    [UI: icon, mode badge,
                                                     uncertainty ellipse]
```

Rates are deliberate: attitude propagates fast because gyro integration needs it; the model runs at
10 Hz because that is the output requirement and inference is the expensive step; map matching runs at
1 Hz because road topology does not change faster than that.

### What the model does, and what it explicitly does not

**Learned:**
- **Forward displacement increment plus its variance**, from a window of calibrated IMU data. The
  primary output and the reason the system works at all — no closed-form physics maps phone vibration
  and chassis kinematics to road speed, so it must be learned.
- **Motion context classification** — stationary, idling, cruising, accelerating, braking, turning, shock.
  Cheap, and it gates ZUPT and drives how the filter handles corrupted windows. Directly satisfies the
  "filter out non-navigation motions" requirement.
- **Gyroscope correction** — scale factor, axis misalignment and temperature-dependent bias that a
  random-walk bias state cannot represent.
- **GNSS quality model** — predicts each fix's position-error scale, becoming the GNSS measurement noise
  and driving the degraded mode.
- **Process-noise adaptation** from the motion context class.

Together these mean the learned models supply the filter's measurement **and both of its noise models**,
while the estimator stays a principled recursive filter. That is what makes the fusion genuinely AI-based
without surrendering the uncertainty accounting. A learned residual on the filter's *output* is
deliberately rejected — it would place an unconstrained term outside the covariance accounting, masking
divergence and making the reported uncertainty untrue. Detail in section 17.

**Deterministic:**
- Integration, state propagation and all EKF mathematics.
- Coordinate frame transforms.
- Map matching and the road graph.
- GNSS quality thresholds and mode arbitration.
- Output smoothing.

The dividing principle: **learn where the mapping is unknown or too non-linear to write down; compute
where the physics is exactly known.** A learned component that replaces known physics adds error and
removes explainability. This split is also the answer to "is the AI doing real work" — the model
produces the one quantity nothing else can produce, and the uncertainty it emits directly drives the
filter's weighting.

### Mode transitions

Three modes, arbitrated by the GNSS quality gate rather than by the presence or absence of a fix:

1. **GNSS-aided INS** — full measurement set; the EKF observes GNSS and continuously re-estimates
   biases and the speed model's scale factor against GNSS Doppler.
2. **Degraded** — fixes are arriving but failing quality or innovation gating (urban canyon multipath).
   GNSS updates are dropped or heavily de-weighted. This mode exists because bad fixes are worse than
   no fix.
3. **Dead reckoning** — no trusted GNSS. Speed model, NHC, ZUPT and map matching only.

Transitions are instant in both directions because nothing switches architecturally — the same filter
keeps running and only the available measurements change. That is what makes the millisecond handover
requirement achievable rather than a special case to engineer. On re-acquisition the position is slewed
over one to two seconds rather than snapped, with covariance driving the blend, so the icon never jumps.

### NavIC in the fusion path

NavIC (IRNSS) is India's own system, built by the organisation setting this problem, and it is named in
the statement alongside GPS and Galileo. Its value here is concrete rather than decorative: its
geostationary and inclined geosynchronous satellites stay high over India, so they slip between buildings
when low GPS satellites are blocked. That shortens the GNSS-denied stretch in urban canyons, and a shorter
outage means less accumulated drift. It also gives faster reacquisition on tunnel exit, and its S-band is
harder to jam simultaneously with L-band — relevant because the statement mentions jamming.

Per-constellation C/N₀ and satellite counts read through `GnssStatus` feed the quality gate directly, not
just a UI badge.

**Honest limits, to state before anyone pitches:** roughly seven satellites against GPS's thirty-one, so
use NavIC *with* GPS rather than instead of it; **in tunnels and basements NavIC is blocked exactly like
GPS** — no satellite system survives there, sensors handle tunnels; and S-band is absent from many phone
chipsets, so the demo phone must be verified.

### Implementation details that matter

- **Inference latency compensation.** The model's output describes a window ending at time *t* but
  arrives later. Apply it as a delayed measurement at the correct timestamp, not at arrival time.
  Ignoring this injects a systematic along-track lag.
- **Threading.** Sensor callbacks on their own thread, the filter on a single dedicated thread, model
  inference asynchronous. The filter must never block on inference.
- **Edge engine parity.** Rates are configuration, not constants. Nothing in the core may assume 10 Hz
  or 100 Hz, or the 200 Hz FOG path becomes a rewrite.

---

## 6. Component inventory and modularity rules

### Six enforced rules

Each exists because a specific failure is otherwise guaranteed.

1. **Every boundary is an interface with at least two implementations from day one** — normally the
   real one plus a replay or stub version. A boundary with a single implementation always grows hidden
   coupling.
2. **Nothing constructs its own dependencies.** They are passed in. This is what allows the same filter
   to run against a live phone, a recorded file, or synthetic test data.
3. **Nothing reads the clock or the sample rate directly.** Time is passed in; time steps are passed in.
4. **No constants in code.** Every threshold, noise parameter and window length comes from a versioned
   config file with documented defaults.
5. **The navigation core imports nothing platform-specific.** Enforced with a build check, not a
   convention — one stray Android import silently kills the edge-engine deliverable.
6. **One canonical data schema** for sensor samples, shared by the dataset loader, the logger, the
   replay reader and the live reader.

### Layer 0 — Shared contracts. Build first, freeze early

Data types (sensor sample, GNSS fix, pose, covariance, mode). A reference-frame conventions document,
and a rotation type that carries which frames it converts between so mismatches are caught at compile
time rather than becoming silent sign errors. A single monotonic nanosecond time base. A versioned
configuration schema with defaults. The core interfaces: sensor source, speed estimator, measurement
provider, map provider, map matcher, asset provider, clock, logger, output sink.

*Solves:* frame confusion, hardcoded rates, the inability to test without a car.

### Layer 1 — Sensor input

Android sensor reader (accelerometer, gyroscope, magnetometer, barometer, GNSS, raw GNSS quality).
**Replay reader** — reads a recorded log and emits identical samples; the single most valuable component
for development speed. External IMU reader (serial or UDP at 200 Hz) for the edge engine. Synthetic
source generating known motion for unit tests. Conditioning stage: resample to a fixed grid, normalise
timestamps, detect gaps, reject outliers.

*Solves:* vendor rate variation and jitter, sensor batching, testability, the external-IMU requirement.

### Layer 2 — Preprocessing and alignment

Calibration store (per-device biases and scale factors, persisted). Gravity and tilt estimator.
Alignment engine — static levelling plus dynamic yaw estimation via PCA on horizontal acceleration
during braking or acceleration events — behind a strategy interface. Mount-change detector that triggers
re-alignment and inflates uncertainty. Windowing buffer feeding the model.

*Solves:* arbitrary phone mounting, the phone being knocked mid-drive, the no-calibration-ritual
requirement, thermal bias drift.

### Layer 3 — Learned components

Model runtime abstraction so TFLite and ONNX Runtime are interchangeable. **Five speed estimator
implementations behind one interface:** the trained ML model, a GNSS-Doppler estimator for labelling and
live evaluation, a constant-velocity baseline, a raw double-integration baseline, and a ground-truth
estimator that replays known speed. This plurality is not academic — it is what lets the filter be
developed and debugged before the model exists, and what makes the benchmark comparisons honest. Motion
context classifier. Manifest loader and validator.

*Solves:* the missing speedometer, non-navigation motion rejection, filter-versus-model bug isolation,
honest baseline comparison.

### Layer 4 — Navigation core

Attitude propagation. Error-state EKF with **measurement providers registered as plugins** — speed,
non-holonomic constraint, zero-velocity, GNSS, map match, barometer — each enabled, disabled or
re-weighted from config, which makes ablation studies trivial and graceful degradation a configuration
rather than a special case. GNSS quality gate with thresholds in config. Mode arbiter. Innovation and
NIS monitoring with a divergence guard. Output smoothing and re-acquisition slew. Delayed-measurement
handling.

*Solves:* drift, multipath fixes that look valid, seamless mode switching, position jumps on
reacquisition, silent filter divergence, inference latency lag.

### Layer 5 — Maps

Map provider interface (nearby segments, geometry, road class, tunnel attributes and lengths). OSM
extraction pipeline on the desktop. Routing graph builder and serialiser. HMM map matcher retaining
multiple hypotheses. A null matcher for areas with no map data, so multi-level parking degrades cleanly
instead of snapping to a nonexistent road. Offline tile or vector-map store for rendering.

*Solves:* cross-track drift, parallel service-road snapping, tunnel landmark anchoring, the
no-map-available case.

### Layer 6 — Mobile application

Foreground service, wake lock, lifecycle handling. Permissions flow. Map rendering. UI: vehicle icon,
uncertainty ellipse, mode badge, speed readout, developer overlay showing raw versus fused position.
GNSS-mute toggle. Trip recorder writing the canonical log format — this is also the data-collection
tool, so it ships first rather than last. Asset manager for model and map versions, update and rollback.
Settings.

### Layer 7 — Offline tooling

Dataset loaders for IO-VNBD and for our own recordings, both emitting the same canonical schema.
Training pipeline with experiment tracking. Evaluation harness: outage simulator, drift metrics split
along-track and cross-track, plotting. Export, quantisation and manifest generation. OSM processing
pipeline. A replay command-line tool that runs **the same navigation core** as the phone, so results are
directly comparable.

### Layer 8 — Edge engine

A command-line or daemon wrapper around the same core, an external IMU adapter, and a configuration
profile for 200 Hz. If layer 0's rules were followed this is packaging; if not, it is a rewrite.

### Cross-cutting

Configuration loading, structured logging, trace recording for post-drive analysis, feature flags, asset
versioning.

### Build order

Contracts, then the replay source and evaluation harness, then baselines, then the speed model, then the
filter driven by ground-truth speed, then integration of model and filter, then maps, then the app, then
the edge engine. Sensor plumbing, data collection and the OSM pipeline run in parallel from day one.

---

## 7. Android application — summary and pointer

**Full detail is in the companion document `SIH-IDR-android.md`.** Summarised here only enough to keep
this document coherent.

The Android app is plumbing and presentation and holds **no navigation mathematics**. It reads sensors,
keeps the phone awake, hosts the model runtime, draws the map and handles the UI; the portable navigation
core does everything else and does not know it is running on a phone. That separation is what makes the
edge-engine deliverable packaging rather than a rewrite, lets the whole engine be tested on a laptop by
replaying recorded traces, and keeps position bugs distinguishable from UI bugs.

Module structure, dependencies pointing downward, with a build check failing on any `android.*` import
below the line:

```
:app · :android-sensors · :android-assets · :android-model
──────────────── no Android below this line ────────────────
:engine · :core-nav · :core-map · :core-model · :core-assets · :core-replay · :core-types
```

`:edge-cli` depends on the same lower half plus an external IMU adapter, duplicating no code.

Five threads: sensor (timestamp and buffer only), engine (all navigation state, emits 10 Hz), inference
(returns delayed measurements carrying their window's timestamp), map matching (1 Hz), and UI
(interpolates the marker to 60 fps over 10 Hz poses). The filter never blocks and IMU samples are never
dropped.

The points from that document with system-level consequences:

- **Sample at the highest rate the device delivers**, and treat decimation to the model's rate as an
  explicit, anti-alias-filtered, separately tested stage.
- **Use the raw GNSS provider, never the fused location provider** — see section 16, decision 2.
- Prefer uncalibrated sensor types; never `TYPE_LINEAR_ACCELERATION`; copy pooled sensor values
  immediately; align sensor and GNSS timestamps on elapsed realtime.
- Assets resolve through a provider — imported, then downloaded, then packaged — with a manifest asserted
  at load. Nothing opens a model or map by literal path.
- A foreground service owns engine lifetime and checkpoints state so process death resumes degraded.
- Staged parity gates G0–G7, plus G2a for decimation, localise the "works on the laptop" failure.

## 8. Asset lifecycle — shipping and updating models and maps

**The principle:** a model, a map region and a configuration file are **versioned data, not code**.
Baking them in as fixed resources means every correction requires an app-store release. The engine loads
them through an indirection layer, and that layer is built on day one — retrofitting it later touches
every module.

**Three sources, checked in order:**

1. **Sideloaded bundle** — a file imported manually through the system document picker. Works with no
   network and allows a model swap in minutes at any time, including at the finale.
2. **Downloaded bundle** — fetched over Wi-Fi during a previous session and stored in app storage.
3. **Bundled default** — shipped inside the app. Guarantees the app works on first launch with no
   network at all.

The engine asks an asset provider for "the current speed model" and receives a file path plus metadata.
It never knows which source supplied it. Nothing anywhere else in the app opens a model or map by
literal path.

**The manifest** carries: asset id and version, SHA-256 checksum verified before use, asset type, the
input and output tensor specification for models, units and conventions, window length and stride,
expected sample rate, normalisation constants, a self-test vector, minimum engine version, and for maps
the region and validity dates. **The engine validates the manifest against its own expectations and
refuses to load anything mismatched**, loudly, rather than producing silently wrong positions.

**Update policy:** checked and downloaded only when parked, charging and on Wi-Fi — never mid-drive.
Download to a temporary location, verify the checksum, then swap atomically. Keep the previous version so
rollback is one pointer change. The swap takes effect at the next session start, because changing the
model under a running filter would produce a discontinuity.

**Automatic rollback:** whenever GNSS is available and trusted the system already has ground truth. If a
newly installed model performs measurably worse than the previous one over a session, revert
automatically and report it.

**Per-device and per-vehicle calibration** is a second, quieter form of updating that needs no network.
Sensor biases, scale factors and the phone-to-vehicle mounting rotation are learned during normal
driving and stored locally, versioned separately, and discarded when the mounting changes. This is what
lets accuracy improve over the first few drives with no download.

---

## 9. Map stack — rendering versus matching

### The split that resolves the question

"Map service" is two unrelated jobs that are habitually merged:

| | Rendering | Matching |
|---|---|---|
| Job | Draw a map on screen | Constrain the estimated position to the road network |
| Affects | User experience only | **Position accuracy** |
| Needs | Vector map files or tiles, a view widget | Road geometry, topology, an index, an algorithm |
| If it fails | The app looks bad | The app is wrong |

MapLibre's difficulty is entirely on the rendering side, and **rendering contributes nothing to
accuracy.** Pick the lowest-risk renderer and spend the saved effort on matching.

### Decision (proposed, pending two spikes)

- **Rendering: Mapsforge.** Built for this case — offline vector maps on Android, prebuilt regional map
  files, an Android map view, no tile server anywhere.
- **Matching graph: our own binary file**, built with pyosmium from a Geofabrik extract, carrying node
  and edge geometry, road class, one-way flags, tunnel attributes and lengths, plus a spatial index.
- **Matcher: our own forward-only HMM** over that graph, keeping several hypotheses alive and emitting a
  position with covariance rather than a hard snap.
- **MapLibre** is a later upgrade, not the starting point.
- The renderer sits behind our own thin map-view interface, so switching costs about a day.

### Why not the alternatives

Off-the-shelf matchers — GraphHopper, Valhalla's Meili, OSRM — are built to snap a *complete recorded
trace* to roads after the fact. We need an online, forward-only constraint that reports uncertainty so
the filter can weight it. That mismatch means none of them drops in cleanly, which removes most of the
argument for taking on their integration cost.

**osmdroid** is the simplest API with excellent MBTiles support, but sourcing raster tiles legally is a
trap: bulk-downloading from the public OSM tile servers violates their usage policy. Reasonable second
choice if tile sourcing is handled. **Google Maps SDK** is rejected as network-dependent, contradicting
the fully-offline requirement. **Mappls / MapmyIndia** is appealing given the evaluator and worth naming
in the proposal as an integration option, but licensing and genuine offline capability need verification
before it can be on the critical path.

### Conditions that could change this

- **GraphHopper spike** (half a day): if it can be driven incrementally and made to expose match
  confidence, it replaces our graph builder and saves real work.
- **Mapsforge render spike** (half a day): load a region, draw a polyline and a marker over it.

### Map data accuracy

OSM coverage in India is generally good on highways and main roads, thinner and less reliable on service
roads and inside interchanges, with inconsistent tunnel and flyover attributes. Since the entire map
matching benefit depends on the underlying geometry being right, inspect the specific corridors intended
for testing and demonstration rather than assuming uniform national coverage. Where data is wrong it can
be corrected upstream in OSM, which is a legitimate contribution worth mentioning.

---

## 10. Map data pipeline

Nothing here happens on the phone at drive time. It runs once on a desktop, produces files, and those
files are downloaded to the phone over Wi-Fi ahead of time. Worked example: Bangalore.

```
Geofabrik regional extract (.osm.pbf)
        |
        |  clip to the area we care about
        v
   area extract
        |
        +---> filter to drivable roads ---> our binary graph  (matching, accuracy)
        +---> Mapsforge writer         ---> render map file   (display)
        +---> place-name index         ---> small database    (optional, search)
        |
        v
   region bundle + manifest  --->  downloaded by the app over Wi-Fi
```

**Step 1 — source data.** Geofabrik publishes extracts by region; India is split into zones and
Karnataka sits in the southern zone:

```
wget https://download.geofabrik.de/asia/india/southern-zone-latest.osm.pbf
```

**Step 2 — clip to the area**, a city plus a generous buffer so a drive leaving the city does not fall
off the edge of the map (roughly 50 km around Bangalore):

```
osmium extract -b 77.0,12.5,78.2,13.5 southern-zone-latest.osm.pbf -o bangalore-area.osm.pbf
```

**Step 3 — filter to roads** for the matching graph:

```
osmium tags-filter bangalore-area.osm.pbf w/highway -o bangalore-roads.osm.pbf
```

A pyosmium script then reads that and writes our own compact binary containing only what the matcher
needs, which is far smaller than the source extract.

**Step 4 — build the render file.** Either download a prebuilt Mapsforge file at the granularity we
want, or generate one from the same extract so region boundaries match the graph exactly.

**Step 5 — optional place index.** A small database of place and road names with coordinates, if
destination search is wanted. Full offline geocoding is far heavier than we need, and search is not part
of the deliverable.

**Step 6 — package and ship.** The files plus a manifest become a **region bundle** with an identifier,
version, bounding box, checksum and build date, hosted in object storage or as a release attachment and
downloaded once over Wi-Fi while parked — following the same three-source precedence as models.

**Region granularity** trades download size against the risk of driving off the edge. A city plus a
50 km buffer keeps downloads modest and covers almost all commuting; a whole state covers intercity
driving at a much larger download. Default to city-plus-buffer, offer the choice, and make the boundary
visible.

**Leaving the covered area must degrade cleanly:** the matcher reports no candidates, the filter stops
applying map corrections and continues on inertial and GNSS data, and the map view shows blank space
rather than failing. This is the same code path as multi-level parking, where no road network exists
either — a good reason to build and test it early rather than treating it as an error case.

**Automate the whole pipeline** behind one script taking a region name and bounding box, so rebuilding
for a new demo city costs minutes. This matters when the finale venue turns out to be somewhere
unplanned.

---

## 11. Tooling and the open-source landscape

### The governing idea

Most tool decisions here are risk decisions. Where two options are similar in capability, pick the one
whose failures are **loud and local** rather than silent and distributed.

### Tool stack

| Layer | Choice | Why, and the risk it carries |
|---|---|---|
| App language | Kotlin, Android Studio, Gradle | Native sensor timing control |
| Core language | Kotlin, platform-free modules | Same code on desktop JVM, so the edge engine is not a rewrite |
| Sensors | Android SensorManager directly | No wrapper library; wrappers hide the settings that matter |
| GNSS | `LocationManager` raw GPS provider, `GnssStatus`, GNSS measurements | **Never the fused provider** — pre-smoothed, may already contain dead reckoning, and its correlated output breaks the filter's white-noise assumption |
| Sampling | Highest available rate, decimated to the model's rate as a tested stage | 10 Hz is the output requirement, not a sampling requirement |
| Training | Python, PyTorch, NumPy, pandas, SciPy | Standard |
| Export | ONNX or LiteRT/TFLite | **The main integration risk in the project** |
| Inference | TFLite, CPU (XNNPACK) path | Hardware accelerator paths vary by chipset and can silently produce different numbers |
| Map rendering | Mapsforge | Offline by design, no tile server, no tile-policy problem |
| Map data | OSM extracts (Geofabrik), osmium / pyosmium | Standard pipeline we control |
| Matching | Our own forward-only HMM; GraphHopper as a spike | Off-the-shelf matchers are batch post-processors |
| Evaluation | pandas, matplotlib | Keep it boring |
| Experiment tracking | Config in git plus a results CSV | MLflow is overhead at this scale |
| Ground truth | ELM327 OBD-II dongle over Bluetooth | Cheap clones are unreliable; buy two, test early |
| Interim collection | An existing sensor-logger app | So collection starts before our logger is finished |
| Version control and CI | git, optionally CI running the JVM replay tests | Replay tests are fast enough for every commit |

### OpenStreetMap is data, not software

Three things share the name and get conflated: **the dataset** (ODbL licensed, what we want); **the
public tile servers** at openstreetmap.org (a demonstration service whose usage policy forbids bulk
downloading, never a production or offline source); and **the community and editing tools**. We consume
the dataset and do not depend on the servers.

**Getting data:** Geofabrik regional extracts, BBBike custom bounding-box extracts, the Overpass API for
querying specific features such as whether OSM knows about a particular tunnel and what length it claims.

**Processing:** osmium-tool for fast filtering and conversion, pyosmium for building our graph, OSMnx
for prototyping over a single corridor, and shapely, geopandas, pyproj and GeographicLib for geometry
and accurate geodesic distance. Use GeographicLib rather than hand-rolled haversine wherever distances
matter. osm2pgsql with PostGIS is almost certainly overkill.

**Tiles, if needed later:** Planetiler, tilemaker, PMTiles as a cleaner single-file alternative to
MBTiles, Maputnik for style editing.

**Routing and matching:** GraphHopper (Java, offline, Android-capable, has a matching module — worth the
spike), Valhalla with Meili (excellent matching, but a C++ and NDK build we do not want to own), OSRM
(server-oriented), leuvenmapmatching (Python — ideal for prototyping the matching approach on the
desktop before writing Kotlin), and hmm-lib (the Java Newson–Krumm implementation, useful as a reference
even if we write our own). **OSMnx plus leuvenmapmatching gives a working desktop matching prototype in
an afternoon**, which de-risks the on-device implementation considerably.

**Editing and verification:** JOSM, iD and StreetComplete for fixing wrong or missing tunnel and road
data on our test corridors.

### Open-source tooling beyond maps

- **Allan variance tools** (for example `allantools`) — characterise the noise and bias instability of a
  specific phone's gyroscope and accelerometer from a long stationary recording. This gives real numbers
  for the filter's process-noise parameters instead of guesses, and is one of the highest-value cheap
  experiments available.
- **RTKLIB** — GNSS processing; more than we need, but the reference for understanding raw GNSS.
- **GNSSLogger** — Google's open-source Android raw-GNSS logging app; a reference implementation and an
  interim collection tool.
- **GTSAM** and **Ceres** — factor-graph and optimisation libraries, if smoothing rather than filtering
  is ever wanted. Out of scope now, worth citing as a future direction.
- Reference implementations of the **Madgwick** and **Mahony** attitude filters, as a sanity baseline.

### Supplementary open datasets

IO-VNBD is mandated, but the problem statement explicitly allows other open-source datasets and the
models will generalise better with more: **comma2k19** (many hours of highway driving with raw sensors
and GNSS), **OxIOD** (Oxford inertial odometry), **Google Smartphone Decimeter Challenge** data (Android
raw GNSS plus IMU, very close to our exact sensor setup), and KITTI and similar for reference though they
are vehicle-rig rather than smartphone. Any additional dataset must be named and licensed correctly in
the submission.

### Licensing, which matters for a submission

- **OSM data is ODbL.** Attribution — "© OpenStreetMap contributors" — is required wherever the map is
  shown. More subtly, a road graph we derive and then distribute is a derived database, which brings
  share-alike obligations.
- **Verify the licence of every library before committing to it.** Permissive licences are typical in
  this space, but at least one plausible rendering choice is copyleft, which has implications for how it
  is linked into a distributed app. A half-hour check that avoids a late surprise.
- Confirm the terms attached to IO-VNBD and to any supplementary dataset used for training.

Record licences and attributions in the repository from the start rather than reconstructing them the
week before submission.

---

## 12. Caveats and failure modes

### 12.1 Dataset caveats

1. **The IO-VNBD smartphone subset is 10 Hz, which kills vibration-spectrum speed estimation.** Engine
   harmonics and tyre or road noise that correlate with speed live at 20–100 Hz; at 10 Hz that
   information is aliased away. Literature doing spectral speed estimation assumes 100–400 Hz. IO-VNBD
   therefore trains the *kinematics-based* model, but our own 100–200 Hz data collection is required to
   exploit vibration. Budget two to three weeks of driving with a logger writing raw sensors, GNSS and
   ideally OBD-II speed.
2. **Ground truth is GPS, not RTK.** Sub-metre claims on this dataset are not defensible. Report drift
   as a percentage of distance, which is robust to a few metres of ground-truth noise.
3. **Domain gap.** IO-VNBD is UK, Nigeria and France, a research vehicle, a specific mount. Indian
   roads, two-wheelers and a phone loose in a holder are different distributions. A two-wheeler leans
   into turns, so roll couples into heading in a way a car's does not and NHC assumptions weaken.
4. **Split by route, never by random window.** Random splits leak neighbouring samples across the
   boundary and inflate results substantially. Hold out entire journeys, ideally entire geographies.
   Informed judges will ask exactly this.

### 12.2 Scenario caveats — state these honestly in the proposal

5. **Multi-level parking has no road network and no meaningful heading reference.** Map matching gives
   nothing. Use the barometer for floor detection and accept degraded horizontal accuracy. Saying so
   reads as competence, not weakness.
6. **Long straight tunnels are the worst along-track case** — no turns to re-anchor distance. Mitigation:
   OSM tunnel ways have known lengths, so portals can be used as landmark measurements.
7. **Constant-speed motorway cruising degrades the speed model most** — almost no kinematic signal. This
   is the case that will break the 1 km test, so over-sample it in training.
8. **Traffic jams:** repeated ZUPTs help enormously, but false ZUPTs while creeping cause
   under-integration. Tune the detector against real slow-crawl data.

### 12.3 Android platform caveats

9. **No gyroscope at all** on some budget Android phones. Detect at startup and refuse clearly.
10. **Requested sample rate is a hint, not a contract.** Measure the real delivered rate per device.
11. **Batching latency** unless the maximum report latency is set to zero.
12. **Timestamp base varies** by vendor. Validate against the system clock at startup.
13. **Accelerometer saturation.** The default range on some devices is around ±2 g and a pothole exceeds
    it. Clipped samples look like plausible readings. Request the widest available range and detect
    clipping explicitly.
14. **Sensor event objects are reused.** The values array is pooled and overwritten by the next event.
    Copy immediately; holding a reference produces corruption that looks exactly like sensor noise.
15. **Callbacks arrive on the main thread unless a handler is supplied.**
16. **Delivery stops when the screen is off**, or an aggressive OEM battery manager kills the process —
    severe on several OEM skins common in India. Foreground service, explicit battery-optimisation
    exemption, and per-OEM instructions for the user.
17. **Display rotation and natural orientation.** Sensor axes are fixed to the device, not the display,
    but the standard remapping helpers make it easy to introduce a rotation that does not exist. Keep our
    own explicit phone-to-vehicle rotation and never mix it with display-derived remapping.
18. **CPU downclocking with the screen off** raises inference latency. Measure in that state.
19. **Thermal throttling is a first-class runtime condition.** Sustained GNSS, high-rate sensors,
    inference and a bright screen heat a phone quickly on a dashboard in the sun. Monitor thermal
    headroom and degrade deliberately rather than letting everything slow unpredictably.
20. **Foreground services can still be killed** on low-memory devices. Checkpoint and resume degraded.
21. **Interruptions** — calls, audio focus, app switching — must not stop recording or navigation.
22. **Bluetooth contention** during collection between the OBD dongle and other devices.
23. **Minimum SDK.** Raw GNSS measurements, foreground service types and notification permissions have
    different version floors. Choose deliberately and record what degrades below which version.
24. **High-rate logging throughput.** Buffer writes, never flush per sample, rotate files. A stuttering
    logger corrupts the data it is collecting.
25. **Bundling city-scale maps inside the app is not viable** given app bundle size limits. Maps must be
    downloaded or delivered as asset packs.
26. **Emulators have no meaningful sensors.** Development must be replay-driven.
27. **Duplicate or out-of-order timestamps.** Never assume monotonicity.
28. **Thermal drift of gyro bias** on a dashboard in the sun; estimate bias online, not once at startup.
29. **The magnetometer inside a car body is close to useless** — steel shell, speaker magnets, wiring,
    charger. Weakly weighted heading prior on long straights at most, or drop it.

### 12.4 GNSS caveats

30. Raw GNSS measurements unsupported on some devices.
31. Speed accuracy field missing, which weakens online recalibration.
32. The user granting only approximate location.
33. Mock location apps producing fake fixes.
34. Slow first fix, so the app must be usable before a fix exists.
35. **Multipath in urban canyons produces confident-looking wrong fixes** — the reason the quality gate
    exists and the reason "degraded" is a distinct mode.
35d. **The fused location provider blends in its own dead reckoning**, making our fusion circular and our
    drift measurement meaningless, and its smoothed, correlated output makes the filter overconfident.

### 12.4a Sampling and decimation caveats

35a. **Aliasing on decimation.** Downsampling a high-rate stream to a model's lower rate folds
    high-frequency energy into the retained band unless it is low-pass filtered first. It corrupts the
    data being kept, not only the data being discarded.
35b. **Parity fixtures that already start at the model's rate never test the decimation stage**, so this
    bug passes every gate while the live input distribution still differs from training. Gate G2a exists
    for exactly this.
35c. **Below 5 Hz of usable bandwidth the entire vibration channel is gone** — wheel rotation around 9 Hz
    at 60 kmph, engine firing roughly 25–70 Hz, tyre and road noise from 20 Hz upward. That channel is the
    only signal during constant-speed cruising, which is the case that breaks the 1 km benchmark.

### 12.5 Model integration caveats

36. **Conversion failure.** Recurrent layers (GRU, LSTM) are a classic source of export breakage and
    quantisation trouble — a strong argument for a convolutional model on engineering grounds alone.
37. **Quantisation accuracy loss shows up as bias**, the error type we can least afford. Always evaluate
    the quantised model, never only the float one.
38. **Duplicated feature computation is the single most likely silent failure.** If features are computed
    in Python for training and re-implemented in Kotlin for inference, the two will drift. Preferred fix:
    **push normalisation and feature computation inside the model graph**, so only a raw sensor window
    crosses the boundary. Fallback: golden test vectors asserted in CI on both sides.
39. **Ship a model self-test vector.** Store a canned input and its expected output in the manifest and
    check it at startup. This one cheap check catches conversion errors, quantisation drift, normalisation
    mismatch and wrong-model-loaded immediately rather than after a confusing test drive.
40. **Units and conventions must live in the manifest and be asserted** — m/s² versus g, radians versus
    degrees per second, gravity included or removed, channel order, window length and stride, sample rate.
41. **Windowing bookkeeping.** An off-by-one in stride or alignment between training and inference
    produces a small constant offset — precisely the systematic bias that destroys long-outage accuracy
    while looking harmless in aggregate metrics.
42. **Sample rate mismatch.** The resampler must be the same algorithm on both sides, not merely similar.
43. **Float precision.** Train and evaluate in the precision the phone will use.
44. **Interpreter threading and warm-up.** The interpreter is not thread-safe; the first inference is much
    slower. Warm up with dummy input at startup.
45. **Stateful models carry hidden state** across calls and must be reset on mode changes and gaps —
    another reason to prefer convolutional over recurrent.
46. **NaN propagation.** A single bad sample can poison filter state permanently. Validate model inputs
    and outputs and reject non-finite values loudly.

### 12.6 Map caveats

47. Offline vector map setup is more involved than documentation suggests — spike it in week one.
48. City-scale packages are large; decide region granularity early.
49. OSM tunnel data may be missing or wrong on Indian roads — verify on specific corridors.
50. Routing graph memory footprint on a phone.
51. **Rendering jank from an unbounded breadcrumb layer.** Cap retained points and simplify the geometry.
52. Coordinate and projection mistakes between WGS84 and Web Mercator.
53. **Bulk-downloading public OSM raster tiles violates the tile usage policy.**

### 12.7 Storage, OS and process caveats

54. Document-picker URI permissions not persisted across restarts.
55. Scoped storage restrictions on where traces can be written.
56. Checksum mismatch on an imported bundle, which must fail loudly.
57. Disk full during a long recording.
58. Process death under memory pressure mid-drive.

### 12.8 Process and programme caveats

59. **Resist end-to-end "deep net outputs latitude and longitude".** It demos well in-distribution,
    collapses outside it, gives no uncertainty, no graceful degradation and no story for the edge variant.
    Hybrid ML-in-the-loop-of-a-filter is both more accurate and more defensible.
60. **Architect for the external 200 Hz IMU on day one.** Retrofitting it later is painful.
61. **Device diversity.** Everything above varies by device — three physically different phones minimum,
    including one budget device and one OEM known for aggressive background killing.
62. **Demo day:** the venue may have usable GNSS everywhere so no real blackout can be shown (hence the
    mute toggle); the phone may overheat during a long demo; screen mirroring adds load.

---

## 13. UX principles

The user is a driver, often on a two-wheeler, glancing for under a second at a hot phone in bright
sunlight. Design for that, not for a desk.

- **Never freeze and never jump.** This is the whole product promise. A continuously moving icon with
  honest uncertainty beats an accurate icon that stalls and then teleports. Every correction is slewed,
  never snapped.
- **Be honest without being alarming.** A quietly growing uncertainty ellipse communicates degradation
  without demanding attention. Never display a confident position we do not have; equally, never raise
  warnings a driver cannot act on.
- **No jargon anywhere the driver can see.** Not "EKF", "dead reckoning", "NHC" or "drift". "GPS lost —
  still tracking you" is the whole message. Technical terms belong in the developer drawer.
- **Zero setup ritual.** No calibration dance, no configuration before driving. Alignment happens
  automatically during normal driving. Any onboarding is one sentence, shown once, skippable.
- **Glanceability.** Large type, high contrast, few elements, automatic day and night themes. Anything
  requiring more than a second of attention is a safety problem, not a feature.
- **Silence while driving.** No notifications, no dialogs, no permission prompts mid-drive.
- **Colours must survive sunlight and colour blindness.** GNSS and dead-reckoned trail segments must
  differ in more than hue — use lightness or pattern as well, never red against green.
- **Degrade with an explanation the user can act on.** No gyroscope on this phone is a clear one-line
  message. A knocked phone shows "re-aligning", not an error. What the driver can fix should say what to
  do; what they cannot should not be raised at all.
- **Build trust after the drive, not during it.** A short post-trip summary — distance covered without
  GNSS, estimated accuracy, where the outages were — is the moment for detail. It is also an excellent
  thing to show an evaluator.
- **Battery honesty.** Show expected drain and offer a lower-power mode that reduces update rate rather
  than silently draining the phone.
- **Separate the evaluator experience from the driver experience.** Judges want the two traces overlaid,
  live numbers and the mute toggle; drivers want none of it. Put the analytical view behind a deliberate
  switch.

---

## 14. Workstreams, critical path and gates

### Goal

**Estimate forward speed accurately enough from a phone IMU that dead-reckoning drift stays under 10% of
distance travelled**, wrapped in a filter, constrained by a map, and delivered in an app.

Two goals in sequence, and they are not the same: the **screening goal** is preliminary models plus
position plots on an IO-VNBD subset, needing only W1 and W2; the **finale goal** is the working app plus
edge engine with all six capabilities. Do not build for the finale before screening is passed.

### Six workstreams, sized for a team of six

**W1 — Data and evaluation.** IO-VNBD loader, unit and frame documentation, windowed dataset builder,
route-level splits, outage simulator (10/30/60/180 s), drift metrics split along-track and cross-track,
the two naive baselines, plotting. Blocks everything. *Done when* baselines reproduce and plots
regenerate from one command.

**W2 — Speed model.** Feature pipeline, CNN or TCN displacement regressor, variance head, signed-bias
tracking, held-out-vehicle test, export. *Done when* it beats both baselines on held-out routes with
signed bias under 3%.

**W3 — Filter and fusion.** Error-state EKF, ZUPT, NHC, GNSS update, innovation and NIS monitoring, GNSS
quality gating, re-acquisition slew. Develops against ground-truth speed first, so it does not wait for
W2. *Done when* replay stays under 10% drift on 60 s outages.

**W4 — Maps.** Splits into two independent tracks: a low-risk rendering track that can finish early, and
the matching track that carries the accuracy weight. OSM extraction, graph build, HMM matcher, tunnel
portal landmarks, region bundles. *Done when* the matcher runs fully offline on a recorded track without
snapping to parallel service roads.

**W5 — Android app.** Sensor plumbing, resampling, foreground service, 10 Hz loop, model runtime, map
view, UI, GNSS-mute toggle, and the data-collection logger. **The logger is W5's first deliverable, not
its last.** *Done when* it runs live at 10 Hz on three different phone models.

**W6 — Alignment, edge engine and demo.** Static tilt, dynamic yaw alignment, mount-change detection,
portable core packaging, 200 Hz validation, plus the deck, documentation, demo rehearsal and backup
video. Alignment comes first because W3 and W5 both depend on it.

### Capability coverage

| Stated capability | Workstream |
|---|---|
| Alignment and calibration engine | W6 |
| AI speed and vibration filter | W2 |
| Map matching plus kinematic constraints | W4 (matching) and W3 (NHC) |
| AI GNSS+INS fusion | W3 with W2's variance head |
| Seamless GNSS deficit handler | W3 |
| Real-time navigation UI | W5 |
| External IMU support | W6 |

### Critical path

W1, then W2, then W3, then integration. W4 and W5 run in parallel and merge at integration. W6's
alignment feeds W3 and W5 early; the edge engine feeds in late.

**The longest pole is not the model — it is integration.** Reserve the final two weeks entirely for it.

### Must start on day one, all three at once

1. W1 dataset loading — blocks everything.
2. W5 logger app — our own data collection has weeks of lead time.
3. W4 OSM pipeline — a bigger job than it appears.

### Interfaces to freeze in week one

Log schema (W5 writes, W1 reads) · frame conventions (W6 defines; W2, W3, W5 obey) · model input and
output tensor spec (W2 defines, W5 consumes) · the position state struct passed from filter to matcher
to UI (W3 defines).

### Go / no-go gates

1. **Evaluation harness plus baseline numbers on IO-VNBD.** If a sane baseline cannot be reproduced,
   stop and fix data understanding — do not proceed to modelling.
2. **Speed model clearly beats both baselines on held-out routes** → proceed to the filter.
3. **End-to-end offline replay under 10% drift on 60 s outages** → proceed to the app.
4. **App runs live at 10 Hz on three different phones** → proceed to polish and demo prep.

The screening submission sits between gates 1 and 2.

### Evaluation protocol, standardised in week one

Drift as a percentage of distance and in absolute metres, bucketed by outage duration {10, 30, 60,
180 s}, split into along-track and cross-track components, evaluated across held-out routes, always
against the two naive baselines. Fixing this early is what stops the team fooling itself later.

### Risk register

| Risk | Mitigation |
|---|---|
| Speed-model bias, the dominant error term | Targeted signed-error evaluation; online recalibration against GNSS Doppler |
| Own data not collected in time | Start collection in week one even with an imperfect logger |
| App integration left late | Parallel track from day one, owned by a named person |
| Overfit results embarrass us at screening | Route-level splits; report honestly with baselines |
| Live demo fails at the finale | GNSS-mute toggle, recorded backup, everything offline |
| Model export or quantisation breaks late | Convert a toy model of the final architecture in week one |
| Map matching consumes the schedule | Split rendering from matching; spike GraphHopper before building |

---

## 15. Assumptions, spikes and open questions

### Assumptions to validate in week one

| Assumption | Experiment |
|---|---|
| Our phones actually deliver 100–200 Hz IMU | A small logger; measure real rate and timestamp jitter |
| Vibration carries speed information at our rate | Spectrogram versus OBD or GNSS speed on one drive |
| IO-VNBD has usable raw IMU and speed labels | Load and plot it on day one, before designing anything |
| Gyro-only heading holds 60 s within about 1° | Bench test plus one driving test |
| OSM covers Indian tunnels and flyovers adequately | Inspect a handful of known tunnels and underpasses |

### Week-one spikes

Roughly half a day each; each can invalidate a tool choice while replacement is still cheap.

1. Sensor rate and jitter probe on every team phone; check gyroscope presence and accelerometer range.
2. Screen-off survival: 30 minutes of recording with the display off on the most aggressive OEM phone
   available.
3. Convert a toy model with the intended final architecture, run it on device, measure latency and
   confirm the quantised output matches the desktop result. **This spike decides convolutional versus
   recurrent.**
4. Render an offline map region and draw a polyline and marker over it.
5. Run GraphHopper offline against a downloaded extract and evaluate whether its matching can be
   constrained the way we need. **This spike decides weeks of matcher work.**
6. Read live speed from an OBD-II dongle.
7. Allan variance on a long stationary recording, to derive real filter noise parameters.

### Decisions to make before writing code

Core implementation language (recommendation: Python for research, Kotlin for the shipped platform-free
core, so the edge engine is the same code; Rust only if a team member already knows it well) · native
Android versus cross-platform (native, for sensor timing) · model runtime · the canonical log schema ·
how ground truth for our own drives is obtained · module ownership per person.

### Open questions

- **Can we get OBD-II dongles for ground-truth collection?** Determines the quality ceiling of the speed
  model. Needs deciding before Phase 2 data collection.
- **Are two-wheelers in scope?** Named in the problem statement, and they materially change the alignment
  and non-holonomic constraint assumptions — a bike leans into turns, so roll couples into heading.

---

## 16. Decisions register

Architectural decisions taken, with status. Full reasoning for each is in the wiki under
`wiki/decisions/`.

### Decision 1 — sample at the highest available rate, emit at 10 Hz
**Status: proposed. Diverges from the team repo's current 10 Hz pipeline.**

The problem statement's 10 Hz is a position *output* rate. Sampling rate and output rate are independent.
The team's 10 Hz came from IO-VNBD's smartphone data being 10 Hz, and that dataset constraint was then
read as a requirement.

At 10 Hz the Nyquist limit is 5 Hz, and the vibration that correlates with speed lives above it. The two
available signal channels fail in different places: **kinematics tell you nothing during constant-speed
cruising**, which is exactly the case that breaks the 1 km benchmark, and vibration works precisely there.
Naive decimation additionally aliases, corrupting the kinematics that were being kept. Pothole rejection
becomes near-impossible when a shock is one or two samples. Heading integration degrades — 6° of turn
between samples at 60°/s, against 0.3° at 200 Hz.

The team's own numbers support this reading: roughly 19% speed error and a median 18.3% distance drift is
what a kinematics-only model looks like. The bottleneck is missing information, not model capacity.

Sampling high is nearly free. Exploiting it needs our own high-rate training data, which has weeks of lead
time — which is why the decision is needed now. Run both tracks: ship the 10 Hz model, collect high-rate
data in parallel, add vibration features and fine-tune when the data exists.

### Decision 2 — raw GNSS provider, never the fused provider
**Status: proposed. Corrects current code in the team repo.**

The fused provider blends GPS, Wi-Fi, cell and the phone's own inertial sensors, then smooths. Four
consequences: in a tunnel it may emit its own dead reckoning, so we would correct against Google's dead
reckoning and our drift numbers would stop meaning anything; its smoothed output is correlated rather than
white, so the Kalman filter treats correlated evidence as fresh, becomes overconfident and can diverge
silently; a Wi-Fi fix and a clean satellite fix look identical, leaving the quality gate nothing to gate
on; and if the icon keeps moving in a tunnel because Play Services is doing the work, we have learned
nothing and cannot answer the obvious judge question. Cost to fix: one class.

### Decision 3 — speed model form: increment, variance, convolutional
**Status: proposed; partly agreed with the team already.**

*Emit an uncertainty alongside the estimate.* A variance head costs little and lets the filter weight each
estimate by the model's own confidence, distrusting it over potholes automatically. It is also the
clearest answer to whether the AI is doing real work or decorating a Kalman filter.

*Predict a displacement increment rather than absolute speed.* Already on the team's next-steps list, and
their numbers argue for it — per-sample speed error can look acceptable while integrated bias destroys the
drift KPI. Optimise and report **mean signed error**, not only RMSE.

*Convolutional rather than recurrent.* Agreed with the team; their stub already confirms a convolutional
path converts to builtin ops. Beyond export convenience, recurrent hidden state must be reset on mode
changes and gaps, and recurrent quantisation commonly loses accuracy as bias.

**Open:** whether to move from rotation-invariant magnitude features to signed forward acceleration.
Magnitudes make braking and accelerating identical to the model, discarding information that directly
predicts speed change. Since yaw alignment is needed for heading anyway, this is worth an experiment once
alignment exists — an experiment, not a rewrite. The current mount-agnostic-by-construction property is
genuinely valuable.

### Decision 4 — map stack: rendering and matching are separate choices
**Status: proposed, pending two half-day spikes.**

Rendering contributes nothing to positional accuracy, so take the lowest-risk renderer — Mapsforge, with
prebuilt offline regional files and no tile server. For matching, build the graph offline with pyosmium and
write a forward-only HMM that emits covariance rather than a hard snap, because off-the-shelf matchers are
batch post-processors of complete traces and none drops in cleanly. MapLibre is a later upgrade; Google
Maps SDK is rejected as network-dependent; Mappls stays off the critical path pending licensing checks.
Spike GraphHopper and Mapsforge rendering before committing.

### Decision 5 — the navigation core is platform-free from day one
**Status: settled by the problem statement.**

The statement requires both a phone app and a standalone engine accepting external IMU data at around
200 Hz. A build check enforces that nothing below the `:engine` line imports platform APIs, and rates are
configuration rather than constants. Followed, the edge engine is packaging; not followed, it is a rewrite.

---

## 17. Requirements coverage and gap closures

Full detail, including labelling strategies and stated limitations, is in
`wiki/notes/idr-requirements-coverage.md`. Summarised here.

**Nothing is built except the team's baseline speed model.** What follows is design coverage, not
delivered capability. Each closure states the evidence it depends on; preconditions that cannot be
confirmed from the desk are listed at the end rather than assumed.

### Gap 1 — non-navigation motion filtering

Four mechanisms for four distinct problems. **Shock detection is deterministic:** band-pass the vertical
linear acceleration and declare a shock on a large excursion whose *duration* is short — the duration
constraint is what separates a pothole from genuine braking. This requires enough samples to see the
transient's shape, so it is conditional on decision 1; at 10 Hz a pothole is one or two samples and is not
separable by any method.

**Detected shocks never cause sample deletion** — a discarded sample is a hole in an integral. Instead
inflate that window's measurement noise and suspend ZUPT and NHC while the shock is active.

**Idling and stationary detection** from accelerometer and gyroscope variance drives ZUPT, with thresholds
fitted against recorded idling and slow-crawl data rather than guessed — a false ZUPT during a crawl
produces systematic distance loss.

**A motion context classifier** is the learned part. Labels are derived weakly from GNSS speed and its
derivative, gyro yaw rate, and the deterministic shock detector. The classifier learns to reproduce those
labels from IMU alone, which is what makes it useful when GNSS is gone — but it is weak supervision and
inherits any bias in the labelling rules, which should be stated rather than presented as ground truth.

**Mount shift detection** tracks gravity direction in the estimated vehicle frame; a step change with no
corresponding manoeuvre in gyro and horizontal acceleration means the phone moved. Re-align, inflate
attitude covariance, mark heading unreliable until re-converged. A shift occurring exactly during a
manoeuvre is not separable — the consequence is a missed detection, with innovation monitoring as the
second line of defence.

### Gap 2 — making the fusion genuinely AI-based

Three learned components sit inside the fusion loop.

**Variance head** on the speed model, trained by Gaussian negative log-likelihood, needing no labels
beyond those the speed model already uses; its output becomes the measurement noise.

**Learned GNSS quality model** taking C/N₀ distribution, satellite count, constellation mix, dilution of
precision and recent innovation history, and predicting that fix's position-error scale. **This cannot be
trained on IO-VNBD**, whose ground truth is itself GPS-derived — learning GNSS error needs a reference
better than the GNSS being evaluated. A dataset such as the Google Smartphone Decimeter Challenge data is
the candidate, and its ground-truth quality and licence must be verified before planning around it. If
that fails, fall back to the deterministic gate already in the design and drop the claim rather than
fabricate it.

**Process-noise adaptation** selecting a fitted noise profile from the motion context class.

**Rejected: a learned residual on the filter output.** Easy to add, easy to demo, and it places an
unconstrained learned term outside the covariance accounting — masking divergence and making the
uncertainty estimate a lie.

Describe the result as **AI-augmented Bayesian fusion**: learned models supply the measurement and both
noise models; the estimator stays a principled recursive filter, which is what keeps the uncertainty
meaningful and the edge deployment tractable. Argue it as a deliberate choice, not a limitation.

### Gap 3 — AI-based IMU noise and bias correction

Split the problem. **The stochastic model is not learned:** Allan variance on a long stationary recording
gives angle random walk, bias instability and rate random walk for that specific device, which become the
filter's process-noise parameters. Measured per device, far better than guessed, and cheap.

**The deterministic residual is learned:** a small dilated convolutional network correcting gyroscope
scale factor, axis misalignment, g-sensitivity and temperature-dependent bias — structure a random-walk
bias state cannot represent. Learned gyroscope denoising with dilated convolutions is established in the
inertial navigation literature; read the source work rather than reconstructing it from this summary.

Training needs an attitude reference. **Whether IO-VNBD provides one is unverified** — check before
committing. The workable fallback is yaw only, using GNSS course-over-ground during good-GNSS driving as a
weak reference, which is acceptable because yaw dominates our error budget.

Sequence the Allan variance work first: a correctly parameterised filter may already estimate bias well
enough to make the learned correction unnecessary, and that is worth knowing before building it.

### Gap 4 — the magnetometer

Named as a live input in the statement, and close to useless inside a steel body. Both are true, so
**ingest it, use it narrowly, and prove the de-weighting with measurements.**

Calibrate hard and soft iron by fitting an ellipsoid to field measurements over a drive. Detect
disturbance deterministically by comparing measured field magnitude and the dip angle against an offline
geomagnetic reference model — a field of the wrong strength or wrong inclination is disturbed and
rejected. Use it only for initial yaw when stationary with no GNSS, and as a heavily de-weighted heading
prior on long straights when the test passes.

Present recorded in-vehicle disturbance statistics alongside the resulting weighting. That converts
"we ignored a listed input" into "we ingested it, measured how often it is disturbed, and weighted it
accordingly".

### Gap 5 — multi-level parking

**Floor detection** from *relative* pressure change over minutes, never absolute altitude, which drifts
with weather. Corroborated by integrated yaw during ramp transit, since parking ramps produce a
characteristic sustained turn rate; the two together are far more robust than either alone. Verify the
barometer resolution on the actual test phones and measure the real inter-floor height rather than
assuming a standard value.

**Horizontally**, no road network exists: the matcher returns no candidates, the filter continues on
inertial data with NHC and ZUPT, and uncertainty grows visibly. This is the *same code path* as driving
off the edge of a downloaded map region, which is a good reason to build and test it early.

The claim is continuous position with honestly growing uncertainty plus correct floor identification —
not lane-level accuracy.

### Gap 6 — the screening deliverable

The statement requires position plots from an IO-VNBD subset; metric tables do not satisfy it. Four plots:
**trajectory overlay** per held-out drive with outage segments shaded; **drift versus outage duration** at
10, 30, 60 and 180 seconds against both baselines; **along-track and cross-track decomposition** over an
outage; and a **cumulative distribution of drift percentage** across all segments with the 10% target
drawn in. The first is what a reviewer looks at; the second and fourth are what make it credible.

### Gap 7 — "lane-level" versus the 10% benchmark

These conflict — 10% of 1 km is 100 m. Decompose rather than choose: **cross-track** error is constrained
by map matching to well inside a lane width once a confident match exists, which is where the lane-level
claim is honest; **along-track** error is what the 10% budget covers and is dominated by speed-model bias,
which map matching barely touches in a straight tunnel. Report both separately everywhere so the claim is
evidenced.

### Gap 8 — jamming and interference

We claim **detection and safe degradation**, not anti-jamming. Four tests: simultaneous C/N₀ collapse
across all constellations, which distinguishes interference from the gradual partial loss of structural
blockage (automatic gain control readings strengthen this considerably where exposed — availability varies
by device and must be checked); innovation gating, which covers spoofing and severe multipath with the
same mechanism; kinematic plausibility against maximum feasible vehicle motion; and a constellation
cross-check between GPS-only and NavIC-inclusive solutions. On detection, drop to dead reckoning and say
so in the interface.

### Gap 9 — two-wheelers

A two-wheeler banks into turns, so measured specific force stays roughly aligned with the machine's own
vertical axis and the gravity-based tilt estimate reads the lean as a change in mounting orientation.
Uncorrected, every turn corrupts alignment.

**Correction:** in a steady coordinated turn the lean angle relates forward speed, yaw rate and gravity, so
with speed from the model and yaw rate from the gyroscope the expected lean can be predicted and separated
from the constant mounting rotation. **Stated plainly, that relation holds only in steady coordinated
turns** — turn entry and exit, counter-steering and mid-corner braking violate it, so the correction is
gated on approximately constant yaw rate and treated as unavailable otherwise.

Vibration characteristics also differ substantially from a car, so a car-trained speed model should not be
assumed to transfer; this needs separate data or a vehicle-type head.

**Decide:** commit, with the lean correction, vehicle-type detection and separate training data; or scope
two-wheelers out explicitly. Both defensible. Silently building for cars while the statement names
two-wheelers is not.

### Preconditions that must be verified, not assumed

1. Whether IO-VNBD provides any attitude or heading reference (Gap 3).
2. Ground-truth quality and licence of any dataset used to learn GNSS error (Gap 2).
3. Barometer resolution on the test phones and the real inter-floor height of the test structure (Gap 5).
4. Automatic gain control availability through the GNSS measurements API on target phones (Gap 8).
5. Achievable IMU sample rate per test phone, since Gaps 1 and 3 both depend on it (decision 1).
6. Whether an Allan-variance-parameterised filter already estimates bias well enough to make the learned
   gyroscope correction unnecessary (Gap 3).

Each is a short experiment. None should be assumed.

---

## 18. Team repo alignment

Review of `sih-26168-notes` (problem ID SIH26168, team of five) against this document, 2026-08-29.

### Converged independently

Positioning engine rather than a maps app, routing and search out of scope · the model is **only** the
speed estimator, everything downstream deterministic · Kotlin native for sensor timing · TFLite float32 on
CPU with XNNPACK · avoid `TYPE_LINEAR_ACCELERATION` · split by drive, never by window · replay harness,
ring buffer, resampler, inference off the sensor and UI threads · laptop-to-phone integration named as the
hidden killer, with a stub model unblocking the app early · blackout simulator toggle · map behind a seam
so the engine never talks to it · road graph treated separately from tile imagery.

### Adopted from them

- **NavIC** (section 5) — absent from our planning entirely, and a genuine advantage for an ISRO problem.
- **Parity gates G0–G7** — a better structure than a single self-test vector.
- **Their competitive framing** against Google Maps, which answers a likely judge question.
- **Rotation-invariant features** making the speed model mount-agnostic by construction — we had not
  proposed this.
- **Honest reporting of the drift KPI** rather than hiding behind R², which is the discipline this document
  argues for throughout.

### Divergences

| # | Their position | This document | Impact |
|---|---|---|---|
| 1 | Train and run at 10 Hz | Sample high, emit 10 Hz | Largest single lever |
| 2 | Parity fixtures already at model rate | Test parity from raw high rate (G2a) | Silent aliasing bug |
| 3 | Speed only, no uncertainty | Speed plus variance | Makes fusion genuinely model-driven |
| 4 | Absolute speed | Displacement increment | Along-track drift comes from integrated bias |
| 5 | `FusedLocationProviderClient` | Raw GNSS provider | Straight bug — circular fusion |
| 6 | GRU currently, TCN proposed | TCN | Agreed |
| 7 | osmdroid on public tile servers | Mapsforge | Tile usage policy, cold-start blank map |
| 8 | Rotation-invariant magnitudes | Signed forward acceleration once aligned | May be why error plateaus at 19% |

Items 1, 2, 3 and 5 change outcomes. Item 5 is a bug. Items 6, 7 and 8 are refinements.

### Present here, absent there

**ZUPT** — appears nowhere in their repo, and in stop-and-go traffic it is the cheapest large win
available, since it also re-observes gyro bias · **GNSS quality gating and a degraded mode** — their
blackout check triggers on zero satellites or a three-second fix age, which multipath passes ·
**asset delivery** — model bundled and swapped by replacing the file, with no manifest, version pinning,
checksum or sideload path · **platform caveats** — saturation, thermal, OEM background killing,
uncalibrated sensors, pooled event objects · **along-track versus cross-track split** in the drift metric ·
and their **Part C is not started** — dead reckoning, NHC, fusion and handover, which is the critical path.

### Their current status

A trained baseline speed model, roughly 19% speed error with R² 0.83 on a 33-drive split, and a first
drift evaluation giving median 18.3% and mean 19.8% over 351 simulated 1 km blackouts with only 19% of
segments under the 10% target. Roughly 2× off the KPI on distance alone, before heading and fusion error
are added. A stub model and preprocessing reference are delivered; export, test fixtures and the fusion
layer are outstanding.

---

## 19. Glossary

**Sensors.** *GNSS* — the general name for satellite positioning; GPS, Galileo and NavIC are all GNSS.
*IMU* — the motion-sensing chip containing an accelerometer and gyroscope. *Accelerometer* — measures
acceleration including gravity. *Gyroscope* — measures rate of rotation; direction comes from adding turn
rate over time. *Magnetometer* — a compass, nearly useless inside a car. *Barometer* — air pressure,
useful for detecting parking floors. *MEMS* — the cheap, noisy, mass-produced sensors in phones. *FOG* —
an expensive, very accurate gyroscope used in aircraft. *OBD-II* — the car diagnostic port that reports
true wheel speed.

**Positioning.** *Dead reckoning* — working out position from a starting point plus speed and direction
over time; the sailor's method, where errors accumulate. *INS* — a system doing dead reckoning with an
IMU. *Drift* — the accumulated error. *Strapdown* — INS mathematics for sensors rigidly fixed to the
vehicle. *Odometry* — measuring distance travelled. *Along-track versus cross-track error* — too far
forward or back along the road, versus on the wrong side or the wrong road. *Attitude* — orientation in
3D: pitch, roll, yaw. *Reference frame* — which set of axes a measurement is expressed in; mixing them up
is the most common source of bugs in this work.

**Filtering and fusion.** *Sensor fusion* — combining imperfect sensors into a better estimate. *Kalman
filter* — keeps a running best guess plus a confidence, predicts forward using physics, corrects when a
measurement arrives. *EKF* — the non-linear version. *Error-state EKF* — tracks the error in the estimate
rather than the estimate itself; more numerically stable for orientation. *Covariance* — the filter's own
measure of uncertainty, drawn as the growing ellipse. *Innovation* — the gap between prediction and
measurement; a useful alarm. *ZUPT* — telling the filter velocity is exactly zero when stationary, which
also reveals gyroscope drift. *NHC* — the assumption that a car cannot slide sideways or fly upwards, fed
in as a measurement.

**Maps.** *OSM* — the free, downloadable world map database. *Map matching* — snapping a noisy position
onto the road network. *HMM* — the mathematics for finding the most likely *sequence* of roads rather
than snapping each point independently, which stops flickering between parallel streets. *MBTiles* and
*PMTiles* — single-file formats for offline map data. *Mapsforge, osmdroid, MapLibre* — Android map
rendering libraries.

**Machine learning and deployment.** *Training* — learning parameters from data; slow, done once on a
desktop. *Inference* — running the trained model on new input; fast, done on the phone. *Model file* —
the frozen result of training, a few megabytes, shipped inside the app. *TFLite / ONNX Runtime* — small
libraries that execute a model file on a phone. *CNN / TCN / GRU* — network types suited to reading a
short stretch of sensor readings. *Heteroscedastic output* — a model that outputs both an answer and its
confidence; the confidence is what the Kalman filter needs. *Bias versus RMSE* — RMSE is average error
size, bias is whether errors lean consistently one way; bias is far more dangerous here because
consistent errors accumulate while random ones partly cancel. *Quantisation* — shrinking a model by
storing its numbers with less precision.

**Signal quality.** *C/N₀* — satellite signal strength relative to noise. *HDOP* — how well spread the
visible satellites are. *Multipath* — signals bouncing off buildings before reaching the phone, giving a
wrong position that still looks confident.

---

*End of document. The Android application architecture is in the companion file `SIH-IDR-android.md`.
Living versions of each section are maintained as individual notes in the wiki under `wiki/notes/` and
`wiki/decisions/`, indexed from `wiki/projects/sih.md`.*
