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
| `:core-nav` | Dead reckoning, mode arbiter, heading/fusion/NHC **placeholders** (no owner yet — see below) | ✅ built, tested |
| `:core-map` | `MapMatcher` interface + no-op (no owner yet — see below) | ✅ built |
| `:core-replay` | Canonical trace format + replay harness (no device needed to test the engine) | ✅ built |
| `:engine` | Ring buffer, conditioning, anti-alias decimator, feature extractor, normalizer, `StubEngine`, `RealEngine` | ✅ built, **28 tests passing** |
| `:android-sensors` | `SensorSource` — accel/gravity/gyro at native rate | ✅ written, **not compiled** (no Android SDK in the environment this was built in) |
| `:android-assets` | Packaged-asset resolution off `context.assets` | ✅ written, **not compiled** |
| `:android-model` | `TfliteSpeedEstimator` — the TFLite runtime wrapper | ✅ written, **not compiled** |
| `:app` | `MainActivity`, `EngineService`, `GnssSource`, map screen, demo controls | ✅ written, **not compiled** |

**"Not compiled" means exactly that** — this was built in an environment with Gradle and
a JDK but no Android SDK, so the Android-specific modules could not be run through
`compileDebugKotlin`/`assembleDebug` here. Every pure-Kotlin module (everything below the
`android.*` import-ban line) **was** actually compiled and test-run via `./gradlew`
during development, repeatedly, and is verified working. **First thing to do when this
opens in Android Studio: `./gradlew assembleDebug` and fix whatever the real compiler
finds** — treat the Android layer as reviewed-but-unverified, not as tested code.

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
