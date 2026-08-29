---
tags: [sih2026, integration, handoff, app-team]
ps_id: SIH26168
from: integrator (Tanmay)
to: app team
---

# Handoff to the App Team — the engine seam

Attached: **`PositioningEngine.kt`** (draft interface — not frozen yet).

> **Your architecture spec is `Aneesh/SIH-IDR-android.md`** — module layout, threading,
> sensor acquisition, GNSS, storage, lifecycle, UI, build order. This note only covers
> **the seam between your app and my engine**, plus the few open decisions and what I
> need back from you. It does not restate `SIH-IDR-android.md`; where they overlap, that
> doc wins.

Full integration plan + code skeletons: [[Integration_pipeline]].

---

## 1. The seam

`PositioningEngine` is a **contract** — one interface + `PositionState` + `Mode`. No logic.
It lives in `:core-types`.

```
 :android-sensors  ──onImuSample(raw accel + gravity + gyro)──▶  ┐
 :app GNSS layer   ──onGnssFix() / onGnssLost()──────────────────▶ RealEngine (mine)
                                                                  │  ring buffer →
                                                                  │  decimate → model →
                                                                  │  heading/DR/EKF/map-match
 UI  ◀────────────── state: StateFlow<PositionState> ─────────────┘  (output rate)
```

You build `:app` + `:android-*` against a **`StubEngine`** (I provide it; it holds last
known GNSS speed). Swapping in `RealEngine` later is a one-line DI change.

---

## 2. What I need you to do (that touches the seam)

Per `SIH-IDR-android.md` §§2, 4, 5 — restated here only for the parts I depend on:

1. **Sensors** — accel + gravity + gyro, **highest reliable rate** (`SENSOR_DELAY_FASTEST`,
   `maxReportLatencyUs = 0`), dedicated handler thread. **Copy `SensorEvent.values`
   immediately** (pooled array). Push into `onImuSample` with `event.timestamp`. Do **not**
   downsample, filter, or transform — the engine does all of that (parity).
2. **GNSS** — **raw `LocationManager` `GPS_PROVIDER`**, **not `FusedLocationProviderClient`**
   (`SIH-IDR-android.md` §5: Fused does its own dead reckoning in tunnels → corrupts our
   drift metric; its smoothing breaks the EKF). Plus `GnssStatus.Callback` for the
   `CONSTELLATION_IRNSS` (NavIC) count.
3. **Threads** — never call `onImuSample` / `onGnssFix` / `onGnssLost` on the main thread.
4. **Consume `state`** — move the icon, draw the uncertainty ellipse from
   `state.uncertaintyM`, set the badge from `state.mode` (`INIT` / `NAVIC` / `GNSS` /
   `DEGRADED` / `DEAD_RECKONING`).
5. **Foreground service** owns engine lifetime; calls `engine.start()` / `stop()`.
6. **Build against `StubEngine`** now.

Ready-to-use `SensorSource` / `GnssSource` skeletons: [[Integration_pipeline]] Appendix C.3, C.6.

---

## 3. Open decisions — need answers

| # | Decision | Who decides | Blocks |
| --- | --- | --- | --- |
| **F4** | **Map library** — `SIH-IDR-android.md` §9 says **Mapsforge**; [[App-Stack-and-Integration-Decisions]] §3 says **osmdroid**. Pick one. | app team | your map layer |
| — | **Module tree** — confirm you're building the `SIH-IDR-android.md` §2 layout (`:app` / `:android-*` / `:engine` / `:core-*`) with the `android.*` import ban below the line. | app team | my `:engine` + `:core-*` placement |
| — | **App repo** — name, and push access for me (I own `:engine` + `:core-*`). | app team | everything |
| — | **`SensorSource` / `GnssSource`** — you write them, or take mine from Appendix C? | app team | — |
| — | **Timeline** — when is the app skeleton + `StubEngine` running on a phone? | app team | when I start wiring the real engine |

F1 (features in the `.tflite` graph vs my Kotlin) and F3 (gyro sensor type) are model-team
decisions — I'm chasing those; they may slightly change `onImuSample`'s parameter list, so
**don't treat the interface as frozen yet.**

---

## 4. What I deliver

- `StubEngine` (holds last GNSS speed) — this week.
- `:engine` + `:core-*` — ring buffer, conditioning, anti-alias decimator, model wrapper
  (with manifest self-test), then heading/DR/EKF/map-match as M2/M3 provide the maths.
- The parity-gate suite (G0–G7) proving the engine matches the model team's Python.
- This is also the **edge-engine deliverable** (`:edge-cli` reuses `:engine` + `:core-*`),
  which is why the `android.*` ban matters.

You never wait on the real engine — everything builds against the stub.
