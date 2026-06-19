package com.example.ridenbmscontroller.ui

import androidx.compose.ui.graphics.Color
import com.example.ridenbmscontroller.riden.RidenTemp
import com.example.ridenbmscontroller.ui.theme.BatteryGreen
import com.example.ridenbmscontroller.ui.theme.TextMuted
import com.example.ridenbmscontroller.ui.theme.VoltageAmber
import com.example.ridenbmscontroller.ui.theme.WarningOrange

private val RidenHotRed = Color(0xFFFF4D4D)

fun ridenInternalTempColor(tempF: Double?): Color {
    if (tempF == null) return TextMuted
    if (tempF > RidenTemp.COLOR_RED_ABOVE_F) return RidenHotRed
    val span = (RidenTemp.COLOR_RED_ABOVE_F - RidenTemp.WARN_START_F).coerceAtLeast(1.0)
    val ratio = ((tempF - RidenTemp.WARN_START_F) / span).coerceIn(0.0, 1.0)
    return when {
        tempF <= RidenTemp.WARN_START_F -> BatteryGreen
        ratio < 0.5 -> WarningOrange
        else -> VoltageAmber
    }
}

fun formatRidenTempF(tempF: Double?): String {
    return tempF?.let { "%.0f".format(it) } ?: "—"
}
