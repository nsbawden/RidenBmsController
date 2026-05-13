package com.example.ridenbmscontroller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ridenbmscontroller.model.AppState
import com.example.ridenbmscontroller.ui.theme.BatteryGreen
import com.example.ridenbmscontroller.ui.theme.CurrentRose
import com.example.ridenbmscontroller.ui.theme.Panel
import com.example.ridenbmscontroller.ui.theme.PanelAlt
import com.example.ridenbmscontroller.ui.theme.PowerBlue
import com.example.ridenbmscontroller.ui.theme.TextMuted
import com.example.ridenbmscontroller.ui.theme.VoltageAmber
import com.example.ridenbmscontroller.ui.theme.WarningOrange
import kotlin.math.abs

private val toolTabs = listOf("Events", "Tuning", "Telemetry")

@Composable
fun ToolsScreen(state: AppState, modifier: Modifier = Modifier) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Tools", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            TabRow(selectedTabIndex = selectedTab) {
                toolTabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (selectedTab) {
                0 -> EventsTool(state)
                1 -> TuningTool(state)
                else -> TelemetryTool(state)
            }
        }
    }
}

@Composable
private fun EventsTool(state: AppState) {
    ToolCard("Controller Events") {
        if (state.events.isEmpty()) {
            Text("No controller events yet.", color = TextMuted)
        } else {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                state.events.forEach {
                    Text(
                        text = it,
                        color = TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun TuningTool(state: AppState) {
    val target = state.settings.socHoldCurrentAmps
    val actual = state.battery.amps
    val deadband = state.settings.bmsCurrentDeadbandAmps
    val inDeadband = abs(actual) <= deadband
    val error = target - actual
    val targetReached = state.battery.socPercent >= state.controller.socTargetPercent
    val guidance = when {
        !state.controller.enabled -> "Controller is off."
        !state.controller.bmsConnected -> "Waiting for BMS telemetry."
        !state.controller.ridenConnected -> "Waiting for Riden telemetry."
        state.controller.pvMode == "Alarm" -> "Charging is inhibited by BMS alarm handling."
        state.controller.recoveryActive -> "Solar recovery is active; SOC hold tuning is secondary right now."
        targetReached && inDeadband -> "No tuning change suggested. BMS current is inside the configured near-zero deadband, so small real currents may be hidden by BMS resolution."
        targetReached && error > 1.0 -> "Battery is below the hold target right now. Brief bursts are normal as loads cycle; consider changing the target only if this stays high for several minutes in steady sun."
        targetReached && error < -1.0 -> "Battery is charging above the hold target right now. The controller should trim ISET; consider lowering the target only if this persists."
        targetReached -> "No tuning change suggested. A small positive error means the battery is near net zero while the controller aims for a gentle +%.2fA hold.".format(target)
        else -> "SOC target has not been reached; normal charge tracking is active."
    }

    ToolCard("SOC Hold Helper") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MiniTile(
                label = "BMS Net",
                value = "%+.2f".format(actual),
                unit = if (inDeadband) "A near 0" else "A",
                color = if (inDeadband) TextMuted else CurrentRose,
                modifier = Modifier.weight(1f)
            )
            MiniTile("Hold Target", "%+.2f".format(target), "A", BatteryGreen, Modifier.weight(1f))
            MiniTile(
                label = "Error",
                value = "%+.2f".format(error),
                unit = "A",
                color = if (abs(error) <= 0.25) BatteryGreen else WarningOrange,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MiniTile(
                label = "SOC Target",
                value = state.controller.socTargetPercent.toString(),
                unit = "%",
                color = if (state.controller.socTargetPercent >= 100) WarningOrange else BatteryGreen,
                modifier = Modifier.weight(1f)
            )
            MiniTile("ISET", "%.2f".format(state.controller.commandIset), "A", CurrentRose, Modifier.weight(1f))
            MiniTile("BMS Deadband", "%.2f".format(deadband), "A", VoltageAmber, Modifier.weight(1f))
        }
        MiniTile("Mode", state.controller.pvMode, "", modeColor(state.controller.pvMode), Modifier.fillMaxWidth())
        Text(guidance, color = TextMuted, fontSize = 13.sp)
        Text(state.controller.status, color = TextMuted, fontSize = 13.sp)
    }
}

@Composable
private fun TelemetryTool(state: AppState) {
    ToolCard("Live Telemetry") {
        state.logs.forEach {
            Text(it, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ToolCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun MiniTile(
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(color = PanelAlt, shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(6.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, color = TextMuted, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
                Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(unit, color = TextMuted, fontSize = 9.sp)
            }
        }
    }
}

private fun modeColor(mode: String): Color {
    return when (mode) {
        "SOC Hold" -> BatteryGreen
        "Balance" -> WarningOrange
        "Recover", "Alarm" -> WarningOrange
        "Voltage Limit" -> VoltageAmber
        "Tracking" -> PowerBlue
        else -> TextMuted
    }
}
