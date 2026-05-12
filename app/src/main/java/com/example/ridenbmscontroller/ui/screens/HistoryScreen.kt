package com.example.ridenbmscontroller.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ridenbmscontroller.model.AppState
import com.example.ridenbmscontroller.model.HistoryPoint
import com.example.ridenbmscontroller.ui.theme.BatteryGreen
import com.example.ridenbmscontroller.ui.theme.CurrentRose
import com.example.ridenbmscontroller.ui.theme.Panel
import com.example.ridenbmscontroller.ui.theme.PowerBlue
import com.example.ridenbmscontroller.ui.theme.TextMuted
import com.example.ridenbmscontroller.ui.theme.VoltageAmber
import com.example.ridenbmscontroller.ui.theme.WarningOrange
import kotlin.math.abs
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(state: AppState, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val dayKeys = remember(state.history) { state.history.map { it.dayKey }.distinct().sortedDescending() }
    var selectedDayKey by remember { mutableIntStateOf(dayKeys.firstOrNull() ?: 0) }
    LaunchedEffect(dayKeys) {
        if (selectedDayKey == 0 || (dayKeys.isNotEmpty() && selectedDayKey !in dayKeys)) {
            selectedDayKey = dayKeys.firstOrNull() ?: 0
        }
    }
    val selectedIndex = dayKeys.indexOf(selectedDayKey).takeIf { it >= 0 } ?: 0
    val dayPoints = remember(state.history, selectedDayKey) {
        state.history.filter { it.dayKey == selectedDayKey }.sortedBy { it.timestampMs }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("History", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (selectedDayKey == 0) "No days recorded" else selectedDayKey.toDayLabel(), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { selectedDayKey = dayKeys.getOrElse(selectedIndex + 1) { selectedDayKey } },
                        enabled = selectedIndex < dayKeys.lastIndex,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Prev")
                    }
                    OutlinedButton(
                        onClick = { selectedDayKey = dayKeys.getOrElse(selectedIndex - 1) { selectedDayKey } },
                        enabled = selectedIndex > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Next")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(dayPoints.toTsv())) },
                        enabled = dayPoints.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Copy Day")
                    }
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(state.history.sortedBy { it.timestampMs }.toTsv())) },
                        enabled = state.history.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Copy 30 Days")
                    }
                }
            }
        }
        LineChart("Battery current", dayPoints, CurrentRose, "A") { it.batteryAmps }
        LineChart("Riden watts", dayPoints, PowerBlue, "W") { it.ridenWatts }
        LineChart("Battery voltage", dayPoints, VoltageAmber, "V") { it.batteryVolts }
        LineChart("BMS watts", dayPoints, PowerBlue, "W") { it.batteryWatts }
        LineChart("State of charge", dayPoints, BatteryGreen, "%") { it.socPercent.toDouble() }
        LineChart("Battery temperature", dayPoints, WarningOrange, "F") { it.temperatureF }
    }
}

@Composable
private fun LineChart(
    title: String,
    points: List<HistoryPoint>,
    color: Color,
    unit: String,
    value: (HistoryPoint) -> Double
) {
    var zoom by remember(title, points.firstOrNull()?.dayKey) { mutableFloatStateOf(1f) }
    var panSamples by remember(title, points.firstOrNull()?.dayKey) { mutableFloatStateOf(0f) }
    var chartWidthPx by remember(title, points.firstOrNull()?.dayKey) { mutableFloatStateOf(1f) }

    fun applyChartGesture(zoomChange: Float, panX: Float) {
        zoom = (zoom * zoomChange).coerceIn(1f, 24f)
        val visibleCount = (points.size / zoom).coerceIn(2f, points.size.toFloat())
        val maxStart = (points.size - visibleCount).coerceAtLeast(0f)
        val samplesPerPixel = visibleCount / chartWidthPx.coerceAtLeast(1f)
        panSamples = (panSamples - panX * samplesPerPixel).coerceIn(0f, maxStart)
    }

    Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            val latest = points.lastOrNull()?.let { "%.2f %s".format(value(it), unit) } ?: "-"
            Text(if (points.isEmpty()) "No history recorded yet" else "Latest $latest", color = TextMuted)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .padding(top = 10.dp)
                    .onSizeChanged { chartWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                    .pointerInput(points.size, title) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var previousCentroid: Offset? = null
                            var previousSpan = 0f

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break

                                if (pressed.size >= 2) {
                                    val centroid = pressed
                                        .map { it.position }
                                        .reduce { acc, offset -> acc + offset } / pressed.size.toFloat()
                                    val span = pressed
                                        .map { (it.position - centroid).getDistance() }
                                        .average()
                                        .toFloat()

                                    val lastCentroid = previousCentroid
                                    if (lastCentroid != null && previousSpan > 0f && span > 0f) {
                                        applyChartGesture(span / previousSpan, centroid.x - lastCentroid.x)
                                    }
                                    previousCentroid = centroid
                                    previousSpan = span
                                    event.changes.forEach { it.consume() }
                                } else {
                                    previousCentroid = null
                                    previousSpan = 0f
                                    val change = pressed.first()
                                    val delta = change.positionChange()
                                    if (abs(delta.x) > abs(delta.y)) {
                                        applyChartGesture(1f, delta.x)
                                        change.consume()
                                    }
                                }
                            }
                        }
                    }
            ) {
                if (points.size < 2) return@Canvas
                val visibleCount = (points.size / zoom).roundToInt().coerceIn(2, points.size)
                val maxStart = points.size - visibleCount
                val start = panSamples.roundToInt().coerceIn(0, maxStart)
                val visible = points.drop(start).take(visibleCount)
                val values = points.map(value)
                val min = values.minOrNull() ?: 0.0
                val max = values.maxOrNull() ?: 1.0
                val padding = ((max - min) * 0.08).takeIf { it > 0.0 } ?: 1.0
                val low = min - padding
                val high = max + padding
                val range = (high - low).takeIf { it > 0.0 } ?: 1.0
                val left = 48.dp.toPx()
                val right = size.width - 12.dp.toPx()
                val top = 10.dp.toPx()
                val bottom = size.height - 34.dp.toPx()
                val firstMs = visible.first().timestampMs.toDouble()
                val spanMs = (visible.last().timestampMs - visible.first().timestampMs).toDouble().takeIf { it > 0.0 } ?: 1.0
                val labelPaint = android.graphics.Paint().apply {
                    setColor(android.graphics.Color.argb(180, 165, 174, 190))
                    textSize = 9.sp.toPx()
                    isAntiAlias = true
                }

                drawLine(TextMuted.copy(alpha = 0.35f), Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
                drawLine(TextMuted.copy(alpha = 0.35f), Offset(left, top), Offset(left, bottom), 1.dp.toPx())

                repeat(5) { tick ->
                    val fraction = tick / 4f
                    val y = bottom - (bottom - top) * fraction
                    val tickValue = low + range * fraction
                    drawLine(TextMuted.copy(alpha = 0.22f), Offset(left, y), Offset(right, y), 1.dp.toPx())
                    drawLine(TextMuted.copy(alpha = 0.60f), Offset(left - 5.dp.toPx(), y), Offset(left, y), 1.dp.toPx())
                    drawIntoCanvas {
                        it.nativeCanvas.drawText(tickValue.formatAxis(unit), 2.dp.toPx(), y + 3.dp.toPx(), labelPaint)
                    }
                }

                repeat(5) { tick ->
                    val fraction = tick / 4f
                    val x = left + (right - left) * fraction
                    val labelMs = firstMs + spanMs * fraction
                    drawLine(TextMuted.copy(alpha = 0.18f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
                    drawLine(TextMuted.copy(alpha = 0.60f), Offset(x, bottom), Offset(x, bottom + 5.dp.toPx()), 1.dp.toPx())
                    drawIntoCanvas {
                        it.nativeCanvas.drawText(labelMs.toLong().formatTimeLabel(), x - 18.dp.toPx(), bottom + 19.dp.toPx(), labelPaint)
                    }
                }

                val chartPoints = visible.map { item ->
                    val x = left + (right - left) * (((item.timestampMs - firstMs) / spanMs).toFloat())
                    val normalized = ((value(item) - low) / range).toFloat()
                    val y = bottom - (bottom - top) * normalized
                    Offset(x, y)
                }

                chartPoints.zipWithNext().forEach { (a, b) ->
                    drawLine(color, a, b, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                }
                if (visible.size <= 20) {
                    chartPoints.forEach {
                        drawCircle(color, radius = 3.5.dp.toPx(), center = it, style = Stroke(width = 1.8.dp.toPx()))
                    }
                }
            }
        }
    }
}

private fun Double.formatAxis(unit: String): String {
    return when {
        unit == "%" -> "%.0f".format(this)
        kotlin.math.abs(this) >= 100.0 -> "%.0f".format(this)
        kotlin.math.abs(this) >= 10.0 -> "%.1f".format(this)
        else -> "%.2f".format(this)
    }
}

private fun Long.formatTimeLabel(): String {
    return SimpleDateFormat("H:mm", Locale.US).format(Date(this))
}

private fun Int.toDayLabel(): String {
    val year = this / 1000
    val dayOfYear = this % 1000
    val formatter = SimpleDateFormat("EEE, MMM d yyyy", Locale.US)
    val calendar = java.util.Calendar.getInstance()
    calendar.set(java.util.Calendar.YEAR, year)
    calendar.set(java.util.Calendar.DAY_OF_YEAR, dayOfYear)
    return formatter.format(calendar.time)
}

private fun List<HistoryPoint>.toTsv(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return buildString {
        appendLine("Time\tBattery V\tBattery A\tBMS W\tSOC %\tTemp F\tRiden VIN\tRiden VOUT\tRiden IOUT\tRiden W")
        this@toTsv.forEach { point ->
            append(formatter.format(Date(point.timestampMs)))
            append('\t')
            append("%.3f".format(point.batteryVolts))
            append('\t')
            append("%.3f".format(point.batteryAmps))
            append('\t')
            append("%.1f".format(point.batteryWatts))
            append('\t')
            append(point.socPercent)
            append('\t')
            append("%.1f".format(point.temperatureF))
            append('\t')
            append("%.3f".format(point.ridenVin))
            append('\t')
            append("%.3f".format(point.ridenVout))
            append('\t')
            append("%.3f".format(point.ridenIout))
            append('\t')
            appendLine("%.1f".format(point.ridenWatts))
        }
    }
}
