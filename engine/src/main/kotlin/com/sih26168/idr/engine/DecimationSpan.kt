package com.sih26168.idr.engine

import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.PositioningEngine

/**
 * How far back a tick's IMU snapshot must reach for the decimator to produce an identical window to
 * the one it would produce from the whole ring buffer.
 *
 * Lives in one place because the engine and its tests both need it: computing it twice is how an
 * optimisation silently starts changing results. Derived entirely from config, so changing the model
 * rate, window length or filter cutoff moves it automatically rather than quietly starving the
 * decimator.
 */
object DecimationSpan {

    /**
     * Exponential settling margin for the decimator's IIR low-pass, in time constants. The filter is
     * initialised from the first sample it is handed, so its early output depends on how much
     * history preceded the window — trimming without this margin shifted the first bin by about
     * 0.2%. Fifteen time constants leaves an e^-15 residual, below anything downstream resolves.
     */
    const val WARMUP_TIME_CONSTANTS = 15.0

    fun nanosFor(config: EngineConfig, windowSamples: Int = PositioningEngine.WINDOW_SAMPLES): Long {
        // One bin per window sample, plus one spare for the half-bin either side of the first and
        // last bin centres.
        val binsNanos = (windowSamples + 1) * (1_000_000_000.0 / config.modelRateHz)
        val tauSeconds = 1.0 / (2.0 * Math.PI * config.antiAliasCutoffHz)
        return (binsNanos + WARMUP_TIME_CONSTANTS * tauSeconds * 1_000_000_000.0).toLong()
    }
}
