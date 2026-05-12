package com.example.ridenbmscontroller.controller

import com.example.ridenbmscontroller.ble.BmsBleUiState
import com.example.ridenbmscontroller.model.AppSettings
import com.example.ridenbmscontroller.riden.RidenUsbState
import java.util.Calendar
import kotlin.math.abs

data class MpptControlState(
    val enabled: Boolean = false,
    val pvMode: String = "Idle",
    val status: String = "Controller off",
    val targetChargeCurrent: Double = 0.0,
    val commandIset: Double = 0.0,
    val targetPvVolts: Double = 0.0,
    val kneeOffsetVolts: Double = 0.0,
    val controlBand: String = "--",
    val chargeVoltageTarget: Double = 0.0,
    val recoveryActive: Boolean = false,
    val socTargetPercent: Int = 0
)

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
    private var pvMode = "Idle"
    private var collapseRecoveryUntilMs = 0L
    private var kneeOffsetVolts = 0.0
    private var lastNoKneeMs = 0L
    private var firstCollapseIgnored = false
    private var lastGoodScaleAmps = 1.0
    private var lastTickMs = 0L
    private var alarmWasActive = false
    private var alarmHoldUntilMs = 0L
    private var alarmBackoffMs = ALARM_BASE_HOLD_MS
    private var lastAlarmStartMs = 0L

    fun tick(
        settings: AppSettings,
        bmsState: BmsBleUiState,
        ridenState: RidenUsbState
    ) {
        val now = System.currentTimeMillis()
        if (now - lastTickMs < LOOP_MS) return
        lastTickMs = now

        val riden = ridenState.telemetry
        if (!settings.controllerEnabled || !ridenState.connected) {
            if (outputEnabled) {
                setOutput(false)
                outputEnabled = false
            }
            commandIset = 0.0
            kneeOffsetVolts = 0.0
            firstCollapseIgnored = false
            alarmWasActive = false
            alarmHoldUntilMs = 0L
            pvMode = "Idle"
            publish(settings, "Controller off", 0.0, "--")
            return
        }

        val bms = bmsState.telemetry
        val batteryVolts = bms.packVoltage
        val batteryAmps = bms.packCurrent
        val socPercent = bms.socPercent
        if (bmsState.connectedDeviceAddress == null || batteryVolts == null || batteryAmps == null || socPercent == null) {
            if (outputEnabled) {
                setOutput(false)
                outputEnabled = false
            }
            commandIset = 0.0
            kneeOffsetVolts = 0.0
            firstCollapseIgnored = false
            alarmWasActive = false
            alarmHoldUntilMs = 0L
            pvMode = "Idle"
            publish(settings, "BMS required: controller idle", 0.0, "--")
            return
        }

        val epochDay = currentEpochDay()
        val balanceDay = settings.balanceEveryDays > 0 &&
            (settings.lastBalanceEpochDay == epochDay ||
                settings.lastBalanceEpochDay <= 0L ||
                epochDay - settings.lastBalanceEpochDay >= settings.balanceEveryDays)
        if (balanceDay && settings.lastBalanceEpochDay != epochDay) {
            onBalanceDayStarted(epochDay)
        }
        val socCeiling = if (balanceDay) 100 else settings.normalSocCeilingPercent

        val chargeBlockingAlarms = bms.chargeBlockingAlarmNames
        if (chargeBlockingAlarms.isNotEmpty()) {
            if (!alarmWasActive) {
                alarmBackoffMs = if (lastAlarmStartMs > 0L && now - lastAlarmStartMs <= ALARM_REPEAT_WINDOW_MS) {
                    (alarmBackoffMs * 2).coerceAtMost(ALARM_MAX_HOLD_MS)
                } else {
                    ALARM_BASE_HOLD_MS
                }
                lastAlarmStartMs = now
            }
            alarmWasActive = true
            alarmHoldUntilMs = now + alarmBackoffMs
            inhibitCharging(
                settings = settings,
                status = "BMS alarm: ${chargeBlockingAlarms.joinToString()}",
                socTargetPercent = socCeiling
            )
            return
        }

        if (alarmWasActive) {
            alarmWasActive = false
            alarmHoldUntilMs = now + alarmBackoffMs
        }
        if (now < alarmHoldUntilMs) {
            val seconds = ((alarmHoldUntilMs - now) / 1000L).coerceAtLeast(1L)
            inhibitCharging(
                settings = settings,
                status = "BMS alarm cleared: retry in ${seconds}s",
                socTargetPercent = socCeiling
            )
            return
        }
        if (lastAlarmStartMs > 0L && now - lastAlarmStartMs >= ALARM_STABLE_RESET_MS) {
            alarmBackoffMs = ALARM_BASE_HOLD_MS
        }

        val vin = riden.vin ?: 0.0
        var targetPv = quantVolts(settings.targetPvVolts + kneeOffsetVolts)

        val socCeilingReached = socPercent >= socCeiling
        val chargeVoltage = settings.maxBatteryVolts
        val controllerVolts = riden.vout ?: 0.0
        val voltageLimited = controllerVolts >= (chargeVoltage - 0.05)
        val targetChargeCurrent = when {
            socCeilingReached -> settings.socHoldCurrentAmps
            voltageLimited -> settings.socHoldCurrentAmps
            else -> settings.maxChargeAmps
        }

        val targetVset = chargeVoltage
        if (!outputEnabled) {
            setOutput(true)
            outputEnabled = true
        }
        if (abs(lastVset - targetVset) >= 0.02) {
            setVset(targetVset)
            lastVset = targetVset
        }

        val oldIset = if (commandIset > 0.0) commandIset else (riden.iset ?: MIN_ISET).coerceAtLeast(MIN_ISET)
        val collapseNow = !voltageLimited && vin > 0.0 && vin < targetPv - COLLAPSE_DROP_V
        val kneeTrigger = !voltageLimited && collapseNow

        if (voltageLimited) {
            lastNoKneeMs = now
        }

        if (kneeTrigger) {
            lastNoKneeMs = now
            if (firstCollapseIgnored) {
                kneeOffsetVolts = (kneeOffsetVolts + KNEE_STEP_V).coerceIn(KNEE_MIN_V, KNEE_MAX_V)
            } else {
                firstCollapseIgnored = true
            }
            targetPv = quantVolts(settings.targetPvVolts + kneeOffsetVolts)
        }

        if (!voltageLimited && pvMode == "Tracking" && now - lastNoKneeMs >= (settings.kneeTrackingDelaySeconds * 1000.0).toLong()) {
            if (vin >= targetPv - KNEE_DECAY_STABLE_EPS) {
                kneeOffsetVolts = (kneeOffsetVolts - KNEE_STEP_V).coerceIn(KNEE_MIN_V, KNEE_MAX_V)
                lastNoKneeMs = now
                targetPv = quantVolts(settings.targetPvVolts + kneeOffsetVolts)
            }
        }

        val absErr = abs(vin - targetPv)
        val band = when {
            kneeTrigger -> "RE"
            absErr > V4 -> "HU"
            absErr > V3 -> "FA"
            absErr > V2 -> "MD"
            absErr > V1 -> "NE"
            else -> "FI"
        }

        if (pvMode == "Tracking" && !voltageLimited && absErr <= HUNT_SCALE_GOOD_ERR_V) {
            lastGoodScaleAmps = (lastGoodScaleAmps * 0.9) + (maxOf(riden.iout ?: 0.0, oldIset, 1.0) * 0.1)
        }

        commandIset = when {
            socCeilingReached -> {
                pvMode = if (balanceDay) "Balance" else "SOC Hold"
                adjustForBatteryCurrent(oldIset, batteryAmps, targetChargeCurrent)
            }
            kneeTrigger -> {
                collapseRecoveryUntilMs = now + (settings.kneeTrackingDelaySeconds * 1000.0).toLong()
                pvMode = "Recover"
                (oldIset * 0.70).coerceAtLeast(MIN_ISET)
            }
            now < collapseRecoveryUntilMs -> {
                pvMode = "Recover"
                (oldIset + 0.05).coerceAtMost(targetChargeCurrent)
            }
            voltageLimited -> {
                pvMode = when {
                    balanceDay -> "Balance"
                    socCeilingReached -> "SOC Hold"
                    else -> "Voltage Limit"
                }
                adjustForBatteryCurrent(oldIset, batteryAmps, targetChargeCurrent)
            }
            vin > targetPv + PV_BAND_V -> {
                pvMode = "Tracking"
                (oldIset + huntStepUp(absErr, lastGoodScaleAmps)).coerceAtMost(targetChargeCurrent)
            }
            vin < targetPv - PV_BAND_V -> {
                pvMode = "Tracking"
                (oldIset - huntStepDown(absErr)).coerceAtLeast(MIN_ISET)
            }
            else -> {
                pvMode = "Tracking"
                oldIset.coerceAtMost(targetChargeCurrent)
            }
        }.coerceIn(MIN_ISET, settings.maxChargeAmps.coerceAtLeast(MIN_ISET))

        setIset(commandIset)
        publish(
            settings = settings,
            status = when {
                balanceDay -> "Balance day: SOC target 100%"
                socCeilingReached -> "SOC ceiling reached: load support"
                voltageLimited -> "Battery voltage limiting"
                else -> "MPPT tracking"
            },
            targetChargeCurrent = targetChargeCurrent,
            band = band,
            recoveryActive = pvMode == "Recover",
            socTargetPercent = socCeiling
        )
    }

    private fun adjustForBatteryCurrent(
        oldIset: Double,
        batteryAmps: Double,
        targetBatteryAmps: Double
    ): Double {
        val error = targetBatteryAmps - batteryAmps
        return (oldIset + error * 0.25).coerceAtLeast(MIN_ISET)
    }

    private fun inhibitCharging(settings: AppSettings, status: String, socTargetPercent: Int) {
        if (outputEnabled) {
            setOutput(false)
            outputEnabled = false
        }
        commandIset = 0.0
        kneeOffsetVolts = 0.0
        firstCollapseIgnored = false
        pvMode = "Alarm"
        publish(
            settings = settings,
            status = status,
            targetChargeCurrent = 0.0,
            band = "--",
            socTargetPercent = socTargetPercent
        )
    }

    private fun currentEpochDay(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis / 86_400_000L
    }

    private fun huntStepUp(absErr: Double, scaleAmps: Double): Double {
        val base = when {
            absErr > V4 -> 1.00
            absErr > V3 -> 0.30
            absErr > V2 -> 0.10
            absErr > V1 -> 0.05
            else -> 0.01
        }
        val scale = (scaleAmps.coerceIn(1.0, HUNT_SCALE_AMPS_CAP) / HUNT_SCALE_AMPS_CAP).coerceAtLeast(0.20)
        return base * scale
    }

    private fun huntStepDown(absErr: Double): Double {
        return when {
            absErr > V4 -> 1.00
            absErr > V3 -> 0.30
            absErr > V2 -> 0.10
            absErr > V1 -> 0.05
            else -> 0.01
        }
    }

    private fun publish(
        settings: AppSettings,
        status: String,
        targetChargeCurrent: Double,
        band: String,
        recoveryActive: Boolean = false,
        socTargetPercent: Int = settings.normalSocCeilingPercent
    ) {
        onState(
            MpptControlState(
                enabled = settings.controllerEnabled,
                pvMode = pvMode,
                status = status,
                targetChargeCurrent = targetChargeCurrent,
                commandIset = commandIset,
                targetPvVolts = quantVolts(settings.targetPvVolts + kneeOffsetVolts),
                kneeOffsetVolts = kneeOffsetVolts,
                controlBand = band,
                chargeVoltageTarget = lastVset,
                recoveryActive = recoveryActive,
                socTargetPercent = socTargetPercent
            )
        )
    }

    private fun quantVolts(volts: Double): Double = kotlin.math.round((volts + 1e-9) * 100.0) / 100.0

    companion object {
        private const val LOOP_MS = 200L
        private const val MIN_ISET = 0.10
        private const val PV_BAND_V = 0.30
        private const val COLLAPSE_DROP_V = 5.0
        private const val KNEE_STEP_V = 0.10
        private const val KNEE_MIN_V = -2.0
        private const val KNEE_MAX_V = 3.0
        private const val KNEE_DECAY_STABLE_EPS = 0.50
        private const val HUNT_SCALE_GOOD_ERR_V = 1.00
        private const val HUNT_SCALE_AMPS_CAP = 10.00
        private const val ALARM_BASE_HOLD_MS = 60_000L
        private const val ALARM_MAX_HOLD_MS = 30 * 60_000L
        private const val ALARM_REPEAT_WINDOW_MS = 10 * 60_000L
        private const val ALARM_STABLE_RESET_MS = 30 * 60_000L
        private const val V1 = 0.25
        private const val V2 = 0.50
        private const val V3 = 1.00
        private const val V4 = 1.50
    }
}
