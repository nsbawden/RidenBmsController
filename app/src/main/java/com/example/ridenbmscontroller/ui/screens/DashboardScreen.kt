package com.example.ridenbmscontroller.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ridenbmscontroller.model.AppState
import com.example.ridenbmscontroller.model.PowerDirection
import com.example.ridenbmscontroller.ui.formatRidenTempF
import com.example.ridenbmscontroller.ui.ridenInternalTempColor
import com.example.ridenbmscontroller.ui.theme.BatteryGreen
import com.example.ridenbmscontroller.ui.theme.CurrentRose
import com.example.ridenbmscontroller.ui.theme.Panel
import com.example.ridenbmscontroller.ui.theme.PanelAlt
import com.example.ridenbmscontroller.ui.theme.PowerBlue
import com.example.ridenbmscontroller.ui.theme.TextMuted
import com.example.ridenbmscontroller.ui.theme.TextPrimary
import com.example.ridenbmscontroller.ui.theme.VoltageAmber
import com.example.ridenbmscontroller.ui.theme.WarningOrange
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import androidx.compose.foundation.layout.offset


@Composable
fun DashboardScreen(
    state: AppState,
    modifier: Modifier = Modifier,
    onSilenceLowSocAlarm: () -> Unit = {},
    onSetActiveKnee: (Double) -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BatteryGaugeCard(state, onSilenceLowSocAlarm)
        RidenPanel(state, onSetActiveKnee)
        HealthPanel(state)
        ControllerPanel(state)
    }
}

@Composable
private fun BatteryGaugeCard(state: AppState, onSilenceLowSocAlarm: () -> Unit) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(14.dp)
        ) {
            HalfCircleSocGauge(state)
            if (state.alerts.lowSocAlarmActive) {
                Spacer(Modifier.height(8.dp))
                LowSocAlertRow(state, onSilenceLowSocAlarm)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ValueTile("Battery V", "%.2f".format(state.battery.volts), "V", VoltageAmber, Modifier.weight(1f))
                ValueTile("Battery A", "%.2f".format(state.battery.amps), "A", CurrentRose, Modifier.weight(1f))
                ValueTile("BMS Watts", "%.0f".format(state.battery.watts), "W", PowerBlue, Modifier.weight(1f))
                ValueTile("Temp", "%.0f".format(state.battery.temperatureF), "F", WarningOrange, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HalfCircleSocGauge(state: AppState) {
    val soc = state.battery.socPercent.coerceIn(0, 100)
    val lowSocActive = state.alerts.lowSocAlarmActive
    val gaugeColor = if (lowSocActive) WarningOrange else BatteryGreen
    val deviceIssueText = deviceIssueText(state)
    val chargeText = deviceIssueText ?: when (state.battery.direction) {
        PowerDirection.Charging -> "CHARGING"
        PowerDirection.Discharging -> "DISCHARGING"
        PowerDirection.Idle -> "IDLE"
    }
    val chargeColor = if (deviceIssueText != null) {
        DeviceRed
    } else {
        when (state.battery.direction) {
            PowerDirection.Charging -> if (lowSocActive) WarningOrange else BatteryGreen
            PowerDirection.Discharging -> VoltageAmber
            PowerDirection.Idle -> TextMuted
        }
    }
    val capacityText = formatAmpHourProgress(state.battery.remainingAh, state.battery.nominalAh)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(188.dp)
    ) {
        if (state.batteryTimeEstimateText.isNotBlank()) {
            Text(
                text = state.batteryTimeEstimateText,
                color = TextMuted,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = (-14).dp)
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 24.dp.toPx()
            val width = size.width
            val gaugeWidth = width * 0.86f
            val arcHeight = gaugeWidth * 0.50f
            val left = (width - gaugeWidth) / 2f
            val top = 28.dp.toPx()
            val rect = Rect(left, top, left + gaugeWidth, top + arcHeight * 2f)
            val arcRadius = rect.width / 2f
            val capAngle = Math.toDegrees((stroke / 2f / arcRadius).toDouble()).toFloat()
            val gaugeStart = 180f + capAngle
            val gaugeSweep = 180f - capAngle * 2f
            val progressSweep = gaugeSweep * (soc / 100f)

            drawArc(
                color = PanelAlt,
                startAngle = gaugeStart,
                sweepAngle = gaugeSweep,
                useCenter = false,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = gaugeColor,
                startAngle = gaugeStart,
                sweepAngle = progressSweep,
                useCenter = false,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            for (tick in 0..10) {
                val angle = Math.toRadians((180.0 + tick * 18.0))
                val center = Offset(rect.center.x, rect.center.y)
                val outer = Offset(
                    x = center.x + cos(angle).toFloat() * rect.width / 2f,
                    y = center.y + sin(angle).toFloat() * rect.height / 2f
                )
                val inner = Offset(
                    x = center.x + cos(angle).toFloat() * (rect.width / 2f - 18.dp.toPx()),
                    y = center.y + sin(angle).toFloat() * (rect.height / 2f - 18.dp.toPx())
                )
                drawLine(
                    color = Color.White.copy(alpha = if (tick % 5 == 0) 0.80f else 0.38f),
                    start = inner,
                    end = outer,
                    strokeWidth = if (tick % 5 == 0) 3.dp.toPx() else 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 58.dp)
        ) {
            Text(
                text = "$soc%",
                color = if (lowSocActive) WarningOrange else MaterialTheme.colorScheme.onSurface,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = chargeText,
                color = chargeColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = capacityText,
                color = TextMuted,
                fontSize = 12.sp
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (state.battery.balancing) WarningOrange else TextMuted.copy(alpha = 0.35f),
                                shape = CircleShape
                            )
                    )
                    Text(
                        text = "BAL ${if (state.battery.balancing) "ON" else "OFF"}",
                        color = if (state.battery.balancing) WarningOrange else TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "Delta ${state.battery.cellDeltaMv} mV",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

private fun deviceIssueText(state: AppState): String? {
    return when {
        !state.controller.bmsConnected && !state.controller.ridenConnected -> "BMS + RIDEN OFFLINE"
        !state.controller.bmsConnected -> "BMS OFFLINE"
        !state.controller.ridenConnected -> "RIDEN OFFLINE"
        else -> null
    }
}

@Composable
private fun LowSocAlertRow(state: AppState, onSilenceLowSocAlarm: () -> Unit) {
    Surface(
        color = WarningOrange.copy(alpha = 0.16f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(8.dp)
        ) {
            Text(
                text = if (state.alerts.lowSocSilenced) {
                    "LOW SOC ${state.battery.socPercent}% - silenced"
                } else {
                    "LOW SOC ${state.battery.socPercent}%"
                },
                color = WarningOrange,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (!state.alerts.lowSocSilenced) {
                Button(onClick = onSilenceLowSocAlarm) {
                    Text("Silence")
                }
            }
        }
    }
}

@Composable
private fun RidenPanel(state: AppState, onSetActiveKnee: (Double) -> Unit) {
    var targetPvDialogOpen by remember { mutableStateOf(false) }
    fun closeTargetPvDialog() {
        targetPvDialogOpen = false
    }

    Surface(
        color = Panel,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SolarSplitHeader(state)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueTile("Watts", "%.0f".format(state.riden.watts), "W", PowerBlue, Modifier.weight(1f))
                ValueTile(
                    "Recovery",
                    recoveryTileValue(state),
                    "",
                    if (state.controller.recoveryActive) WarningOrange else TextMuted,
                    Modifier.weight(1f)
                )
                ValueTile(
                    "SOC Target",
                    state.controller.socTargetPercent.toString(),
                    "%",
                    if (state.controller.socTargetPercent >= 100) WarningOrange else BatteryGreen,
                    Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueTile(
                    "Target PV",
                    "%.1f".format(state.riden.targetVin),
                    "V",
                    VoltageAmber,
                    Modifier
                        .weight(1f)
                        .clickable { targetPvDialogOpen = true }
                )
                ValueTile("VSET", "%.2f".format(state.riden.vset), "V", VoltageAmber, Modifier.weight(1f))
                ValueTile("ISET", "%.2f".format(state.riden.iset), "A", CurrentRose, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueTile("VIN", "%.2f".format(state.riden.vin), "V", VoltageAmber, Modifier.weight(1f))
                ValueTile("VOUT", "%.2f".format(state.riden.vout), "V", VoltageAmber, Modifier.weight(1f))
                ValueTile("IOUT", "%.2f".format(state.riden.iout), "A", CurrentRose, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val today = state.energy.whToday.formatWattHours()
                val kneeDelayS = state.controller.effectiveKneeDelaySeconds
                val kneeDelayColor = when {
                    kneeDelayS >= state.settings.kneeTrackingDelayMaxSeconds -> WarningOrange
                    kneeDelayS <= state.settings.kneeTrackingDelayMinSeconds -> BatteryGreen
                    else -> VoltageAmber
                }
                val ridenTempF = state.riden.internalTempF
                ValueTile("Wh Today", today.value, today.unit, TextPrimary, Modifier.weight(1f))
                ValueTile(
                    "Riden T",
                    formatRidenTempF(ridenTempF),
                    "F",
                    ridenInternalTempColor(ridenTempF),
                    Modifier.weight(1f)
                )
                ValueTile(
                    "Knee Delay",
                    kneeDelayS.toInt().toString(),
                    "s",
                    kneeDelayColor,
                    Modifier.weight(1f)
                )
            }
        }
    }

    if (targetPvDialogOpen) {
        TargetPvDialog(
            onDismiss = { closeTargetPvDialog() },
            onSet = {
                onSetActiveKnee(it)
                closeTargetPvDialog()
            }
        )
    }
}

@Composable
private fun TargetPvDialog(
    onDismiss: () -> Unit,
    onSet: (Double) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    fun commit() {
        val value = text.toDoubleOrNull()?.coerceIn(10.0, 150.0) ?: return
        onSet(value)
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Target PV") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Volts") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.focusRequester(focusRequester)
            )
        },
        confirmButton = {
            Button(onClick = { commit() }, enabled = text.toDoubleOrNull() != null) {
                Text("Set")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SolarSplitHeader(state: AppState) {
    val solarWatts = state.riden.watts.coerceAtLeast(0.0)
    val solarActive = solarWatts > 1.0
    val batteryWatts = if (solarActive) state.battery.watts.coerceAtLeast(0.0).coerceAtMost(solarWatts) else 0.0
    val loadWatts = if (solarActive) (solarWatts - batteryWatts).coerceAtLeast(0.0) else 0.0
    val total = max(solarWatts, batteryWatts + loadWatts)
    val batteryFraction = if (total > 1.0) (batteryWatts / total).toFloat().coerceIn(0f, 1f) else 0f

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Solar", fontWeight = FontWeight.SemiBold)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = TextMuted)) { append("Batt ") }
                    withStyle(SpanStyle(color = if (solarActive) BatteryGreen else TextMuted)) { append(batteryWatts.formatWholeWatts()) }
                    withStyle(SpanStyle(color = TextMuted)) { append("  Load ") }
                    withStyle(SpanStyle(color = if (solarActive) VoltageAmber else TextMuted)) { append(loadWatts.formatWholeWatts()) }
                },
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
        ) {
            val radius = 4.dp.toPx()
            drawRoundRect(
                color = PanelAlt,
                size = size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
            )
            if (solarActive && total > 1.0) {
                val batteryWidth = size.width * batteryFraction
                val loadWidth = size.width - batteryWidth
                if (batteryWidth > 0f) {
                    drawRoundRect(
                        color = BatteryGreen,
                        size = Size(batteryWidth, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                    )
                }
                if (loadWidth > 0f) {
                    drawRoundRect(
                        color = VoltageAmber,
                        topLeft = Offset(batteryWidth, 0f),
                        size = Size(loadWidth, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                    )
                }
            }
        }
    }
}

private fun recoveryTileValue(state: AppState): String {
    if (!state.controller.recoveryActive) return "Off"
    if (state.controller.recoveryCycleCount > 0) return "Cycle ${state.controller.recoveryCycleCount}"
    return when (state.controller.recoveryPhase) {
        "Waiting VIN" -> "Wait VIN"
        "--" -> "Recover"
        else -> state.controller.recoveryPhase
    }
}

@Composable
private fun HealthPanel(state: AppState) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Health", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val cloudCount = state.dailyHealth.unscheduledCrashesToday
                ValueTile(
                    "Cloud Crash",
                    cloudCount.toString(),
                    "today",
                    if (cloudCount > 0) WarningOrange else TextMuted,
                    Modifier.weight(1f)
                )
                ValueTile(
                    "Min Out A",
                    formatHealthAmps(state.dailyHealth.minRidenOutputAmpsToday),
                    "A",
                    CurrentRose,
                    Modifier.weight(1f)
                )
                ValueTile(
                    "Max Out A",
                    formatHealthAmps(state.dailyHealth.maxRidenOutputAmpsToday),
                    "A",
                    PowerBlue,
                    Modifier.weight(1f)
                )
            }
        }
    }
}

private fun formatHealthAmps(amps: Double?): String {
    return amps?.let { "%.2f".format(it) } ?: "—"
}

@Composable
private fun ControllerPanel(state: AppState) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Controller", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    if (state.controller.enabled) state.controller.pvMode else "Off",
                    modeColor(state.controller.pvMode),
                    Modifier.weight(1f)
                )
                StatusChip(
                    if (state.controller.recoveryActive) {
                        state.controller.recoveryPhase
                    } else {
                        "Band ${state.controller.controlBand} ${"%.2f".format(state.controller.controlStepAmps)}"
                    },
                    if (state.controller.recoveryActive) WarningOrange else PowerBlue,
                    Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("Knee ${"%.1f".format(state.controller.targetPvVolts)}V", VoltageAmber, Modifier.weight(1f))
                StatusChip("Err ${"%+.1f".format(state.controller.vinErrorVolts)}V", errorColor(state.controller.vinErrorVolts), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    "ISET ${"%.2f".format(state.controller.commandIset)}A",
                    CurrentRose,
                    Modifier.weight(1f),
                    tintedBackground = false
                )
                StatusChip("Offset ${"%+.1f".format(state.controller.kneeOffsetVolts)}V", VoltageAmber, Modifier.weight(1f))
            }
            Text(state.controller.status, color = TextMuted, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun ValueTile(
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = PanelAlt,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.height(66.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(6.dp)
        ) {
            Text(label, color = TextMuted, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
            Text(
                text = value,
                color = color,
                fontSize = value.tileValueFontSize(),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(unit, color = TextMuted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    tintedBackground: Boolean = true
) {
    Surface(
        color = if (tintedBackground) color.copy(alpha = 0.16f) else PanelAlt,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.height(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = color,
                fontSize = text.statusChipFontSize(),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private data class CompactEnergy(val value: String, val unit: String)

private val DeviceRed = Color(0xFFFF4D4D)

private fun String.tileValueFontSize() = when {
    length >= 8 -> 20.sp
    length >= 7 -> 21.sp
    length >= 6 -> 22.sp
    else -> 24.sp
}

private fun String.statusChipFontSize() = when {
    length >= 15 -> 15.sp
    length >= 12 -> 16.sp
    length >= 9 -> 17.sp
    else -> 18.sp
}

private fun modeColor(mode: String): Color {
    return when (mode) {
        "Recover", "Alarm" -> WarningOrange
        "SOC Hold", "Tracking" -> BatteryGreen
        "Balance" -> WarningOrange
        "Voltage Limit" -> VoltageAmber
        else -> TextMuted
    }
}

private fun errorColor(errorVolts: Double): Color {
    return when {
        kotlin.math.abs(errorVolts) <= 0.50 -> BatteryGreen
        errorVolts < 0.0 -> WarningOrange
        else -> PowerBlue
    }
}

private fun formatAmpHourProgress(remainingAh: Double?, nominalAh: Double?): String {
    return when {
        remainingAh != null && nominalAh != null -> {
            "${remainingAh.roundAh()} of ${nominalAh.roundAh()} Ah"
        }
        remainingAh != null -> "${remainingAh.roundAh()} Ah"
        nominalAh != null -> "of ${nominalAh.roundAh()} Ah"
        else -> "-- of -- Ah"
    }
}

private fun Double.roundAh(): String {
    return "%.2f".format(this)
}

private fun Double.formatWholeWatts(): String {
    return "%.0fW".format(this)
}

private fun Double.formatWattHours(): CompactEnergy {
    val absValue = kotlin.math.abs(this)
    return when {
        absValue >= 1_000_000.0 -> CompactEnergy("%.2f".format(this / 1_000_000.0), "MWH")
        absValue >= 10_000.0 -> CompactEnergy("%.1f".format(this / 1_000.0), "kWH")
        absValue >= 1_000.0 -> CompactEnergy("%.0f".format(this), "WH")
        else -> CompactEnergy("%.1f".format(this), "WH")
    }
}
