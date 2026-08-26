package com.example.ridenbmscontroller.controller

import com.example.ridenbmscontroller.ble.BmsBleUiState
import com.example.ridenbmscontroller.health.CrashLogSample
import com.example.ridenbmscontroller.health.SkyDisturbanceSnapshot
import com.example.ridenbmscontroller.model.AppSettings
import com.example.ridenbmscontroller.riden.RidenTelemetry
import com.example.ridenbmscontroller.riden.RidenUsbState
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

data class MpptControlState(
    val enabled: Boolean = false,
    val pvMode: String = "Idle",
    val status: String = "Controller off",
    val targetChargeCurrent: Double = 0.0,
    val commandIset: Double = 0.0,
    val targetPvVolts: Double = 0.0,
    val kneeOffsetVolts: Double = 0.0,
    val vinErrorVolts: Double = 0.0,
    val policyLimitAmps: Double = 0.0,
    val recoveryPhase: String = "--",
    val recoveryCycleCount: Int = 0,
    val controlBand: String = "--",
    val controlStepAmps: Double = 0.0,
    val chargeVoltageTarget: Double = 0.0,
    val recoveryActive: Boolean = false,
    val socTargetPercent: Int = 0,
    val kneeProbeFast: Boolean = false,
    val vtuneProbePhase: String = "--",
    val huntLockTicks: Int = 0,
    val estimatedPinWatts: Double = 0.0,
    val acceptedProbePinWatts: Double = 0.0,
    val vtuneDescentBlocked: Boolean = false,
    val pastMppActive: Boolean = false,
    val pastMppWrongWay: Boolean = false,
    val pastMppVinBelowKneeV: Double = 0.0,
    val pastMppIsetDeltaA: Double = 0.0,
    val pastMppPoutDeltaW: Double = 0.0,
    val pastMppMissedW: Double = 0.0,
    val pastMppEpisodeCount: Int = 0,
    val pastMppCumulativeMissedW: Double = 0.0,
    val effectiveKneeDelaySeconds: Double = 30.0,
    val lowPowerMode: Boolean = false
)

/**
 * Solar MPPT policy: BMS/SOC limits, single Vin-knee ISET hunt loop, and slow VTune probing.
 */
class SolarMpptController(
    private val setOutput: (Boolean) -> Unit,
    private val setVset: (Double) -> Unit,
    private val setIset: (Double) -> Unit,
    private val onBalanceDayStarted: (Long) -> Unit,
    private val onState: (MpptControlState) -> Unit,
    private val onEvent: (String) -> Unit = {},
    private val onCollapseEntered: (scheduled: Boolean, preCrash: SkyDisturbanceSnapshot?) -> Unit = { _, _ -> },
    private val onCrashEpisodeStart: (kind: String, preCrash: SkyDisturbanceSnapshot?, nowMs: Long) -> Int = { _, _, _ -> 0 },
    private val onCrashLogSample: (CrashLogSample) -> Unit = {},
    private val onTopOffComplete: (reason: String) -> Unit = {}
) {
    private var outputEnabled = false
    private var commandIset = 0.0
    private var lastVset = 0.0
    private var kneeOffsetVolts = 0.0
    private var lastKneeProbeMs = 0L
    private var lastTickMs = 0L
    private var pvMode = MODE_IDLE
    private var recoveryPhase = PHASE_NONE
    private var lastCollapseMs = 0L
    private var lastWorkingIset = Tuning.MIN_ISET
    private var recentCollapseCount = 0
    private var recoveryCycleCount = 0
    private var recoveryWaitSinceMs = 0L
    private var trackingFloorSinceMs = 0L
    private var stableSinceMs = 0L
    private var alarmWasActive = false
    private var alarmHoldUntilMs = 0L
    private var alarmBackoffMs = Tuning.ALARM_BASE_HOLD_MS
    private var lastAlarmStartMs = 0L
    private var pendingVtuneDownProbe = false
    private var lastVtuneDownProbeMs = 0L
    private var kneeProbeFast = false
    private var scheduledDrillActive = false
    private var lastDrillSessionEndMs = 0L
    private var probePacingPhase = ProbePacingPhase.IDLE
    private var huntLockConsecutiveTicks = 0
    private var probeWaitSinceMs = 0L
    private var probePauseUntilMs = 0L
    private var awaitingPowerCheck = false
    private var acceptedProbeInputPowerW = 0.0
    private var vtuneDescentBlocked = false
    private var lastEstimatedPinW = 0.0
    private var lastObsIset = 0.0
    private var lastObsPoutW = 0.0
    private var lastObsMs = 0L
    private var pastMppActive = false
    private var pastMppWrongWay = false
    private var pastMppVinBelowKneeV = 0.0
    private var pastMppIsetDeltaA = 0.0
    private var pastMppPoutDeltaW = 0.0
    private var pastMppMissedW = 0.0
    private var pastMppEpisodeCount = 0
    private var pastMppCumulativeMissedW = 0.0
    private var lastPastMppEventMs = 0L
    private var wasPastMppActive = false
    private var probeOneShotConsumed = false
    private var scheduledRecoveryEpisode = false
    private var probeCrashIset = Tuning.MIN_ISET
    private var probeRecallPending = false
    private var probeRecoveryActive = false
    private var useFixedAmpHunt = false
    private var lastAppliedHuntStepA = 0.0
    private var lastAppliedControlBand = "--"
    private var needsRidenTakeover = true
    private var takeoverAttemptUntilMs = 0L
    /** After hard-lock timeout with full sun, skip micro sun-probe ladder and start at 2A. */
    private var coldStartFastAcquire = false
    private var cachedHandoffVin: Double? = null
    private var cachedHandoffIset: Double? = null
    private var cachedHandoffWatts = 0.0
    private var sunReferenceIset = Tuning.MIN_ISET
    private var sunReferenceKnown = false
    private var morningMinimalSun = false
    private var sunProbeActive = false
    private var sunProbeLadderIndex = 0
    private var sunProbeLastGoodIset = Tuning.MIN_ISET
    private var sunProbeSettleSinceMs = 0L
    private var sunProbeFastColdStart = false
    private var overnightVinNearVout = false
    private var bmsOfflineActive = false
    /** True after this SOC visit has met both SOC and hold-voltage gates (EST already synced). */
    private var holdEstSyncedThisVisit = false
    private var lastPreCollapseSnapshot: SkyDisturbanceSnapshot? = null
    private var crashLogActive = false
    private var crashLogEpisodeId = 0
    private var crashLogEpisodeKind = ""
    private var crashLogRowKind = ""
    private var crashLogEpisodeStartMs = 0L
    private var crashLogPhase = CRASH_LOG_IDLE
    private var crashLogStableTicks = 0
    private var crashLogTailTicks = 0
    private var crashLogEpisodeStartPending = false
    private var crashLogPreCrash: SkyDisturbanceSnapshot? = null
    private var crashLogKneeProbeFastAtEntry = false
    private var lastCrashLoggedRidenSeq = 0L

    fun resetLearnedKnee() {
        kneeOffsetVolts = 0.0
        lastKneeProbeMs = System.currentTimeMillis()
        lastDrillSessionEndMs = lastKneeProbeMs
        recentCollapseCount = 0
        lastCollapseMs = 0L
        stableSinceMs = 0L
        resetFastAcquireState()
        resetCollapseState()
        needsRidenTakeover = false
    }

    fun setActiveKnee(settings: AppSettings, targetPvVolts: Double) {
        kneeOffsetVolts = clampKnee(settings, targetPvVolts) - settings.targetPvVolts
        lastKneeProbeMs = System.currentTimeMillis()
        lastDrillSessionEndMs = lastKneeProbeMs
        recentCollapseCount = 0
        lastCollapseMs = 0L
        stableSinceMs = 0L
        resetFastAcquireState()
        resetCollapseState()
        needsRidenTakeover = false
    }

    private fun resetCollapseState() {
        recoveryPhase = PHASE_NONE
        recoveryWaitSinceMs = 0L
        trackingFloorSinceMs = 0L
        probeRecallPending = false
        probeRecoveryActive = false
        probeCrashIset = Tuning.MIN_ISET
        scheduledRecoveryEpisode = false
        useFixedAmpHunt = false
    }

    private fun resetFastAcquireState() {
        pendingVtuneDownProbe = false
        scheduledDrillActive = false
        kneeProbeFast = false
        resetProbePacingState(clearPowerBaseline = true)
    }

    /** Ends a scheduled drill-down session; next session waits knee delay min. */
    private fun endScheduledDrillSession(now: Long, reason: String? = null) {
        val wasDrilling = scheduledDrillActive
        scheduledDrillActive = false
        kneeProbeFast = false
        lastDrillSessionEndMs = now
        pendingVtuneDownProbe = false
        if (wasDrilling && reason != null) {
            onEvent("Scheduled drill-down ended ($reason)")
        }
    }

    private fun resetProbePacingState(clearPowerBaseline: Boolean) {
        probePacingPhase = ProbePacingPhase.IDLE
        huntLockConsecutiveTicks = 0
        probeWaitSinceMs = 0L
        probePauseUntilMs = 0L
        awaitingPowerCheck = false
        if (clearPowerBaseline) {
            acceptedProbeInputPowerW = 0.0
            vtuneDescentBlocked = false
        }
    }

    private fun maybeEmitCrashLog(
        settings: AppSettings,
        bmsState: BmsBleUiState,
        ridenState: RidenUsbState,
        now: Long
    ) {
        if (!crashLogActive || !settings.controllerEnabled || !ridenState.connected) return
        val sampleSeq = ridenState.telemetrySampleSeq
        if (sampleSeq == lastCrashLoggedRidenSeq) return
        lastCrashLoggedRidenSeq = sampleSeq
        emitCrashLogFromCurrentState(settings, bmsState, ridenState, now)
    }

    fun tick(
        settings: AppSettings,
        bmsState: BmsBleUiState,
        ridenState: RidenUsbState
    ) {
        val now = System.currentTimeMillis()
        val loopMs = settings.controllerLoopMs.toLong()
        if (now - lastTickMs < loopMs) {
            maybeEmitCrashLog(settings, bmsState, ridenState, now)
            return
        }
        lastTickMs = now

        val riden = ridenState.telemetry
        val bms = bmsState.telemetry

        if (!settings.controllerEnabled || !ridenState.connected) {
            bmsOfflineActive = false
            holdEstSyncedThisVisit = false
            idle(settings, ridenState.telemetry, "Controller off")
            return
        }

        val batteryVolts = bms.packVoltage
        val batteryAmps = bms.packCurrent
        val socPercent = bms.socPercent
        val bmsReady = bmsState.connectedDeviceAddress != null &&
            batteryVolts != null && batteryAmps != null && socPercent != null

        updateBmsOfflineTransition(bmsReady, settings)

        val chargeVoltageLimit = if (bmsReady) {
            settings.maxBatteryVolts
        } else {
            settings.bmsOfflineMaxBatteryVolts
        }

        val balanceDay = bmsReady && isBalanceDay(settings)
        if (balanceDay && settings.lastBalanceEpochDay != currentEpochDay()) {
            onBalanceDayStarted(currentEpochDay())
        }
        val socTarget = if (balanceDay) 100 else settings.normalSocCeilingPercent

        if (bmsReady) {
            val chargeBlockingAlarms = bms.chargeBlockingAlarmNames
            if (chargeBlockingAlarms.isNotEmpty()) {
                updateAlarmBackoff(now)
                inhibitCharging(
                    settings = settings,
                    riden = ridenState.telemetry,
                    status = "BMS alarm: ${chargeBlockingAlarms.joinToString()}",
                    socTargetPercent = socTarget
                )
                return
            }
            if (alarmWasActive) {
                alarmWasActive = false
                alarmHoldUntilMs = now + alarmBackoffMs
            }
            if (now < alarmHoldUntilMs) {
                val seconds = ((alarmHoldUntilMs - now) / 1000L).coerceAtLeast(1L)
                inhibitCharging(settings, ridenState.telemetry, "BMS alarm cleared: retry in ${seconds}s", socTarget)
                return
            }
            if (lastAlarmStartMs > 0L && now - lastAlarmStartMs >= Tuning.ALARM_STABLE_RESET_MS) {
                alarmBackoffMs = Tuning.ALARM_BASE_HOLD_MS
            }
        }

        val vin = riden.vin ?: 0.0
        val vout = riden.vout ?: 0.0
        val snapVin = vin
        val snapIset = riden.iset
        val snapWatts = riden.watts ?: 0.0
        val virtualKnee = virtualKnee(settings)
        val voltageLimited = vout >= chargeVoltageLimit - Tuning.VOLTAGE_LIMIT_EPS
        val currentLimit = settings.maxChargeAmps.coerceAtLeast(Tuning.MIN_ISET)
        val holdVolts = if (socTarget >= 100) settings.fullHoldVolts else settings.bmsVoltageHoldVolts
        val holdCurrent = if (socTarget >= 100) {
            settings.fullHoldCurrentAmps.coerceAtLeast(0.0)
        } else {
            settings.socHoldCurrentAmps.coerceAtLeast(0.0)
        }
        val reachedSocTarget = bmsReady && socPercent != null && socPercent >= socTarget
        val reachedHoldVolts = bmsReady && batteryVolts != null && batteryVolts >= holdVolts
        val packHoldActive = reachedSocTarget && reachedHoldVolts
        if (!reachedSocTarget) {
            holdEstSyncedThisVisit = false
        } else if (packHoldActive && !holdEstSyncedThisVisit) {
            holdEstSyncedThisVisit = true
            val reason = if (socTarget >= 100) "100% hold" else "ceiling hold"
            onEvent(
                "Hold: EST=SOC ($reason at ${"%.2f".format(batteryVolts)}V / $socTarget%)"
            )
            onTopOffComplete(reason)
        }
        val targetBatteryCurrent = when {
            !bmsReady && voltageLimited -> settings.socHoldCurrentAmps
            packHoldActive -> holdCurrent
            else -> currentLimit
        }

        ensureOutputAndVoltage(settings, chargeVoltageLimit)

        if (recoveryPhase == PHASE_NONE && !isCollapsed(settings, vin)) {
            lastPreCollapseSnapshot = SkyDisturbanceSnapshot(
                timestampMs = now,
                ioutA = riden.iout,
                voutV = riden.vout,
                vinV = vin,
                vtranV = virtualKnee
            )
        }

        val policyLimit = currentLimit
        var holdIsetForTakeover = false
        if (needsRidenTakeover && !sunReferenceKnown) {
            if (takeoverAttemptUntilMs == 0L) {
                takeoverAttemptUntilMs = now + Tuning.TAKEOVER_RETRY_MS
                onEvent("Cold start: attempting Riden hard lock")
            }
            if (tryAdoptRidenOperatingPoint(settings, snapVin, snapIset, snapWatts, policyLimit, now) ||
                tryAdoptCachedHandoff(settings, policyLimit, now)
            ) {
                needsRidenTakeover = false
                takeoverAttemptUntilMs = 0L
                coldStartFastAcquire = false
            } else if (now > takeoverAttemptUntilMs) {
                needsRidenTakeover = false
                takeoverAttemptUntilMs = 0L
                val morningLike = vout > 0.0 && snapVin > 0.0 &&
                    abs(snapVin - vout) <= Tuning.OVERNIGHT_VIN_VOUT_EPS_V
                coldStartFastAcquire = !morningLike
                onEvent(
                    if (coldStartFastAcquire) {
                        "Cold start: hard lock missed — fast acquire"
                    } else {
                        "Cold start: hard lock missed — morning sun probe"
                    }
                )
            } else {
                // Preserve live Riden ISET; do not drop to 0.01A while waiting for lock.
                holdIsetForTakeover = true
            }
        } else if (needsRidenTakeover && sunReferenceKnown) {
            needsRidenTakeover = false
            takeoverAttemptUntilMs = 0L
        }

        val oldIset = currentIset(riden.iset)
        val nextIset = when {
            holdIsetForTakeover -> {
                pvMode = MODE_TRACKING
                lastAppliedControlBand = "TK"
                lastAppliedHuntStepA = 0.0
                (riden.iset ?: Tuning.MIN_ISET).coerceAtLeast(Tuning.MIN_ISET)
            }
            packHoldActive && batteryVolts != null && batteryAmps != null -> {
                if (recoveryPhase != PHASE_NONE) {
                    recoveryPhase = PHASE_NONE
                    recoveryWaitSinceMs = 0L
                }
                val iset = tickBmsVoltageHold(
                    settings = settings,
                    oldIset = oldIset,
                    packVoltage = batteryVolts,
                    packCurrent = batteryAmps,
                    ridenIout = riden.iout ?: oldIset,
                    vin = vin,
                    virtualKnee = virtualKnee,
                    currentLimit = currentLimit,
                    holdVolts = holdVolts,
                    targetNetAmps = holdCurrent,
                    now = now
                )
                pvMode = MODE_BMS_V_HOLD
                resetCollapseState()
                updateStableRecoveryReset(false, settings, vin, virtualKnee, now)
                iset
            }
            recoveryPhase != PHASE_NONE -> recover(
                settings, oldIset, vin, vout, riden.watts ?: 0.0, virtualKnee, policyLimit, packHoldActive, now
            )
            isCollapsed(settings, vin) -> enterRecovery(
                settings, oldIset, policyLimit, vin, now
            )
            else -> {
                val iset = handleSolarTick(
                    settings, oldIset, vin, vout, riden.watts ?: 0.0, virtualKnee, policyLimit, now
                )
                if (!bmsReady && voltageLimited) {
                    pvMode = MODE_VOLTAGE_LIMIT
                    resetCollapseState()
                    updateStableRecoveryReset(false, settings, vin, virtualKnee, now)
                }
                iset
            }
        }.let { clampPolicyIset(it, policyLimit) }

        commandIset = quantAmps(nextIset)
        setIset(commandIset)

        val finalVinError = vin - virtualKnee(settings)
        lastEstimatedPinW = estimateInputPowerW(riden.watts ?: 0.0, vin, vout)
        updatePastMppObservation(
            iset = commandIset,
            poutW = riden.watts ?: 0.0,
            vin = vin,
            virtualKnee = virtualKnee(settings),
            now = now
        )
        publish(
            settings = settings,
            status = if (bmsReady) {
                statusFor(
                    balanceDay = balanceDay,
                    socReached = packHoldActive,
                    voltageLimited = voltageLimited && !packHoldActive,
                    bmsVoltageHoldActive = packHoldActive,
                    holdVolts = holdVolts
                )
            } else {
                statusForBmsOffline(voltageLimited, chargeVoltageLimit)
            },
            policyLimit = targetBatteryCurrent,
            band = if (lastAppliedControlBand != "--") lastAppliedControlBand else bandFor(finalVinError),
            stepAmps = if (lastAppliedHuntStepA > 0.0) lastAppliedHuntStepA else stepFor(finalVinError, policyLimit),
            vinError = finalVinError,
            socTargetPercent = if (bmsReady) socTarget else settings.normalSocCeilingPercent
        )
        maybeEmitCrashLog(settings, bmsState, ridenState, now)
    }

    private fun emitCrashLogFromCurrentState(
        settings: AppSettings,
        bmsState: BmsBleUiState,
        ridenState: RidenUsbState,
        now: Long
    ) {
        val riden = ridenState.telemetry
        val vin = riden.vin ?: 0.0
        val virtualKnee = virtualKnee(settings)
        val policyLimit = settings.maxChargeAmps.coerceAtLeast(Tuning.MIN_ISET)
        val vinError = vin - virtualKnee
        emitCrashLogTickIfActive(
            settings = settings,
            riden = riden,
            virtualKnee = virtualKnee,
            policyLimit = policyLimit,
            vinError = vinError,
            now = now
        )
    }

    /** Cloud/probe collapse entry — raise knee (or probe undo), wait at MIN for Vin rebound. */
    private fun enterRecovery(
        settings: AppSettings,
        oldIset: Double,
        policyLimit: Double,
        vin: Double,
        now: Long
    ): Double {
        val immediateProbeCrash = pendingVtuneDownProbe &&
            (now - lastVtuneDownProbeMs) <= expectedProbeCollapseWindowMs(settings)
        val scheduled = immediateProbeCrash || scheduledRecoveryEpisode
        endScheduledDrillSession(now, if (immediateProbeCrash) "probe crash" else "collapse")
        pendingVtuneDownProbe = false
        resetProbePacingState(clearPowerBaseline = false)
        vtuneDescentBlocked = false

        recoveryCycleCount += 1
        stableSinceMs = 0L
        resetSunProbeState()
        useFixedAmpHunt = false

        val preCrashIset = max(max(oldIset, commandIset), lastWorkingIset).coerceAtLeast(Tuning.MIN_ISET)
        lastWorkingIset = preCrashIset
        val probeIrefFraction = settings.probeRecoveryIsetFraction
        setSunReferenceFromCrash(
            preCrashIset,
            policyLimit,
            "pre-crash recall",
            probeIrefFraction
        )
        recoveryPhase = PHASE_WAIT_VIN
        pvMode = MODE_RECOVER
        recoveryWaitSinceMs = now
        probeRecallPending = false

        if (immediateProbeCrash) {
            scheduledRecoveryEpisode = true
        }
        if (immediateProbeCrash && !probeOneShotConsumed) {
            probeOneShotConsumed = true
            probeRecoveryActive = true
            probeCrashIset = oldIset.coerceAtLeast(Tuning.MIN_ISET)
            bumpKneeFromCrashVolts(settings, settings.fastProbeRecoveryKneeBackVolts, now)
            onEvent(
                "Probe recovery: knee +${"%.2f".format(settings.fastProbeRecoveryKneeBackVolts)}V -> " +
                    "${"%.2f".format(virtualKnee(settings))}V, recall ISET ${"%.2f".format(probeCrashIset)}A"
            )
        } else if (!immediateProbeCrash) {
            probeRecoveryActive = true
            probeCrashIset = oldIset.coerceAtLeast(Tuning.MIN_ISET)
            val cloudRetry = isRepeatedCollapse(now, settings)
            if (cloudRetry) {
                raiseKneeForCollapse(settings)
                onEvent("Cloud recovery retry: knee -> ${"%.2f".format(virtualKnee(settings))}V")
            } else {
                bumpKneeFromCrashVolts(settings, settings.cloudRecoveryKneeBackVolts, now)
                lastCollapseMs = now
                recentCollapseCount = 0
                onEvent(
                    "Cloud recovery: knee +${"%.2f".format(settings.cloudRecoveryKneeBackVolts)}V -> " +
                        "${"%.2f".format(virtualKnee(settings))}V, recall ISET ${"%.2f".format(probeCrashIset)}A"
                )
            }
        } else {
            probeRecoveryActive = false
            raiseKneeForCollapse(settings)
            onEvent("Cloud recovery: knee -> ${"%.2f".format(virtualKnee(settings))}V")
        }

        if (!scheduled) {
            onCollapseEntered(false, lastPreCollapseSnapshot)
        }

        val crashKind = classifyCrashKind(immediateProbeCrash)
        noteCrashEpisodeEntered(crashKind, immediateProbeCrash, now)

        lastAppliedHuntStepA = 0.0
        lastAppliedControlBand = "RE"
        return Tuning.MIN_ISET
    }

    /** WAIT at MIN, retry knee raises on bounce, handoff to tracking when Vin rebounded. */
    private fun recover(
        settings: AppSettings,
        oldIset: Double,
        vin: Double,
        vout: Double,
        outputWatts: Double,
        virtualKnee: Double,
        policyLimit: Double,
        socHoldActive: Boolean,
        now: Long
    ): Double {
        pvMode = MODE_RECOVER
        stableSinceMs = 0L
        lastAppliedHuntStepA = 0.0
        lastAppliedControlBand = "RE"

        if (socHoldActive) {
            return finishStuckRecovery(
                settings, vin, vout, outputWatts, virtualKnee, policyLimit, "battery policy hold", now
            )
        }

        if (isCollapsed(settings, vin)) {
            if (recoveryPhase != PHASE_WAIT_VIN) {
                raiseKneeForCollapse(settings)
                if (probeRecoveryActive) {
                    onEvent("Recovery retry: knee -> ${"%.2f".format(virtualKnee(settings))}V")
                }
            }
            recoveryPhase = PHASE_WAIT_VIN
            return recoveryMinOrAbort(settings, vin, vout, outputWatts, virtualKnee, policyLimit, now)
        }

        if (vin < recoveryExitFloor(settings)) {
            recoveryPhase = PHASE_WAIT_VIN
            return recoveryMinOrAbort(settings, vin, vout, outputWatts, virtualKnee, policyLimit, now)
        }

        recoveryWaitSinceMs = 0L

        if (probeRecoveryActive) {
            probeRecoveryActive = false
            recoveryPhase = PHASE_NONE
            scheduledRecoveryEpisode = false
            val recallFraction = settings.probeRecoveryIsetFraction
            val recall = clampPolicyIset(probeCrashIset * recallFraction, policyLimit)
            commandIset = recall
            setSunReferenceFromCrash(
                probeCrashIset,
                policyLimit,
                "crash recall",
                recallFraction
            )
            pvMode = MODE_TRACKING
            val recallPct = (recallFraction * 100.0).toInt()
            onEvent(
                "Recovery recall ISET ${"%.2f".format(recall)}A ($recallPct% of ${"%.2f".format(probeCrashIset)}A)"
            )
            updateStableRecoveryReset(true, settings, vin, virtualKnee, now)
            endScheduledDrillSession(now)
            return trackSolarKnee(settings, recall, vin, vout, outputWatts, virtualKnee, policyLimit, now)
        }

        recoveryPhase = PHASE_NONE
        useFixedAmpHunt = true
        onEvent("Cloud recovery handoff -> tracking")
        return trackSolarKnee(settings, oldIset, vin, vout, outputWatts, virtualKnee, policyLimit, now)
    }

    private fun recoveryMinOrAbort(
        settings: AppSettings,
        vin: Double,
        vout: Double,
        outputWatts: Double,
        virtualKnee: Double,
        policyLimit: Double,
        now: Long
    ): Double {
        if (recoveryWaitSinceMs == 0L) {
            recoveryWaitSinceMs = now
        }
        if (now - recoveryWaitSinceMs >= Tuning.RECOVERY_STUCK_TIMEOUT_MS) {
            return finishStuckRecovery(
                settings, vin, vout, outputWatts, virtualKnee, policyLimit, "Vin wait timeout", now
            )
        }
        return Tuning.MIN_ISET
    }

    private fun finishStuckRecovery(
        settings: AppSettings,
        vin: Double,
        vout: Double,
        outputWatts: Double,
        virtualKnee: Double,
        policyLimit: Double,
        reason: String,
        now: Long
    ): Double {
        probeRecoveryActive = false
        recoveryPhase = PHASE_NONE
        scheduledRecoveryEpisode = false
        recoveryWaitSinceMs = 0L
        useFixedAmpHunt = false
        val recall = if (probeCrashIset > Tuning.MIN_ISET) {
                clampPolicyIset(probeCrashIset * settings.probeRecoveryIsetFraction, policyLimit)
        } else {
            clampPolicyIset(
                max(max(lastWorkingIset, sunReferenceIset), Tuning.MIN_ISET),
                policyLimit
            )
        }
        onEvent("Recovery watchdog ($reason) -> ${"%.2f".format(recall)}A")
        endScheduledDrillSession(now)
        return trackSolarKnee(settings, recall, vin, vout, outputWatts, virtualKnee, policyLimit, now)
    }

    /** Normal solar path: optional sun probe, then knee tracking. */
    private fun handleSolarTick(
        settings: AppSettings,
        oldIset: Double,
        vin: Double,
        vout: Double,
        outputWatts: Double,
        virtualKnee: Double,
        policyLimit: Double,
        now: Long
    ): Double {
        updateMorningSunDetection(settings, vin, vout)

        if (sunProbeActive) {
            return tickSunProbe(settings, oldIset, vin, vout, outputWatts, virtualKnee, policyLimit, now)
        }

        if (!sunReferenceKnown) {
            val fast = coldStartFastAcquire
            coldStartFastAcquire = false
            startSunProbe(policyLimit, fastColdStart = fast)
            return tickSunProbe(settings, oldIset, vin, vout, outputWatts, virtualKnee, policyLimit, now)
        }

        return trackSolarKnee(settings, oldIset, vin, vout, outputWatts, virtualKnee, policyLimit, now)
    }

    /**
     * Pack-voltage hold with load follow: raise ISET so solar covers loads plus the mode's
     * trickle. Taper ISET when pack is over the setpoint. VSET stays at maxBatteryVolts.
     */
    private fun tickBmsVoltageHold(
        settings: AppSettings,
        oldIset: Double,
        packVoltage: Double,
        packCurrent: Double,
        ridenIout: Double,
        vin: Double,
        virtualKnee: Double,
        currentLimit: Double,
        holdVolts: Double,
        targetNetAmps: Double,
        now: Long
    ): Double {
        pvMode = MODE_BMS_V_HOLD
        lastAppliedControlBand = "VH"
        recoveryPhase = PHASE_NONE

        var desired = ridenIout + (targetNetAmps - packCurrent)
        val vErr = (packVoltage - holdVolts).coerceAtLeast(0.0)
        if (vErr > 0.0) {
            desired -= vErr * Tuning.BMS_V_HOLD_GAIN_A_PER_V
        }
        desired = clampPolicyIset(desired, currentLimit)
        val blended = oldIset + Tuning.SOC_HOLD_ISET_BLEND * (desired - oldIset)
        lastAppliedHuntStepA = abs(blended - oldIset)
        updateStableRecoveryReset(true, settings, vin, virtualKnee, now)
        if (!isCollapsed(settings, vin)) {
            lastWorkingIset = max(lastWorkingIset * 0.8 + blended * 0.2, Tuning.MIN_ISET)
        }
        return clampPolicyIset(blended, currentLimit)
    }

    private fun updateMorningSunDetection(settings: AppSettings, vin: Double, vout: Double) {
        if (vout > 0.0 && vin > 0.0 && abs(vin - vout) <= Tuning.OVERNIGHT_VIN_VOUT_EPS_V) {
            overnightVinNearVout = true
        }
        if (overnightVinNearVout && !sunReferenceKnown && vin >= lowKneeLimit(settings)) {
            morningMinimalSun = true
            sunReferenceIset = Tuning.MORNING_MIN_IREF_A
            sunReferenceKnown = true
            overnightVinNearVout = false
            onEvent("Morning sun: minimal Iref ${"%.2f".format(sunReferenceIset)}A")
        }
    }

    private fun startSunProbe(policyLimit: Double, fastColdStart: Boolean = false) {
        sunProbeActive = true
        sunProbeFastColdStart = fastColdStart
        sunProbeLadderIndex = if (fastColdStart) Tuning.SUN_PROBE_FAST_START_INDEX else 0
        sunProbeLastGoodIset = Tuning.MIN_ISET
        sunProbeSettleSinceMs = 0L
        pvMode = MODE_SUN_PROBE
        val startA = sunProbeLevelForIndex(sunProbeLadderIndex, policyLimit)
        onEvent(
            if (fastColdStart) {
                "Cold start hard-lock probe -> ${"%.2f".format(startA)}A"
            } else {
                "Sun probe started -> ${"%.2f".format(startA)}A"
            }
        )
    }

    private fun completeSunProbe(lastGood: Double, reason: String) {
        sunReferenceIset = quantAmps(lastGood.coerceAtLeast(Tuning.MIN_ISET))
        sunReferenceKnown = true
        sunProbeActive = false
        sunProbeFastColdStart = false
        pvMode = MODE_TRACKING
        onEvent("Sun probe done: Iref ${"%.2f".format(sunReferenceIset)}A ($reason)")
    }

    private fun tickSunProbe(
        settings: AppSettings,
        oldIset: Double,
        vin: Double,
        vout: Double,
        outputWatts: Double,
        virtualKnee: Double,
        policyLimit: Double,
        now: Long
    ): Double {
        pvMode = MODE_SUN_PROBE
        lastAppliedControlBand = "SP"
        lastAppliedHuntStepA = 0.0

        if (isCollapsed(settings, vin)) {
            if (sunProbeFastColdStart) {
                onEvent("Cold start hard-lock collapsed — falling back to morning sun probe")
                startSunProbe(policyLimit, fastColdStart = false)
                return sunProbeLevelForIndex(0, policyLimit)
            }
            completeSunProbe(sunProbeLastGoodIset.coerceAtLeast(Tuning.MIN_ISET), "collapse at ${"%.2f".format(oldIset)}A")
            val startIset = clampPolicyIset(sunReferenceIset, policyLimit)
            return trackSolarKnee(settings, startIset, vin, vout, outputWatts, virtualKnee, policyLimit, now)
        }

        val target = sunProbeLevelForIndex(sunProbeLadderIndex, policyLimit)
        if (quantAmps(oldIset) != target) {
            sunProbeSettleSinceMs = now
            lastAppliedHuntStepA = abs(target - oldIset)
            return target
        }

        val settleMs = if (sunProbeFastColdStart) {
            Tuning.SUN_PROBE_FAST_SETTLE_MS
        } else {
            Tuning.SUN_PROBE_SETTLE_MS
        }
        if (sunProbeSettleSinceMs == 0L) {
            sunProbeSettleSinceMs = now
        }
        if (now - sunProbeSettleSinceMs < settleMs) {
            return target
        }

        if (sunProbeStepGood(settings, vin, vout, outputWatts, target)) {
            sunProbeLastGoodIset = target
        }

        if (target >= policyLimit - 0.001) {
            completeSunProbe(sunProbeLastGoodIset.coerceAtLeast(Tuning.MIN_ISET), "policy limit")
            return trackSolarKnee(settings, sunReferenceIset, vin, vout, outputWatts, virtualKnee, policyLimit, now)
        }

        sunProbeLadderIndex += 1
        val next = sunProbeLevelForIndex(sunProbeLadderIndex, policyLimit)
        sunProbeSettleSinceMs = 0L
        lastAppliedHuntStepA = abs(next - target)
        return next
    }

    private fun sunProbeStepGood(
        settings: AppSettings,
        vin: Double,
        vout: Double,
        outputWatts: Double,
        iset: Double
    ): Boolean {
        if (isCollapsed(settings, vin)) return false
        if (iset <= Tuning.SUN_PROBE_LADDER_1) return true
        val pin = estimateInputPowerW(outputWatts, vin, vout)
        return outputWatts >= Tuning.SUN_PROBE_MIN_POUT_W || pin >= Tuning.SUN_PROBE_MIN_POUT_W
    }

    private fun sunProbeLevelForIndex(index: Int, policyLimit: Double): Double {
        val level = when (index) {
            0 -> Tuning.SUN_PROBE_LADDER_0
            1 -> Tuning.SUN_PROBE_LADDER_1
            2 -> Tuning.SUN_PROBE_LADDER_2
            3 -> Tuning.SUN_PROBE_LADDER_3
            else -> Tuning.SUN_PROBE_LADDER_3 + (index - 3) * Tuning.SUN_PROBE_RAMP_A
        }
        return clampPolicyIset(level, policyLimit)
    }

    private fun recoveryDropStep(oldIset: Double): Double {
        return max(Tuning.RECOVERY_DROP_MIN_STEP_A, oldIset * Tuning.RECOVERY_DROP_FRACTION)
    }

    /**
     * On enable, adopt Riden Vin as knee and Riden ISET as command when the hardware is already
     * in a valid loaded operating point (not cliff / not Voc-only at minimum current).
     */
    private fun tryAdoptRidenOperatingPoint(
        settings: AppSettings,
        vin: Double,
        ridenIset: Double?,
        outputWatts: Double,
        policyLimit: Double,
        now: Long
    ): Boolean {
        val iset = ridenIset ?: return false
        if (!ridenOperatingPointValid(settings, vin, iset, outputWatts, policyLimit)) {
            return false
        }

        val adoptedKnee = quantVolts(clampKnee(settings, vin))
        kneeOffsetVolts = adoptedKnee - settings.targetPvVolts
        commandIset = clampPolicyIset(iset, policyLimit)
        lastKneeProbeMs = now
        recentCollapseCount = 0
        lastCollapseMs = 0L
        resetCollapseState()
        sunReferenceIset = clampPolicyIset(iset, policyLimit)
        sunReferenceKnown = true
        morningMinimalSun = false
        sunProbeActive = false
        onEvent(
            "Riden takeover: knee ${"%.2f".format(adoptedKnee)}V, " +
                "ISET ${"%.2f".format(commandIset)}A"
        )
        return true
    }

    /** Use last valid operating point saved on disable (before output was turned off). */
    private fun tryAdoptCachedHandoff(
        settings: AppSettings,
        policyLimit: Double,
        now: Long
    ): Boolean {
        val vin = cachedHandoffVin ?: return false
        val iset = cachedHandoffIset ?: return false
        if (!tryAdoptRidenOperatingPoint(settings, vin, iset, cachedHandoffWatts, policyLimit, now)) {
            return false
        }
        onEvent("Riden takeover: used cached handoff snapshot")
        return true
    }

    private fun cacheHandoffIfValid(
        settings: AppSettings,
        vin: Double?,
        iset: Double?,
        watts: Double?,
        policyLimit: Double
    ) {
        val v = vin ?: return
        val i = iset ?: return
        val w = watts ?: 0.0
        if (!ridenOperatingPointValid(settings, v, i, w, policyLimit)) return
        cachedHandoffVin = v
        cachedHandoffIset = i
        cachedHandoffWatts = w
    }

    private fun ridenOperatingPointValid(
        settings: AppSettings,
        vin: Double,
        iset: Double,
        outputWatts: Double,
        policyLimit: Double
    ): Boolean {
        if (vin <= 0.0 || iset < Tuning.TAKEOVER_MIN_ISET_A) return false
        if (iset > policyLimit + Tuning.TAKEOVER_ISET_SLACK_A) return false
        if (isCollapsed(settings, vin)) return false
        if (vin < lowKneeLimit(settings) || vin > highKneeLimit(settings)) return false
        if (outputWatts < Tuning.TAKEOVER_MIN_POUT_W && iset < Tuning.TAKEOVER_MIN_ISET_A * 2.0) return false
        return true
    }

    private fun resetSunReferenceState() {
        sunReferenceIset = Tuning.MIN_ISET
        sunReferenceKnown = false
        morningMinimalSun = false
        resetSunProbeState()
        overnightVinNearVout = false
    }

    private fun resetSunProbeState() {
        sunProbeActive = false
        sunProbeLadderIndex = 0
        sunProbeLastGoodIset = Tuning.MIN_ISET
        sunProbeSettleSinceMs = 0L
        sunProbeFastColdStart = false
    }

    private fun setSunReferenceFromCrash(
        preCrashIset: Double,
        policyLimit: Double,
        label: String,
        irefFraction: Double
    ) {
        sunReferenceIset = quantAmps(
            clampPolicyIset(preCrashIset * irefFraction, policyLimit)
        )
        sunReferenceKnown = true
        morningMinimalSun = false
        sunProbeActive = false
        onEvent("Sun Iref ${"%.2f".format(sunReferenceIset)}A ($label)")
    }

    private fun expectedProbeCollapseWindowMs(settings: AppSettings): Long {
        return Tuning.ACQUISITION_MAX_PROBE_CYCLE_MS + Tuning.EXPECTED_PROBE_COLLAPSE_EXTRA_MS
    }

    private fun trackSolarKnee(
        settings: AppSettings,
        oldIset: Double,
        vin: Double,
        vout: Double,
        outputWatts: Double,
        virtualKnee: Double,
        policyLimit: Double,
        now: Long
    ): Double {
        pvMode = MODE_TRACKING
        recoveryPhase = PHASE_NONE

        if (oldIset <= Tuning.MIN_ISET &&
            !isCollapsed(settings, vin) &&
            kneeOffsetVolts > 0.0 &&
            trackingFloorSinceMs != 0L &&
            now - trackingFloorSinceMs >= Tuning.TRACKING_MIN_STUCK_TIMEOUT_MS
        ) {
            kneeOffsetVolts = clampKnee(
                settings,
                settings.targetPvVolts + kneeOffsetVolts - settings.kneeStepVolts
            ) - settings.targetPvVolts
            lastKneeProbeMs = now
            trackingFloorSinceMs = now
            onEvent(
                "Tracking floor watchdog: knee -${"%.2f".format(settings.kneeStepVolts)}V -> " +
                    "${"%.2f".format(virtualKnee)}V"
            )
        } else if (oldIset > Tuning.MIN_ISET) {
            trackingFloorSinceMs = 0L
        } else if (trackingFloorSinceMs == 0L) {
            trackingFloorSinceMs = now
        }

        val inputPowerW = estimateInputPowerW(outputWatts, vin, vout)
        if (!settings.powerBasedVtuneStop && vtuneDescentBlocked) {
            vtuneDescentBlocked = false
            if (probePacingPhase == ProbePacingPhase.BLOCKED) {
                probePacingPhase = ProbePacingPhase.IDLE
            }
        }
        updateProbePacing(settings, vin, virtualKnee, inputPowerW, now)
        val currentLimited = isAtPolicyCurrentLimit(oldIset, policyLimit)
        if (currentLimited) {
            suspendVtuneDescentWhileCurrentLimited(now)
        } else {
            maybeVtuneDownProbe(settings, inputPowerW, now)
        }
        updateStableRecoveryReset(true, settings, vin, virtualKnee, now)
        if (vin >= virtualKnee - Tuning.RECOVERY_STABLE_ERR_V) {
            lastWorkingIset = max(lastWorkingIset * 0.8 + oldIset * 0.2, Tuning.MIN_ISET)
        }

        return vinKneeHuntIset(oldIset, vin, virtualKnee, policyLimit, now)
    }

    /** Single ISET hunt: FI fixed 0.01 A; fixed-amp table after cloud handoff; else Iref-scaled percent. */
    private fun vinKneeHuntIset(
        oldIset: Double,
        vin: Double,
        virtualKnee: Double,
        policyLimit: Double,
        now: Long
    ): Double {
        val error = vin - virtualKnee
        if (error == 0.0) {
            lastAppliedControlBand = "FI"
            lastAppliedHuntStepA = 0.0
            return oldIset
        }

        val absErr = abs(error)
        val direction = if (error > 0.0) Direction.Up else Direction.Down
        val step = resolveHuntStep(absErr, oldIset, direction, policyLimit)
        lastAppliedControlBand = when {
            useFixedAmpHunt -> bandFor(error)
            else -> bandFor(error, allowHuge = hugeBandAllowed())
        }
        lastAppliedHuntStepA = step
        val next = if (direction == Direction.Up) oldIset + step else oldIset - step
        return clampPolicyIset(next, policyLimit)
    }

    private fun hugeBandAllowed(): Boolean {
        return sunReferenceKnown && !morningMinimalSun && !useFixedAmpHunt
    }

    private fun resolveHuntStep(
        absErr: Double,
        iset: Double,
        direction: Direction,
        policyLimit: Double
    ): Double {
        if (absErr <= Tuning.V1) {
            return Tuning.FINE_STEP_A
        }

        if (useFixedAmpHunt) {
            val step = fixedAmpStepForAbsError(
                absErr,
                max(iset, lastWorkingIset).coerceAtLeast(Tuning.MIN_ISET),
                direction
            )
            if (absErr <= Tuning.V2) {
                useFixedAmpHunt = false
            }
            return step
        }

        return scaledStepForAbsError(absErr, policyLimit, allowHuge = hugeBandAllowed())
    }

    /** Legacy fixed-amp hunt table (used briefly after cloud recovery handoff). */
    private fun fixedAmpStepForAbsError(absErr: Double, iset: Double, direction: Direction): Double {
        val baseStep = when {
            absErr > Tuning.V4 -> Tuning.HUGE_STEP_A
            absErr > Tuning.V3 -> Tuning.FAR_STEP_A
            absErr > Tuning.V2 -> Tuning.MID_STEP_A
            absErr > Tuning.V1 -> Tuning.NEAR_STEP_A
            else -> Tuning.FINE_STEP_A
        }
        val multiplier = ((iset / 10.0) * Tuning.ISET_STEP_MULTIPLIER_AT_10A).coerceAtLeast(1.0)
        val scaled = baseStep * multiplier
        return when (direction) {
            Direction.Up -> scaled.coerceAtMost(Tuning.MAX_STEP_UP_A)
            Direction.Down -> scaled.coerceAtMost(Tuning.MAX_STEP_DOWN_A)
        }
    }

    private fun estimateInputPowerW(outputWatts: Double, vin: Double, vout: Double): Double {
        if (outputWatts <= 0.0 || vin <= 0.0 || vout <= 0.0) return 0.0
        return outputWatts * (vin / vout)
    }

    private fun updatePastMppObservation(
        iset: Double,
        poutW: Double,
        vin: Double,
        virtualKnee: Double,
        now: Long
    ) {
        pastMppWrongWay = false
        pastMppVinBelowKneeV = 0.0
        pastMppIsetDeltaA = 0.0
        pastMppPoutDeltaW = 0.0
        pastMppMissedW = 0.0

        if (pvMode != MODE_TRACKING || poutW < Tuning.PAST_MPP_MIN_POUT_W || iset <= Tuning.MIN_ISET) {
            pastMppActive = false
            wasPastMppActive = false
            lastObsMs = 0L
            return
        }

        pastMppVinBelowKneeV = max(0.0, virtualKnee - vin)

        if (lastObsMs > 0L && now - lastObsMs >= Tuning.PAST_MPP_OBS_MIN_MS) {
            pastMppIsetDeltaA = iset - lastObsIset
            pastMppPoutDeltaW = poutW - lastObsPoutW
            pastMppWrongWay = (pastMppIsetDeltaA > Tuning.MIN_ISET &&
                pastMppPoutDeltaW < -Tuning.PAST_MPP_POUT_EPS_W) ||
                (pastMppIsetDeltaA < -Tuning.MIN_ISET &&
                    pastMppPoutDeltaW > Tuning.PAST_MPP_POUT_EPS_W)
            if (pastMppWrongWay) {
                pastMppMissedW = abs(pastMppPoutDeltaW)
            }
        }

        lastObsIset = iset
        lastObsPoutW = poutW
        lastObsMs = now

        pastMppActive = pastMppVinBelowKneeV > 0.0 || pastMppWrongWay

        val episodeStart = pastMppActive && !wasPastMppActive
        val debounced = now - lastPastMppEventMs >= Tuning.PAST_MPP_EVENT_DEBOUNCE_MS
        if (pastMppActive && (episodeStart || debounced)) {
            lastPastMppEventMs = now
            pastMppEpisodeCount += 1
            if (pastMppWrongWay) {
                pastMppCumulativeMissedW += pastMppMissedW
            }
            val reason = when {
                pastMppWrongWay && pastMppVinBelowKneeV > 0.0 -> "wrong-way+vin-below-knee"
                pastMppWrongWay -> "wrong-way"
                else -> "vin-below-knee"
            }
            onEvent(
                "Past MPP [$reason]: missed ~${"%.1f".format(pastMppMissedW)}W " +
                    "(dP ${"%.1f".format(pastMppPoutDeltaW)}W dI ${"%.2f".format(pastMppIsetDeltaA)}A " +
                    "vinBelow ${"%.2f".format(pastMppVinBelowKneeV)}V " +
                    "Pout ${"%.0f".format(poutW)}W ISET ${"%.1f".format(iset)}A)"
            )
        }
        wasPastMppActive = pastMppActive
    }

    private fun resetPastMppObservation() {
        lastObsMs = 0L
        pastMppActive = false
        wasPastMppActive = false
        pastMppWrongWay = false
        pastMppVinBelowKneeV = 0.0
        pastMppIsetDeltaA = 0.0
        pastMppPoutDeltaW = 0.0
        pastMppMissedW = 0.0
    }

    private fun updateProbePacing(
        settings: AppSettings,
        vin: Double,
        virtualKnee: Double,
        inputPowerW: Double,
        now: Long
    ) {
        when (probePacingPhase) {
            ProbePacingPhase.WAIT_HUNT_LOCK -> {
                if (abs(vin - virtualKnee) <= Tuning.V1) {
                    huntLockConsecutiveTicks += 1
                } else {
                    huntLockConsecutiveTicks = 0
                }
                val locked = huntLockConsecutiveTicks >= Tuning.HUNT_LOCK_TICKS
                val timedOut = now - probeWaitSinceMs >= Tuning.MAX_HUNT_LOCK_WAIT_MS
                if (locked || timedOut) {
                    probePacingPhase = ProbePacingPhase.PAUSE
                    probePauseUntilMs = now + Tuning.ACQUISITION_PAUSE_MS
                    huntLockConsecutiveTicks = 0
                }
            }
            ProbePacingPhase.PAUSE -> {
                if (now >= probePauseUntilMs) {
                    if (awaitingPowerCheck) {
                        evaluateProbePower(settings, inputPowerW)
                        awaitingPowerCheck = false
                    }
                    probePacingPhase = ProbePacingPhase.IDLE
                    if (!scheduledDrillActive) {
                        kneeProbeFast = false
                    }
                }
            }
            ProbePacingPhase.IDLE, ProbePacingPhase.BLOCKED -> Unit
        }
    }

    private fun isAtPolicyCurrentLimit(iset: Double, policyLimit: Double): Boolean {
        if (policyLimit <= Tuning.MIN_ISET) return false
        return iset >= policyLimit - Tuning.CURRENT_LIMIT_EPS_A
    }

    /** Battery/policy current pegged — knee down-probes cannot reveal useful MPPT info. */
    private fun suspendVtuneDescentWhileCurrentLimited(now: Long) {
        endScheduledDrillSession(now, "current limited")
        if (probePacingPhase != ProbePacingPhase.IDLE || pendingVtuneDownProbe) {
            pendingVtuneDownProbe = false
            awaitingPowerCheck = false
            probePacingPhase = ProbePacingPhase.IDLE
            huntLockConsecutiveTicks = 0
        }
    }

    private fun maybeVtuneDownProbe(settings: AppSettings, inputPowerW: Double, now: Long) {
        if (vtuneDescentBlocked) {
            if (scheduledDrillActive) {
                endScheduledDrillSession(now, "descent blocked")
            }
            return
        }
        if (scheduledRecoveryEpisode) return
        if (probePacingPhase != ProbePacingPhase.IDLE) return

        if (scheduledDrillActive) {
            if (now - lastVtuneDownProbeMs < Tuning.MIN_FAST_PROBE_SPACING_MS) return
            executeVtuneDownProbe(settings, inputPowerW, now)
            return
        }

        if (now - lastDrillSessionEndMs < scheduledProbeDelayMs(settings)) return
        startScheduledDrill(settings, inputPowerW, now)
    }

    private fun startScheduledDrill(settings: AppSettings, inputPowerW: Double, now: Long) {
        scheduledDrillActive = true
        kneeProbeFast = true
        vtuneDescentBlocked = false
        resetProbePacingState(clearPowerBaseline = true)
        if (inputPowerW > 0.0) {
            acceptedProbeInputPowerW = inputPowerW
        }
        onEvent("Scheduled drill-down started")
        executeVtuneDownProbe(settings, inputPowerW, now)
    }

    private fun scheduledProbeDelayMs(settings: AppSettings): Long {
        return (effectiveKneeDelaySeconds(settings) * 1000.0).toLong()
    }

    private fun executeVtuneDownProbe(
        settings: AppSettings,
        inputPowerW: Double,
        now: Long
    ) {
        lastVtuneDownProbeMs = now
        pendingVtuneDownProbe = true
        kneeProbeFast = true
        kneeOffsetVolts = clampKnee(settings, settings.targetPvVolts + kneeOffsetVolts - settings.kneeStepVolts) -
            settings.targetPvVolts

        probePacingPhase = ProbePacingPhase.WAIT_HUNT_LOCK
        probeWaitSinceMs = now
        huntLockConsecutiveTicks = 0
        awaitingPowerCheck = true
    }

    private fun evaluateProbePower(settings: AppSettings, inputPowerW: Double) {
        if (inputPowerW <= 0.0) return
        if (!settings.powerBasedVtuneStop) {
            if (acceptedProbeInputPowerW <= 0.0) {
                acceptedProbeInputPowerW = inputPowerW
            } else {
                acceptedProbeInputPowerW = acceptedProbeInputPowerW * 0.5 + inputPowerW * 0.5
            }
            return
        }
        if (acceptedProbeInputPowerW <= 0.0) {
            acceptedProbeInputPowerW = inputPowerW
            return
        }
        if (powerDroppedAfterProbe(inputPowerW, acceptedProbeInputPowerW)) {
            val baseline = acceptedProbeInputPowerW
            revertLastVtuneStep(settings, System.currentTimeMillis())
            vtuneDescentBlocked = true
            probePacingPhase = ProbePacingPhase.BLOCKED
            endScheduledDrillSession(System.currentTimeMillis(), "power stop")
            onEvent(
                "VTune power stop: Pin ${"%.0f".format(inputPowerW)}W < baseline " +
                    "${"%.0f".format(baseline)}W, reverted"
            )
            return
        }
        acceptedProbeInputPowerW = acceptedProbeInputPowerW * 0.5 + inputPowerW * 0.5
    }

    private fun powerDroppedAfterProbe(currentPinW: Double, baselinePinW: Double): Boolean {
        if (currentPinW <= 0.0 || baselinePinW <= 0.0) return false
        val epsilon = max(
            Tuning.POWER_PROBE_MIN_EPS_W,
            baselinePinW * Tuning.POWER_PROBE_EPS_FRACTION
        )
        return currentPinW < baselinePinW - epsilon
    }

    private fun revertLastVtuneStep(settings: AppSettings, now: Long) {
        kneeOffsetVolts = clampKnee(settings, settings.targetPvVolts + kneeOffsetVolts + settings.kneeStepVolts) -
            settings.targetPvVolts
        pendingVtuneDownProbe = false
        awaitingPowerCheck = false
        probePacingPhase = ProbePacingPhase.IDLE
    }

    private fun kneeDelayBounds(settings: AppSettings): Pair<Double, Double> {
        val min = settings.kneeTrackingDelayMinSeconds.coerceAtMost(settings.kneeTrackingDelayMaxSeconds)
        val max = settings.kneeTrackingDelayMaxSeconds.coerceAtLeast(min)
        return min to max
    }

    private fun effectiveKneeDelaySeconds(settings: AppSettings): Double {
        return kneeDelayBounds(settings).first
    }

    private fun isCollapsed(settings: AppSettings, vin: Double): Boolean {
        val collapseFloor = lowKneeLimit(settings) - Tuning.COLLAPSE_FLOOR_EXTRA_MARGIN_V
        return vin > 0.0 && vin < collapseFloor
    }

    private fun recoveryExitFloor(settings: AppSettings): Double = lowKneeLimit(settings)

    private fun stepFor(vinError: Double, policyLimit: Double): Double {
        if (pvMode == MODE_RECOVER || pvMode == MODE_SUN_PROBE || vinError == 0.0) return 0.0
        val direction = if (vinError > 0.0) Direction.Up else Direction.Down
        return scaledStepForAbsError(abs(vinError), policyLimit, allowHuge = hugeBandAllowed())
    }

    private fun effectiveIref(policyLimit: Double): Double {
        return if (sunReferenceKnown) {
            clampPolicyIset(sunReferenceIset, policyLimit)
        } else {
            max(lastWorkingIset, Tuning.MIN_ISET).coerceAtMost(policyLimit)
        }
    }

    private fun scaledStepForAbsError(absErr: Double, policyLimit: Double, allowHuge: Boolean): Double {
        if (absErr <= Tuning.V1) {
            return Tuning.FINE_STEP_A
        }
        val iref = effectiveIref(policyLimit)
        val pct = bandStepPct(absErr, allowHuge)
        val step = isetStepFromPct(iref, pct)
        val capPct = if (allowHuge) Tuning.MAX_STEP_PCT else Tuning.STEP_PCT_FA
        return step.coerceAtMost(isetStepFromPct(iref, capPct))
    }

    private fun bandStepPct(absErr: Double, allowHuge: Boolean): Double = when {
        absErr > Tuning.V4 -> if (allowHuge) Tuning.STEP_PCT_HU else Tuning.STEP_PCT_FA
        absErr > Tuning.V3 -> Tuning.STEP_PCT_FA
        absErr > Tuning.V2 -> Tuning.STEP_PCT_MD
        else -> Tuning.STEP_PCT_NE
    }

    private fun isetStepFromPct(iset: Double, pct: Double): Double {
        val raw = iset.coerceAtLeast(0.0) * pct / 100.0
        return raw.coerceAtLeast(Tuning.FINE_STEP_A)
    }

    private fun isRepeatedCollapse(now: Long, settings: AppSettings): Boolean {
        return lastCollapseMs > 0L && now - lastCollapseMs <= repeatedCollapseWindowMs(settings)
    }

    private fun bumpKneeFromCrashVolts(settings: AppSettings, backVolts: Double, now: Long) {
        val crashKnee = virtualKnee(settings)
        kneeOffsetVolts = clampKnee(settings, crashKnee + backVolts) - settings.targetPvVolts
        lastKneeProbeMs = now
        vtuneDescentBlocked = false
        probePacingPhase = ProbePacingPhase.IDLE
    }

    private fun raiseKneeForCollapse(settings: AppSettings) {
        val now = System.currentTimeMillis()
        recentCollapseCount = if (now - lastCollapseMs <= repeatedCollapseWindowMs(settings)) {
            (recentCollapseCount + 1).coerceAtMost(Tuning.MAX_COLLAPSE_STEP_MULTIPLIER)
        } else {
            1
        }
        lastCollapseMs = now
        val step = (settings.kneeStepVolts * recentCollapseCount).coerceAtMost(maxCollapseStepVolts(settings))
        val previousOffset = kneeOffsetVolts
        kneeOffsetVolts = clampKnee(settings, settings.targetPvVolts + kneeOffsetVolts + step) -
            settings.targetPvVolts
        if (kneeOffsetVolts > previousOffset) {
            lastKneeProbeMs = now
            vtuneDescentBlocked = false
            probePacingPhase = ProbePacingPhase.IDLE
        }
    }

    private fun maxCollapseStepVolts(settings: AppSettings): Double {
        val fullWindow = (highKneeLimit(settings) - lowKneeLimit(settings)).coerceAtLeast(0.0)
        return (round((fullWindow / 6.0) * 10.0) / 10.0).coerceAtLeast(settings.kneeStepVolts)
    }

    private fun repeatedCollapseWindowMs(settings: AppSettings): Long {
        return (effectiveKneeDelaySeconds(settings) * 750.0).toLong().coerceIn(2_000L, 10_000L)
    }

    private fun classifyCrashKind(immediateProbeCrash: Boolean): String {
        if (!immediateProbeCrash) return CRASH_KIND_CLOUD
        return CRASH_KIND_FAST_PROBE
    }

    private fun noteCrashEpisodeEntered(kind: String, kneeProbeFastAtCrash: Boolean, now: Long) {
        if (crashLogActive) {
            crashLogRowKind = CRASH_KIND_RECOLLAPSE
            crashLogPhase = CRASH_LOG_RECOVERING
            crashLogStableTicks = 0
            crashLogTailTicks = 0
            return
        }
        crashLogEpisodeKind = kind
        crashLogRowKind = kind
        crashLogEpisodeStartMs = now
        crashLogPreCrash = lastPreCollapseSnapshot
        crashLogKneeProbeFastAtEntry = kneeProbeFastAtCrash
        crashLogEpisodeId = onCrashEpisodeStart(kind, lastPreCollapseSnapshot, now)
        crashLogActive = true
        crashLogPhase = CRASH_LOG_RECOVERING
        crashLogStableTicks = 0
        crashLogTailTicks = 0
        crashLogEpisodeStartPending = true
        lastCrashLoggedRidenSeq = -1L
    }

    private fun emitCrashLogTickIfActive(
        settings: AppSettings,
        riden: RidenTelemetry,
        virtualKnee: Double,
        policyLimit: Double,
        vinError: Double,
        now: Long
    ) {
        if (!crashLogActive) return

        val vin = riden.vin ?: 0.0
        val episodeStart = crashLogEpisodeStartPending
        crashLogEpisodeStartPending = false
        val preCrash = if (episodeStart) crashLogPreCrash else null

        if (preCrash != null && preCrash.vinV != null) {
            val preVtran = preCrash.vtranV ?: virtualKnee
            val preVin = preCrash.vinV ?: vin
            onCrashLogSample(
                CrashLogSample(
                    timestampMs = preCrash.timestampMs,
                    episodeId = crashLogEpisodeId,
                    msSinceEpisode = preCrash.timestampMs - crashLogEpisodeStartMs,
                    crashKind = crashLogRowKind,
                    episodeKind = crashLogEpisodeKind,
                    episodeStart = true,
                    preIoutA = preCrash.ioutA,
                    preVoutV = preCrash.voutV,
                    preVinV = preCrash.vinV,
                    preVtranV = preCrash.vtranV,
                    ioutA = preCrash.ioutA,
                    voutV = preCrash.voutV,
                    vinV = preVin,
                    vtranV = preVtran,
                    vinErrorV = preVin - preVtran,
                    commandIsetA = commandIset,
                    ridenIsetA = riden.iset,
                    pvMode = pvMode,
                    recoveryPhase = recoveryPhase,
                    poutW = riden.watts ?: 0.0,
                    pinEstW = lastEstimatedPinW,
                    kneeOffsetV = kneeOffsetVolts,
                    recoveryCycleCount = recoveryCycleCount,
                    controlBand = lastAppliedControlBand,
                    controlStepA = lastAppliedHuntStepA,
                    policyLimitA = policyLimit,
                    probeCrashIsetA = probeCrashIset,
                    probeRecoveryActive = probeRecoveryActive,
                    kneeProbeFast = crashLogKneeProbeFastAtEntry
                )
            )
        }

        onCrashLogSample(
            CrashLogSample(
                timestampMs = now,
                episodeId = crashLogEpisodeId,
                msSinceEpisode = (now - crashLogEpisodeStartMs).coerceAtLeast(0L),
                crashKind = crashLogRowKind,
                episodeKind = crashLogEpisodeKind,
                episodeStart = preCrash == null && episodeStart,
                preIoutA = preCrash?.ioutA,
                preVoutV = preCrash?.voutV,
                preVinV = preCrash?.vinV,
                preVtranV = preCrash?.vtranV,
                ioutA = riden.iout,
                voutV = riden.vout,
                vinV = vin,
                vtranV = virtualKnee,
                vinErrorV = vinError,
                commandIsetA = commandIset,
                ridenIsetA = riden.iset,
                pvMode = pvMode,
                recoveryPhase = recoveryPhase,
                poutW = riden.watts ?: 0.0,
                pinEstW = lastEstimatedPinW,
                kneeOffsetV = kneeOffsetVolts,
                recoveryCycleCount = recoveryCycleCount,
                controlBand = lastAppliedControlBand,
                controlStepA = lastAppliedHuntStepA,
                policyLimitA = policyLimit,
                probeCrashIsetA = probeCrashIset,
                probeRecoveryActive = probeRecoveryActive,
                kneeProbeFast = crashLogKneeProbeFastAtEntry
            )
        )
        crashLogRowKind = crashLogEpisodeKind

        val tracking = pvMode == MODE_TRACKING && recoveryPhase == PHASE_NONE
        val stable = tracking &&
            vin >= recoveryExitFloor(settings) &&
            abs(vin - virtualKnee) <= Tuning.RECOVERY_STABLE_ERR_V

        when (crashLogPhase) {
            CRASH_LOG_RECOVERING -> {
                if (stable) {
                    crashLogPhase = CRASH_LOG_STABLE_COUNT
                    crashLogStableTicks = 1
                }
            }
            CRASH_LOG_STABLE_COUNT -> {
                if (!stable) {
                    crashLogPhase = CRASH_LOG_RECOVERING
                    crashLogStableTicks = 0
                } else {
                    crashLogStableTicks += 1
                    if (crashLogStableTicks >= Tuning.CRASH_LOG_STABLE_CYCLES) {
                        crashLogPhase = CRASH_LOG_TAIL
                        crashLogTailTicks = 0
                    }
                }
            }
            CRASH_LOG_TAIL -> {
                if (!stable) {
                    crashLogPhase = CRASH_LOG_RECOVERING
                    crashLogStableTicks = 0
                    crashLogTailTicks = 0
                } else {
                    crashLogTailTicks += 1
                    if (crashLogTailTicks >= Tuning.CRASH_LOG_TAIL_CYCLES) {
                        endCrashLogging()
                    }
                }
            }
        }
    }

    private fun endCrashLogging() {
        crashLogActive = false
        crashLogPhase = CRASH_LOG_IDLE
        crashLogStableTicks = 0
        crashLogTailTicks = 0
        crashLogEpisodeStartPending = false
        crashLogPreCrash = null
        lastCrashLoggedRidenSeq = 0L
    }

    private fun updateStableRecoveryReset(
        tracking: Boolean,
        settings: AppSettings,
        vin: Double,
        virtualKnee: Double,
        now: Long
    ) {
        if (recoveryCycleCount == 0) return

        val stable = tracking &&
            vin >= recoveryExitFloor(settings) &&
            abs(vin - virtualKnee) <= Tuning.RECOVERY_STABLE_ERR_V

        if (!stable) {
            stableSinceMs = 0L
            return
        }

        if (stableSinceMs == 0L) stableSinceMs = now
        if (now - stableSinceMs >= stableRecoveryResetMs(settings)) {
            recoveryCycleCount = 0
            recentCollapseCount = 0
            probeOneShotConsumed = false
            scheduledRecoveryEpisode = false
            stableSinceMs = 0L
        }
    }

    private fun stableRecoveryResetMs(settings: AppSettings): Long {
        return repeatedCollapseWindowMs(settings)
    }

    private fun statusFor(
        balanceDay: Boolean,
        socReached: Boolean,
        voltageLimited: Boolean,
        bmsVoltageHoldActive: Boolean = false,
        holdVolts: Double = 0.0
    ): String {
        return when {
            pvMode == MODE_RECOVER -> "Solar recovery: $recoveryPhase"
            pvMode == MODE_SUN_PROBE -> "Sun capability probe"
            bmsVoltageHoldActive -> "Hold ${"%.2f".format(holdVolts)}V"
            balanceDay -> "Balance day: SOC target 100%"
            socReached -> "SOC and hold voltage reached"
            voltageLimited -> "Controller voltage limit"
            else -> "Tracking solar knee" + if (scheduledDrillActive || kneeProbeFast) " (drill down)" else ""
        }
    }

    private fun statusForBmsOffline(voltageLimited: Boolean, chargeVoltageLimit: Double): String {
        val limitLabel = "%.2f".format(chargeVoltageLimit)
        return when {
            pvMode == MODE_RECOVER -> "Solar recovery (BMS offline): $recoveryPhase"
            pvMode == MODE_SUN_PROBE -> "Sun probe (BMS offline)"
            voltageLimited -> "BMS offline: at ${limitLabel}V limit"
            else -> "BMS offline: tracking to ${limitLabel}V max"
        }
    }

    private fun updateBmsOfflineTransition(bmsReady: Boolean, settings: AppSettings) {
        if (!bmsReady && !bmsOfflineActive) {
            bmsOfflineActive = true
            onEvent("BMS offline: voltage fallback ${"%.2f".format(settings.bmsOfflineMaxBatteryVolts)}V")
        } else if (bmsReady && bmsOfflineActive) {
            bmsOfflineActive = false
            onEvent("BMS online: resuming SOC regulation")
        }
    }

    private fun ensureOutputAndVoltage(settings: AppSettings, chargeVoltageLimit: Double) {
        if (!outputEnabled) {
            setOutput(true)
            outputEnabled = true
        }
        if (abs(lastVset - chargeVoltageLimit) >= Tuning.VSET_UPDATE_EPS) {
            setVset(chargeVoltageLimit)
            lastVset = chargeVoltageLimit
        }
    }

    private fun currentIset(ridenIset: Double?): Double {
        return if (commandIset > 0.0) commandIset else (ridenIset ?: Tuning.MIN_ISET).coerceAtLeast(Tuning.MIN_ISET)
    }

    private fun idle(settings: AppSettings, riden: RidenTelemetry, status: String) {
        bmsOfflineActive = false
        val policyLimit = settings.maxChargeAmps.coerceAtLeast(Tuning.MIN_ISET)
        cacheHandoffIfValid(settings, riden.vin, riden.iset, riden.watts, policyLimit)
        if (outputEnabled) {
            setOutput(false)
            outputEnabled = false
        }
        commandIset = 0.0
        recoveryCycleCount = 0
        recentCollapseCount = 0
        stableSinceMs = 0L
        resetFastAcquireState()
        lastDrillSessionEndMs = System.currentTimeMillis()
        resetPastMppObservation()
        resetCollapseState()
        resetSunReferenceState()
        probeOneShotConsumed = false
        needsRidenTakeover = true
        takeoverAttemptUntilMs = 0L
        pvMode = MODE_IDLE
        endCrashLogging()
        publish(
            settings,
            status,
            0.0,
            "--",
            socTargetPercent = if (isBalanceDay(settings)) 100 else settings.normalSocCeilingPercent
        )
    }

    private fun inhibitCharging(settings: AppSettings, riden: RidenTelemetry, status: String, socTargetPercent: Int) {
        val policyLimit = settings.maxChargeAmps.coerceAtLeast(Tuning.MIN_ISET)
        cacheHandoffIfValid(settings, riden.vin, riden.iset, riden.watts, policyLimit)
        if (outputEnabled) {
            setOutput(false)
            outputEnabled = false
        }
        commandIset = 0.0
        stableSinceMs = 0L
        needsRidenTakeover = true
        takeoverAttemptUntilMs = 0L
        pvMode = MODE_ALARM
        endCrashLogging()
        publish(settings, status, 0.0, "--", socTargetPercent = socTargetPercent)
    }

    private fun updateAlarmBackoff(now: Long) {
        if (!alarmWasActive) {
            alarmBackoffMs = if (lastAlarmStartMs > 0L && now - lastAlarmStartMs <= Tuning.ALARM_REPEAT_WINDOW_MS) {
                (alarmBackoffMs * 2).coerceAtMost(Tuning.ALARM_MAX_HOLD_MS)
            } else {
                Tuning.ALARM_BASE_HOLD_MS
            }
            lastAlarmStartMs = now
        }
        alarmWasActive = true
        alarmHoldUntilMs = now + alarmBackoffMs
    }

    private fun isBalanceDay(settings: AppSettings): Boolean {
        val epochDay = currentEpochDay()
        return settings.balanceEveryDays > 0 &&
            (settings.lastBalanceEpochDay == epochDay ||
                settings.lastBalanceEpochDay <= 0L ||
                epochDay - settings.lastBalanceEpochDay >= settings.balanceEveryDays)
    }

    private fun currentEpochDay(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis / 86_400_000L
    }

    private fun virtualKnee(settings: AppSettings): Double {
        return quantVolts(clampKnee(settings, settings.targetPvVolts + kneeOffsetVolts))
    }

    private fun clampKnee(settings: AppSettings, volts: Double): Double {
        return volts.coerceIn(lowKneeLimit(settings), highKneeLimit(settings))
    }

    /** Upper charge bound; never below MIN_ISET so coerceIn cannot see an empty range. */
    private fun effectivePolicyLimit(policyLimit: Double): Double =
        max(policyLimit, Tuning.MIN_ISET)

    private fun clampPolicyIset(value: Double, policyLimit: Double): Double =
        quantAmps(value.coerceIn(Tuning.MIN_ISET, effectivePolicyLimit(policyLimit)))

    private fun lowKneeLimit(settings: AppSettings): Double =
        minOf(settings.minTargetPvVolts, settings.maxTargetPvVolts)

    private fun highKneeLimit(settings: AppSettings): Double =
        maxOf(settings.minTargetPvVolts, settings.maxTargetPvVolts)

    private fun bandFor(vinError: Double, allowHuge: Boolean = hugeBandAllowed()): String {
        val absErr = abs(vinError)
        return when {
            pvMode == MODE_RECOVER -> "RE"
            pvMode == MODE_SUN_PROBE -> "SP"
            absErr > Tuning.V4 -> if (allowHuge) "HU" else "FA"
            absErr > Tuning.V3 -> "FA"
            absErr > Tuning.V2 -> "MD"
            absErr > Tuning.V1 -> "NE"
            else -> "FI"
        }
    }

    private fun publish(
        settings: AppSettings,
        status: String,
        policyLimit: Double,
        band: String,
        stepAmps: Double = 0.0,
        vinError: Double = 0.0,
        socTargetPercent: Int = settings.normalSocCeilingPercent
    ) {
        val targetPv = virtualKnee(settings)
        onState(
            MpptControlState(
                enabled = settings.controllerEnabled,
                pvMode = pvMode,
                status = status,
                targetChargeCurrent = policyLimit,
                commandIset = commandIset,
                targetPvVolts = targetPv,
                kneeOffsetVolts = kneeOffsetVolts,
                vinErrorVolts = vinError,
                policyLimitAmps = policyLimit,
                recoveryPhase = recoveryPhase,
                recoveryCycleCount = recoveryCycleCount,
                controlBand = band,
                controlStepAmps = stepAmps,
                chargeVoltageTarget = lastVset,
                recoveryActive = pvMode == MODE_RECOVER,
                socTargetPercent = socTargetPercent,
                kneeProbeFast = kneeProbeFast,
                vtuneProbePhase = probePacingPhase.label,
                huntLockTicks = huntLockConsecutiveTicks,
                estimatedPinWatts = lastEstimatedPinW,
                acceptedProbePinWatts = acceptedProbeInputPowerW,
                vtuneDescentBlocked = vtuneDescentBlocked,
                pastMppActive = pastMppActive,
                pastMppWrongWay = pastMppWrongWay,
                pastMppVinBelowKneeV = pastMppVinBelowKneeV,
                pastMppIsetDeltaA = pastMppIsetDeltaA,
                pastMppPoutDeltaW = pastMppPoutDeltaW,
                pastMppMissedW = pastMppMissedW,
                pastMppEpisodeCount = pastMppEpisodeCount,
                pastMppCumulativeMissedW = pastMppCumulativeMissedW,
                effectiveKneeDelaySeconds = effectiveKneeDelaySeconds(settings),
                lowPowerMode = morningMinimalSun
            )
        )
    }

    private fun quantVolts(volts: Double): Double = round((volts + 1e-9) * 100.0) / 100.0
    private fun quantAmps(amps: Double): Double = round((amps + 1e-9) * 100.0) / 100.0

    companion object {
        private const val MODE_IDLE = "Idle"
        private const val MODE_TRACKING = "Tracking"
        private const val MODE_RECOVER = "Recover"
        private const val MODE_SUN_PROBE = "Sun Probe"
        private const val MODE_BMS_V_HOLD = "BMS V Hold"
        private const val MODE_VOLTAGE_LIMIT = "Voltage Limit"
        private const val MODE_ALARM = "Alarm"

        private const val PHASE_NONE = "--"
        private const val PHASE_WAIT_VIN = "Waiting VIN"

        private const val CRASH_LOG_IDLE = 0
        private const val CRASH_LOG_RECOVERING = 1
        private const val CRASH_LOG_STABLE_COUNT = 2
        private const val CRASH_LOG_TAIL = 3

        private const val CRASH_KIND_CLOUD = "cloud"
        private const val CRASH_KIND_FAST_PROBE = "fast_probe"
        private const val CRASH_KIND_RECOLLAPSE = "recollapse"
    }

    private enum class Direction {
        Up,
        Down
    }

    private enum class ProbePacingPhase(val label: String) {
        IDLE("--"),
        WAIT_HUNT_LOCK("WaitLock"),
        PAUSE("Pause"),
        BLOCKED("Blocked")
    }
}

/**
 * Controller tuning not exposed in Settings.
 *
 * Fast loop (200 ms): single Vin-knee ISET hunt via percent table; FI band fixed 0.01 A;
 * percent-band steps floored at 0.01 A (Riden command resolution).
 * Slow loop: periodic VTune down-probe; collapse raises knee.
 */
private object Tuning {
    const val MIN_ISET = 0.01

    const val FINE_STEP_A = 0.01
    const val NEAR_STEP_A = 0.02
    const val MID_STEP_A = 0.05
    const val FAR_STEP_A = 0.10
    const val HUGE_STEP_A = 0.50
    const val STEP_PCT_NE = 1.00
    const val STEP_PCT_MD = 2.50
    const val STEP_PCT_FA = 4.50
    const val STEP_PCT_HU = 6.00
    const val V1 = 0.25
    const val V2 = 0.75
    const val V3 = 1.50
    const val V4 = 2.00
    const val MAX_STEP_PCT = 6.0
    const val ISET_STEP_MULTIPLIER_AT_10A = 5.0
    const val MAX_STEP_UP_A = 1.5
    const val MAX_STEP_DOWN_A = 10.0

    const val RECOVERY_DROP_FRACTION = 0.75
    const val RECOVERY_DROP_MIN_STEP_A = 1.0

    const val CRASH_IREF_FRACTION = 0.90

    const val SUN_PROBE_LADDER_0 = 0.01
    const val SUN_PROBE_LADDER_1 = 0.10
    const val SUN_PROBE_LADDER_2 = 1.00
    const val SUN_PROBE_LADDER_3 = 2.00
    const val SUN_PROBE_RAMP_A = 2.00
    const val SUN_PROBE_SETTLE_MS = 400L
    /** Cold-start hard-lock probe starts at ladder index 3 (2 A) instead of 0.01 A. */
    const val SUN_PROBE_FAST_START_INDEX = 3
    const val SUN_PROBE_FAST_SETTLE_MS = 200L
    const val SUN_PROBE_MIN_POUT_W = 5.0
    const val SOC_HOLD_ISET_BLEND = 0.35
    /** Amps of ISET reduction per volt of BMS pack voltage above hold setpoint. */
    const val BMS_V_HOLD_GAIN_A_PER_V = 15.0

    const val MORNING_MIN_IREF_A = 0.01
    const val OVERNIGHT_VIN_VOUT_EPS_V = 1.0

    const val TAKEOVER_MIN_ISET_A = 1.0
    const val TAKEOVER_MIN_POUT_W = 15.0
    const val TAKEOVER_ISET_SLACK_A = 0.5
    const val CURRENT_LIMIT_EPS_A = 0.05
    /** Wait for live Riden telemetry after app restart / USB reconnect before giving up hard lock. */
    const val TAKEOVER_RETRY_MS = 10_000L

    const val COLLAPSE_FLOOR_EXTRA_MARGIN_V = 2.0

    const val RECOVERY_STABLE_ERR_V = 0.50
    const val RECOVERY_STUCK_TIMEOUT_MS = 90_000L
    const val TRACKING_MIN_STUCK_TIMEOUT_MS = 120_000L
    const val MAX_COLLAPSE_STEP_MULTIPLIER = 10

    const val VOLTAGE_LIMIT_EPS = 0.05
    const val VSET_UPDATE_EPS = 0.02

    const val ALARM_BASE_HOLD_MS = 60_000L
    const val ALARM_MAX_HOLD_MS = 30 * 60_000L
    const val ALARM_REPEAT_WINDOW_MS = 10 * 60_000L
    const val ALARM_STABLE_RESET_MS = 30 * 60_000L

    const val ACQUISITION_PAUSE_MS = 500L
    const val HUNT_LOCK_TICKS = 3
    const val MAX_HUNT_LOCK_WAIT_MS = 5_000L
    const val MIN_FAST_PROBE_SPACING_MS = 200L
    const val ACQUISITION_MAX_PROBE_CYCLE_MS = 8_000L
    const val EXPECTED_PROBE_COLLAPSE_EXTRA_MS = 12_000L
    const val CRASH_LOG_STABLE_CYCLES = 5
    const val CRASH_LOG_TAIL_CYCLES = 5
    const val POWER_PROBE_MIN_EPS_W = 6.0
    const val POWER_PROBE_EPS_FRACTION = 0.05

    const val PAST_MPP_MIN_POUT_W = 40.0
    const val PAST_MPP_POUT_EPS_W = 2.0
    const val PAST_MPP_OBS_MIN_MS = 200L
    const val PAST_MPP_EVENT_DEBOUNCE_MS = 5_000L
}
