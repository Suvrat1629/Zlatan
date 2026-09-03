# sih-26168-app — Intelligent Dead Reckoning

Read **`plan.md`** first — it's the build spec this codebase follows, and `docs/` holds
the full source-of-truth design docs it's built against.

## Status (2026-08-29)

Phase 0 of `plan.md` / `docs/architecture-android.md` §15 is implemented:

| Module | What it is | Status |
| --- | --- | --- |
| `:core-types` | `PositioningEngine` seam, `Mode`, `PositionState`, `EngineConfig`, geo helpers | ✅ built, tested |
| `:core-assets` | Versioned-asset manifest + resolver + checksum verification | ✅ built, tested |
| `:core-model` | `SpeedEstimator` interface, self-test runner, constant-speed stub | ✅ built, tested |
| `:core-nav` | Dead reckoning, mode arbiter, mount-agnostic yaw, forward-axis estimation, error-state EKF | ✅ built, tested |
| `:core-map` | `MapMatcher` interface + no-op (no owner yet — see below) | ✅ built |
| `:core-replay` | Canonical trace format + replay harness (no device needed to test the engine) | ✅ built |
| `:engine` | Ring buffer, conditioning, anti-alias decimator, feature extractor, normalizer, `StubEngine`, `RealEngine` | ✅ built, **28 tests passing** |
| `:android-sensors` | `SensorSource` — accel/gravity/gyro at native rate | ✅ built, field-verified (453 Hz measured) |
| `:android-assets` | Packaged-asset resolution off `context.assets` | ✅ built, manifest shape + self-test enforced on load |
| `:android-model` | `TfliteSpeedEstimator` — the TFLite runtime wrapper | ✅ built, field-verified (inference p95 2.9 ms) |
| `:app` | `MainActivity`, `EngineService`, `GnssSource`, map screen, demo controls | ✅ built, ridden in the field |

**Superseded, 2026-09-01.** This section used to say the Android modules were written but never
compiled, because the environment they were first built in had no Android SDK. That has not been
true for some time: `./gradlew assembleDebug` succeeds, and the app has been ridden in the field for
several days producing the telemetry that drives current development. A 2026-09-01 audit of the
problem-statement deliverables read this paragraph and reported the Android layer as unverified — a
stale caveat is as misleading as a wrong claim, so it is corrected rather than deleted.

## What's missing on purpose

- **`app/src/main/assets/engine.tflite` is not included** — no network access to the
  `sih-26168-model` repo from the build environment. See
  `app/src/main/assets/README.md` for exactly what to drop in and where. Until then,
  `EngineFactory` falls back to `StubEngine` automatically — the app still runs.
- **Heading estimation, the real GNSS+INS fusion filter, and map-matching have no
  design owner yet** (`docs/model-questions-part-2.md` §4) — `:core-nav` and `:core-map`
  ship honest placeholders (`GyroIntegrationHeadingEstimator`, `PassthroughFusionFilter`,
  `NoOpMapMatcher`), clearly marked, not real Part C math. Swapping in the real
  implementations once M2/M3 design them should not require touching `:engine`.
- **Fork F4 (map library) is unresolved** — `OsmdroidMapRenderer` is wired as the
  pragmatic default behind a `MapRenderer` interface; see `plan.md` "Superseded
  decisions" for why Mapsforge is the argued-for alternative.

## JDK requirement

Build on **JDK 17** (AGP 8.6.1's supported range). In Android Studio: **Settings →
Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** — pick a bundled 17/21
JDK (Studio ships its own; you usually don't need to install anything separately) or a
system JDK 17. A very new JDK (25 at the time of writing) makes the Kotlin compiler used
by AGP 8.6.1 / Kotlin 2.0.21 crash outright (`JavaVersion.parse` chokes on its version
string) — this is a real, sharp failure, not a warning, so don't skip this if `Gradle
sync` fails with something that looks unrelated.

## Building

```
./gradlew :core-types:test :core-assets:test :core-nav:test :core-map:test \
          :core-model:test :core-replay:test :engine:test    # pure-Kotlin — no SDK needed
./gradlew assembleDebug                                       # needs an Android SDK (Android Studio sets this up)
```
