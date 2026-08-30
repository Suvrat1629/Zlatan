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
            maxSpeedRiseMps2 = o.optDouble("max_speed_rise_mps2", d.maxSpeedRiseMps2.toDouble()).toFloat(),
            maxSpeedDropMps2 = o.optDouble("max_speed_drop_mps2", d.maxSpeedDropMps2.toDouble()).toFloat(),
            gnssLostNoFixTimeoutMs = o.optLong("gnss_lost_no_fix_timeout_ms", d.gnssLostNoFixTimeoutMs),
            handoverSlewSeconds = o.optDouble("handover_slew_seconds", d.handoverSlewSeconds),
            gnssAccuracyGateM = o.optDouble("gnss_accuracy_gate_m", d.gnssAccuracyGateM.toDouble()).toFloat(),
            gnssStarvedAfterSeconds = o.optDouble("gnss_starved_after_s", d.gnssStarvedAfterSeconds.toDouble()).toFloat(),
            gnssStarvedAccuracyCeilingM = o.optDouble("gnss_starved_accuracy_ceiling_m", d.gnssStarvedAccuracyCeilingM.toDouble()).toFloat(),
            outputRateHz = o.optDouble("output_rate_hz", d.outputRateHz),
            engineTickP95BudgetMs = o.optDouble("engine_tick_p95_budget_ms", d.engineTickP95BudgetMs.toDouble()).toFloat(),
            useErrorStateEkf = o.optBoolean("use_error_state_ekf", d.useErrorStateEkf),
            useHmmMapMatcher = o.optBoolean("use_hmm_map_matcher", d.useHmmMapMatcher),
            hmmMaxSnapM = o.optDouble("hmm_max_snap_m", d.hmmMaxSnapM.toDouble()).toFloat(),
            hmmCandidateCount = o.optInt("hmm_candidate_count", d.hmmCandidateCount),
            hmmEmissionSigmaM = o.optDouble("hmm_emission_sigma_m", d.hmmEmissionSigmaM.toDouble()).toFloat(),
            hmmTransitionBetaM = o.optDouble("hmm_transition_beta_m", d.hmmTransitionBetaM.toDouble()).toFloat(),
            hmmMaxTransitionSearchM = o.optDouble("hmm_max_transition_search_m", d.hmmMaxTransitionSearchM.toDouble()).toFloat(),
            hmmMinAdvanceDisplacementM = o.optDouble("hmm_min_advance_displacement_m", d.hmmMinAdvanceDisplacementM.toDouble()).toFloat(),
        )
    }
}
