# Plan 2 — Real EKF + Real Map Matcher

Reference doc: `../sih-26168-notes/Aneesh/SIH-IDR-architecture.md` (§5, §6 Layer 4/5, §9, §16
decisions 3-4). This plan replaces two components that currently exist only as
placeholders/simplified stand-ins with the versions the architecture doc actually specifies.

Status snapshot taken 2026-08-30 against branch `feat/map-matcher`.

---

## 1. Current state (verified against code, not just notes)

### Fusion filter — not built
- `core-nav/.../FusionFilter.kt`: interface exists, but the only implementation is
  `PassthroughFusionFilter` — overwrites position with the GNSS fix on arrival, or the
  dead-reckoned point otherwise. No covariance, no Kalman gain, no process/measurement noise.
  `uncertaintyM()` is hardcoded `0f`.
- The real "fusion" logic today lives in `RealEngine.tickOnce()` as a hand-coded **scalar
  time-varying blend**: `v = (1-lam)*(v + dv*dt) + lam*v_abs`, anchored on each trusted GNSS
  fix, with EMA'd bias correction and a ZUPT hard-zero. Works, field-tuned, but it's a speed
  blend, not a state estimator — no error-state EKF, no NIS/innovation monitoring, no GNSS
  quality gate, no degraded mode.
- `NonHolonomicConstraint`: interface exists, only implementation is `NoOpNonHolonomicConstraint`
  — not applied at all. Makes sense today: `DeadReckoner` has no lateral-velocity state for NHC
  to constrain.
- `ModeArbiter`: sat-count + fix-staleness only. No innovation-based degraded mode, no
  multipath/jamming detection.
- `HeadingEstimator`: plain gyro-z integration, reseeded from GNSS course per fix. No
  tilt-from-gravity, no magnetometer contribution wired in here, no mount-alignment projection
  (comment in code acknowledges this is a placeholder pending the W6 alignment engine).

### Map matcher — shipped, but narrower than planned
- `RoadMatcher`: real, working forward-only nearest-segment snapper, spatial grid index,
  sticky-way hysteresis. Matches the doc's rendering-layer intent.
- **Display-only**: `mapMatcher.snap(fused)` result is only published to the UI; the
  `deadReckoner` is reset to `fused` (pre-match), never to the matched point — deliberate, per
  the commit `471520c`, because snapping the reckoner itself erased real cross-track motion.
- Not an HMM — single nearest-candidate pick each tick, no multi-hypothesis tracking, no
  emitted covariance.
- `roads.json` = `{bbox, ways}` from one Overpass query: raw polylines only. **No road class,
  no one-way, no tunnel attributes/length, no persisted topology.** `RoutePlanner` independently
  rebuilds an ad hoc node/edge graph from the same polylines at runtime (coords rounded to ~1m
  to merge intersections) — duplicated, lossy, and missing attributes.
- No `pyosmium`/`osmium` pipeline exists anywhere in the repo. The doc's planned
  Geofabrik-extract pipeline was never built; Overpass was used directly for the demo bbox.

### What's already reusable / not blocking
- Layer-0 contracts exist: `PositionState`, `LatLon`, `EngineConfig`, `Mode`, records.
- `core-replay` + offline eval harness (`screening_eval.py`, `blend_tv_eval.json`) — can
  validate against recorded ground-truth traces with no phone/car.
- `EngineConfig`/`config.json` — versioned config, no hardcoded constants needed.
- `GnssSource` already gates fixes deterministically (rejects `>30m` accuracy, `<4` sats) and
  passes `horizAccM` through — usable as a GNSS measurement-noise proxy on day one, even
  without raw C/N0/HDOP.
- `RoadMatcher`'s grid + projection code is directly reusable as the HMM's per-tick candidate
  generator.

---

## 2. EKF — build order

Nothing else needs building first; start directly on the filter.

1. **State vector** (first real design decision). Minimal error-state, 2D bicycle model per
   doc §4: position error (N/E), heading error, forward-speed bias, gyro-z bias. Add lateral
   velocity once NHC is wired (step 4).
2. **Extend `FusionFilter`** — current interface can't carry variances in or covariance out.
   Add measurement noise (R) params to `predict`/`updateWithGnss`, expose real covariance
   instead of the hardcoded `uncertaintyM() = 0f`.
3. **Write `ErrorStateEkf : FusionFilter`** in `core-nav`, behind a config flag so it can be
   A/B'd against the current blend without ripping it out.
4. **Validate offline first with ground-truth GNSS speed as the measurement**, not the TCN
   model output — isolates filter bugs from model bugs (doc's stated build order: "filter
   driven by ground-truth speed" before model integration).
5. Swap in the real speed model (absolute + delta) once the filter checks out on ground truth.
6. **Add NHC as a real measurement** — needs the lateral-velocity state from step 1.
7. **Extend the GNSS gate** from today's threshold check into a quality-scaled R. Raw
   C/N0/HDOP plumbing (`GnssMeasurement` API) is a later add-on, not a blocker — `horizAccM`
   is a usable proxy meanwhile.

---

## 3. Map matcher — build order

One genuine prerequisite here (unlike the EKF): the road data itself is missing attributes an
HMM needs. Don't build the full Geofabrik/osmium pipeline from scratch — extend the working
Overpass path instead; it's sufficient at city/demo scale.

1. **Extend the Overpass query/script** to pull `highway=` (road class), `oneway=`, `tunnel=`
   + `layer=` (for length/portal landmarks) alongside the geometry already fetched.
2. **Build one shared `RoadGraph`** (topology + attributes + spatial index) in `core-map`,
   replacing the current situation where `RoadMatcher` and `RoutePlanner` each reconstruct
   connectivity independently and lossily.
3. **Implement the forward HMM** on top of `RoadGraph`:
   - reuse `RoadMatcher`'s grid + perpendicular-projection as the per-tick candidate generator
   - emission probability from perpendicular distance (already computed)
   - transition probability from route-distance vs great-circle-distance ratio between
     consecutive candidates
   - keep top-k hypotheses (Viterbi-style), no hard single-candidate pick
4. **Change `MapMatcher` interface** to return position + covariance/confidence, not a bare
   snapped `LatLon` — same shift as `FusionFilter` needs.
5. **Validate offline** against recorded traces via `core-replay` before touching live code.
6. **Wire it as a real EKF measurement provider** (not display-only) once §2's EKF exists —
   this is the step that actually delivers along-track/cross-track correction instead of just
   cosmetic on-road snapping.
7. Later: tunnel-portal landmark measurements (needs tunnel length from step 1's attributes).

---

## 3a. EKF progress log

**2026-08-30 — first cut landed (uncommitted).** `ErrorStateEkf` in `core-nav`, plus
`LocalFrame`/`LocalEnu` promoted to `core-types/Geo.kt` as shared layer-0 utilities (was
duplicated per-consumer in `RoadMatcher`/`RoutePlanner`; not refactored to use the shared
version yet — noted as cleanup, not done). Tests added: `ErrorStateEkfTest` (4 cases),
2 new `GeoTest` cases for `LocalFrame`. Full JVM suite (`core-types`, `core-nav`, `engine`,
`core-assets`, `core-map`) passes clean, no regressions.

Scope of this first cut, deliberately smaller than the doc's full error-state EKF (see the
doubts resolved before starting, in chat — not restated in this file):
- State = **position only `[N, E]`** in a local tangent frame, speed+heading taken as
  exogenous controls from the existing `SpeedEstimator`/`HeadingEstimator` pipeline. No
  speed-bias or gyro-bias states yet — those need raw sensor inputs threaded through the
  `FusionFilter` interface, which is a bigger change deferred to a later pass.
- `FusionFilter` interface **left unchanged** — R/Q come from `EngineConfig`
  (`ekfInitialUncertaintyM`, `ekfSpeedNoiseMps`, `ekfHeadingNoiseDegPerSec`,
  `ekfMinGnssAccuracyM`) rather than being threaded through per-call, since there's no real
  per-sample variance source yet (no variance head on the speed model).
- NHC and map-match updates still not wired — both still need a velocity state (NHC) /
  covariance-aware measurement provider (map match), both step 6 below.
- **Not yet wired into `RealEngine`/`EngineFactory`** — still only reachable via unit tests
  and (once done) `core-replay`. Per the plan's own risk note (§ real-world readiness
  discussion in chat): don't make this the default until it beats the current blend
  estimator on the same recorded traces via replay, then re-validate on a real drive.

**2026-08-30 — synthetic outage-drift check added.** No recorded device traces and no
`sih-26168-model` (the `screening_eval.py` harness) exist in this repo/environment, so a real
held-out-route validation isn't possible here. Built the honest substitute instead:
`ErrorStateEkfOutageTest` drives a known ground-truth path (gentle heading drift, 15 m/s)
through both filters with IDENTICAL seeded-random noise on speed/heading/GNSS-fix-position,
withholds GNSS for a 60s window, and compares drift as % of distance travelled (the doc's own
metric convention). Result: EKF enters the outage at 0.5m error vs. Passthrough's 3.3m
(hard-snap-to-last-fix vs. Kalman-averaged fixes) — both drift similarly *during* the outage
itself, since neither filter has extra information there; the EKF's whole advantage in this
test is entry-error, exactly as expected mathematically. **This is a filter-mechanics sanity
check, not a KPI number** — real validation still needs `core-replay` against actual recorded
traces or the IO-VNBD-based harness, neither available here.

**2026-08-30 — real ARW derivation exposed a real bug; heading is now in the state.**
`sih-26168-model` became available (pushed to `/home/suvrat/projects/sih-26168-model`).
That repo's `results/screening/summary.json` gives real cross-track-drift-by-outage-duration
data (10/30/60/180s) from actual IO-VNBD held-out drives. Fitting growth models to it:
sqrt(t) fits ~5.6x better than linear (SSE 16.5 vs 91.9) — confirms cross-track error is a
random walk, not a constant-rate bias, matching the model team's own "Part C" conclusion
(`sih-26168-notes/15-Part-C-Fusion-Drift.md`: gyro heading floor is intrinsic MEMS random-walk
drift, three cheap fixes tried and failed). Converted the fit to an Angle Random Walk (ARW)
coefficient: **1.41 deg/sqrt(s)**, now `EngineConfig.ekfHeadingArwDegPerSqrtSec` (was a guess
before, `ekfHeadingNoiseDegPerSec = 1.5`).

Deriving that number surfaced a real bug in `ErrorStateEkf`: heading noise was scaled by `dt`
instead of `sqrt(dt)` — physically wrong for a random walk (makes total accumulated variance
depend on tick rate). Fixed.

**Bigger finding, from validating the fix with a synthetic outage test using this realistic
ARW value**: the position-only state (§2's "doubt #1" scoping decision) is not just smaller
in scope than the doc's error-state EKF — it's actively WRONG. Tick-by-tick trace of a 60s
outage: EKF and Passthrough accumulate near-identical drift during the outage itself (~120m,
matching real cross-track magnitudes — good), but on GNSS reacquisition, Passthrough snaps
instantly to the fix while the EKF barely moved (P had only grown to ~7 m², i.e. ~2.7m std,
against an actual ~120m error) — needing many more fixes to claw back, ending up WORSE than
naive hard-snap at exactly the moment that matters most. Root cause: a position-only filter
structurally cannot represent "my heading might be off by 10 degrees now" — treating heading
uncertainty as memoryless per-tick noise on position increments (via a Jacobian) massively
underestimates true uncertainty, because heading error is NOT memoryless — it's a persistent
bias that compounds across every subsequent tick until corrected.

**Fix**: `ErrorStateEkf` now has state `[n, e, theta]` — heading is a real filter state, fed
by the exogenous heading estimator's tick-to-tick CHANGE (not its absolute value, wrap-aware),
carrying its own ARW-driven variance, with proper `F P F^T + Q` covariance propagation (3x3,
genuine Jacobian linearisation now, not the old "F=I exactly" reduced case). GNSS position
updates now correct heading too, through the n/e-theta covariance built up in `predict()` —
the real mechanism real GPS/INS fusion uses to bound heading drift, which is genuinely useful
given heading is the dominant error source. Verified: synthetic outage test went from
EKF losing 39/40 trials (mean 18.1m vs Passthrough's 2.7m) to winning 28/40 (mean 2.1m vs
2.7m). Added `reacquisitionAfterSustainedHeadingBiasCorrectsQuickly` as a permanent regression
test for this exact failure mode. All 9 core-nav + 3 core-types tests pass.

**Separately noted, not chased further**: `results/fusion_drift.json` in `sih-26168-model` is
STALE — its current on-disk numbers (75-92% 2D drift) come from the abandoned "3s-averaged
heading seed" experiment that Part-C's own notes say was reverted for being much worse; the
trustworthy numbers (25.4% real system / 18.5% true-speed floor) are in the notes doc and in
that file's git history at commit `83203c1`, before the regression got committed over it at
`419ee90` without ever being regenerated back. Worth telling the model team to regenerate it.

**Also noted, not chased further**: the synthetic test's single-parameter ARW model still
undershoots the real ~18-25% 2D drift floor by ~10x in absolute magnitude (it's a reasonable
mechanism check, not a KPI stand-in) — real gyro noise likely has a second, slower bias-
instability/rate-random-walk component that a pure white-noise angle random walk doesn't
capture. Separating the two properly needs real Allan variance analysis on a stationary phone
recording (architecture doc §11's own recommended method), not something derivable from
aggregate cross-track percentages. Open item, not a blocker for anything above.

Next up: get this validated against real recorded traces (needs either a device trace file or
access to a raw IO-VNBD drive) before any live wiring; NHC is now a much more natural next
addition since heading is finally a real state with real covariance for NHC to correct; or
move to the map matcher track.

## 4. Sequencing note

The HMM's algorithm (§3 steps 1-5) can be built and unit-tested standalone right now, in
parallel with the EKF — they don't block each other. They only converge at §3 step 6, where
the matcher becomes one of the EKF's measurement providers alongside GNSS. If working solo,
do the EKF skeleton first (§2 steps 1-3) so there's something for the matcher to plug into;
if two people, split and integrate at that step.
