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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ridenbmscontroller.model.AppState
import com.example.ridenbmscontroller.model.KneeLearningBin
import com.example.ridenbmscontroller.ui.theme.BatteryGreen
import com.example.ridenbmscontroller.ui.theme.CurrentRose
import com.example.ridenbmscontroller.ui.theme.Panel
import com.example.ridenbmscontroller.ui.theme.PanelAlt
import com.example.ridenbmscontroller.ui.theme.PowerBlue
import com.example.ridenbmscontroller.ui.theme.TextMuted
import com.example.ridenbmscontroller.ui.theme.VoltageAmber
import com.example.ridenbmscontroller.ui.theme.WarningOrange
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

private val toolTabs = listOf("Events", "Tuning", "Knee", "Telemetry")

@Composable
fun ToolsScreen(
    state: AppState,
    modifier: Modifier = Modifier,
    onSaveDebugSnapshot: (String) -> String = { "" },
    onImportKneeData: (String) -> String = { "Import unavailable" }
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

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
                2 -> KneeLearningTool(state, onSaveDebugSnapshot, onImportKneeData)
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
private fun KneeLearningTool(
    state: AppState,
    onSaveDebugSnapshot: (String) -> String,
    onImportKneeData: (String) -> String
) {
    val clipboard = LocalClipboardManager.current
    var saveMessage by rememberSaveable { mutableStateOf("") }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var importText by rememberSaveable { mutableStateOf("") }
    fun closeImportDialog() {
        showImportDialog = false
    }
    ToolCard("Knee Learning") {
        Text(
            "Advisory only. Low knees learn after 30s stable; high knees require at least a full Knee Tracking Delay plus margin.",
            color = TextMuted,
            fontSize = 13.sp
        )
        MiniTile(
            "Live",
            "ISET ${"%.2f".format(state.controller.commandIset)}A",
            "Knee ${"%.1f".format(state.controller.targetPvVolts)}V | Err ${"%+.2f".format(state.controller.vinErrorVolts)}V",
            modeColor(state.controller.pvMode),
            Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { clipboard.setText(AnnotatedString(state.toKneeLearningJson())) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Copy JSON")
            }
            Button(
                onClick = {
                    val path = onSaveDebugSnapshot(state.toKneeLearningJson())
                    saveMessage = if (path.isBlank()) {
                        "Save failed"
                    } else {
                        "Saved debug_snapshot.json"
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Save JSON")
            }
        }
        Button(
            onClick = {
                importText = ""
                showImportDialog = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Import Knee JSON")
        }
        if (saveMessage.isNotBlank()) {
            Text(saveMessage, color = TextMuted, fontSize = 13.sp)
        }
    }
    ToolCard("Learned Table") {
        Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KneeTableHeader()
            state.kneeLearningBins.forEach { bin ->
                KneeBinRow(bin)
            }
        }
    }
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { closeImportDialog() },
            title = { Text("Import Knee Data") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste a full diagnostic JSON, a kneeLearningBins array, or a reset command.", color = TextMuted, fontSize = 13.sp)
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        minLines = 6,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveMessage = onImportKneeData(importText)
                        closeImportDialog()
                    }
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { closeImportDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun AppState.toKneeLearningJson(): String {
    val root = JSONObject()
        .put("schema", "riden-bms-knee-learning-v2")
        .put("exportedAtMs", System.currentTimeMillis())
        .put("settings", settingsJson())
        .put("controller", controllerJson())
        .put("battery", batteryJson())
        .put("riden", ridenJson())
        .put("kneeLearningBins", JSONArray().also { bins ->
            kneeLearningBins.forEach { bins.put(it.toJson()) }
        })
    return root.toString(2)
}

private fun AppState.settingsJson(): JSONObject = JSONObject()
    .put("maximumControllerVoltage", settings.maxBatteryVolts)
    .put("balanceEveryDays", settings.balanceEveryDays)
    .put("lastBalanceEpochDay", settings.lastBalanceEpochDay)
    .put("maxChargeAmps", settings.maxChargeAmps)
    .put("baseTargetPvVolts", settings.targetPvVolts)
    .put("controllerEnabled", settings.controllerEnabled)
    .put("normalSocCeilingPercent", settings.normalSocCeilingPercent)
    .put("socHoldCurrentAmps", settings.socHoldCurrentAmps)
    .put("bmsCurrentDeadbandAmps", settings.bmsCurrentDeadbandAmps)
    .put("lowSocAlarmPercent", settings.lowSocAlarmPercent)
    .put("minTargetPvVolts", settings.minTargetPvVolts)
    .put("maxTargetPvVolts", settings.maxTargetPvVolts)
    .put("kneeStepVolts", settings.kneeStepVolts)
    .put("kneeTrackingDelaySeconds", settings.kneeTrackingDelaySeconds)
    .put("controllerLoopMs", settings.controllerLoopMs)
    .put("keepScreenOn", settings.keepScreenOn)

private fun AppState.controllerJson(): JSONObject = JSONObject()
    .put("enabled", controller.enabled)
    .put("pvMode", controller.pvMode)
    .put("status", controller.status)
    .put("targetChargeCurrent", controller.targetChargeCurrent)
    .put("commandIset", controller.commandIset)
    .put("activeTargetPvVolts", controller.targetPvVolts)
    .put("kneeOffsetVolts", controller.kneeOffsetVolts)
    .put("vinErrorVolts", controller.vinErrorVolts)
    .put("recoveryPhase", controller.recoveryPhase)
    .put("recoveryCycleCount", controller.recoveryCycleCount)
    .put("controlBand", controller.controlBand)
    .put("controlStepAmps", controller.controlStepAmps)
    .put("ridenConnected", controller.ridenConnected)
    .put("bmsConnected", controller.bmsConnected)
    .put("recoveryActive", controller.recoveryActive)
    .put("socTargetPercent", controller.socTargetPercent)

private fun AppState.batteryJson(): JSONObject = JSONObject()
    .put("socPercent", battery.socPercent)
    .put("volts", battery.volts)
    .put("amps", battery.amps)
    .put("watts", battery.watts)
    .putNullable("remainingAh", battery.remainingAh)
    .putNullable("nominalAh", battery.nominalAh)
    .put("temperatureF", battery.temperatureF)
    .put("direction", battery.direction.name)
    .put("balancing", battery.balancing)
    .put("cellDeltaMv", battery.cellDeltaMv)

private fun AppState.ridenJson(): JSONObject = JSONObject()
    .put("vin", riden.vin)
    .put("vout", riden.vout)
    .put("iout", riden.iout)
    .put("watts", riden.watts)
    .put("vset", riden.vset)
    .put("iset", riden.iset)
    .put("targetVin", riden.targetVin)

private fun KneeLearningBin.toJson(): JSONObject = JSONObject()
    .put("index", index)
    .put("minIset", minIset)
    .put("maxIset", maxIset)
    .putNullable("learnedKneeVolts", learnedKneeVolts)
    .put("confidence", confidence)
    .put("sampleCount", sampleCount)
    .put("stableSeconds", stableSeconds)
    .put("currentStableRunSeconds", currentStableRunSeconds)
    .put("longestStableRunSeconds", longestStableRunSeconds)
    .putNullable("candidateKneeVolts", candidateKneeVolts)
    .put("candidateStableSeconds", candidateStableSeconds)
    .putNullable("highestStableKneeVolts", highestStableKneeVolts)
    .put("highStableSeconds", highStableSeconds)
    .put("highCurrentStableRunSeconds", highCurrentStableRunSeconds)
    .put("highLongestStableRunSeconds", highLongestStableRunSeconds)
    .putNullable("candidateHighKneeVolts", candidateHighKneeVolts)
    .put("candidateHighStableSeconds", candidateHighStableSeconds)
    .put("bestWatts", bestWatts)
    .put("lastIset", lastIset)
    .put("lastIout", lastIout)
    .put("lastWatts", lastWatts)
    .put("lastVinError", lastVinError)
    .putNullable("lastTemperatureF", lastTemperatureF)
    .putNullable("minTemperatureF", minTemperatureF)
    .putNullable("maxTemperatureF", maxTemperatureF)
    .put("lastUpdatedMs", lastUpdatedMs)
    .put("manual", manual)

private fun JSONObject.putNullable(name: String, value: Double?): JSONObject {
    return put(name, value ?: JSONObject.NULL)
}

@Composable
private fun KneeTableHeader() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TableText("ISET", 70)
        TableText("Low", 88)
        TableText("LoCand", 88)
        TableText("High", 88)
        TableText("HiCand", 88)
        TableText("Conf", 52)
        TableText("N", 36)
        TableText("LoStbl", 78)
        TableText("HiStbl", 78)
        TableText("Temp", 94)
        TableText("Best W", 62)
        TableText("Last", 160)
    }
}

@Composable
private fun KneeBinRow(bin: KneeLearningBin) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TableText("%.0f-%.0fA".format(bin.minIset, bin.maxIset), 70)
        TableText(bin.learnedKneeVolts?.let { "%.2fV".format(it) } ?: "--", 88, if (bin.manual) VoltageAmber else BatteryGreen)
        TableText(
            bin.candidateKneeVolts?.let { "%.2fV %.0fs".format(it, bin.candidateStableSeconds) } ?: "--",
            88,
            VoltageAmber
        )
        TableText(bin.highestStableKneeVolts?.let { "%.2fV".format(it) } ?: "--", 88, PowerBlue)
        TableText(
            bin.candidateHighKneeVolts?.let { "%.2fV %.0fs".format(it, bin.candidateHighStableSeconds) } ?: "--",
            88,
            VoltageAmber
        )
        TableText("%.0f%%".format(bin.confidence * 100.0), 52)
        TableText(bin.sampleCount.toString(), 36)
        TableText(
            "%.0f/%.0fs".format(bin.stableSeconds, bin.longestStableRunSeconds),
            78
        )
        TableText(
            "%.0f/%.0fs".format(bin.highStableSeconds, bin.highLongestStableRunSeconds),
            78
        )
        TableText(
            formatTempRange(bin),
            94
        )
        TableText("%.0f".format(bin.bestWatts), 62)
        TableText(
            if (bin.lastUpdatedMs == 0L) {
                "--"
            } else {
                "%.1fA %.1fW err%+.2f".format(bin.lastIset, bin.lastWatts, bin.lastVinError)
            },
            160
        )
    }
}

private fun formatTempRange(bin: KneeLearningBin): String {
    val last = bin.lastTemperatureF
    val min = bin.minTemperatureF
    val max = bin.maxTemperatureF
    return when {
        last == null -> "--"
        min != null && max != null -> "%.0fF %.0f-%.0f".format(last, min, max)
        else -> "%.0fF".format(last)
    }
}

@Composable
private fun TableText(text: String, widthDp: Int, color: Color = TextMuted) {
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        maxLines = 1,
        modifier = Modifier.width(widthDp.dp)
    )
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
