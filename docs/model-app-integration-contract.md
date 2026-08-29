---
tags: [sih2026, integration, contract, model, app]
ps_id: SIH26168
---

# Model ↔ App Integration Contract (what the integrator asks the model team)

Back to [[00-Overview]]. Related: [[04-Architecture-Components]], [[05-App-Features]], [[08-Tasks-Checklist]], [[09-Team-Roles]].

> This doc = **the interface** (what crosses the model↔app boundary, in what format).
> For **how to build the app side** of that interface, see [[Integration_pipeline]].

> **Who this is for.** The person integrating the trained TensorFlow model into the
> Kotlin Android app (M4 integration slice). This document is the single source of
> truth for the interface between "the model" and "the app". Fill every blank in
> Part A **before** training finishes. Collect every artifact in Part B **when the
> model is ready**. Part C is the separate dead-reckoning / fusion hand-off.

---

## 0. How to use this document

### 0.1 For the model team / an LLM answering these questions

- Answer **every** item in Part A. Each item says what format the answer must take.
- If a question is a **team decision** (not something an LLM can decide alone), say so
  explicitly and give a recommended default plus the trade-off, so a human can confirm.
- If you make an assumption, mark it **ASSUMPTION** and state what breaks if it is wrong.
- Prefer concrete numbers, shapes, units, and code over prose.
- The goal: after Part A is answered, the integrator can build the entire app pipeline
  (sensor reading → preprocessing → model call → output handling) against a **stub
  model** without waiting for training. When the real model arrives (Part B), swapping
  it in should require **zero interface changes**.

### 0.2 The pipeline this contract describes

```
phone sensors (irregular)                    engine.tflite
   │                                              ▲
   ▼                                              │
[A] resample to fixed rate ──▶ [B] frame transform ──▶ [C] gravity handling
   ──▶ [D] extra filtering ──▶ [E] windowing ──▶ [F] normalize ──▶  MODEL
                                                                     │
                                                                     ▼
                                              [G] de-normalize output ──▶ [H] meaning/units
                                                                     │
                                                                     ▼
                                          dead-reckoning + fusion (Part C, separate math)
```

Every stage A–H must be specified identically for Python (training/reference) and
Kotlin (app), or the model silently produces wrong numbers with no error.

### 0.3 Legend for "When"

| Tag | Meaning |
| --- | --- |
| **DESIGN** | Pure design decision. Answerable **now**, before any training. Blocks app scaffolding. |
| **DESIGN→VALUE** | The *scheme* is decided now; the *numbers* are filled in after training on the final training split. |
| **MODEL-READY** | An artifact produced by the training run. Cannot exist before the model is trained. |

---

## PART A — Answer BEFORE training (design decisions)

These unblock the app skeleton. None of them require a trained model. An LLM plus one
human sign-off can complete this whole section in an afternoon.

---

### A1. Framework, versions, and export format — **DESIGN**

**Ask:**
1. Exact training stack and versions: TensorFlow version, Keras version (Keras 2 vs
   Keras 3), Python version.
2. Will the model be saved as a live `tf.keras.Model`, a `SavedModel` directory, or a
   `.keras` file? (Affects which `TFLiteConverter.from_*` call is used.)
3. Confirm the target on-device runtime is **LiteRT / TensorFlow Lite** and the app
   Gradle artifact will be `org.tensorflow:tensorflow-lite` (or `com.google.ai.edge.litert:litert`).
4. Will any conversion use `SELECT_TF_OPS` (TensorFlow ops fallback)? If yes, the app
   must also bundle `org.tensorflow:tensorflow-lite-select-tf-ops`, which adds ~several MB.

**Answer format:**
```
tensorflow: 2.17.0
keras: 3.x            (2 or 3 — matters)
python: 3.11
save format: SavedModel directory  ->  TFLiteConverter.from_saved_model(...)
runtime: LiteRT (org.tensorflow:tensorflow-lite:2.17.0)
select_tf_ops: NO   (target: builtins only)
```

**Recommendation / default:** TF 2.17, Keras 3, export via `SavedModel`. Target
**builtin ops only** (no `SELECT_TF_OPS`) — this is achievable if A8 avoids raw LSTM
(see A8). Builtins-only keeps the app small and the delegate story simple.

---

### A2. Input window: length and assumed sample rate — **DESIGN**

**Ask:**
1. What **fixed sample rate** (Hz) does the model assume its input is sampled at?
   (The app will resample the phone's irregular sensor stream to exactly this rate.)
2. What is the **window length** — in samples *and* in seconds?
3. What is the **stride / hop** between consecutive windows during inference? At 10 Hz
   output with a 100 Hz input, the stride is 10 samples. Confirm.
4. Does the model output **one value per window** (many-to-one) or **one value per
   input timestep** (many-to-many / sequence output)?
5. What should the app do during the **cold-start period** before the first full window
   is available (first `window_length` seconds after launch)? Options: output nothing,
   output GNSS speed, zero-pad the window. Pick one.

**Answer format:**
```
sample_rate_hz: 100
window_length_samples: 200
window_length_seconds: 2.0
inference_stride_samples: 10        # -> 10 Hz output
output_cardinality: one value per window (many-to-one)
cold_start_policy: fall back to GNSS speed until first full window; then switch to model
```

**Why the integrator needs it now:** the ring buffer size, the resampler, and the
10 Hz scheduler all depend on these four numbers. Cannot build the sensor layer without them.

**Recommendation / default:** 100 Hz input, 1.0–2.0 s window, stride = 10 (→ 10 Hz).
Shorter windows = lower latency and less lag on speed changes; longer windows = smoother,
more robust. 2 s is a safe start; revisit after drift testing.

---

### A3. Input features: which signals, what order, what units, which axes — **DESIGN**

**Ask:**
1. List every input channel in **exact column order**. Example candidates:
   `accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z, mag_x, mag_y, mag_z`.
2. For each channel, the **physical unit** the model expects:
   - accelerometer: m/s² or g?
   - gyroscope: rad/s or deg/s?
   - magnetometer: µT? (or is magnetometer excluded — see A3.4)
3. **Include magnetometer or not?** Magnetometer is heavily distorted by the vehicle
   body and the phone mount. Many IMU-odometry models drop it. State the decision.
4. **Does the model take any non-IMU inputs?** Critically:
   - last known **GNSS speed** as a feature?
   - last known **GNSS position / heading**?
   - a **time-since-GNSS-lost** counter?
   - **dt** (actual timestep) as a channel?
   If yes, these become part of the interface and the app must feed them every call.
5. **Axis convention.** Which coordinate frame are the 3-axis signals in *at the model
   input* — raw Android device frame, or an already-transformed frame? (This links to
   A4/B-frame transform.) State the axis directions (e.g. "Android sensor frame:
   X = right, Y = top, Z = out of screen").
6. **Sign conventions** for gyro (right-hand rule?) and any assumptions about mounting
   orientation (portrait? landscape? screen facing driver?).

**Answer format:**
```
channels (in order):
  0 accel_x   m/s^2   linear acceleration (gravity removed) — see A5
  1 accel_y   m/s^2
  2 accel_z   m/s^2
  3 gyro_x    rad/s
  4 gyro_y    rad/s
  5 gyro_z    rad/s
feature_count: 6
magnetometer: EXCLUDED (vehicle magnetic distortion)
non_imu_inputs: NONE   # model is pure IMU -> speed; GNSS handled outside in fusion
input_frame: gravity-aligned vehicle frame (see A4)  # NOT raw device frame
axis_convention: X = vehicle forward, Y = vehicle left, Z = up
gyro_sign: right-hand rule
mount_assumption: mount-agnostic (frame transform in A4 removes mounting dependence)
```

**Why the integrator needs it now:** decides which Android `Sensor` types to register,
what conversions to apply, and whether GNSS values must be threaded into the model call.
Item A3.4 in particular changes the whole `PositioningEngine` interface.

**Recommendation / default:** 6 channels (accel + gyro), no magnetometer, **no non-IMU
inputs** to keep the model a clean "IMU → speed" box with GNSS fusion done separately in
Part C. If the model *does* need GNSS speed as input, say so loudly now.

---

### A4. Coordinate-frame transform (stage B of the pipeline) — **DESIGN**

This is the "alignment" block ([[04-Architecture-Components]] block 1, owned by M2). It
decides how phone-frame sensor readings become vehicle-frame readings.

**Ask:**
1. Does the **model expect already-aligned** (vehicle-frame) input, or does it expect
   **device-frame** input and learn to be orientation-robust internally?
2. If aligned input: **which transform**, exactly?
   - Use Android `TYPE_ROTATION_VECTOR` / `GAME_ROTATION_VECTOR` to rotate into a
     gravity-aligned world frame, then rotate by an estimated **yaw offset** (phone
     heading vs vehicle heading) into the vehicle frame?
   - Or a fixed calibration rotation captured during the "hold still 5 s" step?
   - Or PCA / dominant-acceleration-axis estimation?
3. Who computes the phone→vehicle **yaw offset**, and is it assumed constant for the
   whole trip or re-estimated? What happens if the phone is bumped mid-drive?
4. Provide the **exact reference implementation** (Python) of this transform, or a
   precise spec, because the app must reproduce it bit-for-bit.
5. Is the transform **inside the `.tflite` graph** or **applied by the app before the
   model**? (Strong preference: applied by the app, in shared code with M2, so it is
   parity-testable independently.)

**Answer format:**
```
model_input_frame: gravity-aligned + vehicle-yaw-aligned (app applies transform)
transform_location: APP (not in tflite graph)
gravity_alignment: from GAME_ROTATION_VECTOR quaternion -> rotate accel & gyro into
                   world frame where Z = up
yaw_alignment: estimated once from first 30 s of GNSS course vs integrated gyro;
               held constant; re-estimated only when GNSS good for >10 s
reference_impl: model_team/align.py  (function align_window(accel, gyro, quat) -> (a_v, g_v))
bump_handling: out of scope for v1; document as known limitation
```

**Why now:** the app needs to know whether to register the rotation-vector sensor and
implement the transform, or just pass raw sensors. This is one of the top-3 silent-bug
sources ([[06-Challenges]] #4, #6).

**Recommendation / default:** transform **in the app**, in code shared with M2, using
the game rotation vector for gravity alignment. Keep the `.tflite` graph pure
(normalized vehicle-frame IMU in, speed out).

---

### A5. Gravity handling (stage C) — **DESIGN**

**Ask:**
1. Does the model see **raw accelerometer** (gravity present, ~9.81 on one axis) or
   **linear acceleration** (gravity removed)?
2. If gravity removed: **by what method?**
   - Android `TYPE_LINEAR_ACCELERATION` sensor (vendor-fused, varies by phone)?
   - Subtract `[0,0,9.81]` in the world frame after the A4 rotation?
   - High-pass / low-pass complementary filter with a stated cutoff?
   - Madgwick / Mahony filter?
3. Exact filter coefficients / cutoff frequency / time constant.
4. Was the **training data** processed with the same method the app will use? (IO-VNBD
   ships raw IMU — confirm what the training pipeline did to it.)

**Answer format:**
```
model_sees: linear acceleration (gravity removed)
method: rotate to world frame via A4 quaternion, subtract [0, 0, 9.80665], rotate back
        to vehicle frame
do NOT use: Android TYPE_LINEAR_ACCELERATION (inconsistent across devices)
filter: none beyond the subtraction
training_data_processed_identically: YES — align.py is the same code used on IO-VNBD
```

**Why now:** determines which sensor types the app registers and the exact math in the
preprocessing port. [[06-Challenges]] #6 ("gravity contamination") is a classic
project-killer.

**Recommendation / default:** compute linear acceleration yourself via the A4 rotation +
constant subtraction, using the **same Python function** on the training data and the
**same logic** in Kotlin. Never rely on the vendor `LINEAR_ACCELERATION` sensor for a
model trained on differently-processed data.

---

### A6. Extra filtering / vibration rejection (stage D) — **DESIGN**

This is the "noise / vibration filter" ([[04-Architecture-Components]] block 2,
[[08-Tasks-Checklist]] task 9).

**Ask:**
1. Is pothole / engine-idle / speed-bump rejection **(a)** a separate model, **(b)** a
   preprocessing filter the app must run, or **(c)** something the main model learned
   implicitly and the app does nothing?
2. If (a): repeat this entire Part A for that second model (its own input/output/format).
3. If (b): exact filter spec (type, order, cutoff, causal vs zero-phase — note: the app
   can only do **causal** filtering; if the training used `filtfilt` zero-phase, that is
   a mismatch that must be fixed).
4. If (c): confirm explicitly, so the app does no extra filtering.

**Answer format:**
```
vibration_rejection: (c) learned implicitly by the main model; app applies NO extra filter
                     OR
                     (b) causal 2nd-order Butterworth low-pass, cutoff 15 Hz, applied
                     per channel after A5, before windowing; coefficients in filt.json
zero_phase_filtering_in_training: NO (causal only, matches on-device capability)
```

**Why now:** if the app must run a filter, that is more preprocessing code and another
parity check. If not, confirm so nobody adds one "to be safe".

**Recommendation / default:** option (c) or a simple **causal** low-pass. Absolutely
forbid zero-phase (`scipy.signal.filtfilt`) anywhere in the training preprocessing — the
phone cannot reproduce it in real time.

---

### A7. Windowing details (stage E) — **DESIGN**

**Ask:**
1. Is the window **left-aligned** (most recent sample is the last row) or does the model
   predict for the **center** of the window (introduces latency)?
2. Any **padding** applied to partial windows, and with what value?
3. Is there any **per-window detrending** or **mean subtraction** (distinct from the
   global normalization in A8)?

**Answer format:**
```
window_alignment: left/causal — row[N-1] is the newest sample; prediction is "speed now"
partial_window_padding: none — app waits for a full window (see A2 cold_start_policy)
per_window_detrend: none
```

**Recommendation / default:** causal, newest-sample-last, no per-window detrending
(keep normalization global and fixed, see A8).

---

### A8. Model architecture family and TFLite-op implications — **DESIGN (team decision)**

**Ask:**
1. Which architecture family: **TCN / 1-D CNN**, **LSTM**, **GRU**, **Transformer**,
   or hybrid?
2. Rough size: number of layers, channels, total parameters, target `.tflite` file size.
3. Does the chosen architecture convert to **TFLite builtin ops only**, or does it need
   `SELECT_TF_OPS`? (1-D CNN / TCN → builtins, small, fast. LSTM → often needs
   `UnidirectionalSequenceLSTM` handling or `SELECT_TF_OPS`; GRU support is weaker.)
4. Is the model **stateless** (full window every call, no carried state) or **stateful**
   (`stateful=True`, hidden state persists between calls)?
5. Target single-inference latency on a mid-range Android phone.

**Answer format:**
```
architecture: Temporal Convolutional Network (dilated 1-D convs), 5 blocks, 64 channels
params: ~180k
tflite_size_target: < 2 MB, float32
ops: builtins only (Conv1D, ReLU, Add, FullyConnected) — no SELECT_TF_OPS
statefulness: STATELESS — full 200-sample window every call
latency_target: < 8 ms per inference on a Snapdragon 6-class device
```

**Why now:** decides the Gradle dependencies (A1.4), whether the interface must carry
hidden state, and how much latency budget the surrounding pipeline has.

**Recommendation / default (strong):** **TCN / 1-D CNN, stateless.** It converts cleanly
to builtin ops, runs in a few ms, is naturally stateless (trivial parity), and matches
or beats LSTM on IMU regression. Avoid stateful LSTM — the carried-state interface is
the single biggest avoidable integration risk. This is a team decision; make it early.

---

### A9. Input tensor shape rigidity and dtype — **DESIGN**

**Ask:**
1. Exact input tensor shape as it will appear in the `.tflite`, with batch dimension:
   `[1, 200, 6]`? Are any dims dynamic (`None`)?
2. Input dtype: `float32` (recommended) or a quantized type?
3. Output tensor shape and dtype.
4. Row-major layout confirmed (C-order), so Kotlin `Array(1){Array(200){FloatArray(6)}}`
   maps directly?

**Answer format:**
```
input:  name="imu_window"  shape=[1, 200, 6]  dtype=float32  layout=row-major, all dims fixed
output: name="speed"       shape=[1, 1]       dtype=float32
```

**Recommendation / default:** all dimensions **fixed**, `float32` in and out. No dynamic
shapes. Quantization only as a later optimization (A11).

---

### A10. Normalization scheme (stage F) — **DESIGN** (values are **DESIGN→VALUE**)

**Ask:**
1. What normalization is applied to the **input** before the model?
   - per-channel `(x - mean) / std` (standardization)?
   - per-channel `(x - min) / (max - min)` (min-max)?
   - a fixed physical scaling (e.g. divide accel by 20, gyro by 5)?
   - none (raw physical units into the model)?
2. Is normalization **inside the `.tflite` graph** (a `Normalization` layer / baked
   constants) or **applied by the app**?
3. Is the **output** normalized during training (so the model emits a normalized speed
   that must be de-scaled)? If so, the de-normalization constants must ship too (stage G).
4. Are the constants computed over the **training split only** (correct) or the full
   dataset (leakage)?

**Answer format:**
```
input_norm: per-channel standardization (x - mean) / std
input_norm_location: APP applies it (constants shipped as norm.json)  # or: baked into graph
output_norm: speed target standardized during training ->
             model emits z; real_speed_mps = z * speed_std + speed_mean
constants_source: training split only
```

**Why the *scheme* is needed now:** the app's preprocessing code structure depends on it.
**Why the *values* are DESIGN→VALUE:** `mean/std` per channel and for the output can only
be finalized once the training split is fixed. Ship placeholders now, real numbers with
the model (B2).

**Recommendation / default:** per-channel standardization, **applied by the app** from a
shipped `norm.json` (not baked into the graph — keeps the graph inspectable and lets you
parity-test normalization separately). Output also standardized, with `speed_mean` /
`speed_std` in the same `norm.json`.

---

### A11. Quantization and delegates — **DESIGN (can defer)**

**Ask:**
1. v1 export: plain **float32**, or **float16**, or **dynamic-range int8**, or **full
   int8** (needs a representative dataset)?
2. Is a **GPU delegate** or **NNAPI delegate** expected, or CPU/XNNPACK only?
3. If full int8 later: who provides the representative-dataset generator?

**Answer format:**
```
v1: float32, CPU + XNNPACK (default). No delegate config needed.
later (optional): float16 for size; benchmark GPU delegate for the TCN.
full_int8: not for v1 (changes outputs; needs representative data + re-parity)
```

**Recommendation / default:** ship **float32** for v1 so on-device outputs match the
Keras reference to ~1e-4 and the parity gate is clean. Optimize only if latency or size
is actually a problem.

---

### A12. Output meaning, units, frame, and post-processing (stages G–H) — **DESIGN**

**Ask:**
1. What does each output number mean, precisely?
   - single **forward speed** (scalar, m/s)?
   - **2-D velocity** `(v_forward, v_lateral)` in the vehicle frame?
   - **displacement delta** over the window (m)?
   - something else?
2. Units and sign (can speed be negative for reverse?).
3. If the output is de-normalized (A10.3), the exact formula and constants.
4. Expected **valid range** and what the app should do with out-of-range values
   (clamp? reject? trust?).
5. Does the model output any **uncertainty / confidence**? If yes, format and how the
   fusion step (Part C) should use it.
6. What is the **timestamp** associated with one prediction — "speed at the newest
   sample of the window", or an average over the window?

**Answer format:**
```
output[0]: forward speed, m/s, >= 0 (reverse not modeled in v1)
denorm: real = z * speed_std + speed_mean       (speed_std, speed_mean in norm.json)
valid_range: 0 .. 60 m/s ; clamp to this range, log if clamped
uncertainty: none in v1
timestamp_semantics: speed at the time of the newest sample in the window
```

**Why now:** the dead-reckoning step (Part C) consumes this directly; its math depends
on whether it gets a scalar speed or a velocity vector.

**Recommendation / default:** **scalar forward speed in m/s**, non-negative, de-normalized
by the app, clamped to a sane range, timestamped at "now". Heading comes from gyro
integration + GNSS init, handled in Part C — not from this model.

---

### A13. Model identity / versioning — **DESIGN (process)**

**Ask:**
1. Naming convention for delivered models, e.g.
   `engine_v0.3_2026-09-14_a1b2c3d.tflite` (version, date, git short hash).
2. Where models are dropped (shared drive folder / repo path / release page).
3. A short **changelog** line per delivery (what changed, expected drift impact).

**Answer format:**
```
filename: engine_v{major}.{minor}_{YYYY-MM-DD}_{githash}.tflite
location: repo path  model/exports/    (LFS or release asset if > 50 MB — it won't be)
changelog: model/exports/CHANGELOG.md, one line per export
app_reads: app/src/main/assets/engine.tflite  (CI copies + renames the chosen version)
```

**Recommendation / default:** exactly the above. The app always loads a fixed asset name;
a build step copies the chosen versioned file into place. Never hand-copy `.tflite` files
around with ambiguous names.

---

### A14. The stub model (so the app can be built now) — **DESIGN**

**Ask the model team to deliver, in week 1, a throwaway `engine_stub.tflite`** with the
**final interface** (A9 shape/dtype) but trivial internals — e.g. outputs a constant, or
outputs `mean(|accel|) * k`. This lets the integrator build and parity-test the entire
pipeline harness before the real model exists.

**Answer format:**
```
stub delivered: engine_stub.tflite, input [1,200,6] f32, output [1,1] f32,
                returns 8.33 (== 30 km/h) regardless of input
also provide: make_stub.py so the integrator can regenerate it
```

**Recommendation / default:** non-negotiable. A 10-line Keras model exported to TFLite.
Cost: 15 minutes. Value: unblocks all of Part-A-dependent app work immediately.

---

### A15. Frozen preprocessing reference code — **DESIGN (deliver as code)**

**Ask for the Python preprocessing as a single self-contained module** with **no
training-only dependencies**, exposing exactly:

```python
def preprocess(raw_imu: np.ndarray,      # shape [T, C_raw], raw sensor units, irregular ok
               timestamps_ns: np.ndarray, # shape [T]
               orientation_quat: np.ndarray | None  # if A4 needs it
               ) -> np.ndarray:            # shape [window_len, C_model], model-ready
    ...
```

Plus every constant it uses (filter coeffs, target rate, window len) as named module
constants, not magic numbers. This module is the **spec** the Kotlin port must match.

**Answer format:** a link to `model_team/preprocess.py` + confirmation it has been run
standalone (outside the training notebook) on a sample and produces a `[200, 6]` array.

**Recommendation / default:** this module is co-owned. The integrator reviews it line by
line and writes the Kotlin equivalent next to it, with the parity test (B3) as the contract.

---

## PART B — Collect WHEN the model is ready (training artifacts)

None of these can exist before training. When they arrive, run the parity gate (Part D).

---

### B1. `engine_vX.Y_DATE_HASH.tflite` — **MODEL-READY**

- Exported per A1 (from `SavedModel` / Keras model).
- Input/output shape and dtype exactly as promised in A9.
- Builtin ops only (or the agreed `SELECT_TF_OPS` list, if A8 said so).
- File size within the A8 target.
- Accompanied by the A13 changelog line.

**Integrator checks on receipt:**
```python
i = tf.lite.Interpreter(model_path); i.allocate_tensors()
print(i.get_input_details())   # shape == [1,200,6], dtype == float32
print(i.get_output_details())  # shape == [1,1],    dtype == float32
```

---

### B2. `norm.json` — normalization constants — **MODEL-READY (DESIGN→VALUE from A10)**

Exact schema (agree on this in A10, fill values now):

```json
{
  "schema_version": 1,
  "model_version": "engine_v0.3_2026-09-14_a1b2c3d",
  "input_norm": {
    "method": "standardize",
    "channel_order": ["accel_x","accel_y","accel_z","gyro_x","gyro_y","gyro_z"],
    "mean": [0.011, -0.004, 0.002, 0.0001, -0.0003, 0.0002],
    "std":  [1.83,   1.72,   2.05,  0.21,   0.19,    0.44]
  },
  "output_norm": {
    "method": "standardize",
    "target": "forward_speed_mps",
    "mean": 12.4,
    "std": 6.1
  },
  "input_units": {"accel": "m/s^2", "gyro": "rad/s"},
  "computed_over": "train_split_only",
  "n_windows_used": 184213
}
```

**Integrator checks:** channel order matches A3; units match A5/A3; `computed_over` is
`train_split_only`.

---

### B3. `testset.npz` — the parity answer key — **MODEL-READY**

The single most important deliverable after the model itself.

**Contents (agree on keys now):**
```
raw_inputs        : float32 [K, T, C_raw]   # K >= 100 windows, RAW sensor units, pre-preprocessing
raw_timestamps_ns : int64   [K, T]
raw_quat          : float32 [K, T, 4]       # only if A4 needs orientation
model_inputs      : float32 [K, 200, 6]     # AFTER preprocess() — model-ready, pre-normalization
model_inputs_norm : float32 [K, 200, 6]     # AFTER normalization — exactly what goes into the tensor
keras_outputs     : float32 [K, 1]          # what the ORIGINAL Keras model produced (normalized)
keras_outputs_denorm : float32 [K, 1]       # de-normalized to m/s
tflite_outputs    : float32 [K, 1]          # what tf.lite.Interpreter produced in Python (B4)
meta              : dict     # model_version, tf version, date, git hash
```

**Why both raw and preprocessed:** `raw_inputs → model_inputs` tests the **Kotlin
preprocessing port**. `model_inputs_norm → output` tests the **Kotlin model call +
de-norm**. Separating them tells you *which* stage is broken when parity fails.

**Selection:** the K windows must include hard cases — hard braking, sharp turns,
stop-and-go, rough road, a stationary segment, and at least one full simulated GNSS-blackout
window. Not 100 random cruise-on-highway windows.

---

### B4. Python parity confirmation — **MODEL-READY**

Written confirmation (and the script) that the model team ran the exported `.tflite`
through `tf.lite.Interpreter` **in Python** on `model_inputs_norm` and got outputs
matching `keras_outputs` within `atol=1e-4` (or a stated, justified larger tolerance).

This proves **export ≠ broken**, so any later on-device mismatch is an app-side bug.

```
result: max abs diff (keras vs tflite, Python) = 3.1e-5 over 120 windows  -> PASS
script: model/exports/verify_export.py
```

---

### B5. Frozen `preprocess.py` (final version) — **MODEL-READY**

The A15 module, pinned to the delivered model version, with final constants. If
preprocessing changed during model development, this is the authoritative final copy and
the Kotlin port must be re-diffed against it.

---

### B6. Model card / one-pager — **MODEL-READY**

Half a page:
- architecture, params, `.tflite` size, measured latency (which device)
- training data: which IO-VNBD subsets / which held-out
- validation drift numbers: % over blackout on the test tunnel segments ([[08-Tasks-Checklist]] task 19)
- known failure modes (e.g. "underestimates speed above 90 km/h", "poor on unpaved roads")
- what changed vs the previous export (the A13 changelog, expanded)

Feeds the pitch deck ([[08-Tasks-Checklist]] task 26) and tells the integrator where to
expect trouble.

---

## PART C — Separate hand-off: dead-reckoning + fusion math

Not the model. This is the deterministic math ([[04-Architecture-Components]] blocks 3–5,
M2/M3) that the app runs **around** the model. Same parity discipline applies. Ask the
fusion owner for:

### C1. Dead-reckoning integrator — **DESIGN + reference code**
- Exact algorithm: how `speed (from model) + heading` become a position delta each tick.
- Heading source: integrated gyro-z? complementary filter with magnetometer? initial
  heading from GNSS course — how is it seeded and when re-seeded?
- Coordinate math: local ENU? direct lat/lon small-step? which Earth radius / model?
- Python reference function `dr_step(state, speed, yaw_rate, dt) -> state`.

### C2. Non-holonomic constraint — **DESIGN + reference code**
- Exact constraint (zero lateral velocity? zero vertical?) and how it is enforced
  (pseudo-measurement in the filter? hard projection?).

### C3. GNSS+INS fusion filter — **DESIGN + reference code**
- Filter type: EKF / UKF / error-state KF / AI fusion.
- **State vector** definition (position, velocity, heading, biases, scale factor?).
- **Process model** and **process noise** `Q`.
- **Measurement model** for a GNSS fix and **measurement noise** `R` (and how `R`
  scales with reported GNSS accuracy).
- **Bias / scale-factor estimation**: which states are estimated only when GNSS is
  healthy, and frozen during blackout.
- Python reference: `ekf_predict(...)`, `ekf_update_gnss(...)`.

### C4. Handover switch — **DESIGN**
- Exact rule for declaring GNSS "lost": sats-in-fix == 0? accuracy > threshold for N
  updates? both?
- Exact rule for declaring GNSS "recovered", and any debounce.
- On recovery: hard snap to GNSS, or let the filter pull the estimate back smoothly
  over ~1 s? (Demo wants a visible correction; smooth is more honest — [[05-App-Features]].)
- What the mode badge shows in each state (GPS / NavIC / DEAD-RECKONING).

### C5. Fusion parity fixture — **MODEL-READY-equivalent**
- One recorded drive (CSV: timestamped IMU + GNSS, with a real or simulated blackout).
- The **expected output trajectory** from the Python reference for that drive.
- Acceptance: Kotlin engine's trajectory matches within a stated tolerance, and final
  drift % matches within ~1–2 points.

### C6. NavIC specifics — **DESIGN**
- How the app decides "NavIC contributed to this fix" (count `CONSTELLATION_IRNSS`
  sats with `usedInFix`), and whether NavIC-vs-GPS affects `R` in the filter
  ([[07-NavIC-Advantages]]).

---

## PART D — Definition of done (the parity gates)

Integration is "done" when all of these are green, on a real device:

| Gate | What it proves | Pass criterion |
| --- | --- | --- |
| **G0** export sane | `.tflite` shape/dtype/ops match A9/A8 | manual check on receipt |
| **G1** Python export parity | export didn't change the model (B4) | Keras vs Python-TFLite ≤ 1e-4 |
| **G2** Kotlin preprocessing parity | `preprocess()` port is correct | Kotlin `raw→model_inputs` vs `testset` ≤ 1e-4 |
| **G3** Kotlin normalization parity | norm + de-norm port is correct | Kotlin `model_inputs→speed_mps` vs `testset` ≤ 1e-3 |
| **G4** on-device model parity | whole model path works on the phone | Kotlin end-to-end vs `keras_outputs_denorm` ≤ 1e-3 |
| **G5** DR + fusion parity | Part C ports are correct | Kotlin trajectory vs Python (C5) within tolerance |
| **G6** end-to-end drift | the actual competition metric | < 10% drift / < 100 m per 1 km on test blackout segments |
| **G7** real-time | sustained on-device | 10 Hz held for 10 min, engine tick p95 < 50 ms, no GC stutter |

G0–G4 need only Part A + Part B. G5 needs Part C. G6–G7 are the final tuning phase.

---

## Quick reference — the ask, condensed

**Send to the model team now (Part A):**
A1 framework/versions · A2 window length + rate + stride + cold-start · A3 features
(channels, order, units, axes, any non-IMU inputs) · A4 frame transform (what + where) ·
A5 gravity method · A6 extra filtering (yes/no/what, causal only) · A7 windowing
alignment · A8 architecture + ops + **stateless** · A9 exact input/output shape + dtype ·
A10 normalization scheme (input + output, where applied) · A11 float32 for v1 · A12
output meaning/units/range/timestamp · A13 file naming + drop location · **A14 stub
model in week 1** · A15 standalone `preprocess.py`.

**Collect when the model is ready (Part B):**
B1 `.tflite` · B2 `norm.json` (final values) · B3 `testset.npz` (raw + preprocessed +
keras + tflite outputs, hard cases) · B4 Python parity confirmation + script · B5 frozen
`preprocess.py` · B6 model card.

**From the fusion owner (Part C):**
C1 DR integrator · C2 non-holonomic · C3 EKF (state, Q, R, bias rules) · C4 handover
rules · C5 recorded-drive parity fixture · C6 NavIC fix logic.

**Done when (Part D):** G0–G7 green, especially G4 (on-device parity), G6 (< 10% drift),
G7 (steady 10 Hz).
