---
tags: [sih2026, integration, contract, model, answers]
ps_id: SIH26168
answers_to: "[[13-Model-App-Integration-Contract]]"
model_state: "round-3 baseline (TensorFlow), speed-only"
---

# Answers to the Model↔App Integration Contract

Back to [[00-Overview]]. Question source: [[13-Model-App-Integration-Contract]]. Pipeline + build detail: [[Integration_pipeline]]. Results: [[12-Model-Results]].

> **Status of the model these answers describe.** A trained baseline exists: TensorFlow
> CNN+GRU speed estimator, 7 engineered IMU features, 50-sample (5 s) window at 10 Hz.
> Code: `sih-26168-model` repo. This is a **working baseline, not the final model** —
> accuracy is still being improved (see the honest-status note at the end). The interface
> below is stable enough to build the app against; the two open **team decisions** are
> flagged (A2 rate, A8 architecture).

> **Important divergence from the contract's example numbers.** Tanmay's template uses
> placeholder examples (100 Hz, 200-sample window, 6 raw device-frame channels). Our
> actual model differs on purpose. Where it differs, the **"Our answer" is authoritative**,
> and the reason is given. Read the answer, not the template example.

---

## PART A — answered

### A1. Framework, versions, export format
```
tensorflow: 2.20.0
keras: 3.10.0          (Keras 3)
python: 3.9.6          (training host; not relevant on-device)
save format: .keras file -> export via SavedModel -> TFLiteConverter.from_saved_model(...)
runtime: LiteRT / TensorFlow Lite  (org.tensorflow:tensorflow-lite)
select_tf_ops: DECISION PENDING on A8. With the current GRU, likely YES (GRU lowers to
               TensorList ops that often need SELECT_TF_OPS). If we switch to a pure
               1-D CNN/TCN (recommended, see A8), then NO — builtins only.
```
**Team decision needed:** keep GRU (simpler now, but `SELECT_TF_OPS` adds a few MB to the
app and complicates the delegate story) vs move to TCN (builtins-only). Recommendation: TCN. See A8.

### A2. Input window: length and sample rate — **TEAM DECISION on rate**
```
sample_rate_hz: 10        # IO-VNBD smartphone data is 10 Hz; the model was trained at 10 Hz
window_length_samples: 50
window_length_seconds: 5.0
inference_stride_samples: 1   # -> 10 Hz output (one new prediction per new sample)
output_cardinality: one value per window (many-to-one)
cold_start_policy: fall back to GNSS speed for the first 5 s (until the first full 50-sample
                   window exists), then switch to the model
```
**Divergence + decision:** the contract example assumes 100 Hz. Our training data is **10 Hz**,
so the model is a 10 Hz model. The app must **downsample** the phone's ~50–200 Hz sensor
stream to exactly 10 Hz before windowing. **Open decision:** whether to retrain at a higher
rate (100 Hz) for lower latency and richer vibration features — this needs re-collected or
re-sampled training data and is a bigger change. For v1, **10 Hz stands**. The 5 s window is
longer than the template's 2 s because it measurably helped accuracy (Round 1→2).

### A3. Input features: channels, order, units, axes — **AUTHORITATIVE**
```
channels (in order) — 7 ENGINEERED features, NOT raw device axes:
  0 a_horiz     m/s^2   horizontal linear-accel magnitude   (forward+lateral)
  1 a_vert      m/s^2   vertical linear-accel component
  2 a_lin_mag   m/s^2   |acceleration - gravity|
  3 gyr_y       rad/s   gyroscope yaw
  4 gyr_p       rad/s   gyroscope pitch
  5 gyr_r       rad/s   gyroscope roll
  6 gyro_mag    rad/s   |gyroscope|
feature_count: 7
magnetometer: EXCLUDED (carries heading, not speed; vehicle-body distortion)
non_imu_inputs: NONE  (pure IMU -> speed; GNSS handled outside, in Part C fusion)
input_frame: rotation-invariant (see A4) — features are magnitudes/components that do NOT
             depend on how the phone is mounted, so there is no device-vs-vehicle axis issue
gyro_sign: right-hand rule (as Android reports)
mount_assumption: mount-agnostic BY CONSTRUCTION (magnitudes are orientation-free)
```
**This is the biggest divergence and the most important thing for the integrator to
understand:** we do **not** feed 6 raw device-frame axes. We feed 7 derived, rotation-
invariant quantities. This is exactly what took error from 33% → 19% (Round 1 → 2). The
Kotlin app must compute these 7 features with the same math (see A4/A5/A15).

### A4. Coordinate-frame transform — **AUTHORITATIVE**
```
model_input_frame: rotation-invariant features (no explicit vehicle-frame alignment needed)
transform_location: APP computes the 7 features before the model (NOT in the tflite graph)
gravity_alignment: uses the phone's GRAVITY vector (Android TYPE_GRAVITY) directly to split
                   linear acceleration into vertical/horizontal — no quaternion needed
yaw_alignment: NOT REQUIRED for the speed model (features are magnitudes). Vehicle-yaw
               alignment is only needed later for HEADING, which is a Part C concern.
reference_impl: sih-26168-model repo -> dataset_v2.py -> load_file() (the feature block)
bump_handling: naturally robust — magnitudes don't flip if the phone shifts; a bump only
               injects transient noise, not a frame break
```
The exact math (must be ported to Kotlin bit-for-bit):
```
a_lin   = accel - gravity          # per-axis, both from Android sensors
g_hat   = gravity / (|gravity| + 1e-6)
a_vert  = dot(a_lin, g_hat)
a_horiz = |a_lin - a_vert * g_hat|
a_lin_mag = |a_lin|
gyro_mag  = |gyro|
features = [a_horiz, a_vert, a_lin_mag, gyro_x, gyro_y, gyro_z, gyro_mag]
```

### A5. Gravity handling — **AUTHORITATIVE**
```
model_sees: linear acceleration (gravity removed), then reduced to the magnitude features above
method: a_lin = raw_accelerometer - gravity, where BOTH come from Android:
        TYPE_ACCELEROMETER  and  TYPE_GRAVITY
do NOT use: TYPE_LINEAR_ACCELERATION (vendor-fused, inconsistent across phones)
filter: none beyond the subtraction
training_data_processed_identically: YES — IO-VNBD provides ACCELEROMETER and GRAVITY
        columns; dataset_v2.py subtracts them exactly as above. The phone must use the
        same two sensors, not the fused linear-accel sensor.
```

### A6. Extra filtering / vibration rejection
```
vibration_rejection: (c) learned implicitly by the model — the app applies NO extra filter
zero_phase_filtering_in_training: NO (none used; nothing to reproduce)
```
The 5 s window + the energy features let the model absorb vibration itself. Do not add a
filter "to be safe" — it would break parity.

### A7. Windowing details
```
window_alignment: left/causal — row[49] is the newest sample; prediction is "speed now"
partial_window_padding: none — app waits for a full 50-sample window (see A2 cold-start)
per_window_detrend: none (normalization is global + fixed, see A10)
```

### A8. Architecture family + TFLite ops — **TEAM DECISION**
```
current architecture: Conv1D(32,k5) -> Conv1D(64,k5) -> GRU(64) -> Dense(1)
params: 36,481
tflite_size_target: < 1 MB float32 (Keras file is ~460 KB; tflite will be similar/smaller)
ops (current, with GRU): likely needs SELECT_TF_OPS (GRU -> TensorList ops)
statefulness: STATELESS — full 50-sample window every call, no carried state
latency_target: < 10 ms per inference on a mid-range Android (small model, easily met)
```
**Recommendation (strong): replace the GRU with a 1-D CNN / TCN.** Reasons: (1) builtins-only
→ no `SELECT_TF_OPS`, smaller app, clean delegates; (2) trivially stateless; (3) on IMU
regression a TCN typically matches a GRU. The GRU was fine for the framework comparison, but
for shipping, TCN is the safer export. **This is a team decision — flagging now, not deciding
alone.** If we keep the GRU, the app must bundle `tensorflow-lite-select-tf-ops` (A1.4).

### A9. Input tensor shape + dtype
```
input:  name="imu_window"  shape=[1, 50, 7]  dtype=float32  layout=row-major, all dims fixed
output: name="speed"       shape=[1, 1]      dtype=float32
```
Kotlin buffer maps directly: `Array(1){ Array(50){ FloatArray(7) } }`. No dynamic dims.

### A10. Normalization scheme + VALUES (from the training split) — **AUTHORITATIVE**
```
input_norm: per-channel standardization (x - mean) / std
input_norm_location: APP applies it (constants below / shipped as norm.json)
output_norm: NONE — the model emits speed directly in m/s (no output de-normalization needed)
constants_source: training split only (no leakage)
```
Current constants (channel order = A3), from the round-3 training split:
```json
{
  "schema_version": 1,
  "model_version": "round3-tf-baseline",
  "input_norm": {
    "method": "standardize",
    "channel_order": ["a_horiz","a_vert","a_lin_mag","gyr_y","gyr_p","gyr_r","gyro_mag"],
    "mean": [1.6039, 0.0405, 1.7428, -0.0004, -0.0031, 0.0001, 0.1939],
    "std":  [1.5529, 0.7247, 1.5759,  0.1148,  0.2310, 0.1372, 0.2196]
  },
  "output_norm": { "method": "none", "target": "forward_speed_mps" },
  "input_units": { "accel": "m/s^2", "gyro": "rad/s" },
  "computed_over": "train_split_only"
}
```
**Note:** these values change every time we retrain. Treat them as **DESIGN→VALUE** — the
final `norm.json` ships with the final model. The **scheme** (per-channel standardize on
input, no output norm) is stable; build against it now.

### A11. Quantization + delegates
```
v1: float32, CPU + XNNPACK. No delegate config needed.
later (optional): float16 for size; test GPU delegate only if latency is ever a problem.
full_int8: not for v1 (changes outputs; needs representative data + re-parity)
```

### A12. Output meaning, units, range — **AUTHORITATIVE**
```
output[0]: forward speed, m/s, >= 0 (reverse not modeled)
denorm: NONE — already m/s
valid_range: 0 .. 60 m/s ; clamp to this range, log if clamped
uncertainty: none in v1
timestamp_semantics: speed at the newest sample of the window ("speed now")
```
The dead-reckoning step (Part C) gets a **scalar forward speed**. Heading comes from
gyro + GNSS init in Part C, NOT from this model.

### A13. Model identity / versioning
```
filename: engine_v{major}.{minor}_{YYYY-MM-DD}_{githash}.tflite
location: sih-26168-model repo -> exports/   (small file, no LFS needed)
changelog: exports/CHANGELOG.md, one line per export
app_reads: app/src/main/assets/engine.tflite  (a build step copies the chosen version in)
```

### A14. Stub model — **ACTION ITEM (model team owes this in week 1)**
```
stub: engine_stub.tflite, input [1,50,7] f32, output [1,1] f32,
      returns a constant (e.g. 8.33 == 30 km/h) regardless of input
also: make_stub.py to regenerate it
status: NOT YET DELIVERED — I will add make_stub.py to the model repo next so the app team
        can build the full pipeline against the real [1,50,7] interface immediately.
```

### A15. Frozen preprocessing reference code — **PARTIAL, action item**
```
current reference: sih-26168-model repo -> dataset_v2.py (feature math) + splits_v2/config.json
                   (mean/std). This IS the spec, but not yet packaged as a single
                   preprocess(raw_imu, timestamps, ...) -> [50,7] function.
action: I will extract a standalone preprocess.py exposing exactly the contract's signature
        so the Kotlin port has one file to match.
```

---

## PART B — status (model is a baseline, not final)
- **B1 .tflite** — not exported yet (need A8 decision first, then convert). Action item.
- **B2 norm.json** — values available now (see A10); final values ship with final model.
- **B3 testset.npz** — not built yet; will include hard cases (braking, turns, stationary,
  a simulated blackout window). Action item.
- **B4 Python parity** — will run after export.
- **B5 preprocess.py** — action item (see A15).
- **B6 model card** — baseline numbers below; full card with the final model.

## PART C — not started (fusion/dead-reckoning owner: M2/M3)
The speed model is done enough to answer A. Part C (DR integrator, non-holonomic, EKF,
handover, NavIC fix logic) is separate math owned by the fusion seat and is not yet built.

## PART D — parity gates
Not reachable until B1 (.tflite) + A14 (stub) exist. G0–G4 unblock as soon as those land.

---

## Honest status of the model (read before trusting the numbers)
- Baseline speed model works. Best clean result so far: **Round 2, TensorFlow, ~19% speed
  error, R² 0.83** on a 33-drive test split ([[12-Model-Results]]).
- **Round 3 (all 72 drives) scored worse — ~24% error, R² 0.57 — NOT because more data
  hurt, but because the larger held-out set contains harder, faster, more varied drives
  (mean 19.2 vs 16.2 m/s).** Different test set ⇒ the R2 and R3 numbers are not directly
  comparable. The real lesson: the current small model under-fits the harder, more diverse
  data. Next step is more model capacity and/or the speed-change reformulation, not just
  more files.
- These are **speed** metrics. The competition KPI is **position drift < 10%** over a
  blackout. A first drift evaluation on the baseline (speed-only, along-track distance
  drift over simulated 1 km blackouts, 351 segments) gives **median 18.3%, mean 19.8%,
  p90 35%, and only 19% of segments under the 10% target.** So the baseline is roughly
  2× off the KPI on distance alone — before heading/fusion error is even added. This is
  the honest gap to close, and it says the model (not the data volume) is the bottleneck.

## Stub + preprocess delivered (A14/A15 done)
- `engine_stub.tflite` — input `[1,50,7]` f32 → output `[1,1]` = 8.33 m/s constant. Built
  and interface-verified. **Converts to builtin ops only (no SELECT_TF_OPS)** — confirms
  the A8 TCN path is clean. App team can build the full pipeline against this now.
- `make_stub.py`, `preprocess.py` (standalone, contract signature) in the `sih-26168-model` repo.

## Immediate action items (model team)
1. **A8 decision** with the team: GRU (needs SELECT_TF_OPS) vs TCN (builtins). Recommend TCN.
2. Ship **A14 stub** `engine_stub.tflite` + `make_stub.py` → unblocks the whole app pipeline.
3. Extract **A15 `preprocess.py`** (standalone, contract signature) → the Kotlin parity spec.
4. Export **B1 `.tflite`** + **B4 Python parity** once A8 is settled.
5. Build **B3 `testset.npz`** with hard cases for the G2–G4 parity gates.
