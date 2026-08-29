---
tags: [sih2026, integration, contract, model, questions]
ps_id: SIH26168
follows: "[[13-ANSWERS-Model-Team]]"
---

# Model Questions — Part 2 (follow-ups after the Part A answers)

Back to [[00-Overview]]. Part 1: [[13-Model-App-Integration-Contract]].
Answers received: [[13-ANSWERS-Model-Team]]. Pipeline + build detail: [[Integration_pipeline]].

> Part A of [[13-Model-App-Integration-Contract]] is answered enough to build the app
> skeleton + the Kotlin preprocessing against the stub. This file is the **remaining
> open items** — split into hard blockers (needed before the parity gates), clarifications
> (build continues regardless), and deliverables still owed.

---

## 1. BLOCKERS — needed before parity gates G2–G4 can run

### Q1. A8 decision: GRU or TCN?
The answer flagged this as an open **team decision**. It blocks the real `.tflite` export (B1).
- Model team recommends **TCN** (builtins-only, no `SELECT_TF_OPS`, smaller app, trivially
  stateless). The delivered stub already confirmed the TCN path converts clean.
- **Question:** is this decided? If TCN — when is the first real export? If GRU stays —
  confirm so the app bundles `tensorflow-lite-select-tf-ops` and plans for it.

### Q2. Who downsamples to 10 Hz, and exactly how?
The phone's sensors run at ~50–200 Hz; the model is a 10 Hz model.
- **Does `preprocess.py` take a raw high-rate stream and downsample internally, or does it
  expect data already at 10 Hz** (i.e. the app must downsample first)?
- If the app downsamples: **what method** — pick nearest sample, average/decimate, or
  linear interpolation onto a 10 Hz grid? This must match whatever IO-VNBD's 10 Hz data
  represents, or preprocessing parity (G2) will fail.
- Is IO-VNBD's 10 Hz data itself raw-10 Hz, or was it decimated from a higher rate? By what method?

### Q3. `preprocess.py` — is it the final frozen contract version?
The answer says a standalone `preprocess.py` with the contract signature was added to the
`sih-26168-model` repo.
- **Confirm the exact signature**: `preprocess(raw_imu, timestamps_ns, ...) -> np.ndarray[50, 7]`.
- Does it include: gravity subtraction, the 7-feature math, downsampling (see Q2),
  windowing, and normalization — or only some of these? List which stages are inside it
  and which the app must do.
- Will this file change before the final model? If yes, we need a "frozen at model vX" tag.

### Q4. `testset.npz` — the parity answer key (B3)
This is the single most important remaining deliverable for the integrator.
- **When can we get it?**
- Must contain, per window `k`: `raw_inputs`, `model_inputs` (after preprocess, pre-norm),
  `model_inputs_norm`, `keras_outputs`, `tflite_outputs`. (Output norm is "none", so
  `keras_outputs` are already m/s.)
- Must include **hard cases**: hard braking, sharp turns, stop-and-go, a stationary
  segment, rough road, and at least one simulated GNSS-blackout window — not 100 cruise
  windows.
- ~100 windows minimum.

### Q5. B1 `.tflite` + B4 Python parity
- Real `engine_vX.Y.tflite` (after Q1).
- Written confirmation + script that the exported `.tflite`, run through `tf.lite.Interpreter`
  in Python on `model_inputs_norm`, matches the Keras model within `1e-4`.

---

## 2. CLARIFICATIONS — build continues without these, but confirm

### Q6. Gyro features: raw axes or resolved?
The feature list labels channels 3–5 "gyro yaw/pitch/roll" but the A4 code block feeds
raw `gyro_x, gyro_y, gyro_z`.
- **Confirm these are identical** — raw Android gyroscope axes, just relabelled — with no
  rotation applied.
- If raw: **what phone orientation was IO-VNBD's smartphone data collected in** (portrait,
  screen facing driver, top up)? We will put that instruction on the calibration screen.
- (Not a blocker: our Kotlin port matches `preprocess.py` regardless; this only affects
  real-world accuracy and one line of calibration-screen text.)

### Q7. Tensor names
The answer gives `input name="imu_window"`, `output name="speed"`.
- Confirm the **final** model and the **stub** both use these exact names (so the app's
  interpreter code doesn't change when swapping stub → real).

### Q8. Cold-start handoff
Answer says: fall back to GNSS speed for the first 5 s (until the first full window).
- Is that acceptable to the **fusion layer** (Part C), or should the engine emit
  "no estimate" so the EKF handles the gap? (Cross-check with the fusion owner too.)

### Q9. Output range / clamp
Answer says clamp to `0..60 m/s`.
- Confirm 60 m/s (216 km/h) is a safe upper bound for the target vehicles/demo, and that
  the model was not trained on data exceeding it in a way that matters.

### Q10. Model output units — final sanity check
[[12-Model-Results]] notes the IO-VNBD "GPS SPEED (Kmh)" column is actually **m/s**
(ratio 1.01 vs GPS-derived speed).
- Confirm the model's output is therefore genuine **m/s**, and the training target was the
  corrected value, not the mislabelled "Kmh".

---

## 3. DELIVERABLES STILL OWED (tracking — from Part B)

| ID | Item | Status per answers | Needed for |
| --- | --- | --- | --- |
| B1 | real `engine.tflite` | not exported (blocked on Q1) | G0, G3, G4 |
| B2 | final `norm.json` values | scheme fixed; values ship with final model | G3, G4 |
| B3 | `testset.npz` | not built (Q4) | G2, G3, G4 |
| B4 | Python export parity | after export (Q5) | G1 |
| B5 | frozen `preprocess.py` | delivered, confirm final (Q3) | G2 |
| B6 | model card | baseline numbers only; full card with final model | context |

### Q11. Retraining cadence
- How often will the model be retrained before the finale?
- Each retrain ships a **new `.tflite` + new `norm.json` together** (versioned per A13),
  and the integrator re-runs G2–G4. Confirm this is the process and where to watch for
  new versions.

---

## 4. NOT FOR THE MODEL TEAM — separate ask (M2 fusion/DR, M3 maps)

Part C of [[13-Model-App-Integration-Contract]] is still not started, **and two pieces are
missing from Part C itself** — flagged during the pipeline review:

- **Heading estimator — NO OWNER.** The speed model outputs speed only; dead reckoning
  needs a direction. Nobody is assigned to it. The answers doc says "heading is a Part C
  concern" but Part C (C1–C6) never lists it. **M2 must own:** the algorithm (gyro-z
  integration / complementary filter / rotation-vector yaw), GNSS-course seeding + re-seed
  rule, the phone→vehicle yaw offset, and the Python reference.
- **Map-matching — NO OWNER / NO SPEC.** It lives inside the engine box (architecture
  block 3, task 12, ranked #3 hardest in [[06-Challenges]]). **M3 must provide:** the
  offline OSM road-graph file + format + who bundles it, the snapping algorithm (HMM or
  Kalman+graph), the Python reference, and failure-mode notes.
- DR integrator (`dr_step`), non-holonomic constraint
- EKF: state vector, `Q`, `R`, bias/scale-factor freeze rules
- Handover rules (GNSS lost / recovered, debounce; snap vs smooth blend on recovery)
- NavIC fix logic (does IRNSS contribution change `R`?)
- A recorded-drive parity fixture (drive CSV + expected trajectory) for G5

---

## Priority order

1. **Q1** (A8 decision) — unblocks B1
2. **Q2 + Q3** (downsampling ownership + `preprocess.py` scope) — unblocks the Kotlin port's correctness
3. **Q4** (`testset.npz`) — unblocks all parity gates
4. **Q5** (B1 + B4) — the real model
5. Q6–Q11 — confirm in parallel, none block current work
6. Part C — chase M2 (heading + DR + EKF + handover) and M3 (map-matching) separately;
   get **owners named for heading and map-matching** first, they have none right now
