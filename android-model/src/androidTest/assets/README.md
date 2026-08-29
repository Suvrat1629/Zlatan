# Device parity-gate assets (G3 / G4)

Both files here are **gitignored** — copy them from the `sih-26168-model` repo before
running `./gradlew :android-model:connectedAndroidTest`:

```
cp ../sih-26168-model/engine_tcn_v1_2026-08-29_419ee90.tflite  engine.tflite
cp ../sih-26168-model/testset.npz                              testset.npz
```

`ParityGatesDeviceTest` reads them to run:
- **G3** — normalise + interpreter == `keras_outputs` (± 1e-3)
- **G4** — `raw_inputs` → full Kotlin pipeline on device == `keras_outputs` (± 1e-3)

The JVM gates (G2 / G2a / G3a) live in `:engine` and need nothing from here.
