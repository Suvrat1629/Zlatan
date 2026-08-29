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
            gnssLostNoFixTimeoutMs = o.optLong("gnss_lost_no_fix_timeout_ms", d.gnssLostNoFixTimeoutMs),
            handoverSlewSeconds = o.optDouble("handover_slew_seconds", d.handoverSlewSeconds),
            outputRateHz = o.optDouble("output_rate_hz", d.outputRateHz),
            engineTickP95BudgetMs = o.optDouble("engine_tick_p95_budget_ms", d.engineTickP95BudgetMs.toDouble()).toFloat(),
        )
    }
}
