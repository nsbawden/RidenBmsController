package com.example.ridenbmscontroller.model

enum class PowerDirection {
    Charging,
    Discharging,
    Idle
}

enum class ChargeMode {
    Bulk,
    Absorb,
    Balance,
    Hold,
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
    val cellDeltaMv: Int
)

data class RidenState(
    val vin: Double,
    val vout: Double,
    val iout: Double,
    val watts: Double,
    val vset: Double,
    val iset: Double,
    val targetVin: Double
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
    val controlBand: String,
    val ridenConnected: Boolean,
    val bmsConnected: Boolean,
    val recoveryActive: Boolean,
    val socTargetPercent: Int
)

data class AppSettings(
    val maxBatteryVolts: Double,
    val balanceEveryDays: Int,
    val lastBalanceEpochDay: Long,
    val maxChargeAmps: Double,
    val targetPvVolts: Double,
    val controllerEnabled: Boolean,
    val normalSocCeilingPercent: Int,
    val socHoldCurrentAmps: Double,
    val kneeTrackingDelaySeconds: Double,
    val keepScreenOn: Boolean
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
    val controller: ControllerState,
    val settings: AppSettings,
    val history: List<HistoryPoint>,
    val logs: List<String>
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
            controller = ControllerState(
                enabled = false,
                pvMode = "Idle",
                status = "Waiting for devices",
                targetChargeCurrent = 0.0,
                commandIset = 0.0,
                targetPvVolts = 33.0,
                kneeOffsetVolts = 0.0,
                controlBand = "--",
                ridenConnected = false,
                bmsConnected = false,
                recoveryActive = false,
                socTargetPercent = 100
            ),
            settings = AppSettings(
                maxBatteryVolts = 14.20,
                balanceEveryDays = 7,
                lastBalanceEpochDay = 0L,
                maxChargeAmps = 24.0,
                targetPvVolts = 33.0,
                controllerEnabled = false,
                normalSocCeilingPercent = 100,
                socHoldCurrentAmps = 0.5,
                kneeTrackingDelaySeconds = 12.0,
                keepScreenOn = true
            ),
            history = emptyList(),
            logs = emptyList()
        )
    }
}
