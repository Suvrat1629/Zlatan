package com.sih26168.idr

import android.content.Context
import com.sih26168.idr.core.types.MotionProfile

/** Persists the user's travel-mode choice across launches. */
class MotionProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var profile: MotionProfile
        get() = runCatching { MotionProfile.valueOf(prefs.getString(KEY, null) ?: "") }
            .getOrDefault(MotionProfile.DEFAULT)
        set(value) {
            prefs.edit().putString(KEY, value.name).apply()
        }

    private companion object {
        const val PREFS = "motion_profile"
        const val KEY = "profile"
    }
}
