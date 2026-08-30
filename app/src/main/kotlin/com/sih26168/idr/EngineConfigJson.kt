package com.sih26168.idr

import com.sih26168.idr.core.types.EngineConfig
import org.json.JSONObject

object EngineConfigJson {
    fun parse(json: String): EngineConfig {
        val o = JSONObject(json)
        val d = EngineConfig.DEFAULT
        return EngineConfig(
            modelRateHz = o.optDouble("model_rate_hz", d.modelRateHz),
            windowSeconds = o.optDouble("window_seconds", d.windowSeconds),
            antiAliasCutoffHz = o.optDouble("anti_alias_cutoff_hz", d.antiAliasCutoffHz),
            ringBufferSeconds = o.optDouble("ring_buffer_seconds", d.ringBufferSeconds),
            coldStartSeconds = o.optDouble("cold_start_seconds", d.coldStartSeconds),
            speedMinMps = o.optDouble("speed_min_mps", d.speedMinMps.toDouble()).toFloat(),
            speedMaxMps = o.optDouble("speed_max_mps", d.speedMaxMps.toDouble()).toFloat(),
            blendTauSeconds = o.optDouble("blend_tau_seconds", d.blendTauSeconds),
            zuptAccelThresholdMps2 = o.optDouble("zupt_accel_threshold_mps2", d.zuptAccelThresholdMps2.toDouble()).toFloat(),
            zuptGyroThresholdRps = o.optDouble("zupt_gyro_threshold_rps", d.zuptGyroThresholdRps.toDouble()).toFloat(),
            walkingSpeedScale = o.optDouble("walking_speed_scale", d.walkingSpeedScale.toDouble()).toFloat(),
            walkingSpeedMaxMps = o.optDouble("walking_speed_max_mps", d.walkingSpeedMaxMps.toDouble()).toFloat(),
            roadHeadingGain = o.optDouble("road_heading_gain", d.roadHeadingGain),
            roadHeadingMaxDistM = o.optDouble("road_heading_max_dist_m", d.roadHeadingMaxDistM),
            roadHeadingMaxTurnRps = o.optDouble("road_heading_max_turn_rps", d.roadHeadingMaxTurnRps),
            gnssLostNoFixTimeoutMs = o.optLong("gnss_lost_no_fix_timeout_ms", d.gnssLostNoFixTimeoutMs),
            handoverSlewSeconds = o.optDouble("handover_slew_seconds", d.handoverSlewSeconds),
            outputRateHz = o.optDouble("output_rate_hz", d.outputRateHz),
            engineTickP95BudgetMs = o.optDouble("engine_tick_p95_budget_ms", d.engineTickP95BudgetMs.toDouble()).toFloat(),
        )
    }
}
