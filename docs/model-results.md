---
tags: [sih2026, model, results]
---

# Model Results — PyTorch vs TensorFlow

Back to [[00-Overview]]. Code lives in the separate `sih-26168-model` GitHub repo.

## What we trained
A speed-estimation model: reads a window of smartphone IMU sensors and predicts the vehicle's forward speed (m/s). Architecture is identical in both frameworks — `Conv1D(32) -> Conv1D(64) -> GRU(64) -> Dense(1)`, about 37k parameters. This is the "AI speed filter" from [[04-Architecture-Components]].

Data: IO-VNBD smartphone (`S-*`) files, 10 Hz. 33 drives downloaded, split by drive into train/val/test so overlapping windows never leak across splits. The same cached arrays feed both frameworks, so any difference is the framework/model, not the data.

Note: the "GPS SPEED (Kmh)" column is actually in m/s (verified against speed computed from GPS lat/lon deltas, ratio 1.01).

## Round 1 — raw features (9 channels, 2 s window)
| Metric | PyTorch | TensorFlow |
| --- | --- | --- |
| Test MAE (m/s) | 4.23 | 4.40 |
| Test RMSE (m/s) | 5.47 | 5.93 |
| Test R² | 0.663 | 0.604 |
| Speed % error | 33.2% | 34.5% |
| Train time (s) | 277.9 | 125.5 |

Weak. Raw accelerometer carries gravity and phone-tilt, so a short window can't map cleanly to absolute speed.

## Round 2 — engineered features (7 channels, 5 s window)
Gravity removed; rotation-invariant features: horizontal linear-accel magnitude, vertical component, linear-accel magnitude, gyro yaw/pitch/roll, gyro magnitude.

| Metric | PyTorch | TensorFlow |
| --- | --- | --- |
| Test MAE (m/s) | 3.16 | **3.12** |
| Test RMSE (m/s) | 4.27 | **4.13** |
| Test R² | 0.820 | **0.832** |
| Speed % error | 19.5% | **19.3%** |
| Train time (s) | 259.0 | **129.0** |

Big improvement from feature engineering: error dropped from ~33% to ~19%, R² from ~0.63 to ~0.83.

## Framework verdict (unbiased)
- Accuracy is effectively tied both rounds (differences within seed noise). Round 1 leaned PyTorch, Round 2 leaned TensorFlow — neither is a real quality edge.
- TensorFlow trains about 2× faster on this CPU.
- **Decision: use TensorFlow.** Accuracy is a tie, it trains faster, and it exports directly to TensorFlow Lite for the Android app — no PyTorch→ONNX→TFLite conversion. See [[02-What-We-Are-Building]].

## Honest status
~19% speed error is a solid baseline but not yet the target. This model predicts speed only; the full drift KPI (under 10% over a 1 km blackout) also depends on heading and the fusion/map-matching layers, and on integrating speed over time with GPS resets.

## Round 3 — all 72 drives (same CNN+GRU)
Test MAE 4.62 m/s (TF) / 4.27 (PyTorch); R² 0.57 / 0.65. Worse-looking than Round 2, but
the test split is different (11 harder, faster drives, mean 19.2 vs 16.2 m/s) — not directly
comparable. Lesson: the tiny model under-fits the harder, more diverse data.

## Round 4 — TCN (dilated causal convs, builtins-only), all 72 drives
Bigger model (~115k params, was 37k). Speed accuracy: MAE 4.06 m/s, R² 0.66, 21% error —
only modestly better than the GRU on speed. **But the real KPI improved a lot:**

| Drift KPI (1 km blackouts, 351 segments) | GRU (R3) | TCN (R4) |
| --- | --- | --- |
| Median drift | 18.3% | **12.0%** |
| Mean drift | 19.8% | **15.8%** |
| Pass rate (< 10%) | 19% | **43%** |

The TCN's errors cancel better over integration even at similar MAE. A GPS-anchored eval
(seed from true speed at blackout start, remove bias) helped only slightly (12.0 → 11.6%
median), meaning the remaining error is time-varying, not a fixed offset — so a bias reset
alone won't close the gap.

**Status:** median 12% vs target < 10% — close but not there yet. TCN confirmed as the
architecture (also builtins-only for clean TFLite).

## Next steps to improve
- More TCN capacity / longer receptive field; try predicting speed-change directly as the target.
- Wire Part C (heading + Kalman fusion + map-matching) to measure true 2D position drift.
- Fine-tune on a little India-collected phone data before the finale.
- Fix the worst drives (p90 still ~35%) — inspect which scenarios fail.

Related: [[06-Challenges]], [[08-Tasks-Checklist]], [[04-Architecture-Components]].
