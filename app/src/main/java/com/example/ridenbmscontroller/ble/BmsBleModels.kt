package com.example.ridenbmscontroller.ble

data class BmsBleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val lastSeenMs: Long
) {
    val looksLikeBattery: Boolean
        get() {
            val normalized = name.lowercase()
            return BATTERY_NAME_HINTS.any { it in normalized }
        }

    companion object {
        private val BATTERY_NAME_HINTS = listOf(
            "rv batt",
            "batt",
            "battery",
            "jbd",
            "jdb",
            "xiaoxiang",
            "llt",
            "bms"
        )
    }
}

data class BmsGattCharacteristicInfo(
    val uuid: String,
    val properties: String
)

data class BmsGattServiceInfo(
    val uuid: String,
    val characteristics: List<BmsGattCharacteristicInfo>
)

data class BmsDecodedTelemetry(
    val packVoltage: Double? = null,
    val packCurrent: Double? = null,
    val socPercent: Int? = null,
    val remainingAh: Double? = null,
    val nominalAh: Double? = null,
    val cycleCount: Int? = null,
    val cellCount: Int? = null,
    val temperatureF: Double? = null,
    val fetStatus: String? = null,
    val protectionStatus: Int? = null,
    val balancingActive: Boolean? = null,
    val cellVoltages: List<Double> = emptyList()
) {
    val cellDeltaMv: Int?
        get() {
            if (cellVoltages.size < 2) return null
            val millivolts = cellVoltages.map { (it * 1000.0).toInt() }
            return (millivolts.maxOrNull() ?: 0) - (millivolts.minOrNull() ?: 0)
        }

    val protectionAlarmNames: List<String>
        get() = decodeProtectionAlarms(protectionStatus).map { it.name }

    val monitoredProtectionAlarmCount: Int
        get() = bmsProtectionAlarms.size

    val monitoredChargeBlockingAlarmCount: Int
        get() = bmsProtectionAlarms.count { it.blocksCharging }

    val activeProtectionAlarmCount: Int
        get() = decodeProtectionAlarms(protectionStatus).size

    val activeChargeBlockingAlarmCount: Int
        get() = decodeProtectionAlarms(protectionStatus).count { it.blocksCharging }

    val chargeBlockingAlarmNames: List<String>
        get() = decodeProtectionAlarms(protectionStatus)
            .filter { it.blocksCharging }
            .map { it.name }

    val monitoredChargeBlockingAlarmNames: List<String>
        get() = bmsProtectionAlarms.filter { it.blocksCharging }.map { it.name }
}

data class BmsBleUiState(
    val supported: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val locationEnabled: Boolean = false,
    val hasPermissions: Boolean = false,
    val scanning: Boolean = false,
    val connecting: Boolean = false,
    val connectedDeviceName: String? = null,
    val connectedDeviceAddress: String? = null,
    val status: String = "Ready",
    val devices: List<BmsBleDevice> = emptyList(),
    val gattServices: List<BmsGattServiceInfo> = emptyList(),
    val rawPackets: List<String> = emptyList(),
    val telemetry: BmsDecodedTelemetry = BmsDecodedTelemetry()
)

private data class BmsProtectionAlarm(
    val bit: Int,
    val name: String,
    val blocksCharging: Boolean
)

private val bmsProtectionAlarms = listOf(
    BmsProtectionAlarm(0, "Cell overvoltage", true),
    BmsProtectionAlarm(1, "Cell undervoltage", false),
    BmsProtectionAlarm(2, "Pack overvoltage", true),
    BmsProtectionAlarm(3, "Pack undervoltage", false),
    BmsProtectionAlarm(4, "Charge overtemp", true),
    BmsProtectionAlarm(5, "Charge undertemp", true),
    BmsProtectionAlarm(6, "Discharge overtemp", false),
    BmsProtectionAlarm(7, "Discharge undertemp", false),
    BmsProtectionAlarm(8, "Charge overcurrent", true),
    BmsProtectionAlarm(9, "Discharge overcurrent", false),
    BmsProtectionAlarm(10, "Short circuit", true),
    BmsProtectionAlarm(11, "AFE error", true),
    BmsProtectionAlarm(12, "MOS lock", true)
)

private fun decodeProtectionAlarms(status: Int?): List<BmsProtectionAlarm> {
    if (status == null || status == 0) return emptyList()
    val known = bmsProtectionAlarms.filter { alarm -> status and (1 shl alarm.bit) != 0 }
    val knownMask = bmsProtectionAlarms.fold(0) { mask, alarm -> mask or (1 shl alarm.bit) }
    val unknownBits = status and knownMask.inv()
    return if (unknownBits == 0) {
        known
    } else {
        known + BmsProtectionAlarm(-1, "Unknown protection 0x%04X".format(unknownBits), true)
    }
}
