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
    val controlBand: String = "--",
    val chargeVoltageTarget: Double = 0.0,
    val recoveryActive: Boolean = false,
    val socTargetPercent: Int = 0
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
    private val onState: (MpptControlState) -> Unit
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
    private var alarmWasActive = false
    private var alarmHoldUntilMs = 0L
    private var alarmBackoffMs = Tuning.ALARM_BASE_HOLD_MS
    private var lastAlarmStartMs = 0L

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
        // Battery policy layer: BMS/SOC/voltage safety decides the maximum current that
        // the solar layer may request. It does not decide how to hunt the solar knee.
        val voltageLimited = vout >= settings.maxBatteryVolts - Tuning.VOLTAGE_LIMIT_EPS
        val policyLimit = when {
            socPercent >= socTarget -> settings.socHoldCurrentAmps
            voltageLimited -> settings.socHoldCurrentAmps
            else -> settings.maxChargeAmps
        }.coerceAtLeast(Tuning.MIN_ISET)

        ensureOutputAndVoltage(settings)

        val oldIset = currentIset(riden.iset)
        val nextIset = when {
            recoveryPhase != PHASE_NONE -> recover(settings, oldIset, vin, virtualKnee)
            isCollapsed(settings, vin) -> enterRecovery(settings, oldIset, policyLimit, vin)
            socPercent >= socTarget || voltageLimited -> {
                pvMode = if (balanceDay) MODE_BALANCE else if (socPercent >= socTarget) MODE_SOC_HOLD else MODE_VOLTAGE_LIMIT
                recoveryPhase = PHASE_NONE
                adjustForBatteryCurrent(oldIset, batteryAmps, policyLimit).coerceAtMost(policyLimit)
            }
            else -> trackSolarKnee(settings, oldIset, vin, virtualKnee, now)
        }.coerceIn(Tuning.MIN_ISET, policyLimit.coerceAtLeast(Tuning.MIN_ISET))

        commandIset = quantAmps(nextIset)
        setIset(commandIset)

        val finalVinError = vin - virtualKnee(settings)
        publish(
            settings = settings,
            status = statusFor(balanceDay, socPercent >= socTarget, voltageLimited),
            policyLimit = policyLimit,
            band = bandFor(finalVinError),
            vinError = finalVinError,
            socTargetPercent = socTarget
        )
    }

    private fun trackSolarKnee(
        settings: AppSettings,
        oldIset: Double,
        vin: Double,
        virtualKnee: Double,
        now: Long
    ): Double {
        pvMode = MODE_TRACKING

        // Solar tracker layer: push current up when VIN is above the virtual knee, pull it
        // down when VIN is below. The table keeps very small corrections near the knee
        // while still allowing decisive movement when VIN is far from target.
        val error = vin - virtualKnee
        val absErr = abs(error)
        val step = huntStep(absErr)
        val next = when {
            error > Tuning.TRACK_DEADBAND_V -> oldIset + step
            error < -Tuning.TRACK_DEADBAND_V -> oldIset - step
            else -> oldIset
        }

        if (vin >= virtualKnee - Tuning.RECOVERY_STABLE_ERR_V) {
            lastWorkingIset = max(lastWorkingIset * 0.8 + oldIset * 0.2, Tuning.MIN_ISET)
        }

        // Periodically try a lower virtual knee. If that asks too much of the panels,
        // collapse recovery will raise the knee back up.
        if (now - lastKneeProbeMs >= (settings.kneeTrackingDelaySeconds * 1000.0).toLong()) {
            lastKneeProbeMs = now
            kneeOffsetVolts = (kneeOffsetVolts - settings.kneeStepVolts).coerceIn(
                -settings.kneeVarianceVolts,
                settings.kneeVarianceVolts
            )
        }

        return next
    }

    private fun enterRecovery(
        settings: AppSettings,
        oldIset: Double,
        policyLimit: Double,
        vin: Double
    ): Double {
        lastWorkingIset = max(oldIset, commandIset).coerceAtLeast(Tuning.MIN_ISET)
        recoveryPhase = PHASE_WAIT_VIN
        pvMode = MODE_RECOVER
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
        virtualKnee: Double
    ): Double {
        pvMode = MODE_RECOVER

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
                trackSolarKnee(settings, oldIset, vin, virtualKnee, System.currentTimeMillis())
            }
        }
    }

    private fun isCollapsed(settings: AppSettings, vin: Double): Boolean {
        val collapseFloor = settings.targetPvVolts - settings.kneeVarianceVolts - Tuning.COLLAPSE_FLOOR_EXTRA_MARGIN_V
        return vin > 0.0 && vin < collapseFloor
    }

    private fun recoveryExitFloor(settings: AppSettings): Double {
        // Recovery exits at the low edge of the configured knee window. With target
        // 33V and variance 3V, VIN >= 30V means the panel is no longer collapsed.
        return settings.targetPvVolts - settings.kneeVarianceVolts
    }

    private fun adjustForBatteryCurrent(oldIset: Double, batteryAmps: Double, targetBatteryAmps: Double): Double {
        return oldIset + (targetBatteryAmps - batteryAmps) * Tuning.BATTERY_CURRENT_GAIN
    }

    private fun recoveryDropStep(oldIset: Double): Double {
        return max(Tuning.RECOVERY_DROP_MIN_STEP_A, oldIset * Tuning.RECOVERY_DROP_FRACTION)
    }

    private fun huntStep(absErr: Double): Double {
        return when {
            absErr > Tuning.V4 -> Tuning.HUGE_STEP_A
            absErr > Tuning.V3 -> Tuning.FAR_STEP_A
            absErr > Tuning.V2 -> Tuning.MID_STEP_A
            absErr > Tuning.V1 -> Tuning.NEAR_STEP_A
            else -> Tuning.FINE_STEP_A
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
        kneeOffsetVolts = (kneeOffsetVolts + step).coerceIn(
            -settings.kneeVarianceVolts,
            settings.kneeVarianceVolts
        )
    }

    private fun maxCollapseStepVolts(settings: AppSettings): Double {
        // The full learned-knee window is target +/- variance. Divide that full span
        // by six so worst-case repeated collapse can traverse the window in six bumps,
        // then round to tenths for predictable tuning.
        val fullWindow = settings.kneeVarianceVolts * 2.0
        return (round((fullWindow / 6.0) * 10.0) / 10.0).coerceAtLeast(settings.kneeStepVolts)
    }

    private fun repeatedCollapseWindowMs(settings: AppSettings): Long {
        // This window must be shorter than the deliberate knee-probe delay, otherwise
        // ordinary scheduled probes can look like repeated fast collapses. Keeping it
        // around 75% of the delay preserves acceleration for rapid cloud changes.
        return (settings.kneeTrackingDelaySeconds * 750.0).toLong().coerceIn(2_000L, 10_000L)
    }

    private fun statusFor(balanceDay: Boolean, socReached: Boolean, voltageLimited: Boolean): String {
        return when {
            pvMode == MODE_RECOVER -> "Solar recovery: $recoveryPhase"
            balanceDay -> "Balance day: SOC target 100%"
            socReached -> "SOC ceiling reached: load support"
            voltageLimited -> "Controller voltage limit"
            else -> "Tracking solar knee"
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
        return quantVolts(settings.targetPvVolts + kneeOffsetVolts)
    }

    private fun bandFor(vinError: Double): String {
        val absErr = abs(vinError)
        return when {
            pvMode == MODE_RECOVER -> "RE"
            absErr > 1.50 -> "HU"
            absErr > 1.00 -> "FA"
            absErr > 0.50 -> "MD"
            absErr > 0.25 -> "NE"
            else -> "FI"
        }
    }

    private fun publish(
        settings: AppSettings,
        status: String,
        policyLimit: Double,
        band: String,
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
                controlBand = band,
                chargeVoltageTarget = lastVset,
                recoveryActive = pvMode == MODE_RECOVER,
                socTargetPercent = socTargetPercent
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
    const val NEAR_STEP_A = 0.05 // ISET change when VIN error is between V1 and V2.
    const val MID_STEP_A = 0.10 // ISET change when VIN error is between V2 and V3.
    const val FAR_STEP_A = 0.30 // ISET change when VIN error is between V3 and V4.
    const val HUGE_STEP_A = 1.00 // ISET change when VIN error is larger than V4.
    const val V1 = 0.25 // Fine/near VIN error boundary in volts.
    const val V2 = 0.50 // Near/mid VIN error boundary in volts.
    const val V3 = 1.00 // Mid/far VIN error boundary in volts.
    const val V4 = 1.50 // Far/huge VIN error boundary in volts.
    const val TRACK_DEADBAND_V = 0.05 // VIN error small enough to leave ISET unchanged this tick.

    const val COLLAPSE_FLOOR_EXTRA_MARGIN_V = 2.0 // Collapse below target PV minus knee variance minus this cushion.

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
}
