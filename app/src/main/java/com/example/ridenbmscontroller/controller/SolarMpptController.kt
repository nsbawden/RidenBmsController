package com.example.ridenbmscontroller.controller

import com.example.ridenbmscontroller.ble.BmsBleUiState
import com.example.ridenbmscontroller.model.AppSettings
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
    val vtuneDescentBlocked: Boolean = false
)

/**
 * Hardware-agnostic controller logic.
 *
 * This class is intended to describe the reusable behavior from the README:
 * BMS/SOC battery policy, solar knee tracking, and collapse recovery. It talks to
 * charger hardware only through the small command surface below. In this app those
 * commands are backed by a Riden USB adapter, but another programmable charger or
 * MPPT controller can reuse this policy layer by providing equivalent commands and
 * telemetry.
 */
class SolarMpptController(
    private val setOutput: (Boolean) -> Unit,
    private val setVset: (Double) -> Unit,
    private val setIset: (Double) -> Unit,
    private val onBalanceDayStarted: (Long) -> Unit,
    private val onState: (MpptControlState) -> Unit,
    private val onEvent: (String) -> Unit = {}
) {
    private var outputEnabled = false
    private var commandIset = 0.0
    private var lastVset = 0.0
    private var kneeOffsetVolts = 0.0
    private var lastKneeProbeMs = 0L
    private var lastTickMs = 0L
    private var pvMode = MODE_IDLE
    private var recoveryPhase = PHASE_NONE
    private var lastWorkingIset = Tuning.MIN_ISET
    private var lastCollapseMs = 0L
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

    fun resetLearnedKnee() {
        kneeOffsetVolts = 0.0
        lastKneeProbeMs = System.currentTimeMillis()
        recentCollapseCount = 0
        lastCollapseMs = 0L
        stableSinceMs = 0L
        resetFastAcquireState()
    }

    fun setActiveKnee(settings: AppSettings, targetPvVolts: Double) {
        kneeOffsetVolts = clampKnee(settings, targetPvVolts) - settings.targetPvVolts
        lastKneeProbeMs = System.currentTimeMillis()
        recentCollapseCount = 0
        lastCollapseMs = 0L
        stableSinceMs = 0L
        resetFastAcquireState()
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
            idle(settings, "Controller off")
            return
        }

        val batteryVolts = bms.packVoltage
        val batteryAmps = bms.packCurrent
        val socPercent = bms.socPercent
        if (bmsState.connectedDeviceAddress == null || batteryVolts == null || batteryAmps == null || socPercent == null) {
            idle(settings, "BMS required: controller idle")
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
            inhibitCharging(settings, "BMS alarm cleared: retry in ${seconds}s", socTarget)
            return
        }
        if (lastAlarmStartMs > 0L && now - lastAlarmStartMs >= Tuning.ALARM_STABLE_RESET_MS) {
            alarmBackoffMs = Tuning.ALARM_BASE_HOLD_MS
        }

        val vin = riden.vin ?: 0.0
        val vout = riden.vout ?: 0.0
        val virtualKnee = virtualKnee(settings)
        // Battery policy layer: maxChargeAmps remains the hardware/request ceiling.
        // At SOC hold or voltage limit, socHoldCurrentAmps is the desired *net BMS*
        // current, not a hard Riden ISET cap. This lets solar keep carrying RV loads.
        val voltageLimited = vout >= settings.maxBatteryVolts - Tuning.VOLTAGE_LIMIT_EPS
        val currentLimit = settings.maxChargeAmps.coerceAtLeast(Tuning.MIN_ISET)
        val targetBatteryCurrent = if (socPercent >= socTarget || voltageLimited) {
            settings.socHoldCurrentAmps
        } else {
            currentLimit
        }

        ensureOutputAndVoltage(settings)

        val oldIset = currentIset(riden.iset)
        val nextIset = when {
            recoveryPhase != PHASE_NONE -> recover(settings, oldIset, vin, vout, riden.watts ?: 0.0, virtualKnee)
            isCollapsed(settings, vin) -> enterRecovery(settings, oldIset, currentLimit, vin, now)
            socPercent >= socTarget || voltageLimited -> {
                pvMode = if (balanceDay) MODE_BALANCE else if (socPercent >= socTarget) MODE_SOC_HOLD else MODE_VOLTAGE_LIMIT
                recoveryPhase = PHASE_NONE
                updateStableRecoveryReset(false, settings, vin, virtualKnee, now)
                holdBatteryCurrentAtSolarKnee(oldIset, batteryAmps, targetBatteryCurrent, vin, virtualKnee)
                    .coerceAtMost(currentLimit)
            }
            else -> trackSolarKnee(settings, oldIset, vin, vout, riden.watts ?: 0.0, virtualKnee, now)
        }.coerceIn(Tuning.MIN_ISET, currentLimit)

        commandIset = quantAmps(nextIset)
        setIset(commandIset)

        val finalVinError = vin - virtualKnee(settings)
        lastEstimatedPinW = estimateInputPowerW(riden.watts ?: 0.0, vin, vout)
        publish(
            settings = settings,
            status = statusFor(balanceDay, socPercent >= socTarget, voltageLimited),
            policyLimit = targetBatteryCurrent,
            band = bandFor(finalVinError),
            stepAmps = stepFor(finalVinError),
            vinError = finalVinError,
            socTargetPercent = socTarget
        )
    }

    private fun estimateInputPowerW(outputWatts: Double, vin: Double, vout: Double): Double {
        if (outputWatts <= 0.0 || vin <= 0.0 || vout <= 0.0) return 0.0
        return outputWatts * (vin / vout)
    }

    private fun trackSolarKnee(
        settings: AppSettings,
        oldIset: Double,
        vin: Double,
        vout: Double,
        outputWatts: Double,
        virtualKnee: Double,
        now: Long
    ): Double {
        pvMode = MODE_TRACKING

        // Solar tracker layer: push current up when VIN is above the virtual knee, pull it
        // down when VIN is below. The table keeps very small corrections near the knee
        // while still allowing decisive movement when VIN is far from target.
        val error = vin - virtualKnee
        val absErr = abs(error)
        val next = when {
            error > Tuning.TRACK_DEADBAND_V -> oldIset + huntStep(absErr, oldIset, Direction.Up)
            error < -Tuning.TRACK_DEADBAND_V -> oldIset - huntStep(absErr, oldIset, Direction.Down)
            else -> oldIset
        }

        if (vin >= virtualKnee - Tuning.RECOVERY_STABLE_ERR_V) {
            lastWorkingIset = max(lastWorkingIset * 0.8 + oldIset * 0.2, Tuning.MIN_ISET)
        }
        updateStableRecoveryReset(true, settings, vin, virtualKnee, now)
        val inputPowerW = estimateInputPowerW(outputWatts, vin, vout)
        if (!settings.powerBasedVtuneStop && vtuneDescentBlocked) {
            vtuneDescentBlocked = false
            if (probePacingPhase == ProbePacingPhase.BLOCKED) {
                probePacingPhase = ProbePacingPhase.IDLE
            }
        }
        updateProbePacing(settings, vin, virtualKnee, inputPowerW, now)
        updateMaintenanceDownshiftStability(settings, inputPowerW, now)

        // Periodically try a lower virtual knee. If that asks too much of the panels,
        // collapse recovery will raise the knee back up.
        maybeVtuneDownProbe(settings, inputPowerW, now)

        return next
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
                if (abs(vin - virtualKnee) <= Tuning.TRACK_DEADBAND_V) {
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

        if (fastAcquire) {
            return
        }

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
        return (settings.kneeTrackingDelaySeconds * 1000.0).toLong()
    }

    private fun expectedProbeCollapseWindowMs(settings: AppSettings): Long {
        val cycleMs = if (kneeProbeFast) {
            Tuning.ACQUISITION_MAX_PROBE_CYCLE_MS
        } else {
            maintenanceProbeDelayMs(settings)
        }
        return cycleMs + Tuning.EXPECTED_PROBE_COLLAPSE_EXTRA_MS
    }

    private fun enterRecovery(
        settings: AppSettings,
        oldIset: Double,
        policyLimit: Double,
        vin: Double,
        now: Long
    ): Double {
        val probeCausedRecovery = pendingVtuneDownProbe &&
            (now - lastVtuneDownProbeMs) <= expectedProbeCollapseWindowMs(settings)
        pendingVtuneDownProbe = false
        watchingMaintenanceDownshiftStability = false
        maintenancePinBeforeProbeW = 0.0
        resetProbePacingState(clearPowerBaseline = false)
        vtuneDescentBlocked = false

        if (kneeProbeFast && probeCausedRecovery) {
            kneeProbeFast = false
            maintenanceDownshiftSuccessCount = 0
        } else {
            maintenanceDownshiftSuccessCount = 0
        }

        lastWorkingIset = max(oldIset, commandIset).coerceAtLeast(Tuning.MIN_ISET)
        recoveryPhase = PHASE_WAIT_VIN
        pvMode = MODE_RECOVER
        recoveryCycleCount += 1
        stableSinceMs = 0L
        raiseKneeForCollapse(settings)
        return if (isCollapsed(settings, vin)) {
            Tuning.MIN_ISET
        } else {
            (oldIset - recoveryDropStep(oldIset)).coerceAtLeast(Tuning.MIN_ISET).coerceAtMost(policyLimit)
        }
    }

    private fun recover(
        settings: AppSettings,
        oldIset: Double,
        vin: Double,
        vout: Double,
        outputWatts: Double,
        virtualKnee: Double
    ): Double {
        pvMode = MODE_RECOVER
        stableSinceMs = 0L

        if (isCollapsed(settings, vin)) {
            if (recoveryPhase != PHASE_WAIT_VIN) {
                raiseKneeForCollapse(settings)
            }
            recoveryPhase = PHASE_WAIT_VIN
            return Tuning.MIN_ISET
        }

        val reboundedAboveFloor = vin >= recoveryExitFloor(settings)

        return when {
            !reboundedAboveFloor -> {
                recoveryPhase = PHASE_WAIT_VIN
                Tuning.MIN_ISET
            }
            else -> {
                // Recovery is deliberately narrow: it only clears the collapse by
                // raising the learned knee and dropping current. Once VIN is usable
                // again, normal tracking owns the search for the best current.
                recoveryPhase = PHASE_NONE
                pvMode = MODE_TRACKING
                trackSolarKnee(
                    settings,
                    oldIset,
                    vin,
                    vout,
                    outputWatts,
                    virtualKnee,
                    System.currentTimeMillis()
                )
            }
        }
    }

    private fun isCollapsed(settings: AppSettings, vin: Double): Boolean {
        val collapseFloor = lowKneeLimit(settings) - Tuning.COLLAPSE_FLOOR_EXTRA_MARGIN_V
        return vin > 0.0 && vin < collapseFloor
    }

    private fun recoveryExitFloor(settings: AppSettings): Double {
        // Recovery exits at the configured low edge of the knee window. With min
        // target 30V, VIN >= 30V means the panel is no longer collapsed.
        return lowKneeLimit(settings)
    }

    private fun adjustForBatteryCurrent(oldIset: Double, batteryAmps: Double, targetBatteryAmps: Double): Double {
        return oldIset + (targetBatteryAmps - batteryAmps) * Tuning.BATTERY_CURRENT_GAIN
    }

    private fun holdBatteryCurrentAtSolarKnee(
        oldIset: Double,
        batteryAmps: Double,
        targetBatteryAmps: Double,
        vin: Double,
        virtualKnee: Double
    ): Double {
        val batteryDemandIset = adjustForBatteryCurrent(oldIset, batteryAmps, targetBatteryAmps)
        val kneeError = vin - virtualKnee
        return when {
            // In hold mode, this means load support is asking for more current than
            // the panels can sustain at the learned knee. Solar wins; back ISET down.
            kneeError < -Tuning.TRACK_DEADBAND_V ->
                oldIset - huntStep(abs(kneeError), oldIset, Direction.Down)
            // With PV headroom available, let BMS net-current control raise or lower
            // ISET to cover loads plus the configured battery trickle target.
            else -> batteryDemandIset
        }
    }

    private fun recoveryDropStep(oldIset: Double): Double {
        return max(Tuning.RECOVERY_DROP_MIN_STEP_A, oldIset * Tuning.RECOVERY_DROP_FRACTION)
    }

    private fun huntStep(absErr: Double, iset: Double, direction: Direction): Double {
        return scaledStepForAbsError(absErr, iset, direction)
    }

    private fun stepFor(vinError: Double): Double {
        if (pvMode == MODE_RECOVER || abs(vinError) <= Tuning.TRACK_DEADBAND_V) return 0.0
        val direction = if (vinError > 0.0) Direction.Up else Direction.Down
        return scaledStepForAbsError(abs(vinError), commandIset, direction)
    }

    private fun scaledStepForAbsError(absErr: Double, iset: Double, direction: Direction): Double {
        val baseStep = when {
            absErr > Tuning.V4 -> Tuning.HUGE_STEP_A
            absErr > Tuning.V3 -> Tuning.FAR_STEP_A
            absErr > Tuning.V2 -> Tuning.MID_STEP_A
            absErr > Tuning.V1 -> Tuning.NEAR_STEP_A
            else -> Tuning.FINE_STEP_A
        }
        val multiplier = ((iset.coerceAtLeast(0.0) / 10.0) * Tuning.ISET_STEP_MULTIPLIER_AT_10A)
            .coerceAtLeast(1.0)
        val scaled = baseStep * multiplier
        return when (direction) {
            Direction.Up -> scaled.coerceAtMost(Tuning.MAX_STEP_UP_A)
            Direction.Down -> scaled.coerceAtMost(Tuning.MAX_STEP_DOWN_A)
        }
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
            // Recovery just raised the learned knee, so give that safer point a full
            // tracking-delay window before the normal down-probe tries lower again.
            lastKneeProbeMs = now
            vtuneDescentBlocked = false
            probePacingPhase = ProbePacingPhase.IDLE
        }
    }

    private fun maxCollapseStepVolts(settings: AppSettings): Double {
        // The full learned-knee window is min target PV through max target PV. Divide that full span
        // by six so worst-case repeated collapse can traverse the window in six bumps,
        // then round to tenths for predictable tuning.
        val fullWindow = (highKneeLimit(settings) - lowKneeLimit(settings)).coerceAtLeast(0.0)
        return (round((fullWindow / 6.0) * 10.0) / 10.0).coerceAtLeast(settings.kneeStepVolts)
    }

    private fun repeatedCollapseWindowMs(settings: AppSettings): Long {
        // This window must be shorter than the deliberate knee-probe delay, otherwise
        // ordinary scheduled probes can look like repeated fast collapses. Keeping it
        // around 75% of the delay preserves acceleration for rapid cloud changes.
        return (settings.kneeTrackingDelaySeconds * 750.0).toLong().coerceIn(2_000L, 10_000L)
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
            stableSinceMs = 0L
        }
    }

    private fun stableRecoveryResetMs(settings: AppSettings): Long {
        // "Stable" for UI counting means the charger has returned to ordinary tracking
        // near the learned knee for at least one repeated-collapse window.
        return repeatedCollapseWindowMs(settings)
    }

    private fun statusFor(balanceDay: Boolean, socReached: Boolean, voltageLimited: Boolean): String {
        return when {
            pvMode == MODE_RECOVER -> "Solar recovery: $recoveryPhase"
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

    private fun idle(settings: AppSettings, status: String) {
        if (outputEnabled) {
            setOutput(false)
            outputEnabled = false
        }
        commandIset = 0.0
        recoveryPhase = PHASE_NONE
        recoveryCycleCount = 0
        recentCollapseCount = 0
        stableSinceMs = 0L
        resetFastAcquireState()
        pvMode = MODE_IDLE
        publish(settings, status, 0.0, "--")
    }

    private fun inhibitCharging(settings: AppSettings, status: String, socTargetPercent: Int) {
        if (outputEnabled) {
            setOutput(false)
            outputEnabled = false
        }
        commandIset = 0.0
        recoveryPhase = PHASE_NONE
        stableSinceMs = 0L
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

    private fun lowKneeLimit(settings: AppSettings): Double = minOf(settings.minTargetPvVolts, settings.maxTargetPvVolts)

    private fun highKneeLimit(settings: AppSettings): Double = maxOf(settings.minTargetPvVolts, settings.maxTargetPvVolts)

    private fun bandFor(vinError: Double): String {
        val absErr = abs(vinError)
        return when {
            pvMode == MODE_RECOVER -> "RE"
            absErr > Tuning.V4 -> "HU"
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
                vtuneDescentBlocked = vtuneDescentBlocked
            )
        )
    }

    private fun quantVolts(volts: Double): Double = round((volts + 1e-9) * 100.0) / 100.0
    private fun quantAmps(amps: Double): Double = round((amps + 1e-9) * 100.0) / 100.0

    companion object {
        private const val MODE_IDLE = "Idle"
        private const val MODE_TRACKING = "Tracking"
        private const val MODE_RECOVER = "Recover"
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
 * Single tuning point for controller parameters that are not exposed in Settings.
 *
 * Model overview:
 * 1. Battery policy caps current from BMS SOC, BMS alarms, and the maximum Riden voltage.
 * 2. Solar tracking hunts around a virtual PV knee by adjusting Riden ISET.
 * 3. Collapse recovery is a separate state machine that drops to minimum current, waits
 *    for VIN to rebound, raises the virtual knee, then ramps current back up.
 */
private object Tuning {
    const val MIN_ISET = 0.01 // Lowest Riden current request used for weak sun and collapse recovery.

    const val FINE_STEP_A = 0.01 // ISET change when VIN is within V1 of the learned knee.
    const val NEAR_STEP_A = 0.02 // ISET change when VIN error is between V1 and V2.
    const val MID_STEP_A = 0.05 // ISET change when VIN error is between V2 and V3.
    const val FAR_STEP_A = 0.10 // ISET change when VIN error is between V3 and V4.
    const val HUGE_STEP_A = 0.50 // ISET change when VIN error is larger than V4.
    const val V1 = 0.25 // Fine/near VIN error boundary in volts.
    const val V2 = 0.75 // Near/mid VIN error boundary in volts.
    const val V3 = 1.50 // Mid/far VIN error boundary in volts.
    const val V4 = 2.00 // Far/huge VIN error boundary in volts.
    const val TRACK_DEADBAND_V = 0.02 // VIN error small enough to leave ISET unchanged this tick.
    const val ISET_STEP_MULTIPLIER_AT_10A = 5.0 // Table-step multiplier at 10A ISET; scales linearly and never below 1x.
    const val MAX_STEP_UP_A = 1.5 // Largest single upward ISET hunt step; limits Riden falloff risk.
    const val MAX_STEP_DOWN_A = 10.0 // Largest single downward ISET hunt step; intentionally high for fast cloud unloading.

    const val COLLAPSE_FLOOR_EXTRA_MARGIN_V = 2.0 // Collapse below the minimum target PV minus this cushion.

    const val RECOVERY_STABLE_ERR_V = 0.50 // VIN proximity to learned knee considered recovered enough for old ramp/verify paths.
    const val RECOVERY_DROP_FRACTION = 0.75 // Non-severe recovery current drop as a fraction of current ISET.
    const val RECOVERY_DROP_MIN_STEP_A = 1.0 // Minimum non-severe recovery current drop in amps.
    const val MAX_COLLAPSE_STEP_MULTIPLIER = 10 // Caps repeated-collapse acceleration before the voltage cap is applied.

    const val BATTERY_CURRENT_GAIN = 0.25 // SOC-hold proportional gain from BMS current error to ISET adjustment.
    const val VOLTAGE_LIMIT_EPS = 0.05 // Treat Riden VOUT within this much of max controller voltage as voltage-limited.
    const val VSET_UPDATE_EPS = 0.02 // Rewrite Riden VSET only when requested voltage changes by at least this much.

    const val ALARM_BASE_HOLD_MS = 60_000L // First BMS alarm-clear retry delay before charging resumes.
    const val ALARM_MAX_HOLD_MS = 30 * 60_000L // Longest retry delay after repeated BMS alarms.
    const val ALARM_REPEAT_WINDOW_MS = 10 * 60_000L // Alarm recurrences inside this window increase backoff.
    const val ALARM_STABLE_RESET_MS = 30 * 60_000L // Stable time before alarm backoff resets to the base delay.

    const val ACQUISITION_PAUSE_MS = 500L // Soak after hunt lock before next fast-acquire probe or power check.
    const val HUNT_LOCK_TICKS = 3 // Consecutive in-deadband ticks before hunt is considered locked.
    const val MAX_HUNT_LOCK_WAIT_MS = 5_000L // Force pause if hunt has not locked by this time.
    const val MIN_FAST_PROBE_SPACING_MS = 200L // Minimum spacing between fast-acquire down-probes.
    const val ACQUISITION_MAX_PROBE_CYCLE_MS = 8_000L // Upper bound for probe-caused recovery window timing.
    const val EXPECTED_PROBE_COLLAPSE_EXTRA_MS = 12_000L // Grace after a down-probe before collapse counts as probe-caused.
    const val MAINTENANCE_DOWNSHIFT_STABLE_MS = 5_000L // Stable time after maintenance down-probe with no recovery.
    const val POWER_PROBE_MIN_EPS_W = 6.0 // Minimum Pin drop to treat a down-probe as past MPP.
    const val POWER_PROBE_EPS_FRACTION = 0.05 // Relative Pin drop threshold for power-based stop.
}
