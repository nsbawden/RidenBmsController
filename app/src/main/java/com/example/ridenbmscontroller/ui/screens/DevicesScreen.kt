package com.example.ridenbmscontroller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ridenbmscontroller.ble.BmsBleDevice
import com.example.ridenbmscontroller.ble.BmsBleUiState
import com.example.ridenbmscontroller.model.EnergyCounters
import com.example.ridenbmscontroller.riden.RidenUsbState
import com.example.ridenbmscontroller.ui.theme.BatteryGreen
import com.example.ridenbmscontroller.ui.theme.Panel
import com.example.ridenbmscontroller.ui.theme.PanelAlt
import com.example.ridenbmscontroller.ui.theme.TextMuted
import com.example.ridenbmscontroller.ui.theme.WarningOrange

@Composable
fun DevicesScreen(
    bleState: BmsBleUiState,
    ridenState: RidenUsbState,
    energy: EnergyCounters,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BmsBleDevice) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAllDevices by remember { mutableStateOf(false) }
    val batteryLikeDevices = bleState.devices.filter { it.looksLikeBattery }
    val visibleDevices = if (showAllDevices || batteryLikeDevices.isEmpty()) {
        bleState.devices
    } else {
        batteryLikeDevices
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Devices", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)

        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusRow("Bluetooth", if (bleState.bluetoothEnabled) "On" else "Off", bleState.bluetoothEnabled)
                StatusRow("Phone location", if (bleState.locationEnabled) "On" else "Off", bleState.locationEnabled)
                StatusRow("BLE permissions", if (bleState.hasPermissions) "Granted" else "Needed", bleState.hasPermissions)
                StatusRow("BMS scan", bleState.status, bleState.scanning || bleState.devices.isNotEmpty())
                StatusRow(
                    "BMS connection",
                    bleState.connectedDeviceName ?: if (bleState.connecting) "Connecting" else "Disconnected",
                    bleState.connectedDeviceName != null
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (bleState.hasPermissions) onStartScan() else onRequestPermissions()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (bleState.hasPermissions) "Scan BMS" else "Allow BLE")
                    }
                    OutlinedButton(
                        onClick = onStopScan,
                        enabled = bleState.scanning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Stop")
                    }
                }
                OutlinedButton(
                    onClick = onDisconnect,
                    enabled = bleState.connectedDeviceName != null || bleState.connecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect BMS")
                }
            }
        }

        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Riden USB", fontWeight = FontWeight.SemiBold)
                StatusRow("Connection", if (ridenState.connected) "Online" else "Offline", ridenState.connected)
                StatusRow("Permission", if (ridenState.permissionNeeded) "Needed" else "OK", !ridenState.permissionNeeded)
                TelemetryRow("Status", ridenState.status)
                ridenState.deviceName?.let { TelemetryRow("Device", it) }
                if (ridenState.vendorId != null && ridenState.productId != null) {
                    TelemetryRow("VID/PID", "%04X:%04X".format(ridenState.vendorId, ridenState.productId))
                }
                val telemetry = ridenState.telemetry
                telemetry.vin?.let { TelemetryRow("VIN", "%.2f V".format(it)) }
                telemetry.vout?.let { TelemetryRow("VOUT", "%.2f V".format(it)) }
                telemetry.iout?.let { TelemetryRow("IOUT", "%.2f A".format(it)) }
                telemetry.watts?.let { TelemetryRow("Power", "%.1f W".format(it)) }
            }
        }

        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Discovered BLE Devices (${visibleDevices.size}/${bleState.devices.size})",
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("All", color = TextMuted)
                        Switch(
                            checked = showAllDevices || batteryLikeDevices.isEmpty(),
                            onCheckedChange = { showAllDevices = it },
                            enabled = batteryLikeDevices.isNotEmpty()
                        )
                    }
                }
                if (visibleDevices.isEmpty()) {
                    Text(
                        if (bleState.devices.isEmpty()) {
                            "No devices found yet."
                        } else {
                            "No battery-like devices shown. Turn on All to inspect every BLE device."
                        },
                        color = TextMuted
                    )
                } else {
                    visibleDevices.forEach { device ->
                        Surface(color = PanelAlt, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(device.name, fontWeight = FontWeight.SemiBold)
                                    Button(
                                        onClick = { onConnect(device) },
                                        enabled = !bleState.connecting && bleState.connectedDeviceAddress != device.address
                                    ) {
                                        Text(if (bleState.connectedDeviceAddress == device.address) "Connected" else "Connect")
                                    }
                                }
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(device.address, color = TextMuted)
                                    Text("${device.rssi} dBm", color = TextMuted)
                                }
                            }
                        }
                    }
                }
            }
        }

        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("BMS Telemetry", fontWeight = FontWeight.SemiBold)
                val telemetry = bleState.telemetry
                if (telemetry.packVoltage == null && telemetry.cellVoltages.isEmpty()) {
                    Text("No live BMS values yet.", color = TextMuted)
                } else {
                    TelemetryRow("BMS Wh today", energy.bmsWhToday.formatWattHoursLong())
                    TelemetryRow("BMS Wh yesterday", energy.bmsWhYesterday.formatWattHoursLong())
                    TelemetryRow("Solar load Wh today", (energy.whToday - energy.bmsWhToday).coerceAtLeast(0.0).formatWattHoursLong())
                    TelemetryRow("Solar load Wh yesterday", (energy.whYesterday - energy.bmsWhYesterday).coerceAtLeast(0.0).formatWattHoursLong())
                    TelemetryRow("Pack voltage", telemetry.packVoltage?.let { "%.2f V".format(it) } ?: "-")
                    TelemetryRow("Pack current", telemetry.packCurrent?.let { "%.2f A".format(it) } ?: "-")
                    TelemetryRow("SOC", telemetry.socPercent?.let { "$it%" } ?: "-")
                    TelemetryRow("Capacity", formatCapacity(telemetry.remainingAh, telemetry.nominalAh))
                    TelemetryRow("Temperature", telemetry.temperatureF?.let { "%.1f F".format(it) } ?: "-")
                    TelemetryRow("Cells", telemetry.cellCount?.toString() ?: telemetry.cellVoltages.size.toString())
                    TelemetryRow("Cell delta", telemetry.cellDeltaMv?.let { "$it mV" } ?: "-")
                    TelemetryRow("Balancing", telemetry.balancingActive?.let { if (it) "On" else "Off" } ?: "-")
                    TelemetryRow("FET status", telemetry.fetStatus ?: "-")
                    TelemetryRow(
                        "Alarm counts",
                        "${telemetry.activeProtectionAlarmCount} active / ${telemetry.monitoredProtectionAlarmCount} monitored"
                    )
                    TelemetryRow(
                        "Charge stops",
                        "${telemetry.activeChargeBlockingAlarmCount} active / ${telemetry.monitoredChargeBlockingAlarmCount} monitored"
                    )
                    TelemetryRow(
                        "Active protection",
                        telemetry.protectionStatus?.let {
                            val hex = "0x%04X".format(it)
                            if (telemetry.protectionAlarmNames.isEmpty()) {
                                "None ($hex)"
                            } else {
                                "${telemetry.protectionAlarmNames.joinToString()} ($hex)"
                            }
                        } ?: "-"
                    )
                    if (telemetry.cellVoltages.isNotEmpty()) {
                        Text("Cell voltages", fontWeight = FontWeight.SemiBold)
                        telemetry.cellVoltages.forEachIndexed { index, volts ->
                            TelemetryRow("Cell ${index + 1}", "%.3f V".format(volts))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, color = TextMuted)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatCapacity(remainingAh: Double?, nominalAh: Double?): String {
    return when {
        remainingAh != null && nominalAh != null -> "%.2f / %.2f Ah".format(remainingAh, nominalAh)
        remainingAh != null -> "%.2f Ah".format(remainingAh)
        nominalAh != null -> "%.2f Ah nominal".format(nominalAh)
        else -> "-"
    }
}

private fun Double.formatWattHoursLong(): String {
    val absValue = kotlin.math.abs(this)
    return when {
        absValue >= 1_000_000.0 -> "%.2f MWH".format(this / 1_000_000.0)
        absValue >= 10_000.0 -> "%.1f kWH".format(this / 1_000.0)
        else -> "%.1f WH".format(this)
    }
}

@Composable
private fun StatusRow(label: String, value: String, good: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, color = TextMuted)
        Text(value, color = if (good) BatteryGreen else WarningOrange, fontWeight = FontWeight.SemiBold)
    }
}
