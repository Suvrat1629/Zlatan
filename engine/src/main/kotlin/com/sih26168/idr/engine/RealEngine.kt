package com.sih26168.idr.engine

import com.sih26168.idr.core.model.SpeedEstimator
import com.sih26168.idr.core.nav.DeadReckoner
import com.sih26168.idr.core.nav.FusionFilter
import com.sih26168.idr.core.nav.GyroIntegrationHeadingEstimator
import com.sih26168.idr.core.nav.HeadingEstimator
import com.sih26168.idr.core.nav.ModeArbiter
import com.sih26168.idr.core.nav.PassthroughFusionFilter
import com.sih26168.idr.core.map.MapMatcher
import com.sih26168.idr.core.map.NoOpMapMatcher
import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.GnssFixRecord
import com.sih26168.idr.core.types.ImuSampleRecord
import com.sih26168.idr.core.types.LatLon
import com.sih26168.idr.core.types.Mode
import com.sih26168.idr.core.types.PositionState
import com.sih26168.idr.core.types.PositioningEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RealEngine(
    private val config: EngineConfig,
    private val speedEstimator: SpeedEstimator,
    private val normalizer: Normalizer,
    startAt: LatLon,
    private val headingEstimator: HeadingEstimator = GyroIntegrationHeadingEstimator(),
    private val fusionFilter: FusionFilter = PassthroughFusionFilter(startAt),
    private val mapMatcher: MapMatcher = NoOpMapMatcher(),

    ringBufferCapacitySamples: Int = 4000,
) : PositioningEngine {

    private val ringBuffer = RingBuffer(ringBufferCapacitySamples)
    private val conditioning = ConditioningStage()
    private val decimator = Decimator(
        cutoffHz = config.antiAliasCutoffHz,
        modelRateHz = config.modelRateHz,
        windowSamples = PositioningEngine.WINDOW_SAMPLES,
    )
    private val origin = startAt   // start position, for logging displacement from origin
    private val deadReckoner = DeadReckoner(startAt)
    private val modeArbiter = ModeArbiter(config.gnssLostNoFixTimeoutMs)

    private val _state = MutableStateFlow(PositionState(lat = startAt.lat, lon = startAt.lon))
    override val state: StateFlow<PositionState> = _state.asStateFlow()

    @Volatile private var lastGyroZ = 0f
    private var tickCount = 0
    private val LOG_EVERY_N_TICKS = 5   // ~2 logs/sec at 10 Hz
    @Volatile private var smoothedSpeed = 0f
    private val pendingGnssFix = AtomicReference<GnssFixRecord?>(null)
    private val lastKnownGnssSpeedMps = AtomicReference(0f)

    @Volatile private var running = false
    private val periodMs = (1000.0 / config.outputRateHz).toLong()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "real-engine").apply {
            isDaemon = true

            priority = Thread.MAX_PRIORITY - 1
        }
    }

    override fun start() {
        if (running) return
        running = true
        scheduler.scheduleAtFixedRate({ safeTick() }, 0, periodMs, TimeUnit.MILLISECONDS)
    }

    override fun stop() {
        running = false
        scheduler.shutdown()
        speedEstimator.close()
    }

    private fun safeTick() {
        if (!running) return
        try {
            tickOnce()
        } catch (t: Throwable) {

            System.err.println("[RealEngine] tick failed: ${t.message}")
        }
    }

    override fun onImuSample(
        tNanos: Long,
        ax: Float, ay: Float, az: Float,
        grx: Float, gry: Float, grz: Float,
        gx: Float, gy: Float, gz: Float,
    ) {
        lastGyroZ = gz
        ringBuffer.push(ImuSampleRecord(tNanos, ax, ay, az, grx, gry, grz, gx, gy, gz))
    }

    override fun onGnssFix(
        tNanos: Long,
        lat: Double, lon: Double,
        speedMps: Float, bearingDeg: Float, horizAccM: Float,
        satsInFix: Int, irnssSatsInFix: Int,
    ) {
        pendingGnssFix.set(GnssFixRecord(tNanos, lat, lon, speedMps, bearingDeg, horizAccM, satsInFix, irnssSatsInFix))
        lastKnownGnssSpeedMps.set(speedMps)
        modeArbiter.onGnssFix(tNanos, satsInFix, irnssSatsInFix)
    }

    override fun onGnssLost(tNanos: Long) {
        modeArbiter.onGnssLost()
    }

    fun tickOnce() {
        val t0 = System.nanoTime()
        val samples = ringBuffer.snapshot()
        if (samples.isEmpty()) return

        val conditioned = conditioning.process(samples)

        conditioned.warnings.forEach { System.err.println("[RealEngine] conditioning: $it") }

        val tEndNanos = conditioned.samples.last().tNanos
        val rawWindow = decimator.decimate(conditioned.samples, tEndNanos)

        val fix = pendingGnssFix.getAndSet(null)
        if (fix != null) {
            headingEstimator.seedFromGnssCourse(fix.bearingDeg)
            fusionFilter.updateWithGnss(LatLon(fix.lat, fix.lon), fix.speedMps, fix.bearingDeg, fix.horizAccM)
        }

        if (rawWindow == null) {

            publish(
                lat = fusionFilter.estimate().lat, lon = fusionFilter.estimate().lon,
                speedMps = lastKnownGnssSpeedMps.get(), headingDeg = headingEstimator.headingDeg().toFloat(),
                mode = Mode.INIT, tEndNanos = tEndNanos, tickStartNanos = t0,
            )
            return
        }
        modeArbiter.markWindowReady()

        val features = FeatureExtractor.featureWindow(rawWindow)
        val normalized = normalizer.apply(features)
        // ZUPT: a stationary phone still yields a non-zero model speed; clamp it to 0 so the
        // dot doesn't drift forever while parked / indoors (no GNSS to correct it).
        val stationary = ZeroVelocityDetector.isStationary(
            features, config.zuptAccelThresholdMps2, config.zuptGyroThresholdRps,
        )
        val rawModelSpeed = speedEstimator.estimate(normalized)
        val instantSpeed = if (stationary) {
            0f
        } else {
            rawModelSpeed.coerceIn(config.speedMinMps, config.speedMaxMps)
        }
        // Exponential smoothing tames the model's spiky output (a single jumpy estimate would
        // otherwise lurch the dot). Decays toward 0 on its own when ZUPT holds instantSpeed at 0.
        val a = config.speedSmoothingAlpha
        smoothedSpeed = a * instantSpeed + (1f - a) * smoothedSpeed
        val speedMps = smoothedSpeed

        val dtSeconds = 1.0 / config.outputRateHz
        headingEstimator.predict(lastGyroZ, dtSeconds)
        val headingDeg = headingEstimator.headingDeg()

        val deadReckoned = deadReckoner.step(speedMps, headingDeg, dtSeconds)
        fusionFilter.predict(deadReckoned, speedMps, headingDeg, dtSeconds)
        val fused = fusionFilter.estimate()
        val matched = mapMatcher.snap(fused)
        deadReckoner.reset(matched)

        // Real-time diagnostics — view with:  adb logcat -s System.out:I | grep IDR-TICK
        if (tickCount++ % LOG_EVERY_N_TICKS == 0) {
            // motion energy from the feature window (means)
            var aLin = 0f; var gyroMag = 0f
            for (row in features) { aLin += row[2]; gyroMag += row[6] }
            aLin /= features.size; gyroMag /= features.size
            // tilt + raw accel from the newest raw sample [ax,ay,az,grx,gry,grz,gx,gy,gz]
            val last = rawWindow.last()
            val grx = last[3]; val gry = last[4]; val grz = last[5]
            val pitchDeg = Math.toDegrees(kotlin.math.atan2(-grx.toDouble(),
                kotlin.math.sqrt((gry * gry + grz * grz).toDouble())))
            val rollDeg = Math.toDegrees(kotlin.math.atan2(gry.toDouble(), grz.toDouble()))
            val rawAccMag = kotlin.math.sqrt((last[0] * last[0] + last[1] * last[1] + last[2] * last[2]).toDouble())
            // displacement of the navigator from the origin (equirectangular metres)
            val dNorth = (matched.lat - origin.lat) * 111_320.0
            val dEast = (matched.lon - origin.lon) * 111_320.0 * kotlin.math.cos(Math.toRadians(origin.lat))
            val distFromOrigin = kotlin.math.sqrt(dNorth * dNorth + dEast * dEast)
            System.out.println(
                "IDR-TICK mode=${modeArbiter.currentMode(tEndNanos)} " +
                    "tilt(pitch=${"%.0f".format(pitchDeg)} roll=${"%.0f".format(rollDeg)})deg " +
                    "acc(raw=${"%.2f".format(rawAccMag)} lin=${"%.3f".format(aLin)})m/s2 " +
                    "gyroMag=${"%.4f".format(gyroMag)}rad/s stationary=$stationary " +
                    "modelSpeed=${"%.2f".format(rawModelSpeed)} published=${"%.2f".format(speedMps)}m/s(${"%.1f".format(speedMps * 3.6f)}km/h) " +
                    "hdg=${"%.0f".format(headingDeg)}deg " +
                    "origin(dE=${"%.1f".format(dEast)} dN=${"%.1f".format(dNorth)} dist=${"%.1f".format(distFromOrigin)})m " +
                    "sats=${modeArbiter.satsInFix()} navic=${modeArbiter.irnssSatsInFix()}"
            )
        }

        publish(
            lat = matched.lat, lon = matched.lon, speedMps = speedMps, headingDeg = headingDeg.toFloat(),
            mode = modeArbiter.currentMode(tEndNanos), tEndNanos = tEndNanos, tickStartNanos = t0,
        )
    }

    private fun publish(
        lat: Double, lon: Double, speedMps: Float, headingDeg: Float,
        mode: Mode, tEndNanos: Long, tickStartNanos: Long,
    ) {
        _state.value = PositionState(
            lat = lat, lon = lon, speedMps = speedMps, headingDeg = headingDeg, mode = mode,
            satsInFix = modeArbiter.satsInFix(),
            irnssSatsInFix = modeArbiter.irnssSatsInFix(),
            uncertaintyM = fusionFilter.uncertaintyM(),
            engineTickMs = (System.nanoTime() - tickStartNanos) / 1_000_000f,
        )
    }
}
