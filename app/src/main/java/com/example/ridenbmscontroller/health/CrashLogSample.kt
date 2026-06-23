package com.example.ridenbmscontroller.health

/** One 200 ms controller tick during a crash/recovery episode. */
data class CrashLogSample(
    val timestampMs: Long,
    val episodeId: Int,
    val msSinceEpisode: Long,
    val crashKind: String,
    val episodeKind: String,
    val episodeStart: Boolean,
    val preIoutA: Double?,
    val preVoutV: Double?,
    val preVinV: Double?,
    val preVtranV: Double?,
    val ioutA: Double?,
    val voutV: Double?,
    val vinV: Double,
    val vtranV: Double,
    val vinErrorV: Double,
    val commandIsetA: Double,
    val ridenIsetA: Double?,
    val pvMode: String,
    val recoveryPhase: String,
    val poutW: Double,
    val pinEstW: Double,
    val kneeOffsetV: Double,
    val recoveryCycleCount: Int,
    val controlBand: String,
    val controlStepA: Double,
    val policyLimitA: Double,
    val probeCrashIsetA: Double,
    val probeRecoveryActive: Boolean,
    val kneeProbeFast: Boolean
)
