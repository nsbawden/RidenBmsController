package com.example.ridenbmscontroller.controller

import com.example.ridenbmscontroller.ble.BmsBleUiState
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
    private val onCollapseEntered: (scheduled: Boolean) -> Unit = {}
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
    private var stableSinceMs = 0L
    private var alarmWasActive = false
    private var alarmHoldUntilMs = 0L
    private var alarmBackoffMs = Tuning.ALARM_BASE_HOLD_MS
    private var lastAlarmStartMs = 0L
    private var pendingVtuneDownProbe = false
    private var lastVtuneDownProbeMs = 0L
    private var kneeProbeFast = false
    private var maintenanceDownshiftSuccessCount = 0
    private var watchingMaintenanceDownshiftStability = false
    private var lastMaintenanceDownProbeMs = 0L
    private var maintenancePinBeforeProbeW = 0.0
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
    private var maintenanceProbeCount = 0
    private var maintenancePeriodClean = true
    private var probeOneShotConsumed = false
    private var scheduledRecoveryEpisode = false
    private var probeCrashIset = Tuning.MIN_ISET
    private var probeRecallPending = false
    private var probeRecoveryActive = false
    private var useFixedAmpHunt = false
    private var lastAppliedHuntStepA = 0.0
    private var lastAppliedControlBand = "--"
    private var needsRidenTakeover = false
    private var takeoverAttemptUntilMs = 0L
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
    private var overnightVinNearVout = false

    fun resetLearnedKnee() {
        kneeOffsetVolts = 0.0
        lastKneeProbeMs = System.currentTimeMillis()
        recentCollapseCount = 0
        lastCollapseMs = 0L
        stableSinceMs = 0L
        resetFastAcquireState()
        resetEffectiveKneeDelay()
        resetCollapseState()
        needsRidenTakeover = false
    }

    fun setActiveKnee(settings: AppSettings, targetPvVolts: Double) {
        kneeOffsetVolts = clampKnee(settings, targetPvVolts) - settings.targetPvVolts
        lastKneeProbeMs = System.currentTimeMillis()
        recentCollapseCount = 0
        lastCollapseMs = 0L
        stableSinceMs = 0L
        resetFastAcquireState()
        resetEffectiveKneeDelay()
        resetCollapseState()
        needsRidenTakeover = false
    }

    private fun resetCollapseState() {
        recoveryPhase = PHASE_NONE
        probeRecallPending = false
        probeRecoveryActive = false
        probeCrashIset = Tuning.MIN_ISET
        scheduledRecoveryEpisode = false
        useFixedAmpHunt = false
    }

    private fun resetFastAcquireState() {
        pendingVtuneDownProbe = false
        kneeProbeFast = false
        maintenanceDownshiftSuccessCount = 0
        watchingMaintenanceDownshiftStability = false
        lastMaintenanceDownProbeMs = 0L
        maintenancePinBeforeProbeW = 0.0
        resetProbePacingState(clearPowerBaseline = true)
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

    fun tick(
        settings: AppSettings,
        bmsState: BmsBleUiState,
        ridenState: RidenUsbState
    ) {
        val now = System.currentTimeMillis()
        if (now - lastTickMs < settings.controllerLoopMs.toLong()) return
        lastTickMs = now

        val riden = ridenState.telemetry
        val bms = bmsState.telemetry

        if (!settings.controllerEnabled || !ridenState.connected) {
            idle(settings, ridenState.telemetry, "Controller off")
            return
        }

        val batteryVolts = bms.packVoltage
        val batteryAmps = bms.packCurrent
        val socPercent = bms.socPercent
        if (bmsState.connectedDeviceAddress == null || batteryVolts == null || batteryAmps == null || socPercent == null) {
            idle(settings, ridenState.telemetry, "BMS required: controller idle")
            return
        }

        val balanceDay = isBalanceDay(settings)
        if (balanceDay && settings.lastBalanceEpochDay != currentEpochDay()) {
            onBalanceDayStarted(currentEpochDay())
        }
        val socTarget = if (balanceDay) 100 else settings.normalSocCeilingPercent

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

        val vin = riden.vin ?: 0.0
        val vout = riden.vout ?: 0.0
        val snapVin = vin
        val snapIset = riden.iset
        val snapWatts = riden.watts ?: 0.0
        val virtualKnee = virtualKnee(settings)
        val voltageLimited = vout >= settings.maxBatteryVolts - Tuning.VOLTAGE_LIMIT_EPS
        val currentLimit = settings.maxChargeAmps.coerceAtLeast(Tuning.MIN_ISET)
        val targetBatteryCurrent = if (socPercent >= socTarget || voltageLimited) {
            settings.socHoldCurrentAmps
        } else {
            currentLimit
        }

        ensureOutputAndVoltage(settings)

        val policyLimit = targetBatteryCurrent.coerceAtMost(currentLimit)
        if (needsRidenTakeover) {
            if (takeoverAttemptUntilMs == 0L) {
                takeoverAttemptUntilMs = now + Tuning.TAKEOVER_RETRY_MS
            }
            if (now <= takeoverAttemptUntilMs &&
                (tryAdoptRidenOperatingPoint(settings, snapVin, snapIset, snapWatts, policyLimit, now) ||
                    tryAdoptCachedHandoff(settings, policyLimit, now))
            ) {
                needsRidenTakeover = false
                takeoverAttemptUntilMs = 0L
            } else if (now > takeoverAttemptUntilMs) {
                needsRidenTakeover = false
                takeoverAttemptUntilMs = 0L
            }
        }

        val oldIset = currentIset(riden.iset)
        val nextIset = when {
            recoveryPhase != PHASE_NONE -> recover(
                settings, oldIset, vin, vout, riden.watts ?: 0.0, virtualKnee, policyLimit, now
            )
            isCollapsed(settings, vin) -> enterRecovery(
                settings, oldIset, policyLimit, vin, now
            )
            else -> {
                val iset = handleSolarTick(
                    settings, oldIset, vin, vout, riden.watts ?: 0.0, virtualKnee, policyLimit, now
                )
                if (socPercent >= socTarget || voltageLimited) {
                    pvMode = if (balanceDay) MODE_BALANCE else if (socPercent >= socTarget) MODE_SOC_HOLD else MODE_VOLTAGE_LIMIT
                    resetCollapseState()
                    updateStableRecoveryReset(false, settings, vin, virtualKnee, now)
                }
                iset
            }
        }.coerceIn(Tuning.MIN_ISET, policyLimit)

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
            status = statusFor(balanceDay, socPercent >= socTarget, voltageLimited),
            policyLimit = targetBatteryCurrent,
            band = if (lastAppliedControlBand != "--") lastAppliedControlBand else bandFor(finalVinError),
            stepAmps = if (lastAppliedHuntStepA > 0.0) lastAppliedHuntStepA else stepFor(finalVinError, policyLimit),
            vinError = finalVinError,
            socTargetPercent = socTarget
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
        pendingVtuneDownProbe = false
        watchingMaintenanceDownshiftStability = false
        maintenancePinBeforeProbeW = 0.0
        resetProbePacingState(clearPowerBaseline = false)
        vtuneDescentBlocked = false

        if (kneeProbeFast && immediateProbeCrash) {
            kneeProbeFast = false
        }
        maintenanceDownshiftSuccessCount = 0
        maintenancePeriodClean = false
        recoveryCycleCount += 1
        stableSinceMs = 0L
        resetSunProbeState()
        useFixedAmpHunt = false

        val preCrashIset = max(max(oldIset, commandIset), lastWorkingIset).coerceAtLeast(Tuning.MIN_ISET)
        lastWorkingIset = preCrashIset
        setSunReferenceFromCrash(preCrashIset, policyLimit, "pre-crash recall")
        recoveryPhase = PHASE_WAIT_VIN
        pvMode = MODE_RECOVER
        probeRecallPending = false

        if (immediateProbeCrash) {
            scheduledRecoveryEpisode = true
        }
        if (immediateProbeCrash && !probeOneShotConsumed) {
            probeOneShotConsumed = true
            probeRecoveryActive = true
            probeCrashIset = oldIset.coerceAtLeast(Tuning.MIN_ISET)
            val crashKnee = virtualKnee(settings)
            kneeOffsetVolts = clampKnee(
                settings,
                crashKnee + Tuning.PROBE_RECOVERY_KNEE_BACK_V
            ) - settings.targetPvVolts
            lastKneeProbeMs = now
            onEvent(
                "Probe recovery: knee +${"%.2f".format(Tuning.PROBE_RECOVERY_KNEE_BACK_V)}V -> " +
                    "${"%.2f".format(virtualKnee(settings))}V, recall ISET ${"%.2f".format(probeCrashIset)}A"
            )
        } else {
            probeRecoveryActive = false
            raiseKneeForCollapse(settings)
            onEvent("Cloud recovery: knee -> ${"%.2f".format(virtualKnee(settings))}V")
        }

        if (!scheduled) {
            onCollapseEntered(false)
        }

        lastAppliedHuntStepA = 0.0
        lastAppliedControlBand = "RE"
        return if (isCollapsed(settings, vin)) {
            Tuning.MIN_ISET
        } else {
            (oldIset - recoveryDropStep(oldIset)).coerceAtLeast(Tuning.MIN_ISET).coerceAtMost(policyLimit)
        }
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
        now: Long
    ): Double {
        pvMode = MODE_RECOVER
        stableSinceMs = 0L
        lastAppliedHuntStepA = 0.0
        lastAppliedControlBand = "RE"

        if (isCollapsed(settings, vin)) {
            if (recoveryPhase != PHASE_WAIT_VIN) {
                if (probeRecoveryActive) {
                    probeRecoveryActive = false
                    raiseKneeForCollapse(settings)
                    onEvent("Probe recovery failed: cloud recovery")
                } else {
                    raiseKneeForCollapse(settings)
                }
            }
            recoveryPhase = PHASE_WAIT_VIN
            return Tuning.MIN_ISET
        }

        if (vin < recoveryExitFloor(settings)) {
            recoveryPhase = PHASE_WAIT_VIN
            return Tuning.MIN_ISET
        }

        if (probeRecoveryActive) {
            probeRecoveryActive = false
            recoveryPhase = PHASE_NONE
            val recall = quantAmps(probeCrashIset * Tuning.PROBE_RECOVERY_ISET_FRACTION)
                .coerceIn(Tuning.MIN_ISET, policyLimit)
            commandIset = recall
            setSunReferenceFromCrash(probeCrashIset, policyLimit, "probe crash recall")
            pvMode = MODE_TRACKING
            onEvent("Probe recall ISET ${"%.2f".format(recall)}A (90% of ${"%.2f".format(probeCrashIset)}A)")
            updateStableRecoveryReset(true, settings, vin, virtualKnee, now)
            return trackSolarKnee(settings, recall, vin, vout, outputWatts, virtualKnee, policyLimit, now)
        }

        recoveryPhase = PHASE_NONE
        useFixedAmpHunt = true
        onEvent("Cloud recovery handoff -> tracking")
        return trackSolarKnee(settings, oldIset, vin, vout, outputWatts, virtualKnee, policyLimit, now)
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
            startSunProbe(policyLimit)
            return tickSunProbe(settings, oldIset, vin, vout, outputWatts, virtualKnee, policyLimit, now)
        }

        return trackSolarKnee(settings, oldIset, vin, vout, outputWatts, virtualKnee, policyLimit, now)
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

    private fun startSunProbe(policyLimit: Double) {
        sunProbeActive = true
        sunProbeLadderIndex = 0
        sunProbeLastGoodIset = Tuning.MIN_ISET
        sunProbeSettleSinceMs = 0L
        pvMode = MODE_SUN_PROBE
        onEvent("Sun probe started -> ${"%.2f".format(sunProbeLevelForIndex(0, policyLimit))}A")
    }

    private fun completeSunProbe(lastGood: Double, reason: String) {
        sunReferenceIset = quantAmps(lastGood.coerceAtLeast(Tuning.MIN_ISET))
        sunReferenceKnown = true
        sunProbeActive = false
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
            completeSunProbe(sunProbeLastGoodIset.coerceAtLeast(Tuning.MIN_ISET), "collapse at ${"%.2f".format(oldIset)}A")
            val startIset = sunReferenceIset.coerceIn(Tuning.MIN_ISET, policyLimit)
            return trackSolarKnee(settings, startIset, vin, vout, outputWatts, virtualKnee, policyLimit, now)
        }

        val target = sunProbeLevelForIndex(sunProbeLadderIndex, policyLimit)
        if (quantAmps(oldIset) != target) {
            sunProbeSettleSinceMs = now
            lastAppliedHuntStepA = abs(target - oldIset)
            return target
        }

        if (sunProbeSettleSinceMs == 0L) {
            sunProbeSettleSinceMs = now
        }
        if (now - sunProbeSettleSinceMs < Tuning.SUN_PROBE_SETTLE_MS) {
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
        return quantAmps(level.coerceIn(Tuning.MIN_ISET, policyLimit))
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
        commandIset = quantAmps(iset.coerceIn(Tuning.MIN_ISET, policyLimit))
        lastKneeProbeMs = now
        recentCollapseCount = 0
        lastCollapseMs = 0L
        resetCollapseState()
        sunReferenceIset = quantAmps(iset.coerceIn(Tuning.MIN_ISET, policyLimit))
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
    }

    private fun setSunReferenceFromCrash(preCrashIset: Double, policyLimit: Double, label: String) {
        sunReferenceIset = quantAmps(
            (preCrashIset * Tuning.CRASH_IREF_FRACTION).coerceIn(Tuning.MIN_ISET, policyLimit)
        )
        sunReferenceKnown = true
        morningMinimalSun = false
        sunProbeActive = false
        onEvent("Sun Iref ${"%.2f".format(sunReferenceIset)}A ($label)")
    }

    private fun expectedProbeCollapseWindowMs(settings: AppSettings): Long {
        val cycleMs = if (kneeProbeFast) {
            Tuning.ACQUISITION_MAX_PROBE_CYCLE_MS
        } else {
            maintenanceProbeDelayMs(settings)
        }
        return cycleMs + Tuning.EXPECTED_PROBE_COLLAPSE_EXTRA_MS
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
            suspendVtuneDescentWhileCurrentLimited()
        } else {
            updateMaintenanceDownshiftStability(settings, inputPowerW, now)
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
        return next.coerceIn(Tuning.MIN_ISET, policyLimit)
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
    private fun suspendVtuneDescentWhileCurrentLimited() {
        if (kneeProbeFast) {
            kneeProbeFast = false
        }
        if (probePacingPhase != ProbePacingPhase.IDLE || pendingVtuneDownProbe || watchingMaintenanceDownshiftStability) {
            pendingVtuneDownProbe = false
            awaitingPowerCheck = false
            watchingMaintenanceDownshiftStability = false
            probePacingPhase = ProbePacingPhase.IDLE
            huntLockConsecutiveTicks = 0
        }
    }

    private fun updateMaintenanceDownshiftStability(
        settings: AppSettings,
        inputPowerW: Double,
        now: Long
    ) {
        if (!watchingMaintenanceDownshiftStability || kneeProbeFast) return
        if (now - lastMaintenanceDownProbeMs < Tuning.MAINTENANCE_DOWNSHIFT_STABLE_MS) return

        watchingMaintenanceDownshiftStability = false
        if (settings.powerBasedVtuneStop &&
            powerDroppedAfterProbe(inputPowerW, maintenancePinBeforeProbeW)
        ) {
            revertLastVtuneStep(settings, now)
            maintenanceDownshiftSuccessCount = 0
            maintenancePeriodClean = false
            onEvent(
                "VTune maintenance power stop: Pin ${"%.0f".format(inputPowerW)}W < baseline " +
                    "${"%.0f".format(maintenancePinBeforeProbeW)}W, reverted"
            )
            return
        }

        maintenanceDownshiftSuccessCount += 1
        val required = settings.fastAcquireSuccessCount.coerceIn(1, 5)
        if (maintenanceDownshiftSuccessCount >= required) {
            maintenanceDownshiftSuccessCount = 0
            startFastAcquire(inputPowerW)
        }
    }

    private fun startFastAcquire(inputPowerW: Double) {
        kneeProbeFast = true
        vtuneDescentBlocked = false
        resetProbePacingState(clearPowerBaseline = true)
        if (inputPowerW > 0.0) {
            acceptedProbeInputPowerW = inputPowerW
        }
        probePacingPhase = ProbePacingPhase.IDLE
    }

    private fun maybeVtuneDownProbe(settings: AppSettings, inputPowerW: Double, now: Long) {
        if (vtuneDescentBlocked) return

        if (kneeProbeFast) {
            if (probePacingPhase != ProbePacingPhase.IDLE) return
            if (now - lastKneeProbeMs < Tuning.MIN_FAST_PROBE_SPACING_MS) return
            executeVtuneDownProbe(settings, inputPowerW, now, fastAcquire = true)
            return
        }

        if (now - lastKneeProbeMs < maintenanceProbeDelayMs(settings)) return
        executeVtuneDownProbe(settings, inputPowerW, now, fastAcquire = false)
    }

    private fun executeVtuneDownProbe(
        settings: AppSettings,
        inputPowerW: Double,
        now: Long,
        fastAcquire: Boolean
    ) {
        lastKneeProbeMs = now
        lastVtuneDownProbeMs = now
        pendingVtuneDownProbe = true
        kneeOffsetVolts = clampKnee(settings, settings.targetPvVolts + kneeOffsetVolts - settings.kneeStepVolts) -
            settings.targetPvVolts

        probePacingPhase = ProbePacingPhase.WAIT_HUNT_LOCK
        probeWaitSinceMs = now
        huntLockConsecutiveTicks = 0
        awaitingPowerCheck = true

        if (fastAcquire) return

        maintenanceProbeCount += 1
        maintenancePeriodClean = true
        watchingMaintenanceDownshiftStability = true
        lastMaintenanceDownProbeMs = now
        maintenancePinBeforeProbeW = inputPowerW.coerceAtLeast(0.0)
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
            maintenancePeriodClean = false
            if (kneeProbeFast) {
                kneeProbeFast = false
            }
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
        lastKneeProbeMs = now
        pendingVtuneDownProbe = false
        awaitingPowerCheck = false
        probePacingPhase = ProbePacingPhase.IDLE
    }

    private fun maintenanceProbeDelayMs(settings: AppSettings): Long {
        return (effectiveKneeDelaySeconds(settings) * 1000.0).toLong()
    }

    private fun kneeDelayBounds(settings: AppSettings): Pair<Double, Double> {
        val min = settings.kneeTrackingDelayMinSeconds.coerceAtMost(settings.kneeTrackingDelayMaxSeconds)
        val max = settings.kneeTrackingDelayMaxSeconds.coerceAtLeast(min)
        return min to max
    }

    private fun effectiveKneeDelaySeconds(settings: AppSettings): Double {
        return kneeDelayBounds(settings).first
    }

    private fun resetEffectiveKneeDelay() {
        maintenanceProbeCount = 0
        maintenancePeriodClean = true
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
            sunReferenceIset.coerceIn(Tuning.MIN_ISET, policyLimit)
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
        return iset.coerceAtLeast(0.0) * pct / 100.0
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

    private fun statusFor(balanceDay: Boolean, socReached: Boolean, voltageLimited: Boolean): String {
        return when {
            pvMode == MODE_RECOVER -> "Solar recovery: $recoveryPhase"
            pvMode == MODE_SUN_PROBE -> "Sun capability probe"
            balanceDay -> "Balance day: SOC target 100%"
            socReached -> "SOC ceiling reached: load support"
            voltageLimited -> "Controller voltage limit"
            else -> "Tracking solar knee" + if (kneeProbeFast) " (fast acquire)" else ""
        }
    }

    private fun ensureOutputAndVoltage(settings: AppSettings) {
        if (!outputEnabled) {
            setOutput(true)
            outputEnabled = true
        }
        if (abs(lastVset - settings.maxBatteryVolts) >= Tuning.VSET_UPDATE_EPS) {
            setVset(settings.maxBatteryVolts)
            lastVset = settings.maxBatteryVolts
        }
    }

    private fun currentIset(ridenIset: Double?): Double {
        return if (commandIset > 0.0) commandIset else (ridenIset ?: Tuning.MIN_ISET).coerceAtLeast(Tuning.MIN_ISET)
    }

    private fun idle(settings: AppSettings, riden: RidenTelemetry, status: String) {
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
        resetPastMppObservation()
        resetEffectiveKneeDelay()
        resetCollapseState()
        resetSunReferenceState()
        probeOneShotConsumed = false
        needsRidenTakeover = true
        takeoverAttemptUntilMs = 0L
        pvMode = MODE_IDLE
        publish(settings, status, 0.0, "--")
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
        private const val MODE_SOC_HOLD = "SOC Hold"
        private const val MODE_BALANCE = "Balance"
        private const val MODE_VOLTAGE_LIMIT = "Voltage Limit"
        private const val MODE_ALARM = "Alarm"

        private const val PHASE_NONE = "--"
        private const val PHASE_WAIT_VIN = "Waiting VIN"
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
 * Fast loop (200 ms): single Vin-knee ISET hunt via percent table; FI band fixed 0.01 A.
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
    const val SUN_PROBE_MIN_POUT_W = 5.0

    const val MORNING_MIN_IREF_A = 0.01
    const val OVERNIGHT_VIN_VOUT_EPS_V = 1.0

    const val TAKEOVER_MIN_ISET_A = 1.0
    const val TAKEOVER_MIN_POUT_W = 15.0
    const val TAKEOVER_ISET_SLACK_A = 0.5
    const val CURRENT_LIMIT_EPS_A = 0.05
    const val TAKEOVER_RETRY_MS = 5_000L

    const val COLLAPSE_FLOOR_EXTRA_MARGIN_V = 2.0

    const val RECOVERY_STABLE_ERR_V = 0.50
    const val MAX_COLLAPSE_STEP_MULTIPLIER = 10

    const val VOLTAGE_LIMIT_EPS = 0.05
    const val VSET_UPDATE_EPS = 0.02

    const val ALARM_BASE_HOLD_MS = 60_000L
    const val ALARM_MAX_HOLD_MS = 30 * 60_000L
    const val ALARM_REPEAT_WINDOW_MS = 10 * 60_000L
    const val ALARM_STABLE_RESET_MS = 30 * 60_000L

    const val PROBE_RECOVERY_KNEE_BACK_V = 0.20
    const val PROBE_RECOVERY_ISET_FRACTION = 0.90

    const val ACQUISITION_PAUSE_MS = 500L
    const val HUNT_LOCK_TICKS = 3
    const val MAX_HUNT_LOCK_WAIT_MS = 5_000L
    const val MIN_FAST_PROBE_SPACING_MS = 200L
    const val ACQUISITION_MAX_PROBE_CYCLE_MS = 8_000L
    const val EXPECTED_PROBE_COLLAPSE_EXTRA_MS = 12_000L
    const val MAINTENANCE_DOWNSHIFT_STABLE_MS = 5_000L
    const val POWER_PROBE_MIN_EPS_W = 6.0
    const val POWER_PROBE_EPS_FRACTION = 0.05

    const val PAST_MPP_MIN_POUT_W = 40.0
    const val PAST_MPP_POUT_EPS_W = 2.0
    const val PAST_MPP_OBS_MIN_MS = 200L
    const val PAST_MPP_EVENT_DEBOUNCE_MS = 5_000L
}
