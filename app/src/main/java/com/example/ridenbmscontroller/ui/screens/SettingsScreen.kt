package com.example.ridenbmscontroller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.ridenbmscontroller.model.AppSettings
import com.example.ridenbmscontroller.model.EnergyCounters
import com.example.ridenbmscontroller.ui.theme.Panel
import com.example.ridenbmscontroller.ui.theme.TextMuted

@Composable
fun SettingsScreen(
    settings: AppSettings,
    energy: EnergyCounters,
    onSettingsChanged: (AppSettings) -> Unit,
    balanceDayToday: Boolean,
    daysUntilNextBalance: Int,
    onToggleBalanceToday: () -> Unit,
    onResetEnergyTotal: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        SettingsGroup("Charge profile") {
            IntFieldRow("Normal SOC ceiling", settings.normalSocCeilingPercent, "%", 50, 100) {
                onSettingsChanged(settings.copy(normalSocCeilingPercent = it))
            }
            NumberFieldRow("Maximum controller voltage", settings.maxBatteryVolts, "V", 12.0, 15.0) {
                onSettingsChanged(settings.copy(maxBatteryVolts = it))
            }
            NumberFieldRow("SOC hold current", settings.socHoldCurrentAmps, "A", 0.0, 5.0) {
                onSettingsChanged(settings.copy(socHoldCurrentAmps = it))
            }
            NumberFieldRow("BMS current deadband", settings.bmsCurrentDeadbandAmps, "A", 0.0, 5.0) {
                onSettingsChanged(settings.copy(bmsCurrentDeadbandAmps = it))
            }
            IntFieldRow("Low SOC alarm", settings.lowSocAlarmPercent, "%", 0, 100) {
                onSettingsChanged(settings.copy(lowSocAlarmPercent = it))
            }
            IntFieldRow("Balance interval", settings.balanceEveryDays, "days", 1, 60) {
                onSettingsChanged(settings.copy(balanceEveryDays = it))
            }
            Button(onClick = onToggleBalanceToday, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (balanceDayToday) {
                        "Cancel Balance Today"
                    } else {
                        "Force Balance Today (next in ${daysUntilNextBalance}d)"
                    }
                )
            }
        }
        SettingsGroup("Solar control") {
            NumberFieldRow("Maximum charge current", settings.maxChargeAmps, "A", 0.1, 60.0) {
                onSettingsChanged(settings.copy(maxChargeAmps = it))
            }
            NumberFieldRow("Target PV voltage", settings.targetPvVolts, "V", 10.0, 150.0) {
                onSettingsChanged(settings.copy(targetPvVolts = it))
            }
            NumberFieldRow("Knee variance", settings.kneeVarianceVolts, "V", 0.0, 10.0) {
                onSettingsChanged(settings.copy(kneeVarianceVolts = it))
            }
            NumberFieldRow("Knee step", settings.kneeStepVolts, "V", 0.01, 1.0) {
                onSettingsChanged(settings.copy(kneeStepVolts = it))
            }
            NumberFieldRow("Knee Tracking Delay", settings.kneeTrackingDelaySeconds, "s", 0.0, 300.0) {
                onSettingsChanged(settings.copy(kneeTrackingDelaySeconds = it))
            }
            IntFieldRow("Controller loop", settings.controllerLoopMs, "ms", 100, 2000) {
                onSettingsChanged(settings.copy(controllerLoopMs = it))
            }
            ToggleRow("Controller enabled", settings.controllerEnabled) {
                onSettingsChanged(settings.copy(controllerEnabled = it))
            }
        }
        SettingsGroup("Devices") {
            SettingRow("Riden connection", "USB serial")
            SettingRow("Battery BMS", "Xiaoxiang / JBD BLE")
            ToggleRow("Auto reconnect", true) {}
            ToggleRow("Keep screen on", settings.keepScreenOn) {
                onSettingsChanged(settings.copy(keepScreenOn = it))
            }
        }
        SettingsGroup("Energy counters") {
            SettingRow("Today", energy.whToday.formatWattHoursLong())
            SettingRow("Yesterday", energy.whYesterday.formatWattHoursLong())
            SettingRow("Total", energy.whTotal.formatWattHoursLong())
            Button(onClick = onResetEnergyTotal, modifier = Modifier.fillMaxWidth()) {
                Text("Reset Total Wh")
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, color = TextMuted)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NumberFieldRow(
    label: String,
    value: Double,
    unit: String,
    min: Double,
    max: Double,
    onValueChange: (Double) -> Unit
) {
    var text by remember(label) { mutableStateOf(value.formatSetting()) }
    var focused by remember(label) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val latestText by rememberUpdatedState(text)
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)

    fun commitText(currentText: String, fallback: Double) {
        val committed = currentText.toDoubleOrNull()?.coerceIn(min, max) ?: fallback
        text = committed.formatSetting()
        latestOnValueChange(committed)
    }

    LaunchedEffect(value, focused) {
        if (!focused) text = value.formatSetting()
    }

    DisposableEffect(label) {
        onDispose {
            val committed = latestText.toDoubleOrNull()?.coerceIn(min, max) ?: latestValue
            latestOnValueChange(committed)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, color = TextMuted, modifier = Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1.15f)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { next ->
                    text = next
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        commitText(text, value)
                        focusManager.clearFocus()
                    }
                ),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (!it.isFocused) {
                            commitText(text, value)
                        }
                    }
            )
            Text(unit, color = TextMuted)
        }
    }
}

@Composable
private fun IntFieldRow(
    label: String,
    value: Int,
    unit: String,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit
) {
    var text by remember(label) { mutableStateOf(value.toString()) }
    var focused by remember(label) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val latestText by rememberUpdatedState(text)
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)

    fun commitText(currentText: String, fallback: Int) {
        val committed = currentText.toIntOrNull()?.coerceIn(min, max) ?: fallback
        text = committed.toString()
        latestOnValueChange(committed)
    }

    LaunchedEffect(value, focused) {
        if (!focused) text = value.toString()
    }

    DisposableEffect(label) {
        onDispose {
            val committed = latestText.toIntOrNull()?.coerceIn(min, max) ?: latestValue
            latestOnValueChange(committed)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, color = TextMuted, modifier = Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1.15f)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { next ->
                    text = next
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        commitText(text, value)
                        focusManager.clearFocus()
                    }
                ),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (!it.isFocused) {
                            commitText(text, value)
                        }
                    }
            )
            Text(unit, color = TextMuted)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, color = TextMuted)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun Double.formatSetting(): String {
    return if (this < 1.0) {
        "%.3f".format(this).trimEnd('0').trimEnd('.')
    } else {
        "%.2f".format(this).trimEnd('0').trimEnd('.')
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
