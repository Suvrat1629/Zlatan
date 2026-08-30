package com.sih26168.idr

import android.content.Context
import com.sih26168.idr.androidassets.AndroidAssetProvider
import com.sih26168.idr.androidmodel.TfliteSpeedEstimator
import com.sih26168.idr.core.assets.AssetManifest
import com.sih26168.idr.core.assets.AssetManifestMismatchException
import com.sih26168.idr.core.assets.ManifestValidation
import com.sih26168.idr.core.model.ModelSelfTest
import com.sih26168.idr.core.nav.ErrorStateEkf
import com.sih26168.idr.core.nav.PassthroughFusionFilter
import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.LatLon
import com.sih26168.idr.core.types.PositioningEngine
import com.sih26168.idr.engine.FeatureExtractor
import com.sih26168.idr.engine.Normalizer
import com.sih26168.idr.engine.RealEngine
import com.sih26168.idr.engine.StubEngine

object EngineFactory {
    private const val CONFIG_ASSET = "config.json"
    private const val MODEL_KIND = "model/speed"
    private const val MODEL_ASSET = "engine.tflite"
    private const val MODEL_MANIFEST_ASSET = "engine.manifest.json"
    private const val DELTA_KIND = "model/speed_delta"
    private const val DELTA_ASSET = "engine_delta.tflite"
    private const val DELTA_MANIFEST_ASSET = "delta.manifest.json"

    fun create(context: Context, startAt: LatLon = DEFAULT_START): PositioningEngine {
        val config = loadConfig(context)
        return try {
            buildRealEngine(context, config, startAt)
        } catch (e: AssetManifestMismatchException) {
            // Deliberately NOT caught by the StubEngine fallback below. That fallback exists for a
            // MISSING model, which is a legitimate development state. A model that is present but
            // does not match its manifest is a shipping error, and degrading it to a stub would
            // hide exactly the failure this check was added to expose (TODO.md G2).
            throw e
        } catch (e: ModelSelfTest.SelfTestFailedException) {
            throw e
        } catch (e: ModelSelfTest.SelfTestMissingException) {
            throw e
        } catch (e: Exception) {
            System.err.println(
                "[EngineFactory] no usable packaged model (${e.message}) — falling back to StubEngine. " +
                    "This is expected until a real engine.tflite is dropped into app/src/main/assets/."
            )
            StubEngine(outputRateHz = config.outputRateHz, startAt = startAt)
        }
    }

    private fun buildRealEngine(context: Context, config: EngineConfig, startAt: LatLon): PositioningEngine {
        val provider = AndroidAssetProvider(context).build(
            listOf(AndroidAssetProvider.PackagedEntry(MODEL_KIND, MODEL_ASSET, MODEL_MANIFEST_ASSET))
        )
        val handle = provider.resolve(MODEL_KIND) ?: error("no packaged asset for '$MODEL_KIND'")
        validateManifest(handle.manifest, "imu_window")
        val estimator = TfliteSpeedEstimator(handle)
        val normalizer = Normalizer.fromManifest(handle.manifest)

        // Delta model is optional: present -> time-varying blend; absent -> absolute only.
        var deltaEstimator: TfliteSpeedEstimator? = null
        var deltaNormalizer: Normalizer? = null
        try {
            val deltaProvider = AndroidAssetProvider(context).build(
                listOf(AndroidAssetProvider.PackagedEntry(DELTA_KIND, DELTA_ASSET, DELTA_MANIFEST_ASSET))
            )
            val dh = deltaProvider.resolve(DELTA_KIND)
            if (dh != null) {
                validateManifest(dh.manifest, "imu_window")
                deltaEstimator = TfliteSpeedEstimator(dh)
                deltaNormalizer = Normalizer.fromManifest(dh.manifest)
                System.out.println("[EngineFactory] delta model ${dh.manifest.version} loaded — time-varying blend active (tau=${config.blendTauSeconds}s)")
            }
        } catch (e: Exception) {
            System.err.println("[EngineFactory] no delta model (${e.message}) — running absolute-only speed")
        }

        val fusionFilter = if (config.useErrorStateEkf) {
            System.out.println("[EngineFactory] fusion filter: ErrorStateEkf (config.use_error_state_ekf=true)")
            ErrorStateEkf(startAt, config)
        } else {
            System.out.println("[EngineFactory] fusion filter: PassthroughFusionFilter (config.use_error_state_ekf=false)")
            PassthroughFusionFilter(startAt)
        }

        return RealEngine(
            config = config, speedEstimator = estimator, normalizer = normalizer, startAt = startAt,
            deltaEstimator = deltaEstimator, deltaNormalizer = deltaNormalizer,
            mapMatcher = RoadsLoader.load(context, config),
            fusionFilter = fusionFilter,
        )
    }

    // Public so EngineService can hand config values to components built outside this factory
    // (GnssSource's acceptance gates). Same asset, same parse, same fallback.
    fun loadConfig(context: Context): EngineConfig = try {

    /**
     * Check the manifest against what the engine is actually built to feed the model.
     *
     * [TfliteSpeedEstimator] already checks the *interpreter's* shape, which catches a mismatched
     * .tflite. This checks the *manifest's declared* shape and channel order, which catches the
     * different and quieter failure: a manifest that no longer describes the model beside it. That
     * is what happened on 2026-08-31 -- an exporter schema change meant the manifest parsed to
     * empty shapes and an absent self-test, and a model swap carrying different normalization
     * constants went through unnoticed because the only key both schemas shared was `input_norm`.
     *
     * A manifest declaring nothing is therefore not "nothing to check": it is the symptom.
     */
    private fun validateManifest(manifest: AssetManifest, inputTensorName: String) {
        check(manifest.tensorShapes.isNotEmpty()) {
            "manifest '${manifest.assetId}' v${manifest.version} declares no tensor shapes. Its schema " +
                "probably no longer matches AssetManifestJson (which reads 'tensor_shapes'/'self_test'). " +
                "Refusing to load a model the manifest cannot describe."
        }
        ManifestValidation.validateShape(
            manifest = manifest,
            expectedWindowSamples = PositioningEngine.WINDOW_SAMPLES,
            expectedFeatures = PositioningEngine.FEATURES,
            expectedChannelOrder = FeatureExtractor.CHANNEL_ORDER,
            inputTensorName = inputTensorName,
        )
    }

    private fun loadConfig(context: Context): EngineConfig = try {
        val json = context.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
        EngineConfigJson.parse(json)
    } catch (e: Exception) {
        System.err.println("[EngineFactory] no/invalid config.json (${e.message}) — using EngineConfig.DEFAULT")
        EngineConfig.DEFAULT
    }

    /**
     * Model version from the packaged manifest, for the telemetry summary header. Read separately
     * from [create] so a summary can be labelled even when the engine falls back to the stub.
     */
    fun modelVersion(context: Context): String = try {
        AndroidAssetProvider(context)
            .build(listOf(AndroidAssetProvider.PackagedEntry(MODEL_KIND, MODEL_ASSET, MODEL_MANIFEST_ASSET)))
            .resolve(MODEL_KIND)?.manifest?.version ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }

    private val DEFAULT_START = LatLon(12.9716, 77.5946)
}
