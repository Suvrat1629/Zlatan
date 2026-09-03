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

**2026-08-30 — NHC reconsidered; GNSS bearing wired in as a direct heading measurement
instead.** Classical NHC (lateral body-frame velocity = 0) has no state to attach to here:
the motion model (`n += v*cos(theta)*dt`, `e += v*sin(theta)*dt`) has no lateral velocity
degree of freedom at all — it structurally can't move sideways except through heading error,
so NHC is already implicit by construction. Adding it explicitly would constrain a quantity
with no other information source, i.e. a no-op.

What actually delivers NHC's value here: `updateWithGnss` was receiving `bearingDeg` and
silently ignoring it — heading was only ever corrected indirectly via the n/e-theta
covariance. Wired GNSS course-over-ground in as a direct scalar heading measurement (gated on
a minimum speed, config `ekfMinBearingTrustSpeedMps`, since bearing is unreliable
near-stationary).

Found a real landmine while doing this: `GnssFixRecord.bearingDeg` had no validity signal —
`GnssSource.kt` defaults it to `0f` whenever Android's `location.hasBearing()` is false, so a
fix with unavailable bearing could be silently misread as "heading due north". Fixed properly
rather than gated around: added `bearingValid: Boolean` to `GnssFixRecord`, threaded through
`PositioningEngine.onGnssFix` -> `RealEngine`/`StubEngine`/`TripRecordingEngine` ->
`GnssSource` (`bearingValid = location.hasBearing()`), and through `core-replay`'s
`TraceFormat`/`ReplayEngine` (old 9-field trace lines parse as `bearingValid = false`,
consistent with the "unknown -> untrusted" default used everywhere else). `FusionFilter` and
`ErrorStateEkf` both took the new parameter, defaulted `false` so no existing call site broke.

Verified: full JVM suite (`core-types`, `core-nav`, `engine`, `core-map`, `core-assets`)
green, AND `:app:compileDebugKotlin` clean — the Android-only files (`GnssSource.kt`,
`TripRecordingEngine.kt`) are real, compiled, verified changes, not best-effort-unverified
ones. Two new tests: a valid, fast-moving bearing measurably shrinks heading uncertainty
beyond what position coupling alone gives; an invalid or too-slow bearing is a no-op,
identical to the position-only path.

**Still not completable in this environment**: replay validation against real recorded
traces — this repo has none.

**2026-08-30 — wired into EngineFactory, gated behind a config flag, ON by default.**
Held this back earlier reasoning "don't make it the default until it beats blend on real
replayed traces" — but that data was never going to materialize in this environment, and
the actual available real-world test is the user's own phone, which this was blocking for no
reason. `EngineConfig.useErrorStateEkf` (default `true`, also settable via
`config.json`'s `use_error_state_ekf`) switches `EngineFactory` between `ErrorStateEkf` and
the old `PassthroughFusionFilter`. Verified `:app:compileDebugKotlin` and
`:app:assembleDebug` both succeed — a real installable APK builds. To fall back to the old
behavior for comparison: set `"use_error_state_ekf": false` in `app/src/main/assets/config.json`
and rebuild.

**Not yet swapped**: the fusion filter is the EKF now, but `RealEngine`'s own hand-tuned
speed blend (anchor+delta, dv-bias EMA, ZUPT) is untouched — the EKF receives that blend's
*output* as its speed control input, it doesn't replace it. What's actually being tested on
a real drive now is: EKF position/heading fusion + GNSS-bearing correction, on top of the
existing speed pipeline.

**2026-08-30 — covariance-realism check before step 6; ARW re-grounded 1.41 -> 4.2.**
Advisor flagged (correctly) that the `ekfHeadingArwDegPerSqrtSec = 1.41` fit — least-squares
against `summary.json` cross-track-vs-duration medians — is not an ARW: it lumps angle random
walk, rate random walk and gyro scale-factor error into one white-noise coefficient, then
feeds it to Q as if it were only the first. And `ErrorStateEkfOutageTest` injected its
synthetic heading noise from that *same* constant, so it structurally could not detect an
under-inflated Q.

Added `ekfCovarianceRealismUnderHeadingNoiseMismatch`: drives the 60 s outage with the
injected ARW decoupled from the filter's assumed value, at 1x / 3x / 10x. Result — at 1x the
filter is consistent (reported sigma ~= actual error at outage end); **at 3x it is ~2.5x
overconfident and the EKF loses to a raw last-fix snap on reacquisition** (21/40 down from
30/40), at 10x it is ~9x overconfident (7/40). This is the same failure mode this log's
earlier "position-only is actively WRONG" entry describes, and the `[n,e,theta]` fix only
holds while Q is roughly right.

Interim fix, pending a real Allan variance: set `ekfHeadingArwDegPerSqrtSec = 4.2` (= 3x).
Chosen because at that value the synthetic 60 s outage drift (~22%) matches Part C's measured
~25% median 2D system drift (`15-Part-C-Fusion-Drift.md`), and because underconfident is the
safe failure direction — slower but monotonic reacquisition, not divergence. Full JVM suite +
`:app:compileDebugKotlin`/`:app:assembleDebug` green. **Still open**: the real
ARW + bias-instability split needs a stationary-phone Allan variance recording (architecture
doc §11) — this 4.2 is a grounded stopgap, not the measured number. Also a behaviour change
to A/B on a real drive: the EKF's uncertainty ellipse is now ~3x larger between fixes and it
weights GNSS more heavily.

**2026-08-30 — external PR review; three fixes landed, one decision recorded.**

- **BLOCKER — `seedFromGnssCourse` was corrupting EKF heading (review finding 1).**
  `RealEngine` called `headingEstimator.seedFromGnssCourse(fix.bearingDeg)` on every fix,
  unconditionally. Harmless in the Passthrough era (heading wasn't a filter state); with the
  EKF driving `theta` from the tick-to-tick delta of the incoming `headingDeg`, each fix jumped
  that input by (course − gyroHeading) and `predict()` applied the whole gap to `theta` as
  real rotation — unweighted, bypassing covariance, and then `updateWithGnss` corrected heading
  *again*. Double-counted, once unweighted, and worse at low speed since it has no equivalent
  of the `ekfMinBearingTrustSpeedMps` gate. This is the likely primary cause of the first
  real-drive test wandering off-road (sideways drift from the start of a blackout, not just
  accumulation). **Fix:** the reseed now only runs when `fusionFilter.headingDeg() == null`
  (Passthrough); when the EKF owns heading it corrects `theta` through its own weighted
  measurements and the gyro estimator free-runs as a pure delta source.
- **`uncertaintyM()` is now the covariance ellipse's major axis** (largest eigenvalue of the
  2x2 position block), not `sqrt((pNN+pEE)/2)` (review finding 4). Post-outage the covariance
  is strongly anisotropic and the RMS understated the real uncertainty exactly when the UI
  ellipse and the `MainActivity` drift % are read. Diagnostic `reported/actual` at matched ARW
  went 1.09 → 1.54 (conservative, safe side).
- **`bin/` build output removed from git** (24 stale duplicate `.kt` files) and `bin/`
  gitignored (review finding 2).
- **Gyro-z bias state — decision: NOT adding it now, recorded here (review finding 5).**
  `[[filter-state-vector]]` proposed 5 states; this ships 3 (`n, e, theta`). Dropping the
  speed scale-factor state is well justified — field data (`17-Screening-Eval-Baselines.md`)
  shows signed speed bias ~0, so a scale state has nothing consistent to estimate. Dropping
  gyro-z bias is *less* obviously right: heading is the measured dominant error, and an ARW
  process-noise term models a random walk, not a persistent offset. But `15-Part-C` Finding 3
  tried a **fitted constant** gyro bias offline and it moved the drift floor 18.5% → 18.5%
  (nothing). A Kalman bias *state* differs from a fitted constant (it re-estimates online
  against GNSS, tracks thermal drift, carries covariance), so that null result doesn't settle
  it — but it does mean this isn't clearly the next win, and tuning its random-walk Q needs
  the Allan-variance number we don't have. **Revisit once real device traces + Allan variance
  exist**; not blocking.
- Still owed from the review, not done here: innovation/NIS gating + degraded mode (finding 3,
  separate workstream), telemetry fields for heading uncertainty / map-match gate outcome /
  `onRoad` (finding 7), logging the silent `det`/`s` degenerate-covariance returns.

Full JVM suite (51 tests) + `:app:compileDebugKotlin` + `:app:assembleDebug` green.

## 3b. Map matcher progress log

**2026-08-30 — RoadGraph landed; HMM built on top (§3 steps 2-4, uncommitted).** Scoped per an
advisor check before starting: build steps 2-4 now, stop before step 6 (wiring the matcher as
an EKF measurement) — that step re-treads exactly the ground commit `471520c` reverted
(snapping the reckoner itself erased cross-track motion) and structurally can't start until
step 4's covariance exists anyway. Step 1 (extending the Overpass query for `highway=`/
`oneway=`/`tunnel=`/`layer=`) turned out not to be a real prerequisite despite this file
originally calling it one: the HMM's own inputs — perpendicular-distance emission,
route-distance-vs-great-circle-distance transition, top-k hypotheses — are all geometry/
topology only. `RoadGraph` carries those attribute fields as nullable so the day that query is
extended is a data upgrade, not a code change.

`RoadGraph` (new, `core-map`) is now the single shared topology + spatial index: nodes/edges
built from the polylines once, a grid index lifted from `RoadMatcher`'s old per-instance one,
bounded and unbounded Dijkstra (`shortestPathDistanceM`/`shortestPath`) over directed adjacency
that defaults to bidirectional (no one-way data exists yet). Anchored on `LocalFrame` from
`core-types/Geo.kt` — the shared frame utility `ErrorStateEkf` already uses, promoted during
the EKF work but left unused by the map code until now (that gap is closed). `RoadMatcher` and
`RoutePlanner` are both refactored to consume it instead of independently rebuilding
connectivity from raw ways: `RoutePlanner`'s old O(nodes) linear nearest-node scan is gone,
replaced by the same grid query the matcher uses.

`HmmMapMatcher` (new) keeps up to `hmmCandidateCount` hypotheses, scored each tick with a
Newson-Krumm-style emission (perpendicular distance) and transition (route-distance vs
great-circle-distance mismatch) term — online forward decode, no backtrace, since nothing
downstream replays history to correct retroactively. `MapMatcher.snap()` now returns
`MapMatchResult(position, uncertaintyM, onRoad)` instead of a bare `LatLon` (§3 step 4); the
one live caller, `RealEngine.tickOnce()`, takes `.position` and is otherwise untouched — the
matcher is still display-only, `deadReckoner.reset(fused)` is unchanged.

Real finding, not just plumbing: `snap()` is called every engine tick (10 Hz), which at driving
speed is roughly a metre or two between calls — far too little for the route-distance/
great-circle term to discriminate anything, silently degenerating the HMM into emission-only
(i.e. greedy) scoring. Fixed with `hmmMinAdvanceDisplacementM` (default 8 m): the hypothesis
chain only advances once genuine displacement has accumulated; ticks in between republish the
last result. All new parameters are `EngineConfig`/`config.json` fields
(`use_hmm_map_matcher`, default `false` — RoadMatcher stays the default, matching the
cautious-until-validated pattern the EKF started with, since unlike the EKF this hasn't yet
been run against a real drive at all).

Validated the same way the EKF's outage test was — synthetic, since no recorded device traces
exist in this repo/environment (plan2.md has said this twice already for the EKF; still true
here). `HmmMapMatcherTest` builds two parallel roads 8 m apart joined by cross streets at both
ends (a realistic minimal topology — a service road beside a main road), feeds a trace with one
noisy fix 0.1 m from the wrong road and 7.9 m from the correct one, and shows `RoadMatcher`
gets pulled onto the wrong road (0.1 m beats even its sticky bonus) while `HmmMapMatcher` stays
on the correct one — the route from its live hypothesis to the wrong candidate (~68 m via the
nearest junction) is wildly inconsistent with the ~21.5 m actually travelled, while staying put
is fully consistent. `RoadGraphTest` separately checks `routeDistanceM` picks the shorter of
two junction paths and returns null when genuinely unreachable within the search cutoff. Full
JVM suite (all modules) and `:app:compileDebugKotlin`/`:app:assembleDebug` verified clean.

**Not done** (at time of the entry above): step 1 (attribute extraction — data upgrade, not
blocking), step 5 in the sense the doc means it (validation against a real recorded track — no
such data here, same gap as the EKF's), step 6 (wiring as an EKF measurement — deliberately
deferred), tunnel-portal landmarks (needs step 1's tunnel lengths).

**2026-08-30 — step 2 (interface) + step 6 (matcher as an EKF measurement) landed, gated
OFF (uncommitted).** Done together because step 6 is what actually delivers the KPI value: it
is the one thing in this whole plan that bounds heading-driven cross-track error *during* a
blackout (GNSS bearing, wired in §3a, is unavailable then; architecture doc §4 — "map matching
fixes cross-track brilliantly").

- **`FusionFilter` interface (step 2).** `predict` gained `speedSigmaMps: Float? = null` —
  threaded through everywhere as null today, so the speed model's variance head (Decision 3)
  becomes a value change not an interface change when it lands. New `updateWithMapMatch(pos,
  alongTrackSigmaM, crossTrackSigmaM, roadBearingDeg)` with a default no-op body
  (`PassthroughFusionFilter` and non-map callers untouched). `headingUncertaintyDeg()` promoted
  onto the interface; `ErrorStateEkf.positionCovarianceNE()` added for the real 2×2 the
  uncertainty ellipse needs.
- **`ErrorStateEkf.updateWithMapMatch`.** Two scalar Kalman updates in the road-aligned basis
  via a new generic `updateRow(H, y, r)`: cross-track gets `R = crossTrackSigmaM²` (the real
  information), along-track gets `R = (10 km)²` — a deliberate near-no-op, because a straight
  road carries zero information about how far along it you are and must not shrink the
  along-track covariance. Verified by `mapMatchCorrectsCrossTrackButNotAlongTrack` (+ an
  east–west-road variant for the axis rotation): cross-track error and variance collapse,
  along-track estimate moves <10% of the cross-track correction and its variance stays >85%.
- **`MapMatchResult.roadBearingDeg`** — new nullable field, the matched segment's bearing as
  an axis, from `RoadGraph.bearingDegOf`. Populated by both `RoadMatcher` and `HmmMapMatcher`.
- **`RealEngine` wiring.** One `snap()` call per tick (the HMM advances state per call, so two
  would double-step it). When `config.useMapMatchFusion` (default **false**) and the match is
  on-road and confident (`uncertaintyM ≤ mapMatchMaxFuseUncertaintyM`, default 15 m),
  `updateWithMapMatch` is applied and the road-corrected filter estimate is published. With the
  flag off, behaviour is byte-for-byte the old display-only path — snapped point published,
  reckoner kept on the true trajectory.
- **Why OFF by default.** This is the step commit `471520c` territory. That revert was a *hard
  snap* of the reckoner; this is a covariance-weighted partial pull that only touches
  cross-track and can't erase a genuine turn (the HMM switches hypotheses as sideways motion
  accumulates). Different mechanism — but unvalidated on a real drive, so same discipline as
  `useErrorStateEkf`/`useHmmMapMatcher`: ships reachable, not default, until a real drive
  (or replay against real traces, still absent here) shows it beats display-only.
- **Also, per the advisor's fixture-fit check on §3b:** added
  `hmmStillDiscriminatesParallelRoadsTighterThanTheDisplacementGate` — rebuilds the parallel-
  roads fixture at 4 m spacing (tighter than the 8 m `hmmMinAdvanceDisplacementM` default) and
  confirms the HMM stays on the correct road at gate values 2 / 8 / 16 m. The earlier 8 m/8 m
  coincidence was not load-bearing.

**Two real HMM bugs found while adding the turn-following test the advisor asked for:**

1. **The HMM would not follow a genuine turn onto a cross street.** A right-angle turn taken
   over one advance step makes the on-road route ~0.59x longer than the straight-line distance
   between the two observations — pure geometry — and the old `-|routeDist - gcDist|/beta`
   transition term penalised that as a detour, so the strong "keep going straight" hypothesis
   always edged out the turn. This is 471520c's failure mode via a different mechanism, and it
   would have made `useMapMatchFusion=true` fight every turn. Fixed: tolerate a route excess of
   up to `0.6 * step` before the penalty engages (`CORNER_TOLERANCE_FRAC`); a genuinely wrong
   match still needs a real detour and is still rejected hard. `hmmFollowsAGenuineTurnOntoThe-
   CrossStreet` is the regression test; the parallel-road and tight-4 m tests still pass, so
   the fix isn't overfit — it holds both "stay put under noise" and "switch on a real turn".

2. **`HmmMapMatcher.uncertaintyM` ignored distance-to-road.** It was only the hypothesis-
   position spread, so a match that was unambiguous but 20 m off any road reported ~4 m and
   would have been fused. `RoadMatcher.uncertaintyM` (perpendicular snap distance) is a
   different quantity, and `mapMatchMaxFuseUncertaintyM` has to gate both. Fixed: HMM now adds
   the top candidate's perpendicular distance in quadrature, so both matchers emit a real
   positional 1-std on the same scale. `uncertaintyGrowsWithDistanceFromTheRoadNotJust-
   HypothesisSpread` pins it.

**Display-only regression guard (advisor item 1):** `RealEngineTest.mapMatchFusionOffLeaves-
TheDisplayOnlyPathUnchanged` drives the engine with a fake fixed matcher and
`useMapMatchFusion=false` and asserts the published dot is exactly the snapped point and the
filter was never pulled — the one path that ships today is now a check, not a claim.
`mapMatchFusionOnPullsTheEstimateNotJustTheDisplay` covers the flag-on path.

**Guards added before the flag can be flipped (advisor round 2):**

- **`useMapMatchFusion` requires the HMM.** `useHmmMapMatcher` defaults false, so the natural
  one-line `use_map_match_fusion: true` would otherwise feed the *greedy* `RoadMatcher` (the
  one `greedyMatcherFlickersOntoTheWrongParallelRoad` proves gets pulled onto the wrong road)
  into the EKF as a tight cross-track measurement — strictly worse than display-only. New
  `MapMatcher.emitsFusableCovariance` (true only for `HmmMapMatcher`); `RealEngine` logs a loud
  `use_map_match_fusion IGNORED` and stays display-only otherwise. Test:
  `mapMatchFusionIsRefusedForAMatcherThatDoesNotEmitFusableCovariance`.
- **Gate-rejection telemetry.** `IDR-MAPFUSE` logs every 20 ticks with the reason
  (`off-road` / `no-bearing` / `unc=X>thr` / `fused`) and the cumulative fused fraction, so a
  real-drive A/B can tell "fusion didn't help" from "fusion never fired". The 15 m
  `mapMatchMaxFuseUncertaintyM` was calibrated on the 4-edge toy fixture; on the real 25k-way
  Bangalore graph the hypothesis spread (now added in quadrature with snap distance) may push
  many matches over it, so the first drive needs a sanity check of the reject rate before
  reading any A/B result.
- Display-only regression test strengthened to also assert the filter's uncertainty is
  identical to a no-matcher twin engine — proves the match never reaches the filter with the
  flag off, which the position-only assertion didn't.

**2026-08-30 — `useHmmMapMatcher` flipped to true (default); fusion stays off (advisor
round 3).** The two decisions are independent and were sequenced deliberately:

- **`useHmmMapMatcher = true`** changes only what is drawn — display-only, worst case the dot
  sits on a slightly different road than the greedy snapper picked. The HMM is now tested
  against both failure modes that matter (parallel-road, which the greedy matcher demonstrably
  fails; turn-following, which an earlier HMM version failed), so display-only is the low-risk
  way to see it on the real 25k-way graph. `snap()` has one caller (`RealEngine`, engine
  thread); `RoutePlanner` shares only the read-only `RoadGraph`, so the HMM's per-instance
  mutable hypothesis state is safe.
- **`useMapMatchFusion` stays false** — it touches position estimation, unvalidated.
- **`IDR-MAPFUSE` gate stats moved out of the fusion-enabled branch** so this display-only
  drive also answers "does the 15 m gate fire often enough on the real graph" — turning one
  drive into the data that decides the fusion flag, instead of needing a second.

Full JVM suite (`core-types`, `core-nav`, `core-map`, `core-assets`, `engine`; 51 tests) +
`:app:compileDebugKotlin` + `:app:assembleDebug` green; APK builds.

**Still not done**: step 1, step 5 (real-trace validation — no data here), tunnel-portal
landmarks. Flipping `useMapMatchFusion` on now needs: a real-drive A/B, and a check from a
display-only `use_hmm_map_matcher` drive that `IDR-MAPFUSE` shows the gate passing on a decent
fraction of on-road ticks (the 15 m threshold is calibrated on a toy fixture). The
turn-following fix removed the structural blocker.

## 4. Sequencing note

The HMM's algorithm (§3 steps 1-5) can be built and unit-tested standalone right now, in
parallel with the EKF — they don't block each other. They only converge at §3 step 6, where
the matcher becomes one of the EKF's measurement providers alongside GNSS. If working solo,
do the EKF skeleton first (§2 steps 1-3) so there's something for the matcher to plug into;
if two people, split and integrate at that step.
