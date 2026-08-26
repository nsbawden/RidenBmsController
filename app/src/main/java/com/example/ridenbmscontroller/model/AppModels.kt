package com.example.ridenbmscontroller.model

import com.example.ridenbmscontroller.logging.OpsLogStorageSummary
enum class PowerDirection {
    Charging,
    Discharging,
    Idle
}

enum class ChargeMode {
    Bulk,
    Balance,
    Idle
}

data class BatteryState(
    val socPercent: Int,
    val volts: Double,
    val amps: Double,
    val watts: Double,
    val remainingAh: Double?,
    val nominalAh: Double?,
    val temperatureF: Double,
    val direction: PowerDirection,
    val chargeMode: ChargeMode,
    val balancing: Boolean,
    val cellDeltaMv: Int,
    /** Worst-case SOC after deadband coulomb uncertainty; null if capacity unknown. */
    val worstCaseSocPercent: Int? = null,
    val socDriftAh: Double = 0.0
)

data class RidenState(
    val vin: Double,
    val vout: Double,
    val iout: Double,
    val watts: Double,
    val vset: Double,
    val iset: Double,
    val targetVin: Double,
    val internalTempF: Double? = null,
    val outputOn: Boolean? = null,
    val otpLimitF: Double = 176.0,
    val otpTripped: Boolean = false
)

data class EnergyCounters(
    val currentWatts: Double,
    val whToday: Double,
    val whYesterday: Double,
    val whTotal: Double,
    val bmsWhToday: Double,
    val bmsWhYesterday: Double
)

data class ControllerState(
    val enabled: Boolean,
    val pvMode: String,
    val status: String,
    val targetChargeCurrent: Double,
    val commandIset: Double,
    val targetPvVolts: Double,
    val kneeOffsetVolts: Double,
    val vinErrorVolts: Double,
    val policyLimitAmps: Double,
    val recoveryPhase: String,
    val recoveryCycleCount: Int,
    val controlBand: String,
    val controlStepAmps: Double,
    val ridenConnected: Boolean,
    val bmsConnected: Boolean,
    val recoveryActive: Boolean,
    val socTargetPercent: Int,
    val effectiveKneeDelaySeconds: Double = 30.0,
    /** 60 s rolling average of BMS pack current (A), used by BMS voltage-hold end-current. */
    val averagePackCurrentA: Double? = null
)

data class AlertState(
    val usbAlarmActive: Boolean,
    val lowSocAlarmActive: Boolean,
    val lowSocSilenced: Boolean,
    val lowSocThresholdPercent: Int,
    val ridenOtpAlarmActive: Boolean = false,
    val ridenOtpTempF: Double? = null,
    val socDriftAlarmActive: Boolean = false,
    val socDriftSilenced: Boolean = false,
    val socDriftThresholdPercent: Int = 20
)

data class AppSettings(
    val maxBatteryVolts: Double,
    val bmsOfflineMaxBatteryVolts: Double,
    val balanceEveryDays: Int,
    val lastBalanceEpochDay: Long,
    val maxChargeAmps: Double,
    val targetPvVolts: Double,
    val controllerEnabled: Boolean,
    val normalSocCeilingPercent: Int,
    /** Net pack trickle (A) once ceiling SOC and [bmsVoltageHoldVolts] are both met. */
    val socHoldCurrentAmps: Double,
    val bmsCurrentDeadbandAmps: Double,
    val lowSocAlarmPercent: Int,
    /** Hardware BMS near-zero current deadband used for worst-case SOC drift (A). */
    val socDriftDeadbandAmps: Double,
    /** Alarm when estimated worst-case SOC falls to this percent or below. */
    val socDriftAlarmPercent: Int,
    /** Pack-voltage hold setpoint used at the normal SOC ceiling (V). */
    val bmsVoltageHoldVolts: Double,
    /** Pack-voltage hold setpoint used at 100% SOC (V). */
    val fullHoldVolts: Double,
    /** Net pack trickle (A) once 100% SOC and [fullHoldVolts] are both met. */
    val fullHoldCurrentAmps: Double,
    val minTargetPvVolts: Double,
    val maxTargetPvVolts: Double,
    val kneeStepVolts: Double,
    val fastProbeRecoveryKneeBackVolts: Double,
    val cloudRecoveryKneeBackVolts: Double,
    val probeRecoveryIsetFraction: Double,
    val kneeTrackingDelayMinSeconds: Double,
    val kneeTrackingDelayMaxSeconds: Double,
    val powerBasedVtuneStop: Boolean,
    val controllerLoopMs: Int,
    val keepScreenOn: Boolean,
    val logEventsEnabled: Boolean = true,
    val logTelemetryEnabled: Boolean = true,
    val logSkyDisturbancesEnabled: Boolean = true,
    val logCrashEpisodesEnabled: Boolean = true,
    val logHistoryEnabled: Boolean = true
)

data class HistoryPoint(
    val timestampMs: Long,
    val dayKey: Int,
    val batteryVolts: Double,
    val batteryAmps: Double,
    val batteryWatts: Double,
    val socPercent: Int,
    val temperatureF: Double,
    val ridenVin: Double,
    val ridenVout: Double,
    val ridenIout: Double,
    val ridenWatts: Double
)

data class AppState(
    val battery: BatteryState,
    val riden: RidenState,
    val energy: EnergyCounters,
    val dailyHealth: DailyHealthState,
    val controller: ControllerState,
    val alerts: AlertState,
    val settings: AppSettings,
    val history: List<HistoryPoint>,
    val events: List<String>,
    val logs: List<String>,
    val opsLogSummary: OpsLogStorageSummary,
    /** Phone barometer station pressure in inches of mercury, or null if unavailable. */
    val barometricPressureInHg: Double? = null,
    /** GPS altitude lock (meters MSL) used for sea-level correction; null until tapped. */
    val gpsAltitudeMeters: Double? = null,
    /** True while a pressure-tile GPS altitude average is in progress. */
    val gpsAltitudeSampling: Boolean = false
) {
    companion object {
        val preview = AppState(
            battery = BatteryState(
                socPercent = 0,
                volts = 0.0,
                amps = 0.0,
                watts = 0.0,
                remainingAh = null,
                nominalAh = null,
                temperatureF = 0.0,
                direction = PowerDirection.Idle,
                chargeMode = ChargeMode.Idle,
                balancing = false,
                cellDeltaMv = 0
            ),
            riden = RidenState(
                vin = 0.0,
                vout = 0.0,
                iout = 0.0,
                watts = 0.0,
                vset = 0.0,
                iset = 0.0,
                targetVin = 33.0
            ),
            energy = EnergyCounters(
                currentWatts = 0.0,
                whToday = 0.0,
                whYesterday = 0.0,
                whTotal = 0.0,
                bmsWhToday = 0.0,
                bmsWhYesterday = 0.0
            ),
            dailyHealth = DailyHealthState(),
            controller = ControllerState(
                enabled = false,
                pvMode = "Idle",
                status = "Waiting for devices",
                targetChargeCurrent = 0.0,
                commandIset = 0.0,
                targetPvVolts = 33.0,
                kneeOffsetVolts = 0.0,
                vinErrorVolts = 0.0,
                policyLimitAmps = 0.0,
                recoveryPhase = "--",
                recoveryCycleCount = 0,
                controlBand = "--",
                controlStepAmps = 0.0,
                ridenConnected = false,
                bmsConnected = false,
                recoveryActive = false,
                socTargetPercent = 100
            ),
            alerts = AlertState(
                usbAlarmActive = false,
                lowSocAlarmActive = false,
                lowSocSilenced = false,
                lowSocThresholdPercent = 20
            ),
            settings = AppSettings(
                maxBatteryVolts = 14.20,
                bmsOfflineMaxBatteryVolts = 14.20,
                balanceEveryDays = 7,
                lastBalanceEpochDay = 0L,
                maxChargeAmps = 24.0,
                targetPvVolts = 33.0,
                controllerEnabled = false,
                normalSocCeilingPercent = 100,
                socHoldCurrentAmps = 0.5,
                bmsCurrentDeadbandAmps = 1.0,
                lowSocAlarmPercent = 20,
                socDriftDeadbandAmps = 1.5,
                socDriftAlarmPercent = 20,
                bmsVoltageHoldVolts = 13.60,
                fullHoldVolts = 14.00,
                fullHoldCurrentAmps = 0.5,
                minTargetPvVolts = 30.0,
                maxTargetPvVolts = 36.0,
                kneeStepVolts = 0.10,
                fastProbeRecoveryKneeBackVolts = 0.50,
                cloudRecoveryKneeBackVolts = 0.50,
                probeRecoveryIsetFraction = 0.80,
                kneeTrackingDelayMinSeconds = 30.0,
                kneeTrackingDelayMaxSeconds = 300.0,
                powerBasedVtuneStop = false,
                controllerLoopMs = 200,
                keepScreenOn = true,
                logEventsEnabled = true,
                logTelemetryEnabled = true,
                logSkyDisturbancesEnabled = true,
                logCrashEpisodesEnabled = true,
                logHistoryEnabled = true
            ),
            history = emptyList(),
            events = emptyList(),
            logs = emptyList(),
            opsLogSummary = OpsLogStorageSummary(
                totalBytes = 0L,
                days = emptyList(),
                logDirectory = ""
            )
        )
    }
}
