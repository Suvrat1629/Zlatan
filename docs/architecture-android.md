# SIH — Intelligent Dead Reckoning
## Android application architecture

**Companion document.** System-wide architecture, requirements, algorithms, maps, tooling and planning
are in `SIH-IDR-architecture.md`. This document covers only the Android application and the on-device
concerns. Living versions of both are in the wiki at `wiki/`.

**Compiled:** 2026-08-29

---

## Contents

1. Scope of the app layer
2. Module structure
3. Threading
4. Sensor acquisition
5. GNSS and NavIC acquisition
6. Storage layout
7. Asset resolution and updates
8. Application lifecycle
9. User interface
10. Configuration
11. Parity gates and testing
12. Android caveats
13. Model integration caveats
14. UX principles
15. App-layer build order

---

## 1. Scope of the app layer

The Android application is plumbing and presentation. It holds **no navigation mathematics**.

- **The app is the body** — reads sensors, keeps the phone awake, draws the map, handles buttons.
- **The navigation core is the brain** — takes sensor readings in, produces position out.

The app calls the core and displays what it returns. The core does not know it is running on a phone.

Three reasons this separation is non-negotiable:

1. The problem statement requires **two products** — a phone app and a standalone engine that runs on
   external IMU data at around 200 Hz. If the mathematics is tangled into Android code, it gets built
   twice.
2. **Testability.** Recorded data in, trajectory out, a hundred runs a minute on a laptop. With the
   mathematics inside the app, every test needs a phone and a drive.
3. **Diagnosability.** Wrong position means the core. Frozen screen means the app.

---

## 2. Module structure

Gradle modules, dependencies pointing downward only. Everything below the line is pure Kotlin with no
Android dependency and runs unchanged on a desktop JVM — that is the edge engine.

```
:app                  UI, foreground service, map view, settings, permissions
:android-sensors      SensorManager, GNSS, timestamp normalisation
:android-assets       file system, downloader, document-picker import
:android-model        TFLite runtime implementation
─────────────────────────── no Android below this line ───────────────────────────
:engine               orchestrator; wires sources, model, filter, matcher; owns the loop
:core-nav             alignment, attitude, error-state EKF, measurement providers, mode arbiter
:core-map             map provider interface, routing graph reader, HMM matcher
:core-model           speed estimator interface, manifest validation, baseline estimators
:core-assets          asset resolution, manifest parsing, versioning, checksums
:core-replay          canonical log format, writer and reader
:core-types           data types, reference frames, config schema, all interfaces
```

`:edge-cli` depends on the same lower half plus an external IMU adapter. No code is duplicated between
the phone and the edge engine.

Two rules keep this honest:

- `:app` depends on `:engine`; `:engine` never depends on `:app`.
- A build check fails the build if anything below the line imports `android.*`. Enforce it mechanically,
  not by convention — one stray import silently kills the edge-engine deliverable.

### The seam

The app codes against a `PositioningEngine`-shaped interface with at least two implementations from the
first commit: the real engine, and a stub. This is what allows the entire app pipeline — sensors,
threading, UI, model loading — to be built and tested before the real model or filter exists. The team
repo's stub-first approach is exactly right and should be preserved.

---

## 3. Threading

Five threads with strict responsibilities. The filter must never block.

- **Sensor thread** — a dedicated handler thread receiving sensor callbacks. It timestamps and writes
  into a pre-allocated ring buffer. No allocation, no logging, no mathematics.
- **Engine thread** — a single dedicated thread draining the ring buffer, propagating the filter and
  emitting a pose at 10 Hz. All navigation state lives here and is touched by nothing else.
- **Inference thread** — runs the model on a copied window. Results return as **delayed measurements
  carrying the timestamp of the window they describe**, so inference jitter on a slow phone causes no
  position lag.
- **Map-matching thread** — roughly 1 Hz on a snapshot of recent poses. Slow, and allowed to be.
- **UI thread** — collects pose updates and renders. Never touches filter state.

The ring buffer is sized generously and **IMU samples are never dropped** — a missing sample is a gap in
an integral. If the engine thread cannot keep up, that is a defect to log loudly, not to paper over with
a drop policy.

Policy for inference not completing before the next tick: reuse the previous output and mark it stale, so
the filter can de-weight it.

---

## 4. Sensor acquisition

### Rate

**Sample at the highest rate the device reliably delivers.** The problem statement's 10 Hz is the
*position output* rate, not a sampling requirement — the two are independent.

Decimation from the sampling rate to whatever rate the model expects is an **explicit, tested pipeline
stage**, not an incidental one. It must include anti-alias low-pass filtering: high-frequency energy
folded into the retained band corrupts the low-frequency data that is being kept, not just the
high-frequency data being discarded.

### Additional sensors

The magnetometer and barometer are both ingested, both narrowly used.

**Magnetometer.** Named as a live input in the problem statement, and close to useless inside a steel
vehicle body — so ingest it and weight it honestly rather than dropping it. Calibrate hard and soft iron by
fitting an ellipsoid to field measurements over a drive. Detect disturbance by comparing measured field
magnitude and the dip angle between field and gravity against an offline geomagnetic reference model; a
field of the wrong strength or inclination is disturbed and rejected. Use it only for initial yaw when
stationary with no GNSS, and as a heavily de-weighted heading prior on long straights when the test
passes.

**Barometer.** Floor detection in multi-level parking, using *relative* pressure change over minutes only —
absolute altitude drifts with weather and is unusable. Corroborate with integrated yaw during ramp transit.
Verify the barometer's resolution on the actual test phones and measure the real inter-floor height rather
than assuming a standard value.

### Sensor selection

- Prefer `TYPE_ACCELEROMETER_UNCALIBRATED` and `TYPE_GYROSCOPE_UNCALIBRATED`. They expose the raw reading
  and the vendor's bias estimate separately, so we do our own bias estimation instead of inheriting an
  opaque vendor correction.
- **Do not use `TYPE_LINEAR_ACCELERATION`.** It is vendor-fused and inconsistent across phones. Take
  `TYPE_ACCELEROMETER` and `TYPE_GRAVITY` and subtract, which is also what the training pipeline does.
- Register with an **explicit handler** bound to the sensor thread. Callbacks otherwise arrive on the main
  thread and compete with UI rendering.
- Set `maxReportLatencyUs` to zero. Batching saves power but delivers samples in delayed bursts.

### Timestamps

- `SensorEvent.timestamp` is nanoseconds since boot on most devices, with vendor deviations. Validate
  against `SystemClock.elapsedRealtimeNanos()` at startup and record the offset.
- **Copy sensor values immediately.** The values array inside a sensor event is pooled and overwritten by
  the next event. Holding a reference produces corruption that looks exactly like sensor noise and is
  extremely hard to diagnose.
- Never assume monotonic timestamps — handle duplicates and out-of-order delivery in the conditioning
  stage.

### Conditioning stage

Resample to a fixed grid, normalise timestamps, detect gaps, reject outliers, detect and flag
accelerometer clipping.

---

## 5. GNSS and NavIC acquisition

### Use the raw provider

Take fixes from `LocationManager` with the raw GPS provider. **Do not use
`FusedLocationProviderClient`.** It blends GPS, Wi-Fi, cell towers and the phone's own inertial sensors,
then smooths the result. Four consequences:

1. **Circular fusion.** In a tunnel it may keep emitting positions derived from its own dead reckoning.
   Our engine would then correct against Google's dead reckoning, and the drift numbers — the entire
   competition metric — stop meaning anything.
2. **It breaks the Kalman filter's assumptions.** The filter assumes independent, white measurement noise.
   Smoothed output is correlated between fixes, so the filter treats correlated evidence as fresh, becomes
   overconfident, shrinks its covariance too far, and can diverge silently while reporting high confidence.
3. **Opacity.** A Wi-Fi-derived fix and a clean satellite fix arrive looking identical. There is nothing
   for the quality gate to gate on.
4. **Demo risk.** If the position keeps moving in a tunnel because Play Services is doing the work, we have
   learned nothing, and "what happens with Play Services disabled" is an awkward question.

### Status callback

Register a `GnssStatus` callback for per-satellite constellation type, C/N₀ and satellite count, and the
GNSS measurements callback where available. A fix alone never reveals that it is a multipath fix.

This callback serves three purposes at once:

- **NavIC contribution** — count satellites where the constellation type is IRNSS and `usedInFix` is true.
  Drives the mode badge and is a genuine pitch point.
- **Quality gating** — the C/N₀ distribution and satellite count feed the gate that decides between
  GNSS-aided, degraded and dead-reckoning modes.
- **Doppler speed labels** — the fix's speed field, with its accuracy estimate, is the label used for
  online recalibration of the speed model.

### Interference detection

The statement mentions jamming. The claim is **detection and safe degradation**, not anti-jamming. Four
tests, all cheap:

- **Simultaneous C/N₀ collapse across all constellations** — distinguishes interference from the gradual,
  partial loss caused by structural blockage. Automatic gain control readings from the GNSS measurements
  API strengthen this considerably, but AGC exposure varies by device and must be checked on the target
  phones rather than assumed.
- **Innovation gating** — a fix inconsistent with inertial motion beyond the filter's gate is rejected.
  Covers spoofing and severe multipath with one mechanism.
- **Kinematic plausibility** — a position step larger than the vehicle could physically have travelled.
- **Constellation cross-check** — GPS-only and NavIC-inclusive solutions disagreeing beyond their stated
  accuracies.

On detection, drop to dead reckoning and say so in the interface.

### Mode determination

Do **not** decide the mode on "no fix" alone. By the time fixes stop, seconds of degraded multipath data
may already have entered the filter, and bad fixes are worse than no fix. Gate on C/N₀ distribution,
satellite count, fix geometry and filter innovation, producing three modes:

- **GNSS-aided INS** — full measurement set.
- **Degraded** — fixes arriving but failing quality or innovation gating. Dropped or heavily de-weighted.
- **Dead reckoning** — no trusted GNSS.

On re-acquisition, slew the position over one to two seconds with covariance driving the blend. Never snap.

### NavIC, briefly

NavIC's geostationary and inclined geosynchronous satellites stay high over India, so they slip between
buildings when low GPS satellites are blocked. This shortens the GNSS-denied stretch in urban canyons and
therefore reduces accumulated drift, and it gives faster reacquisition on tunnel exit.

Honest limits worth knowing before anyone pitches: roughly seven satellites versus GPS's thirty-one, so use
it *with* GPS rather than instead; **in tunnels and basements NavIC is blocked exactly like GPS** — sensors
handle tunnels, no satellite system survives there; S-band is not on all chipsets, so verify the demo
phone.

---

## 6. Storage layout

App-private storage, with a pointer file per asset kind rather than fixed paths:

```
files/
  assets/
    models/speed/<version>/{model.tflite, manifest.json}
    models/context/<version>/...
    maps/<region>/<version>/{graph.bin, map file, manifest.json}
    config/<version>/config.json
    current.json          <- which version of each asset is active, plus previous for rollback
  imported/               <- sideloaded bundles, highest precedence
  traces/                 <- recorded drives in the canonical log format
```

Recorded traces go to app-specific external storage so they can be pulled off the device without root.

High-rate logging needs buffered writes, never a flush per sample, and file rotation. A logger that
stutters corrupts the very data it is collecting.

---

## 7. Asset resolution and updates

A model, a map region and a configuration file are **versioned data, not code**. Bundling them as fixed
resources means every correction needs an app-store release. Build the indirection from the first commit —
retrofitting it later touches every module that loads a file.

A single `AssetProvider` answers "give me the active speed model" and resolves in order:

1. **Imported** — a bundle the user picked with the system document picker, verified and unpacked.
   Highest precedence, needs no network, allows a model swap in minutes at any time.
2. **Downloaded** — fetched earlier over Wi-Fi while parked and charging.
3. **Packaged** — shipped inside the app. Guarantees a fresh install works offline immediately.

The provider returns a handle carrying the file path and the parsed manifest. **Nothing else in the app
opens a model or map by literal path.**

### The manifest

Carries and asserts at load: asset id and version, SHA-256 checksum, asset type, input and output tensor
shapes and dtypes, channel order, units and conventions, gravity handling, window length and stride,
expected sample rate, normalisation constants, a self-test vector, minimum engine version, and for maps
the region and validity dates.

**The engine refuses to start on a mismatch**, loudly, rather than producing silently wrong positions. The
self-test vector — a canned input with its expected output, checked once at startup — catches conversion
errors, quantisation drift, normalisation mismatch and wrong-model-loaded in one cheap check.

### Update policy

Checked and downloaded only when parked, charging and on Wi-Fi. Never mid-drive. Download to a temporary
location, verify the checksum, swap atomically, keep the previous version so rollback is one pointer
change. The swap takes effect at the next session start — changing the model under a running filter would
produce a position discontinuity.

**Automatic rollback:** whenever GNSS is trusted the system has ground truth. If a newly installed model
performs measurably worse than its predecessor over a session, revert and report.

### Calibration, the update that needs no network

Sensor biases, scale factors and the phone-to-vehicle mounting rotation are learned during normal driving
and stored locally, versioned separately, and discarded when the mounting changes. This is what makes
accuracy improve over the first few drives with nothing downloaded.

### Maps

Same mechanism, larger files, packaged per region. The routing graph and the render map are versioned
together so they can never disagree about which roads exist. Bundling city-scale maps inside the app is
not viable given app bundle size limits — maps are downloaded or delivered as asset packs.

---

## 8. Application lifecycle

- A **foreground service** owns the engine, with a location service type, a persistent notification showing
  current mode and distance, and a partial wake lock. Navigation must survive the screen going off and the
  app being backgrounded.
- The service is the **single owner of engine lifetime**. Activities bind and observe; they never create
  engine state, so rotating the screen or killing the UI cannot disturb navigation.
- Pose updates are exposed as a flow the UI collects lifecycle-aware.
- State is **checkpointed periodically** so a process death mid-drive resumes degraded rather than cold.
- Battery optimisation exemption is requested explicitly, with an explanation, because Doze will otherwise
  throttle sensor delivery. On OEM skins with aggressive background management, per-OEM instructions may be
  needed as well.
- Interruptions — incoming calls, audio focus changes, app switching — must not stop recording or
  navigation.

---

## 9. User interface

### Main view

- **Map** with the vehicle icon, an uncertainty ellipse sized from the filter covariance, and a breadcrumb
  trail drawn in two colours for GNSS-aided and dead-reckoned segments. The trail is the single most
  persuasive thing a judge can look at.
- **Mode badge** — GNSS-aided, NavIC-contributing, degraded, or dead reckoning — with time and distance
  spent in the current mode.
- **Readouts** — estimated speed, distance travelled since the outage began, current uncertainty.
- **Satellite bar** — count, NavIC flag, signal strength.
- **GNSS-mute toggle** — simulates a blackout anywhere, clearly labelled as a simulation. Essential,
  because the finale venue may have perfectly good GNSS everywhere.
- **Record button** — writes a trace in the canonical log format. This is also the data-collection tool,
  so it ships first rather than last.

### Developer drawer

Real delivered sensor rates and jitter, filter innovation and NIS, model inference latency, alignment
confidence, active version of every asset, and drift percentage in demo mode. Invaluable during
integration and during questioning.

### Rendering

The engine emits at 10 Hz but the screen refreshes at 60 fps, so **the marker is interpolated between pose
updates in the UI layer.** Without this the icon visibly steps, which reads as a broken product even when
the positioning is correct.

Cap the breadcrumb layer's retained points and simplify its geometry, or rendering jank appears on long
drives.

### Map rendering choice

Rendering contributes nothing to positional accuracy, so take the lowest-risk option. Mapsforge is built
for this case — offline vector maps, prebuilt regional files, no tile server, no tile-usage-policy problem.
osmdroid is simpler still but sourcing raster tiles legally is a trap, since bulk-downloading from the
public OSM tile servers violates their usage policy, and its opportunistic caching leaves a blank map on a
cold start into a dead zone. MapLibre is the later upgrade. Google Maps SDK is rejected as
network-dependent.

Keep the renderer behind a thin map-view interface so switching costs about a day. The engine never talks
to the map directly — it emits a position, and the map draws it.

---

## 10. Configuration

A single versioned config file holds every threshold, noise parameter and window length. It ships
packaged, can be overridden by an imported bundle, and can be overridden again by a developer settings
screen writing a local override file.

**No numeric constant relevant to navigation appears in Kotlin source.**

---

## 11. Parity gates and testing

### Testing strategy

The `:engine` module and everything below it is tested **entirely on the JVM by replaying recorded
traces** — no device, no vehicle, seconds per run. Golden-trajectory tests assert that a known trace still
produces the known result. Synthetic motion tests validate the filter against exactly known answers.
On-device tests cover only the Android-specific layers: sensor rates, service lifetime, permission flow,
asset import.

This split is the practical payoff of platform-free lower modules — the parts most likely to be wrong are
the parts that need no phone to test.

### The parity gates

These prevent the failure that kills hackathon teams: the model works in Python, behaves differently on
the phone, and nobody can say which of the eight stages between raw sensor and displayed position
introduced the difference.

| Gate | Proves | Criterion |
|---|---|---|
| G0 | Exported file's shapes, dtypes and ops match what was agreed | manual check on receipt |
| G1 | Export did not change the model | Keras versus Python-TFLite within 1e-4 |
| G2 | The Kotlin preprocessing port is correct | Kotlin raw-to-features versus fixture within 1e-4 |
| **G2a** | **The decimation stage is correct** | **high-rate raw, decimated, versus the fixture's rate grid** |
| G3 | The normalisation and de-normalisation port is correct | within 1e-3 |
| G4 | The whole model path works on the device | on-device end-to-end versus Keras within 1e-3 |
| G5 | The dead-reckoning and fusion ports are correct | Kotlin trajectory versus Python within tolerance |
| G6 | The competition metric | under 10% drift, under 100 m per 1 km, on test blackout segments |
| G7 | Sustained real-time behaviour | 10 Hz for 10 minutes, engine tick p95 under 50 ms, no GC stutter |

**G2a matters and is easy to miss.** G2 compares against a fixture already at the model's sample rate, so
if the phone samples high and decimates, that stage is never tested. Aliasing introduced there passes every
other gate while still making the live input distribution differ from training.

### Fixtures the model side must supply

The exported model file, versioned and hashed · normalisation constants as data, not numbers pasted into
code · a test set with raw inputs, preprocessed inputs and expected outputs, deliberately including hard
cases (hard braking, turns, stationary periods, high speed) · a standalone preprocessing script that is the
authoritative specification for the Kotlin port · and a **stub model delivered before the real model**, so
the app pipeline can be built and gated immediately.

The gates prove correctness at build time; the manifest enforces it at load time. Both are needed — the
gates cannot catch someone shipping the wrong file later, and the manifest cannot catch a subtly wrong
preprocessing port.

---

## 12. Android caveats

1. **No gyroscope at all** on some budget phones. Detect at startup and refuse clearly.
2. **Requested sample rate is a hint, not a contract.** Measure the real delivered rate per device.
3. **Batching latency** unless maximum report latency is zero.
4. **Timestamp base varies** by vendor. Validate at startup.
5. **Accelerometer saturation.** The default range on some devices is around ±2 g and a pothole exceeds it.
   Clipped samples look like plausible readings. Request the widest range and detect clipping explicitly.
6. **Sensor event objects are reused.** Copy values immediately.
7. **Callbacks arrive on the main thread** unless a handler is supplied.
8. **Sensor delivery stops when the screen is off**, or an aggressive OEM battery manager kills the
   process. Severe on several OEM skins common in India.
9. **Display rotation and natural orientation.** Sensor axes are fixed to the device, not the display, but
   the standard remapping helpers make it easy to introduce a rotation that does not exist. Tablets and
   foldables have a different natural orientation. Keep an explicit phone-to-vehicle rotation and never mix
   it with display-derived remapping.
10. **CPU downclocking with the screen off** raises inference latency. Measure in that state.
11. **Thermal throttling is a first-class runtime condition**, not an edge case. Sustained GNSS, high-rate
    sensors, inference and a bright screen heat a phone quickly on a dashboard in the sun. Monitor thermal
    headroom and degrade deliberately — reduce model rate, simplify the map — rather than letting
    everything slow unpredictably.
12. **Foreground services can still be killed** on low-memory devices.
13. **Bluetooth contention** during data collection between the OBD dongle and other devices.
14. **Minimum SDK.** Raw GNSS measurements, foreground service types and notification permissions have
    different version floors. Choose deliberately and record what degrades below which version.
15. **Emulators have no meaningful sensors.** Development must be replay-driven, with real devices for the
    Android layers only.
16. **Document-picker URI permissions** not persisted across restarts; scoped storage limits on trace
    locations; checksum mismatch must fail loudly; disk full during a long recording.
17. **Device diversity.** All of the above varies. Three physically different phones minimum, including one
    budget device and one OEM known for aggressive background management.
18. **The magnetometer inside a car body is close to useless** — steel shell, speaker magnets, wiring,
    charger.
19. **Thermal drift of gyro bias.** Estimate bias online, never once at startup.

---

## 13. Model integration caveats

1. **Conversion failure.** Recurrent layers are a classic source of export breakage and quantisation
   trouble, and they carry hidden state that must be reset on mode changes and gaps. A convolutional model
   avoids both.
2. **Quantisation accuracy loss shows up as bias**, the error type we can least afford. Always evaluate the
   quantised model, never only the float one.
3. **Duplicated feature computation is the single most likely silent failure.** If features are computed in
   Python for training and re-implemented in Kotlin for inference, the two will drift. Preferred fix: push
   normalisation and feature computation **inside the model graph**, so only a raw sensor window crosses the
   boundary. Fallback: golden test vectors asserted on both sides in CI.
4. **Units and conventions must live in the manifest and be asserted** — m/s² versus g, radians versus
   degrees per second, gravity included or removed, channel order, window length and stride, sample rate.
5. **Windowing bookkeeping.** An off-by-one in stride or alignment between training and inference produces
   a small constant offset — precisely the systematic bias that destroys long-outage accuracy while looking
   harmless in aggregate metrics.
6. **Aliasing on decimation** — see G2a above.
7. **Float precision.** Train and evaluate in the precision the phone will use.
8. **Interpreter threading and warm-up.** The interpreter is not thread-safe; one per thread or serialise.
   The first inference is much slower, so warm up with dummy input at startup rather than during the first
   seconds of a drive.
9. **NaN propagation.** A single bad sample can poison filter state permanently. Validate model inputs and
   outputs and reject non-finite values loudly.
10. **Float32 CPU with XNNPACK for v1.** Hardware accelerator paths vary by chipset and can silently produce
    different numbers. The model is small enough that CPU is fine.

---

## 14. UX principles

The user is a driver, often on a two-wheeler, glancing for under a second at a hot phone in bright
sunlight.

- **Never freeze and never jump.** This is the whole product promise. A continuously moving icon with honest
  uncertainty beats an accurate icon that stalls and then teleports. Every correction is slewed.
- **Be honest without being alarming.** A quietly growing uncertainty ellipse communicates degradation
  without demanding attention. Never display a confident position we do not have; never raise warnings a
  driver cannot act on.
- **No jargon where the driver can see it.** Not "EKF", "dead reckoning", "NHC" or "drift". "GPS lost —
  still tracking you" is the whole message.
- **Zero setup ritual.** No calibration dance. Alignment happens automatically during normal driving. Any
  onboarding is one sentence, once, skippable.
- **Glanceability.** Large type, high contrast, few elements, automatic day and night themes.
- **Silence while driving.** No notifications, dialogs or permission prompts mid-drive.
- **Colours must survive sunlight and colour blindness.** Trail segments must differ in more than hue —
  use lightness or pattern as well, never red against green.
- **Degrade with an explanation the user can act on.** No gyroscope is a clear one-line message. A knocked
  phone shows "re-aligning", not an error.
- **Build trust after the drive.** A short post-trip summary — distance covered without GNSS, estimated
  accuracy, where the outages were — is the moment for detail, and an excellent thing to show an evaluator.
- **Battery honesty.** Show expected drain and offer a lower-power mode that reduces update rate rather
  than silently draining the phone.
- **Separate the evaluator experience from the driver experience.** Judges want two traces overlaid, live
  numbers and the mute toggle; drivers want none of it. Put the analytical view behind a deliberate switch.

---

## 15. App-layer build order

Phase 0, before any model or filter exists:

1. Project skeleton with the module structure and the `android.*` build check.
2. Permissions flow, foreground service, engine lifetime ownership.
3. Sensor layer: high-rate acquisition, ring buffer, conditioning, measured real rates per device.
4. GNSS layer: raw provider, `GnssStatus` callback, NavIC counting, quality inputs.
5. The `PositioningEngine` seam plus a stub implementation (hold last known GNSS speed — safe, and it does
   not diverge the way raw double integration would).
6. Trip recorder writing the canonical log format. **This unblocks data collection, which has weeks of lead
   time, so it ships early.**
7. Map view with a moving marker, mode badge, GNSS-mute toggle.
8. Replay harness, so everything above can be exercised without driving.
9. Load a placeholder model file of the agreed shape and run inference on-device, proving the interpreter
   pipeline before the real model exists.

Phase 1 onward: receive the real model, run G0–G4, port preprocessing, wire the real engine, port dead
reckoning and fusion, then G5 through G7.

The ordering principle: **the risky integration points get exercised first, with fake data, while there is
still time to fix them.** Leaving laptop-to-phone integration to the final week is the most common way
these projects fail.
