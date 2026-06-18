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
    val floorProbePhase: String = "--",
    val huntLockTicks: Int = 0,
    val estimatedPinWatts: Double = 0.0,
    val mppWantsLowerFloor: Boolean = false,
    val mppDirection: String = "--"
)

/**
 * Hardware-agnostic controller logic.
 *
 * Solar tracking uses perturb-and-observe on estimated input power (ISET primary).
 * The learned virtual knee is a VIN floor: raised on collapse, lowered only when MPP
 * wants more current but is blocked at the floor.
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
    private var lastFloorProbeMs = 0L
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
    private var pendingFloorDownProbe = false
    private var lastFloorDownProbeMs = 0L
    private var kneeProbeFast = false
    private var slowFloorSuccessCount = 0
    private var watchingSlowFloorStability = false
    private var lastSlowFloorProbeMs = 0L
    private var probePacingPhase = FloorProbePhase.IDLE
    private var huntLockConsecutiveTicks = 0
    private var probeWaitSinceMs = 0L
    private var probePauseUntilMs = 0L
    private var lastEstimatedPinW = 0.0
    private var mppWantsLowerFloor = false
    private var mppDirection = Direction.Up
    private var mppRefPinW = 0.0
    private var mppLastEvalMs = 0L
    private var lastMppStepAmps = 0.0

    fun resetLearnedKnee() {
        kneeOffsetVolts = 0.0
        lastFloorProbeMs = System.currentTimeMillis()
        recentCollapseCount = 0
        lastCollapseMs = 0L
        stableSinceMs = 0L
        resetFastFloorState()
        resetMppState()
    }

    fun setActiveKnee(settings: AppSettings, targetPvVolts: Double) {
        kneeOffsetVolts = clampKnee(settings, targetPvVolts) - settings.targetPvVolts
        lastFloorProbeMs = System.currentTimeMillis()
        recentCollapseCount = 0
        lastCollapseMs = 0L
        stableSinceMs = 0L
        resetFastFloorState()
        resetMppState()
    }

    private fun resetFastFloorState() {
        pendingFloorDownProbe = false
        kneeProbeFast = false
        slowFloorSuccessCount = 0
        watchingSlowFloorStability = false
        lastSlowFloorProbeMs = 0L
        resetFloorProbePacing()
    }

    private fun resetFloorProbePacing() {
        probePacingPhase = FloorProbePhase.IDLE
        huntLockConsecutiveTicks = 0
        probeWaitSinceMs = 0L
        probePauseUntilMs = 0L
    }

    private fun resetMppState() {
        mppWantsLowerFloor = false
        mppDirection = Direction.Up
        mppRefPinW = 0.0
        mppLastEvalMs = 0L
        lastMppStepAmps = 0.0
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
        val virtualFloor = virtualFloor(settings)
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
            recoveryPhase != PHASE_NONE -> recover(settings, oldIset, vin, vout, riden.watts ?: 0.0, virtualFloor, currentLimit, now)
            isCollapsed(settings, vin) -> enterRecovery(settings, oldIset, currentLimit, vin, now)
            socPercent >= socTarget || voltageLimited -> {
                pvMode = if (balanceDay) MODE_BALANCE else if (socPercent >= socTarget) MODE_SOC_HOLD else MODE_VOLTAGE_LIMIT
                recoveryPhase = PHASE_NONE
                updateStableRecoveryReset(false, settings, vin, virtualFloor, now)
                holdBatteryCurrentAtSolarKnee(oldIset, batteryAmps, targetBatteryCurrent, vin, virtualFloor)
                    .coerceAtMost(currentLimit)
            }
            else -> trackSolar(settings, oldIset, vin, vout, riden.watts ?: 0.0, virtualFloor, currentLimit, now)
        }.coerceIn(Tuning.MIN_ISET, currentLimit)

        commandIset = quantAmps(nextIset)
        setIset(commandIset)

        val finalVinError = vin - virtualFloor(settings)
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

    private fun trackSolar(
        settings: AppSettings,
        oldIset: Double,
        vin: Double,
        vout: Double,
        outputWatts: Double,
        virtualFloor: Double,
        policyLimit: Double,
        now: Long
    ): Double {
        pvMode = MODE_TRACKING
        updateStableRecoveryReset(true, settings, vin, virtualFloor, now)

        if (probePacingPhase != FloorProbePhase.IDLE) {
            updateFloorProbePacing(vin, virtualFloor, now)
            if (vin >= virtualFloor - Tuning.RECOVERY_STABLE_ERR_V) {
                lastWorkingIset = max(lastWorkingIset * 0.8 + oldIset * 0.2, Tuning.MIN_ISET)
            }
            return huntVinToFloor(oldIset, vin, virtualFloor)
        }

        updateSlowFloorStability(settings, now)

        val next = mppTrackIset(oldIset, vin, vout, outputWatts, virtualFloor, policyLimit, now)

        if (mppWantsLowerFloor) {
            maybeFloorDownProbe(settings, now)
        }

        if (vin >= virtualFloor - Tuning.RECOVERY_STABLE_ERR_V) {
            lastWorkingIset = max(lastWorkingIset * 0.8 + oldIset * 0.2, Tuning.MIN_ISET)
        }
        return next
    }

    private fun mppTrackIset(
        oldIset: Double,
        vin: Double,
        vout: Double,
        outputWatts: Double,
        virtualFloor: Double,
        policyLimit: Double,
        now: Long
    ): Double {
        val inputPowerW = estimateInputPowerW(outputWatts, vin, vout)
        if (inputPowerW <= 0.0) return oldIset

        updateMppFloorWant(oldIset, vin, virtualFloor, policyLimit)

        if (vin < virtualFloor - Tuning.TRACK_DEADBAND_V) {
            return huntVinToFloor(oldIset, vin, virtualFloor)
        }

        if (now - mppLastEvalMs < Tuning.MPP_SETTLE_MS) {
            return oldIset
        }

        if (mppRefPinW <= 0.0) {
            mppRefPinW = inputPowerW
            mppLastEvalMs = now
            return oldIset
        }

        val pinDelta = inputPowerW - mppRefPinW
        val pinEps = max(Tuning.MPP_PIN_MIN_EPS_W, mppRefPinW * Tuning.MPP_PIN_EPS_FRACTION)
        if (pinDelta < -pinEps) {
            mppDirection = mppDirection.opposite()
        }

        val step = mppStepAmps(oldIset)
        lastMppStepAmps = step
        val blockedAtFloor = mppWantsLowerFloor &&
            mppDirection == Direction.Up &&
            vin <= virtualFloor + Tuning.FLOOR_MARGIN_V

        val next = when {
            blockedAtFloor -> oldIset
            mppDirection == Direction.Up -> oldIset + step
            else -> oldIset - step
        }

        mppRefPinW = mppRefPinW * 0.65 + inputPowerW * 0.35
        mppLastEvalMs = now
        return next
    }

    private fun updateMppFloorWant(
        oldIset: Double,
        vin: Double,
        virtualFloor: Double,
        policyLimit: Double
    ) {
        val atFloor = vin <= virtualFloor + Tuning.FLOOR_MARGIN_V
        val wantsMore = mppDirection == Direction.Up && oldIset < policyLimit - Tuning.MIN_ISET
        val headroom = vin > virtualFloor + Tuning.FLOOR_CLEAR_MARGIN_V

        if (wantsMore && atFloor) {
            if (!mppWantsLowerFloor) {
                onEvent("MPP wants lower floor (VIN at ${"%.2f".format(vin)}V, floor ${"%.2f".format(virtualFloor)}V)")
            }
            mppWantsLowerFloor = true
        } else if (headroom && !wantsMore) {
            mppWantsLowerFloor = false
        }
    }

    private fun mppStepAmps(iset: Double): Double {
        val multiplier = ((iset.coerceAtLeast(0.0) / 10.0) * Tuning.ISET_STEP_MULTIPLIER_AT_10A)
            .coerceAtLeast(1.0)
        return (Tuning.MPP_BASE_STEP_A * multiplier).coerceAtMost(Tuning.MAX_STEP_UP_A)
    }

    private fun huntVinToFloor(oldIset: Double, vin: Double, virtualFloor: Double): Double {
        val error = vin - virtualFloor
        val absErr = abs(error)
        return when {
            error > Tuning.TRACK_DEADBAND_V -> oldIset + huntStep(absErr, oldIset, Direction.Up)
            error < -Tuning.TRACK_DEADBAND_V -> oldIset - huntStep(absErr, oldIset, Direction.Down)
            else -> oldIset
        }
    }

    private fun updateFloorProbePacing(vin: Double, virtualFloor: Double, now: Long) {
        when (probePacingPhase) {
            FloorProbePhase.WAIT_HUNT_LOCK -> {
                if (abs(vin - virtualFloor) <= Tuning.TRACK_DEADBAND_V) {
                    huntLockConsecutiveTicks += 1
                } else {
                    huntLockConsecutiveTicks = 0
                }
                val locked = huntLockConsecutiveTicks >= Tuning.HUNT_LOCK_TICKS
                val timedOut = now - probeWaitSinceMs >= Tuning.MAX_HUNT_LOCK_WAIT_MS
                if (locked || timedOut) {
                    probePacingPhase = FloorProbePhase.PAUSE
                    probePauseUntilMs = now + Tuning.FLOOR_PROBE_PAUSE_MS
                    huntLockConsecutiveTicks = 0
                }
            }
            FloorProbePhase.PAUSE -> {
                if (now >= probePauseUntilMs) {
                    probePacingPhase = FloorProbePhase.IDLE
                    mppRefPinW = 0.0
                    mppLastEvalMs = now
                }
            }
            FloorProbePhase.IDLE -> Unit
        }
    }

    private fun updateSlowFloorStability(settings: AppSettings, now: Long) {
        if (!watchingSlowFloorStability || kneeProbeFast) return
        if (now - lastSlowFloorProbeMs < Tuning.SLOW_FLOOR_STABLE_MS) return

        watchingSlowFloorStability = false
        slowFloorSuccessCount += 1
        val required = settings.fastAcquireSuccessCount.coerceIn(1, 5)
        if (slowFloorSuccessCount >= required) {
            slowFloorSuccessCount = 0
            startFastFloorProbing()
        }
    }

    private fun startFastFloorProbing() {
        kneeProbeFast = true
        resetFloorProbePacing()
        onEvent("Fast floor probing started")
    }

    private fun maybeFloorDownProbe(settings: AppSettings, now: Long) {
        if (probePacingPhase != FloorProbePhase.IDLE) return

        if (kneeProbeFast) {
            if (now - lastFloorProbeMs < Tuning.MIN_FAST_PROBE_SPACING_MS) return
            executeFloorDownProbe(settings, now, fast = true)
            return
        }

        if (now - lastFloorProbeMs < slowFloorProbeDelayMs(settings)) return
        executeFloorDownProbe(settings, now, fast = false)
    }

    private fun executeFloorDownProbe(settings: AppSettings, now: Long, fast: Boolean) {
        lastFloorProbeMs = now
        lastFloorDownProbeMs = now
        pendingFloorDownProbe = true
        kneeOffsetVolts = clampKnee(settings, settings.targetPvVolts + kneeOffsetVolts - settings.kneeStepVolts) -
            settings.targetPvVolts

        probePacingPhase = FloorProbePhase.WAIT_HUNT_LOCK
        probeWaitSinceMs = now
        huntLockConsecutiveTicks = 0
        mppRefPinW = 0.0
        mppLastEvalMs = now

        if (!fast) {
            watchingSlowFloorStability = true
            lastSlowFloorProbeMs = now
        }
    }

    private fun slowFloorProbeDelayMs(settings: AppSettings): Long {
        return (settings.kneeTrackingDelaySeconds * 1000.0).toLong()
    }

    private fun expectedProbeCollapseWindowMs(settings: AppSettings): Long {
        val cycleMs = if (kneeProbeFast) {
            Tuning.FAST_FLOOR_MAX_PROBE_CYCLE_MS
        } else {
            slowFloorProbeDelayMs(settings)
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
        val probeCausedRecovery = pendingFloorDownProbe &&
            (now - lastFloorDownProbeMs) <= expectedProbeCollapseWindowMs(settings)
        pendingFloorDownProbe = false
        watchingSlowFloorStability = false
        resetFloorProbePacing()
        mppWantsLowerFloor = false

        if (kneeProbeFast && probeCausedRecovery) {
            kneeProbeFast = false
            onEvent("Fast floor probing ended (collapse)")
            slowFloorSuccessCount = 0
        } else {
            slowFloorSuccessCount = 0
        }

        lastWorkingIset = max(oldIset, commandIset).coerceAtLeast(Tuning.MIN_ISET)
        recoveryPhase = PHASE_WAIT_VIN
        pvMode = MODE_RECOVER
        recoveryCycleCount += 1
        stableSinceMs = 0L
        raiseFloorForCollapse(settings)
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
        virtualFloor: Double,
        policyLimit: Double,
        now: Long
    ): Double {
        pvMode = MODE_RECOVER
        stableSinceMs = 0L

        if (isCollapsed(settings, vin)) {
            if (recoveryPhase != PHASE_WAIT_VIN) {
                raiseFloorForCollapse(settings)
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
                recoveryPhase = PHASE_NONE
                pvMode = MODE_TRACKING
                trackSolar(
                    settings,
                    oldIset,
                    vin,
                    vout,
                    outputWatts,
                    virtualFloor,
                    policyLimit,
                    now
                )
            }
        }
    }

    private fun isCollapsed(settings: AppSettings, vin: Double): Boolean {
        val collapseFloor = lowKneeLimit(settings) - Tuning.COLLAPSE_FLOOR_EXTRA_MARGIN_V
        return vin > 0.0 && vin < collapseFloor
    }

    private fun recoveryExitFloor(settings: AppSettings): Double {
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
        virtualFloor: Double
    ): Double {
        val batteryDemandIset = adjustForBatteryCurrent(oldIset, batteryAmps, targetBatteryAmps)
        val kneeError = vin - virtualFloor
        return when {
            kneeError < -Tuning.TRACK_DEADBAND_V ->
                oldIset - huntStep(abs(kneeError), oldIset, Direction.Down)
            else -> batteryDemandIset
        }
    }

    private fun recoveryDropStep(oldIset: Double): Double {
        return max(Tuning.RECOVERY_DROP_FRACTION * oldIset, Tuning.RECOVERY_DROP_MIN_STEP_A)
    }

    private fun huntStep(absErr: Double, iset: Double, direction: Direction): Double {
        return scaledStepForAbsError(absErr, iset, direction)
    }

    private fun stepFor(vinError: Double): Double {
        if (pvMode == MODE_RECOVER) return 0.0
        if (probePacingPhase != FloorProbePhase.IDLE) {
            if (abs(vinError) <= Tuning.TRACK_DEADBAND_V) return 0.0
            val direction = if (vinError > 0.0) Direction.Up else Direction.Down
            return scaledStepForAbsError(abs(vinError), commandIset, direction)
        }
        return if (pvMode == MODE_TRACKING) lastMppStepAmps else 0.0
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

    private fun raiseFloorForCollapse(settings: AppSettings) {
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
            lastFloorProbeMs = now
            mppWantsLowerFloor = false
            resetFloorProbePacing()
            mppRefPinW = 0.0
        }
    }

    private fun maxCollapseStepVolts(settings: AppSettings): Double {
        val fullWindow = (highKneeLimit(settings) - lowKneeLimit(settings)).coerceAtLeast(0.0)
        return (round((fullWindow / 6.0) * 10.0) / 10.0).coerceAtLeast(settings.kneeStepVolts)
    }

    private fun repeatedCollapseWindowMs(settings: AppSettings): Long {
        return (settings.kneeTrackingDelaySeconds * 750.0).toLong().coerceIn(2_000L, 10_000L)
    }

    private fun updateStableRecoveryReset(
        tracking: Boolean,
        settings: AppSettings,
        vin: Double,
        virtualFloor: Double,
        now: Long
    ) {
        if (recoveryCycleCount == 0) return

        val stable = tracking &&
            vin >= recoveryExitFloor(settings) &&
            abs(vin - virtualFloor) <= Tuning.RECOVERY_STABLE_ERR_V

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
        return repeatedCollapseWindowMs(settings)
    }

    private fun statusFor(balanceDay: Boolean, socReached: Boolean, voltageLimited: Boolean): String {
        return when {
            pvMode == MODE_RECOVER -> "Solar recovery: $recoveryPhase"
            balanceDay -> "Balance day: SOC target 100%"
            socReached -> "SOC ceiling reached: load support"
            voltageLimited -> "Controller voltage limit"
            probePacingPhase != FloorProbePhase.IDLE -> "Floor probe: ${probePacingPhase.label}"
            mppWantsLowerFloor -> "MPP tracking (wants lower floor)" + if (kneeProbeFast) " (fast)" else ""
            else -> "MPP tracking" + if (kneeProbeFast) " (fast floor)" else ""
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
        resetFastFloorState()
        resetMppState()
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

    private fun virtualFloor(settings: AppSettings): Double {
        return quantVolts(clampKnee(settings, settings.targetPvVolts + kneeOffsetVolts))
    }

    private fun clampKnee(settings: AppSettings, volts: Double): Double {
        return volts.coerceIn(lowKneeLimit(settings), highKneeLimit(settings))
    }

    private fun lowKneeLimit(settings: AppSettings): Double = minOf(settings.minTargetPvVolts, settings.maxTargetPvVolts)

    private fun highKneeLimit(settings: AppSettings): Double = maxOf(settings.minTargetPvVolts, settings.maxTargetPvVolts)

    private fun bandFor(vinError: Double): String {
        if (pvMode == MODE_RECOVER) return "RE"
        if (probePacingPhase != FloorProbePhase.IDLE) {
            val absErr = abs(vinError)
            return when {
                absErr > Tuning.V4 -> "HU"
                absErr > Tuning.V3 -> "FA"
                absErr > Tuning.V2 -> "MD"
                absErr > Tuning.V1 -> "NE"
                else -> "FI"
            }
        }
        if (pvMode == MODE_TRACKING) return "MP"
        val absErr = abs(vinError)
        return when {
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
        val targetPv = virtualFloor(settings)
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
                floorProbePhase = probePacingPhase.label,
                huntLockTicks = huntLockConsecutiveTicks,
                estimatedPinWatts = lastEstimatedPinW,
                mppWantsLowerFloor = mppWantsLowerFloor,
                mppDirection = mppDirection.label
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

    private enum class Direction(val label: String) {
        Up("Up"),
        Down("Down");

        fun opposite(): Direction = if (this == Up) Down else Up
    }

    private enum class FloorProbePhase(val label: String) {
        IDLE("--"),
        WAIT_HUNT_LOCK("WaitLock"),
        PAUSE("Pause")
    }
}

/**
 * Controller parameters not exposed in Settings.
 *
 * Model overview:
 * 1. Battery policy caps current from BMS SOC, BMS alarms, and max Riden voltage.
 * 2. MPP perturb-and-observe on estimated Pin adjusts ISET as the primary tracker.
 * 3. Virtual knee is a VIN floor: raised on collapse; lowered only when MPP is
 *    blocked at the floor and a deliberate floor probe succeeds.
 */
private object Tuning {
    const val MIN_ISET = 0.01

    const val FINE_STEP_A = 0.01
    const val NEAR_STEP_A = 0.02
    const val MID_STEP_A = 0.05
    const val FAR_STEP_A = 0.10
    const val HUGE_STEP_A = 0.50
    const val V1 = 0.25
    const val V2 = 0.75
    const val V3 = 1.50
    const val V4 = 2.00
    const val TRACK_DEADBAND_V = 0.02
    const val ISET_STEP_MULTIPLIER_AT_10A = 5.0
    const val MAX_STEP_UP_A = 1.5
    const val MAX_STEP_DOWN_A = 10.0

    const val MPP_BASE_STEP_A = 0.02
    const val MPP_SETTLE_MS = 400L
    const val MPP_PIN_MIN_EPS_W = 3.0
    const val MPP_PIN_EPS_FRACTION = 0.02
    const val FLOOR_MARGIN_V = 0.15
    const val FLOOR_CLEAR_MARGIN_V = 0.50

    const val COLLAPSE_FLOOR_EXTRA_MARGIN_V = 2.0

    const val RECOVERY_STABLE_ERR_V = 0.50
    const val RECOVERY_DROP_FRACTION = 0.75
    const val RECOVERY_DROP_MIN_STEP_A = 1.0
    const val MAX_COLLAPSE_STEP_MULTIPLIER = 10

    const val BATTERY_CURRENT_GAIN = 0.25
    const val VOLTAGE_LIMIT_EPS = 0.05
    const val VSET_UPDATE_EPS = 0.02

    const val ALARM_BASE_HOLD_MS = 60_000L
    const val ALARM_MAX_HOLD_MS = 30 * 60_000L
    const val ALARM_REPEAT_WINDOW_MS = 10 * 60_000L
    const val ALARM_STABLE_RESET_MS = 30 * 60_000L

    const val FLOOR_PROBE_PAUSE_MS = 500L
    const val HUNT_LOCK_TICKS = 3
    const val MAX_HUNT_LOCK_WAIT_MS = 5_000L
    const val MIN_FAST_PROBE_SPACING_MS = 200L
    const val FAST_FLOOR_MAX_PROBE_CYCLE_MS = 8_000L
    const val EXPECTED_PROBE_COLLAPSE_EXTRA_MS = 12_000L
    const val SLOW_FLOOR_STABLE_MS = 5_000L
}
