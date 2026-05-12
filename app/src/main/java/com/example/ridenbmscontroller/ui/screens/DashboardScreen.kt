package com.example.ridenbmscontroller.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ridenbmscontroller.model.AppState
import com.example.ridenbmscontroller.model.PowerDirection
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
import kotlin.math.sin

@Composable
fun DashboardScreen(state: AppState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BatteryGaugeCard(state)
        RidenPanel(state)
        ControllerPanel(state)
    }
}

@Composable
private fun BatteryGaugeCard(state: AppState) {
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
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ValueTile("Battery V", "%.2f".format(state.battery.volts), "V", VoltageAmber, Modifier.weight(1f))
                ValueTile("Battery A", "%.1f".format(state.battery.amps), "A", CurrentRose, Modifier.weight(1f))
                ValueTile("BMS Watts", "%.0f".format(state.battery.watts), "W", PowerBlue, Modifier.weight(1f))
                ValueTile("Temp", "%.0f".format(state.battery.temperatureF), "F", WarningOrange, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HalfCircleSocGauge(state: AppState) {
    val soc = state.battery.socPercent.coerceIn(0, 100)
    val chargeText = when (state.battery.direction) {
        PowerDirection.Charging -> "CHARGING"
        PowerDirection.Discharging -> "DISCHARGING"
        PowerDirection.Idle -> "IDLE"
    }
    val chargeColor = when (state.battery.direction) {
        PowerDirection.Charging -> BatteryGreen
        PowerDirection.Discharging -> VoltageAmber
        PowerDirection.Idle -> TextMuted
    }
    val capacityText = formatAmpHourProgress(state.battery.remainingAh, state.battery.nominalAh)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(188.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 24.dp.toPx()
            val width = size.width
            val gaugeWidth = width * 0.86f
            val arcHeight = gaugeWidth * 0.50f
            val left = (width - gaugeWidth) / 2f
            val top = 28.dp.toPx()
            val rect = Rect(left, top, left + gaugeWidth, top + arcHeight * 2f)
            val progressSweep = 180f * (soc / 100f)

            drawArc(
                color = PanelAlt,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = BatteryGreen,
                startAngle = 180f,
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
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = chargeColor)) {
                        append(chargeText)
                    }
                    withStyle(SpanStyle(color = TextMuted)) {
                        append("  |  ")
                    }
                    withStyle(SpanStyle(color = TextMuted)) {
                        append(capacityText)
                    }
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Cell delta ${state.battery.cellDeltaMv} mV",
                color = TextMuted,
                fontSize = 12.sp
            )
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
        }
    }
}

@Composable
private fun RidenPanel(state: AppState) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Riden / Solar", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueTile("Target PV", "%.1f".format(state.riden.targetVin), "V", VoltageAmber, Modifier.weight(1f))
                ValueTile("VSET", "%.2f".format(state.riden.vset), "V", VoltageAmber, Modifier.weight(1f))
                ValueTile("ISET", "%.1f".format(state.riden.iset), "A", CurrentRose, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueTile("VIN", "%.2f".format(state.riden.vin), "V", VoltageAmber, Modifier.weight(1f))
                ValueTile("VOUT", "%.2f".format(state.riden.vout), "V", VoltageAmber, Modifier.weight(1f))
                ValueTile("IOUT", "%.1f".format(state.riden.iout), "A", CurrentRose, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueTile("Watts", "%.0f".format(state.riden.watts), "W", PowerBlue, Modifier.weight(1f))
                ValueTile(
                    "Recovery",
                    if (state.controller.recoveryActive) "On" else "Off",
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
                val today = state.energy.whToday.formatWattHours()
                val yesterday = state.energy.whYesterday.formatWattHours()
                val total = state.energy.whTotal.formatWattHours()
                ValueTile("Wh Today", today.value, today.unit, TextPrimary, Modifier.weight(1f))
                ValueTile("Wh Yday", yesterday.value, yesterday.unit, TextPrimary, Modifier.weight(1f))
                ValueTile("Wh Total", total.value, total.unit, TextPrimary, Modifier.weight(1f))
            }
        }
    }
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
                StatusChip(if (state.controller.enabled) "Enabled" else "Off", BatteryGreen, Modifier.weight(1f))
                StatusChip("Solar ${state.controller.pvMode}", PowerBlue, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("Target ${"%.1f".format(state.controller.targetChargeCurrent)}A", CurrentRose, Modifier.weight(1f))
                StatusChip("ISET ${"%.1f".format(state.controller.commandIset)}A", CurrentRose, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("Band ${state.controller.controlBand}", PowerBlue, Modifier.weight(1f))
                StatusChip("Knee ${"%+.1f".format(state.controller.kneeOffsetVolts)}V", VoltageAmber, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(if (state.controller.bmsConnected) "BMS Online" else "BMS Offline", BatteryGreen, Modifier.weight(1f))
                StatusChip(if (state.controller.ridenConnected) "Riden Online" else "Riden Offline", BatteryGreen, Modifier.weight(1f))
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
            Text(value, color = color, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(unit, color = TextMuted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.height(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private data class CompactEnergy(val value: String, val unit: String)

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
    return "%.0f".format(this)
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
