package com.sih26168.idr.engine

import com.sih26168.idr.core.model.ConstantSpeedEstimator
import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.Geo
import com.sih26168.idr.core.types.ImuSampleRecord
import com.sih26168.idr.core.types.LatLon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end mount invariance (TODO E2c, heading work plan F1).
 *
 * The property: the same drive must produce the same trajectory regardless of how the phone is
 * mounted. Rotating the phone rotates the accelerometer, gravity and gyroscope vectors together, so
 * a pipeline that measures rotation about the *local vertical* is unaffected — while one that
 * integrates a device axis is scaled by cos(tilt) and diverges.
 *
 * This complements `YawRateTest`, which tests the projection in isolation. Here the whole engine
 * runs, so a regression anywhere in the heading path is caught, not just in the one function.
 *
 * The feature extractor is exercised as a control: its seven inputs are magnitudes and
 * gravity-relative projections, so it should pass by construction. If it ever fails this, the
 * Kotlin port has drifted from the Python spec.
 */
class MountInvarianceTest {

    private val identityNormalizer = Normalizer(FloatArray(7), FloatArray(7) { 1f })
    private val start = LatLon(12.9716, 77.5946)

    /** Rotate a device-frame vector about the device x axis — the phone pitched in its mount. */
    private fun rot(x: Float, y: Float, z: Float, rad: Double): Triple<Float, Float, Float> {
        val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
        return Triple(x, c * y - s * z, s * y + c * z)
    }

    /**
     * A synthetic drive with a sustained turn, in the phone's own frame, optionally re-expressed as
     * if the phone were mounted at [tiltDeg]. Gravity, accelerometer and gyroscope all rotate
     * together, exactly as they would on a real tilted mount.
     */
    private fun driveTrajectory(tiltDeg: Double): LatLon {
        val rad = Math.toRadians(tiltDeg)
        val engine = RealEngine(
            config = EngineConfig(outputRateHz = 10.0),
            speedEstimator = ConstantSpeedEstimator(12f),
            normalizer = identityNormalizer,
            startAt = start,
        )
        val stepNs = 1_000_000_000L / 200          // 200 Hz, close to the measured device rate
        val yawRateRadS = Math.toRadians(20.0)     // a sustained 20 deg/s turn
        var t = 0L
        repeat(2400) {                             // 12 s: past the 5 s window, well into the turn
            // Flat-phone frame: gravity down the device z axis, rotation about the same axis.
            val (ax, ay, az) = rot(0f, 0f, 9.81f, rad)
            val (grx, gry, grz) = rot(0f, 0f, 9.81f, rad)
            val (gx, gy, gz) = rot(0f, 0f, yawRateRadS.toFloat(), rad)
            engine.onImuSample(t, ax, ay, az, grx, gry, grz, gx, gy, gz)
            t += stepNs
            if (t % 100_000_000L < stepNs) engine.tickOnce()
        }
        return engine.state.value.let { LatLon(it.lat, it.lon) }
    }

    @Test
    fun trajectoryIsInvariantAcrossMountingAngles() {
        val reference = driveTrajectory(0.0)
        for (tilt in listOf(30.0, 45.0, 70.0, 90.0, 180.0)) {
            val got = driveTrajectory(tilt)
            val deltaM = Geo.distanceM(reference, got)
            assertTrue(
                deltaM < 1.0,
                "mount at ${tilt}deg diverged from flat by ${"%.2f".format(deltaM)} m over a 12 s turn",
            )
        }
    }

    /**
     * Only four of the seven model features are actually rotation-invariant.
     *
     * The model-to-app integration contract (A3) states "mount-agnostic BY CONSTRUCTION (magnitudes
     * are orientation-free)". That holds for channels 0, 1, 2 and 6 -- horizontal and vertical
     * linear-acceleration components, the linear-acceleration magnitude, and the gyroscope
     * magnitude. It does NOT hold for channels 3, 4 and 5: despite being named gyr_y / gyr_p /
     * gyr_r they are the raw device-frame gyroscope axes, and those rotate with the phone.
     *
     * Consequence: the speed model is mount-dependent, so a mount angle unlike the training data's
     * is a domain shift in its own right, and the feature port cannot serve as a free control on
     * the heading work. Encoded as a test rather than left in prose so it cannot quietly stop being
     * true in either direction.
     */
    @Test
    fun onlyTheMagnitudeFeaturesAreRotationInvariant() {
        val invariant = setOf(0, 1, 2, 6)
        val flat = FeatureExtractor.features(
            floatArrayOf(0.4f, -0.2f, 9.9f, 0f, 0f, 9.81f, 0.05f, -0.02f, 0.3f)
        )
        var sawDeviceAxisChange = false
        for (tilt in listOf(30.0, 45.0, 70.0, 90.0)) {
            val rad = Math.toRadians(tilt)
            val (ax, ay, az) = rot(0.4f, -0.2f, 9.9f, rad)
            val (grx, gry, grz) = rot(0f, 0f, 9.81f, rad)
            val (gx, gy, gz) = rot(0.05f, -0.02f, 0.3f, rad)
            val rotated = FeatureExtractor.features(
                floatArrayOf(ax, ay, az, grx, gry, grz, gx, gy, gz)
            )
            for (c in invariant) {
                assertTrue(
                    abs(flat[c] - rotated[c]) < 1e-4f,
                    "feature " + c + " should be orientation-free but moved at " + tilt + "deg",
                )
            }
            for (c in listOf(3, 4, 5)) {
                if (abs(flat[c] - rotated[c]) > 1e-4f) sawDeviceAxisChange = true
            }
        }
        assertTrue(
            sawDeviceAxisChange,
            "channels 3-5 are raw device axes and must move under rotation; if they stopped, " +
                "either the feature set changed or this test no longer measures what it claims",
        )
    }
}
