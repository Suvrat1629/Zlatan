# Assets shipped in this build vs. missing on purpose

- `config.json` — real, ships as-is (see `EngineConfig.kt` for what each field controls).
- `engine.manifest.json` — a **template** with real current numbers from the model
  team's answers doc, but no checksum and no self-test vector, because...
- **`engine.tflite` is NOT here.** This app was built without network access to the
  `sih-26168-model` repo, so no binary model file could be included honestly. Without it,
  `EngineFactory` catches the load failure and falls back to `StubEngine` automatically —
  the app still runs and demos the full sensor/GNSS/map pipeline, just with a
  hold-last-GNSS-speed placeholder instead of the real model.

**To wire up the real (or stub) model:**
1. Get `engine_stub.tflite` (Phase 0/1) or a real `engine_vX.Y....tflite` (Phase 2) from
   the `sih-26168-model` repo (docs/integration-pipeline.md Row 5/8).
2. Drop it in this directory as `engine.tflite`.
3. Fill in `engine.manifest.json`'s `sha256` (via `sha256sum engine.tflite`) and, once a
   real `testset.npz` exists, its `self_test` block:
   ```json
   "self_test": { "input": [[...50 rows of 7 floats...]], "expected_output": 8.33, "tolerance_abs": 1e-3 }
   ```
4. Rebuild. `EngineFactory` will pick it up automatically — no code changes needed.
