package com.sih26168.idr.core.nav

import kotlin.math.sqrt

/**
 * Vehicle yaw rate from a phone-frame gyroscope reading — fix F1 in the heading work plan
 * (`wiki/notes/idr-heading-fix-plan.md`).
 *
 * Vehicle yaw is rotation about the **local vertical**, not about the phone's z axis. Integrating
 * raw `gz` therefore reads `cos(tilt)` of the true turn rate: 87% at 30° of tilt, 34% at 70°, so a
 * 90° corner integrates to 78° or 31°. With a fixed mount that is a *systematic* scale error applied
 * to every turn, and the filter cannot correct it — consistently under-rotating looks like genuine
 * rotation.
 *
 * Projecting the angular-velocity vector onto the gravity direction recovers the rotation rate about
 * vertical exactly, at any static mounting angle. It also self-corrects for a face-down phone,
 * because the gravity vector flips with it.
 *
 * Backward compatible with the previous behaviour for the flat screen-up case this codebase was
 * tuned against: with gravity `(0, 0, +g)` the projection reduces to `gz`, so the sign convention in
 * [GyroIntegrationHeadingEstimator] is unchanged.
 *
 * Not solved here, and deliberately so: a shake rotates the phone about the vertical axis too, so it
 * survives this projection. Separating vehicle rotation from phone rotation needs the mounting state
 * described in the plan's F4.
 */
object YawRate {

    /** Below this the gravity vector is unusable and we fall back to the raw z axis. */
    private const val MIN_GRAVITY_MAG = 1e-3f

    /**
     * @param gx,gy,gz angular velocity in the device frame, rad/s.
     * @param grx,gry,grz the gravity vector in the device frame, any magnitude.
     * @return rotation rate about the local vertical, rad/s, in the same sign convention as `gz`
     *         for a flat screen-up phone.
     */
    fun aboutVertical(
        gx: Float, gy: Float, gz: Float,
        grx: Float, gry: Float, grz: Float,
    ): Float {
        val mag = sqrt(grx * grx + gry * gry + grz * grz)
        if (mag < MIN_GRAVITY_MAG || mag.isNaN()) return gz
        return (gx * grx + gy * gry + gz * grz) / mag
    }
}
