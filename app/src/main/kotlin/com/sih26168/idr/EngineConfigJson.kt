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
            ekfInitialUncertaintyM = o.optDouble("ekf_initial_uncertainty_m", d.ekfInitialUncertaintyM.toDouble()).toFloat(),
            ekfSpeedNoiseMps = o.optDouble("ekf_speed_noise_mps", d.ekfSpeedNoiseMps.toDouble()).toFloat(),
            ekfHeadingArwDegPerSqrtSec = o.optDouble("ekf_heading_arw_deg_per_sqrt_s", d.ekfHeadingArwDegPerSqrtSec.toDouble()).toFloat(),
            ekfMinGnssAccuracyM = o.optDouble("ekf_min_gnss_accuracy_m", d.ekfMinGnssAccuracyM.toDouble()).toFloat(),
            ekfMinBearingTrustSpeedMps = o.optDouble("ekf_min_bearing_trust_speed_mps", d.ekfMinBearingTrustSpeedMps.toDouble()).toFloat(),
            ekfGnssBearingNoiseDeg = o.optDouble("ekf_gnss_bearing_noise_deg", d.ekfGnssBearingNoiseDeg.toDouble()).toFloat(),
            useRoadBearingHeading = o.optBoolean("use_road_bearing_heading", d.useRoadBearingHeading),
            ekfRoadBearingNoiseDeg = o.optDouble("ekf_road_bearing_noise_deg", d.ekfRoadBearingNoiseDeg.toDouble()).toFloat(),
            ekfInitialGyroBiasDps = o.optDouble("ekf_initial_gyro_bias_dps", d.ekfInitialGyroBiasDps.toDouble()).toFloat(),
            ekfGyroBiasRandomWalkDpsPerSqrtSec = o.optDouble("ekf_gyro_bias_random_walk_dps_per_sqrt_s", d.ekfGyroBiasRandomWalkDpsPerSqrtSec.toDouble()).toFloat(),
            ekfZuptGyroNoiseDps = o.optDouble("ekf_zupt_gyro_noise_dps", d.ekfZuptGyroNoiseDps.toDouble()).toFloat(),
            maxYawRateDps = o.optDouble("max_yaw_rate_dps", d.maxYawRateDps.toDouble()).toFloat(),
            ekfGnssNisGate = o.optDouble("ekf_gnss_nis_gate", d.ekfGnssNisGate.toDouble()).toFloat(),
            useGnssNisGate = o.optBoolean("use_gnss_nis_gate", d.useGnssNisGate),
            gnssMaxImpliedSpeedMps = o.optDouble("gnss_max_implied_speed_mps", d.gnssMaxImpliedSpeedMps.toDouble()).toFloat(),
            ekfMaxConsecutiveGnssRejects = o.optInt("ekf_max_consecutive_gnss_rejects", d.ekfMaxConsecutiveGnssRejects),
            ringBufferSeconds = o.optDouble("ring_buffer_seconds", d.ringBufferSeconds),
            coldStartSeconds = o.optDouble("cold_start_seconds", d.coldStartSeconds),
            speedMinMps = o.optDouble("speed_min_mps", d.speedMinMps.toDouble()).toFloat(),
            speedMaxMps = o.optDouble("speed_max_mps", d.speedMaxMps.toDouble()).toFloat(),
            blendTauSeconds = o.optDouble("blend_tau_seconds", d.blendTauSeconds),
            zuptAccelThresholdMps2 = o.optDouble("zupt_accel_threshold_mps2", d.zuptAccelThresholdMps2.toDouble()).toFloat(),
            zuptGyroThresholdRps = o.optDouble("zupt_gyro_threshold_rps", d.zuptGyroThresholdRps.toDouble()).toFloat(),
            dvBiasEmaAlpha = o.optDouble("dv_bias_ema_alpha", d.dvBiasEmaAlpha.toDouble()).toFloat(),
            dvBiasFixDtMinSeconds = o.optDouble("dv_bias_fix_dt_min_s", d.dvBiasFixDtMinSeconds.toDouble()).toFloat(),
            dvBiasFixDtMaxSeconds = o.optDouble("dv_bias_fix_dt_max_s", d.dvBiasFixDtMaxSeconds.toDouble()).toFloat(),
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
            useMapMatchFusion = o.optBoolean("use_map_match_fusion", d.useMapMatchFusion),
            mapMatchMaxFuseUncertaintyM = o.optDouble("map_match_max_fuse_uncertainty_m", d.mapMatchMaxFuseUncertaintyM.toDouble()).toFloat(),
            mapMatchMinCrossTrackSigmaM = o.optDouble("map_match_min_cross_track_sigma_m", d.mapMatchMinCrossTrackSigmaM.toDouble()).toFloat(),
            mapMatchAlongTrackSigmaM = o.optDouble("map_match_along_track_sigma_m", d.mapMatchAlongTrackSigmaM.toDouble()).toFloat(),
        )
    }
}
