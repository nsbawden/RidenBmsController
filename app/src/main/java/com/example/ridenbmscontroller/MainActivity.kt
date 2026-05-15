package com.example.ridenbmscontroller

import android.os.Bundle
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import com.example.ridenbmscontroller.ble.BmsBleDevice
import com.example.ridenbmscontroller.ble.BmsBleScanner
import com.example.ridenbmscontroller.ble.BmsBleUiState
import com.example.ridenbmscontroller.controller.MpptControlState
import com.example.ridenbmscontroller.controller.SolarMpptController
import com.example.ridenbmscontroller.model.AppState
import com.example.ridenbmscontroller.model.AppSettings
import com.example.ridenbmscontroller.model.AlertState
import com.example.ridenbmscontroller.model.BatteryState
import com.example.ridenbmscontroller.model.ChargeMode
import com.example.ridenbmscontroller.model.ControllerState
import com.example.ridenbmscontroller.model.EnergyCounters
import com.example.ridenbmscontroller.model.HistoryPoint
import com.example.ridenbmscontroller.model.KneeLearningBin
import com.example.ridenbmscontroller.model.PowerDirection
import com.example.ridenbmscontroller.model.RidenState
import com.example.ridenbmscontroller.riden.RidenUsbMonitor
import com.example.ridenbmscontroller.riden.RidenUsbState
import com.example.ridenbmscontroller.ui.screens.DashboardScreen
import com.example.ridenbmscontroller.ui.screens.DevicesScreen
import com.example.ridenbmscontroller.ui.screens.HistoryScreen
import com.example.ridenbmscontroller.ui.screens.SettingsScreen
import com.example.ridenbmscontroller.ui.screens.ToolsScreen
import com.example.ridenbmscontroller.ui.theme.RidenBmsTheme
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var bmsBleScanner: BmsBleScanner
    private lateinit var ridenUsbMonitor: RidenUsbMonitor
    private lateinit var mpptController: SolarMpptController
    private var bmsBleState by mutableStateOf(BmsBleUiState())
    private var ridenUsbState by mutableStateOf(RidenUsbState())
    private var appSettings by mutableStateOf(AppState.preview.settings)
    private var mpptControlState by mutableStateOf(MpptControlState())
    private var energyCounters by mutableStateOf(AppState.preview.energy)
    private var energyDayKey = 0
    private var lastEnergyMs = 0L
    private var lastBmsEnergyMs = 0L
    private var lastPersistEnergyMs = 0L
    private var lastEnergyWatts: Double? = null
    private var lastBmsEnergyWatts: Double? = null
    private var historyPoints by mutableStateOf(emptyList<HistoryPoint>())
    private var controllerEvents by mutableStateOf(emptyList<String>())
    private var alertState by mutableStateOf(AppState.preview.alerts)
    private var batteryTimeEstimateText by mutableStateOf("")
    private var lastBatteryTimeEstimateMs = 0L
    private var lastControllerEventSignature = ""
    private var lastHistorySampleMs = 0L
    private var lastHistoryPruneMs = 0L
    private var lastKneeLearningSampleMs = 0L
    private var kneeLearningBins by mutableStateOf(defaultKneeLearningBins())
    private var batteryCurrentAverage = RollingAverageWindow(60_000L)
    private var historyAccumulator = HistoryAccumulator()
    private var hadGoodRidenConnection = false
    private var usbAlarmJob: Job? = null
    private var lowSocAlarmJob: Job? = null
    private var lowSocSilenced = false

    private val requestBlePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        bmsBleScanner.refresh()
        if (bmsBleScanner.hasPermissions()) {
            bmsBleScanner.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appSettings = loadSettings()
        kneeLearningBins = loadKneeLearningBins()
        loadEnergy()
        historyPoints = loadHistory()
        lastHistorySampleMs = System.currentTimeMillis()
        applyKeepScreenOn(appSettings.keepScreenOn)
        applyControllerKeepAlive(appSettings.controllerEnabled)
        mpptController = SolarMpptController(
            setOutput = { ridenUsbMonitor.setOutput(it) },
            setVset = { ridenUsbMonitor.setVset(it) },
            setIset = { ridenUsbMonitor.setIset(it) },
            onBalanceDayStarted = { markBalanceDayStarted(it) },
            onState = { handleMpptControlState(it) }
        )
        bmsBleScanner = BmsBleScanner(this) {
            bmsBleState = it
            batteryCurrentAverage.add(System.currentTimeMillis(), it.telemetry.packCurrent)
            updateBatteryTimeEstimate()
            historyAccumulator.addBms(it)
            updateBmsEnergyFromPower(it.telemetry.packVoltage, it.telemetry.packCurrent)
            maybeRecordHistory()
            updateLowSocAlarm()
            tickController()
        }
        ridenUsbMonitor = RidenUsbMonitor(this, lifecycleScope) {
            ridenUsbState = it
            historyAccumulator.addRiden(it)
            updateEnergyFromPower(it.telemetry.watts)
            maybeRecordHistory()
            updateUsbAlarm(it.connected)
            tickController()
        }
        bmsBleScanner.refresh()
        ridenUsbMonitor.start()
        setContent {
            RidenBmsTheme {
                RidenBmsApp(
                    bmsBleState = bmsBleState,
                    ridenUsbState = ridenUsbState,
                    appSettings = appSettings,
                    mpptControlState = mpptControlState,
                    energyCounters = energyCounters,
                    historyPoints = historyPoints,
                    controllerEvents = controllerEvents,
                    alertState = alertState,
                    kneeLearningBins = kneeLearningBins,
                    batteryTimeEstimateText = batteryTimeEstimateText,
                    onSettingsChanged = {
                        appSettings = it
                        saveSettings(it)
                        applyKeepScreenOn(it.keepScreenOn)
                        applyControllerKeepAlive(it.controllerEnabled)
                        updateLowSocAlarm()
                        tickController()
                    },
                    onRequestBlePermissions = {
                        requestBlePermissions.launch(bmsBleScanner.requiredPermissions())
                    },
                    onStartBmsScan = { bmsBleScanner.startScan() },
                    onStopBmsScan = { bmsBleScanner.stopScan() },
                    onConnectBms = { bmsBleScanner.connect(it) },
                    onDisconnectBms = { bmsBleScanner.disconnect() },
                    balanceDayToday = isBalanceDayToday(appSettings),
                    daysUntilNextBalance = daysUntilNextBalance(appSettings),
                    onToggleBalanceToday = { toggleBalanceToday() },
                    onResetEnergyTotal = { resetEnergyTotal() },
                    onResetLearnedKnee = {
                        mpptController.resetLearnedKnee()
                        tickController()
                    },
                    onSetActiveKnee = { volts ->
                        mpptController.setActiveKnee(appSettings, volts)
                        tickController()
                    },
                    onSaveDebugSnapshot = { json -> saveDebugSnapshot(json) },
                    onImportKneeData = { json -> importKneeLearningData(json) },
                    onSilenceLowSocAlarm = { silenceLowSocAlarm() }
                )
            }
        }
    }

    private fun tickController() {
        if (!::mpptController.isInitialized) return
        mpptController.tick(appSettings, bmsBleState, ridenUsbState)
    }

    private fun updateUsbAlarm(connected: Boolean) {
        if (connected) {
            if (!hadGoodRidenConnection) addControllerEvent("Riden USB connected")
            hadGoodRidenConnection = true
            stopUsbAlarm()
            publishAlerts()
            return
        }
        if (hadGoodRidenConnection) {
            if (usbAlarmJob == null) addControllerEvent("Riden USB disconnected")
            startUsbAlarmIfNeeded()
        }
        publishAlerts()
    }

    private fun startUsbAlarmIfNeeded() {
        if (usbAlarmJob != null) return
        usbAlarmJob = lifecycleScope.launch {
            val tone = try {
                ToneGenerator(AudioManager.STREAM_ALARM, 100)
            } catch (_: Exception) {
                null
            }
            try {
                while (isActive) {
                    repeat(3) {
                        try {
                            tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
                        } catch (_: Exception) {
                        }
                        delay(350)
                    }
                    delay(3000)
                }
            } finally {
                try {
                    tone?.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun stopUsbAlarm() {
        usbAlarmJob?.cancel()
        usbAlarmJob = null
    }

    private fun updateLowSocAlarm() {
        val soc = bmsBleState.telemetry.socPercent
        val active = bmsBleState.connectedDeviceAddress != null &&
            soc != null &&
            soc <= appSettings.lowSocAlarmPercent

        if (!active) {
            if (lowSocAlarmJob != null || lowSocSilenced) addControllerEvent("Low SOC alarm cleared")
            lowSocSilenced = false
            stopLowSocAlarm()
            publishAlerts()
            return
        }

        if (!lowSocSilenced) {
            if (lowSocAlarmJob == null) addControllerEvent("Low SOC alarm: $soc%")
            startLowSocAlarmIfNeeded()
        } else {
            stopLowSocAlarm()
        }
        publishAlerts()
    }

    private fun startLowSocAlarmIfNeeded() {
        if (lowSocAlarmJob != null) return
        lowSocAlarmJob = lifecycleScope.launch {
            val tone = try {
                ToneGenerator(AudioManager.STREAM_ALARM, 80)
            } catch (_: Exception) {
                null
            }
            try {
                while (isActive) {
                    try {
                        tone?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 500)
                    } catch (_: Exception) {
                    }
                    delay(6000)
                }
            } finally {
                try {
                    tone?.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun stopLowSocAlarm() {
        lowSocAlarmJob?.cancel()
        lowSocAlarmJob = null
    }

    private fun silenceLowSocAlarm() {
        if (!alertState.lowSocAlarmActive) return
        lowSocSilenced = true
        stopLowSocAlarm()
        addControllerEvent("Low SOC alarm silenced")
        publishAlerts()
    }

    private fun publishAlerts() {
        val soc = bmsBleState.telemetry.socPercent
        val lowSocActive = bmsBleState.connectedDeviceAddress != null &&
            soc != null &&
            soc <= appSettings.lowSocAlarmPercent
        alertState = AlertState(
            usbAlarmActive = usbAlarmJob != null,
            lowSocAlarmActive = lowSocActive,
            lowSocSilenced = lowSocActive && lowSocSilenced,
            lowSocThresholdPercent = appSettings.lowSocAlarmPercent
        )
    }

    private fun handleMpptControlState(state: MpptControlState) {
        val previous = mpptControlState
        mpptControlState = state
        updateBatteryTimeEstimate()
        maybeLearnKneePoint(state)

        val signature = listOf(
            state.enabled,
            state.pvMode,
            state.status,
            "%.1f".format(state.targetChargeCurrent),
            "%.1f".format(state.commandIset),
            state.recoveryActive,
            state.socTargetPercent
        ).joinToString("|")
        if (signature == lastControllerEventSignature) return
        lastControllerEventSignature = signature

        val important = previous.enabled != state.enabled ||
            previous.pvMode != state.pvMode ||
            previous.status != state.status ||
            previous.recoveryActive != state.recoveryActive ||
            previous.socTargetPercent != state.socTargetPercent
        if (!important) return

        addControllerEvent("${state.pvMode}: ${state.status}")
    }

    private fun addControllerEvent(text: String) {
        val event = "${eventTimeFormat.format(System.currentTimeMillis())}  $text"
        controllerEvents = (listOf(event) + controllerEvents).take(MAX_CONTROLLER_EVENTS)
    }

    private fun updateBatteryTimeEstimate() {
        val now = System.currentTimeMillis()
        if (now - lastBatteryTimeEstimateMs < BATTERY_TIME_UPDATE_MS) return
        lastBatteryTimeEstimateMs = now
        val telemetry = bmsBleState.telemetry
        val remainingAh = telemetry.remainingAh
        val nominalAh = telemetry.nominalAh
        val averageAmps = batteryCurrentAverage.average(now)
        batteryTimeEstimateText = if (remainingAh == null || nominalAh == null) {
            ""
        } else {
            batteryTimeEstimate(
                averageAmps = averageAmps,
                remainingAh = remainingAh,
                nominalAh = nominalAh,
                socTargetPercent = mpptControlState.socTargetPercent.takeIf { it > 0 }
                    ?: appSettings.normalSocCeilingPercent
            )
        }
    }

    private fun saveDebugSnapshot(json: String): String {
        return try {
            val file = File(filesDir, DEBUG_SNAPSHOT_FILE)
            file.writeText(json)
            addControllerEvent("Saved debug snapshot: $DEBUG_SNAPSHOT_FILE")
            file.absolutePath
        } catch (error: IOException) {
            addControllerEvent("Debug snapshot save failed: ${error.message ?: "IO error"}")
            ""
        }
    }

    private fun importKneeLearningData(json: String): String {
        return try {
            val trimmed = json.trim()
            if (trimmed.isBlank()) return "Nothing to import"

            val imported = when {
                trimmed.startsWith("[") -> importKneeBins(JSONArray(trimmed))
                else -> {
                    val root = JSONObject(trimmed)
                    when {
                        root.has("resetBinIndexes") -> resetKneeBins(root.getJSONArray("resetBinIndexes"))
                        root.has("kneeLearningBins") -> importKneeBins(root.getJSONArray("kneeLearningBins"))
                        else -> return "No kneeLearningBins or resetBinIndexes found"
                    }
                }
            }
            kneeLearningBins = imported
            persistKneeLearningBins()
            addControllerEvent("Imported knee table data")
            "Imported ${imported.size} knee bins"
        } catch (error: JSONException) {
            "Import failed: ${error.message ?: "invalid JSON"}"
        }
    }

    private fun importKneeBins(array: JSONArray): List<KneeLearningBin> {
        val parsed = (0 until array.length())
            .mapNotNull { index -> array.optJSONObject(index)?.toKneeLearningBinOrNull() }
            .associateBy { it.index }

        return defaultKneeLearningBins().map { default ->
            parsed[default.index]
                ?.copy(minIset = default.minIset, maxIset = default.maxIset)
                ?.withDerivedConfidence()
                ?: default
        }
    }

    private fun resetKneeBins(indexes: JSONArray): List<KneeLearningBin> {
        val resetIndexes = (0 until indexes.length())
            .mapNotNull { indexes.optIntOrNull(it) }
            .toSet()
        val defaults = defaultKneeLearningBins().associateBy { it.index }
        return kneeLearningBins.map { bin -> defaults[bin.index]?.takeIf { bin.index in resetIndexes } ?: bin }
    }

    private fun maybeLearnKneePoint(control: MpptControlState) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKneeLearningSampleMs < KNEE_LEARNING_SAMPLE_MS) return
        if (!control.enabled || control.pvMode != "Tracking" || control.recoveryActive) return
        if (control.commandIset < 0.2 || control.commandIset >= appSettings.maxChargeAmps - 0.1) return
        if (abs(control.vinErrorVolts) > KNEE_LEARNING_MAX_ERR_V) return

        val telemetry = ridenUsbState.telemetry
        val watts = telemetry.watts ?: return
        val iout = telemetry.iout ?: 0.0
        val temperatureF = bmsBleState.telemetry.temperatureF
        if (watts < KNEE_LEARNING_MIN_WATTS) return

        val index = kneeLearningBins.indexOfFirst {
            control.commandIset >= it.minIset && control.commandIset < it.maxIset
        }
        if (index < 0) return

        lastKneeLearningSampleMs = now
        var changed = false
        kneeLearningBins = kneeLearningBins.mapIndexed { binIndex, bin ->
            if (binIndex != index) {
                if (bin.currentStableRunSeconds > 0.0 || bin.highCurrentStableRunSeconds > 0.0 || bin.candidateHighKneeVolts != null) {
                    changed = true
                    bin.copy(
                        currentStableRunSeconds = 0.0,
                        highCurrentStableRunSeconds = 0.0,
                        candidateHighKneeVolts = null,
                        candidateHighStableSeconds = 0.0
                    )
                } else {
                    bin
                }
            } else {
                val sampleSeconds = KNEE_LEARNING_SAMPLE_MS / 1000.0
                val observedKnee = control.targetPvVolts
                val provesCurrentLearned = bin.learnedKneeVolts?.let {
                    abs(observedKnee - it) <= KNEE_LEARNING_KNEE_EPS_V
                } ?: false
                val lowerThanLearned = bin.learnedKneeVolts?.let {
                    observedKnee < it - KNEE_LEARNING_KNEE_EPS_V
                } ?: true
                val sameCandidate = bin.candidateKneeVolts?.let {
                    abs(observedKnee - it) <= KNEE_LEARNING_KNEE_EPS_V
                } ?: false
                val candidateKnee = if (lowerThanLearned) {
                    if (sameCandidate) bin.candidateKneeVolts else observedKnee
                } else {
                    null
                }
                val candidateRun = if (lowerThanLearned) {
                    if (sameCandidate) bin.candidateStableSeconds + sampleSeconds else sampleSeconds
                } else {
                    0.0
                }
                val acceptsCandidate = lowerThanLearned && candidateRun >= KNEE_LEARNING_MIN_STABLE_SECONDS
                val nextKnee = if (acceptsCandidate) {
                    bin.learnedKneeVolts?.let { min(it, candidateKnee ?: observedKnee) } ?: (candidateKnee ?: observedKnee)
                } else {
                    bin.learnedKneeVolts
                }
                val currentRun = when {
                    acceptsCandidate -> candidateRun
                    provesCurrentLearned -> bin.currentStableRunSeconds + sampleSeconds
                    else -> 0.0
                }
                val learnedEvidenceSeconds = when {
                    acceptsCandidate -> candidateRun
                    provesCurrentLearned -> sampleSeconds
                    else -> 0.0
                }
                val nextStableSeconds = bin.stableSeconds + learnedEvidenceSeconds
                val nextLongestRun = maxOf(bin.longestStableRunSeconds, currentRun, candidateRun)
                val nextSampleCount = bin.sampleCount + 1
                val provesCurrentHigh = bin.highestStableKneeVolts?.let {
                    abs(observedKnee - it) <= KNEE_LEARNING_KNEE_EPS_V
                } ?: false
                val higherThanLearnedHigh = bin.highestStableKneeVolts?.let {
                    observedKnee > it + KNEE_LEARNING_KNEE_EPS_V
                } ?: true
                val sameHighCandidate = bin.candidateHighKneeVolts?.let {
                    abs(observedKnee - it) <= KNEE_LEARNING_KNEE_EPS_V
                } ?: false
                val highCandidateKnee = if (higherThanLearnedHigh) {
                    if (sameHighCandidate) bin.candidateHighKneeVolts else observedKnee
                } else {
                    null
                }
                val highCandidateRun = if (higherThanLearnedHigh) {
                    if (sameHighCandidate) bin.candidateHighStableSeconds + sampleSeconds else sampleSeconds
                } else {
                    0.0
                }
                val acceptsHighCandidate = higherThanLearnedHigh &&
                    highCandidateRun >= highKneeStableRequirementSeconds()
                val nextHighKnee = if (acceptsHighCandidate) {
                    bin.highestStableKneeVolts?.let { maxOf(it, highCandidateKnee ?: observedKnee) }
                        ?: (highCandidateKnee ?: observedKnee)
                } else {
                    bin.highestStableKneeVolts
                }
                val highCurrentRun = when {
                    acceptsHighCandidate -> highCandidateRun
                    provesCurrentHigh -> bin.highCurrentStableRunSeconds + sampleSeconds
                    else -> 0.0
                }
                val highEvidenceSeconds = when {
                    acceptsHighCandidate -> highCandidateRun
                    provesCurrentHigh -> sampleSeconds
                    else -> 0.0
                }
                changed = true
                bin.copy(
                    learnedKneeVolts = nextKnee,
                    confidence = kneeLearningConfidence(nextStableSeconds, nextLongestRun, nextSampleCount),
                    sampleCount = nextSampleCount,
                    stableSeconds = nextStableSeconds,
                    currentStableRunSeconds = currentRun,
                    longestStableRunSeconds = nextLongestRun,
                    candidateKneeVolts = if (acceptsCandidate) null else candidateKnee,
                    candidateStableSeconds = if (acceptsCandidate) 0.0 else candidateRun,
                    highestStableKneeVolts = nextHighKnee,
                    highStableSeconds = bin.highStableSeconds + highEvidenceSeconds,
                    highCurrentStableRunSeconds = highCurrentRun,
                    highLongestStableRunSeconds = maxOf(
                        bin.highLongestStableRunSeconds,
                        highCurrentRun,
                        highCandidateRun
                    ),
                    candidateHighKneeVolts = if (acceptsHighCandidate) null else highCandidateKnee,
                    candidateHighStableSeconds = if (acceptsHighCandidate) 0.0 else highCandidateRun,
                    bestWatts = maxOf(bin.bestWatts, watts),
                    lastIset = control.commandIset,
                    lastIout = iout,
                    lastWatts = watts,
                    lastVinError = control.vinErrorVolts,
                    lastTemperatureF = temperatureF,
                    minTemperatureF = minNullable(bin.minTemperatureF, temperatureF),
                    maxTemperatureF = maxNullable(bin.maxTemperatureF, temperatureF),
                    lastUpdatedMs = System.currentTimeMillis(),
                    manual = bin.manual && nextKnee == bin.learnedKneeVolts
                )
            }
        }
        if (changed) persistKneeLearningBins()
    }

    private fun loadKneeLearningBins(): List<KneeLearningBin> {
        val byIndex = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
            .getString(KEY_KNEE_LEARNING_BINS, null)
            ?.lineSequence()
            ?.mapNotNull { parseKneeLearningBin(it) }
            ?.associateBy { it.index }
            .orEmpty()

        return defaultKneeLearningBins().map { default ->
            byIndex[default.index]
                ?.copy(minIset = default.minIset, maxIset = default.maxIset)
                ?.withDerivedConfidence()
                ?: default
        }
    }

    private fun persistKneeLearningBins() {
        val encoded = kneeLearningBins.joinToString("\n") { bin ->
            listOf(
                bin.index.toString(),
                bin.learnedKneeVolts?.toString() ?: "",
                bin.confidence.toString(),
                bin.sampleCount.toString(),
                bin.stableSeconds.toString(),
                bin.currentStableRunSeconds.toString(),
                bin.longestStableRunSeconds.toString(),
                bin.candidateKneeVolts?.toString() ?: "",
                bin.candidateStableSeconds.toString(),
                bin.bestWatts.toString(),
                bin.lastIset.toString(),
                bin.lastIout.toString(),
                bin.lastWatts.toString(),
                bin.lastVinError.toString(),
                bin.lastTemperatureF?.toString() ?: "",
                bin.minTemperatureF?.toString() ?: "",
                bin.maxTemperatureF?.toString() ?: "",
                bin.lastUpdatedMs.toString(),
                bin.manual.toString(),
                bin.highestStableKneeVolts?.toString() ?: "",
                bin.highStableSeconds.toString(),
                bin.highCurrentStableRunSeconds.toString(),
                bin.highLongestStableRunSeconds.toString(),
                bin.candidateHighKneeVolts?.toString() ?: "",
                bin.candidateHighStableSeconds.toString()
            ).joinToString("|")
        }
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit {
            putString(KEY_KNEE_LEARNING_BINS, encoded)
        }
    }

    private fun parseKneeLearningBin(line: String): KneeLearningBin? {
        val parts = line.split("|")
        if (parts.size < 11) return null
        val index = parts[0].toIntOrNull() ?: return null
        val default = defaultKneeLearningBins().firstOrNull { it.index == index } ?: return null
        return if (parts.size >= 25) {
            default.copy(
                learnedKneeVolts = parts[1].toDoubleOrNull(),
                confidence = parts[2].toDoubleOrNull() ?: 0.0,
                sampleCount = parts[3].toIntOrNull() ?: 0,
                stableSeconds = parts[4].toDoubleOrNull() ?: 0.0,
                currentStableRunSeconds = parts[5].toDoubleOrNull() ?: 0.0,
                longestStableRunSeconds = parts[6].toDoubleOrNull() ?: 0.0,
                candidateKneeVolts = parts[7].toDoubleOrNull(),
                candidateStableSeconds = parts[8].toDoubleOrNull() ?: 0.0,
                bestWatts = parts[9].toDoubleOrNull() ?: 0.0,
                lastIset = parts[10].toDoubleOrNull() ?: 0.0,
                lastIout = parts[11].toDoubleOrNull() ?: 0.0,
                lastWatts = parts[12].toDoubleOrNull() ?: 0.0,
                lastVinError = parts[13].toDoubleOrNull() ?: 0.0,
                lastTemperatureF = parts[14].toDoubleOrNull(),
                minTemperatureF = parts[15].toDoubleOrNull(),
                maxTemperatureF = parts[16].toDoubleOrNull(),
                lastUpdatedMs = parts[17].toLongOrNull() ?: 0L,
                manual = parts[18].toBooleanStrictOrNull() ?: false,
                highestStableKneeVolts = parts[19].toDoubleOrNull(),
                highStableSeconds = parts[20].toDoubleOrNull() ?: 0.0,
                highCurrentStableRunSeconds = parts[21].toDoubleOrNull() ?: 0.0,
                highLongestStableRunSeconds = parts[22].toDoubleOrNull() ?: 0.0,
                candidateHighKneeVolts = parts[23].toDoubleOrNull(),
                candidateHighStableSeconds = parts[24].toDoubleOrNull() ?: 0.0
            )
        } else if (parts.size >= 19) {
            default.copy(
                learnedKneeVolts = parts[1].toDoubleOrNull(),
                confidence = parts[2].toDoubleOrNull() ?: 0.0,
                sampleCount = parts[3].toIntOrNull() ?: 0,
                stableSeconds = parts[4].toDoubleOrNull() ?: 0.0,
                currentStableRunSeconds = parts[5].toDoubleOrNull() ?: 0.0,
                longestStableRunSeconds = parts[6].toDoubleOrNull() ?: 0.0,
                candidateKneeVolts = parts[7].toDoubleOrNull(),
                candidateStableSeconds = parts[8].toDoubleOrNull() ?: 0.0,
                bestWatts = parts[9].toDoubleOrNull() ?: 0.0,
                lastIset = parts[10].toDoubleOrNull() ?: 0.0,
                lastIout = parts[11].toDoubleOrNull() ?: 0.0,
                lastWatts = parts[12].toDoubleOrNull() ?: 0.0,
                lastVinError = parts[13].toDoubleOrNull() ?: 0.0,
                lastTemperatureF = parts[14].toDoubleOrNull(),
                minTemperatureF = parts[15].toDoubleOrNull(),
                maxTemperatureF = parts[16].toDoubleOrNull(),
                lastUpdatedMs = parts[17].toLongOrNull() ?: 0L,
                manual = parts[18].toBooleanStrictOrNull() ?: false
            )
        } else if (parts.size >= 17) {
            default.copy(
                learnedKneeVolts = parts[1].toDoubleOrNull(),
                confidence = parts[2].toDoubleOrNull() ?: 0.0,
                sampleCount = parts[3].toIntOrNull() ?: 0,
                stableSeconds = parts[4].toDoubleOrNull() ?: 0.0,
                currentStableRunSeconds = parts[5].toDoubleOrNull() ?: 0.0,
                longestStableRunSeconds = parts[6].toDoubleOrNull() ?: 0.0,
                bestWatts = parts[7].toDoubleOrNull() ?: 0.0,
                lastIset = parts[8].toDoubleOrNull() ?: 0.0,
                lastIout = parts[9].toDoubleOrNull() ?: 0.0,
                lastWatts = parts[10].toDoubleOrNull() ?: 0.0,
                lastVinError = parts[11].toDoubleOrNull() ?: 0.0,
                lastTemperatureF = parts[12].toDoubleOrNull(),
                minTemperatureF = parts[13].toDoubleOrNull(),
                maxTemperatureF = parts[14].toDoubleOrNull(),
                lastUpdatedMs = parts[15].toLongOrNull() ?: 0L,
                manual = parts[16].toBooleanStrictOrNull() ?: false
            )
        } else {
            default.copy(
                learnedKneeVolts = parts[1].toDoubleOrNull(),
                confidence = parts[2].toDoubleOrNull() ?: 0.0,
                sampleCount = parts[3].toIntOrNull() ?: 0,
                bestWatts = parts[4].toDoubleOrNull() ?: 0.0,
                lastIset = parts[5].toDoubleOrNull() ?: 0.0,
                lastIout = parts[6].toDoubleOrNull() ?: 0.0,
                lastWatts = parts[7].toDoubleOrNull() ?: 0.0,
                lastVinError = parts[8].toDoubleOrNull() ?: 0.0,
                lastUpdatedMs = parts[9].toLongOrNull() ?: 0L,
                manual = parts[10].toBooleanStrictOrNull() ?: false
            )
        }
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        window.decorView.keepScreenOn = enabled
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun highKneeStableRequirementSeconds(): Double {
        return maxOf(
            appSettings.kneeTrackingDelaySeconds + KNEE_LEARNING_HIGH_EXTRA_STABLE_SECONDS,
            appSettings.kneeTrackingDelaySeconds * KNEE_LEARNING_HIGH_STABLE_MULTIPLIER
        )
    }

    private fun applyControllerKeepAlive(enabled: Boolean) {
        if (enabled) {
            ControllerKeepAliveService.start(this)
        } else {
            ControllerKeepAliveService.stop(this)
        }
    }

    private fun loadSettings(): AppSettings {
        val defaults = AppState.preview.settings
        val prefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
        val targetPvVolts = prefs.getFloat(KEY_TARGET_PV_V, defaults.targetPvVolts.toFloat()).toDouble()
        val legacyKneeVariance = prefs.getFloat(KEY_KNEE_VARIANCE_V, 3.0f).toDouble()
        val loadedMinTargetPv = prefs.getFloat(
            KEY_MIN_TARGET_PV_V,
            (targetPvVolts - legacyKneeVariance).toFloat()
        ).toDouble()
        val loadedMaxTargetPv = prefs.getFloat(
            KEY_MAX_TARGET_PV_V,
            (targetPvVolts + legacyKneeVariance).toFloat()
        ).toDouble()
        val minTargetPv = minOf(loadedMinTargetPv, loadedMaxTargetPv, targetPvVolts)
        val maxTargetPv = maxOf(loadedMinTargetPv, loadedMaxTargetPv, targetPvVolts)
        return AppSettings(
            maxBatteryVolts = prefs.getFloat(
                KEY_MAX_BATTERY_V,
                prefs.getFloat(KEY_BALANCE_V, defaults.maxBatteryVolts.toFloat())
            ).toDouble(),
            balanceEveryDays = prefs.getInt(KEY_BALANCE_EVERY_DAYS, defaults.balanceEveryDays),
            lastBalanceEpochDay = prefs.getLong(KEY_LAST_BALANCE_EPOCH_DAY, defaults.lastBalanceEpochDay),
            maxChargeAmps = prefs.getFloat(KEY_MAX_CHARGE_A, defaults.maxChargeAmps.toFloat()).toDouble(),
            targetPvVolts = targetPvVolts,
            controllerEnabled = prefs.getBoolean(KEY_CONTROLLER_ENABLED, defaults.controllerEnabled),
            normalSocCeilingPercent = prefs.getInt(
                KEY_NORMAL_SOC_CEILING_PERCENT,
                prefs.getInt(KEY_MAX_CHARGE_PERCENT, defaults.normalSocCeilingPercent)
            ),
            socHoldCurrentAmps = prefs.getFloat(
                KEY_SOC_HOLD_CURRENT_A,
                prefs.getFloat(KEY_MAX_SOC_TRICKLE_A, defaults.socHoldCurrentAmps.toFloat())
            ).toDouble(),
            bmsCurrentDeadbandAmps = prefs.getFloat(
                KEY_BMS_CURRENT_DEADBAND_A,
                defaults.bmsCurrentDeadbandAmps.toFloat()
            ).toDouble(),
            lowSocAlarmPercent = prefs.getInt(KEY_LOW_SOC_ALARM_PERCENT, defaults.lowSocAlarmPercent),
            minTargetPvVolts = minTargetPv,
            maxTargetPvVolts = maxTargetPv,
            kneeStepVolts = prefs.getFloat(
                KEY_KNEE_STEP_V,
                defaults.kneeStepVolts.toFloat()
            ).toDouble(),
            kneeTrackingDelaySeconds = prefs.getFloat(
                KEY_KNEE_TRACKING_DELAY_S,
                prefs.getFloat(KEY_HCC_QUIET_S, defaults.kneeTrackingDelaySeconds.toFloat())
            ).toDouble(),
            controllerLoopMs = prefs.getInt(KEY_CONTROLLER_LOOP_MS, defaults.controllerLoopMs),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, defaults.keepScreenOn)
        )
    }

    private fun saveSettings(settings: AppSettings) {
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit {
            putFloat(KEY_MAX_BATTERY_V, settings.maxBatteryVolts.toFloat())
            putInt(KEY_BALANCE_EVERY_DAYS, settings.balanceEveryDays)
            putLong(KEY_LAST_BALANCE_EPOCH_DAY, settings.lastBalanceEpochDay)
            putFloat(KEY_MAX_CHARGE_A, settings.maxChargeAmps.toFloat())
            putFloat(KEY_TARGET_PV_V, settings.targetPvVolts.toFloat())
            putBoolean(KEY_CONTROLLER_ENABLED, settings.controllerEnabled)
            putInt(KEY_NORMAL_SOC_CEILING_PERCENT, settings.normalSocCeilingPercent)
            putFloat(KEY_SOC_HOLD_CURRENT_A, settings.socHoldCurrentAmps.toFloat())
            putFloat(KEY_BMS_CURRENT_DEADBAND_A, settings.bmsCurrentDeadbandAmps.toFloat())
            putInt(KEY_LOW_SOC_ALARM_PERCENT, settings.lowSocAlarmPercent)
            putFloat(KEY_MIN_TARGET_PV_V, settings.minTargetPvVolts.toFloat())
            putFloat(KEY_MAX_TARGET_PV_V, settings.maxTargetPvVolts.toFloat())
            putFloat(KEY_KNEE_STEP_V, settings.kneeStepVolts.toFloat())
            putFloat(KEY_KNEE_TRACKING_DELAY_S, settings.kneeTrackingDelaySeconds.toFloat())
            putInt(KEY_CONTROLLER_LOOP_MS, settings.controllerLoopMs)
            putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
        }
    }

    private fun markBalanceDayStarted(epochDay: Long) {
        if (appSettings.lastBalanceEpochDay == epochDay) return
        appSettings = appSettings.copy(lastBalanceEpochDay = epochDay)
        saveSettings(appSettings)
    }

    private fun toggleBalanceToday() {
        val today = currentEpochDay()
        val wasBalanceDay = isBalanceDayToday(appSettings)
        val epochDay = if (wasBalanceDay) today - 1L else today
        markBalanceDayStarted(epochDay)
        addControllerEvent(if (wasBalanceDay) "Balance day canceled" else "Balance day forced")
        tickController()
    }

    private fun isBalanceDayToday(settings: AppSettings): Boolean {
        if (settings.balanceEveryDays <= 0) return false
        val today = currentEpochDay()
        return settings.lastBalanceEpochDay == today ||
            settings.lastBalanceEpochDay <= 0L ||
            today - settings.lastBalanceEpochDay >= settings.balanceEveryDays
    }

    private fun daysUntilNextBalance(settings: AppSettings): Int {
        if (isBalanceDayToday(settings)) return 0
        val today = currentEpochDay()
        val elapsedDays = (today - settings.lastBalanceEpochDay).coerceAtLeast(0L)
        return (settings.balanceEveryDays - elapsedDays).coerceAtLeast(0L).toInt()
    }

    private fun loadEnergy() {
        val prefs = getSharedPreferences(ENERGY_PREFS, MODE_PRIVATE)
        energyDayKey = prefs.getInt(KEY_ENERGY_DAY_KEY, 0)
        lastPersistEnergyMs = prefs.getLong(KEY_ENERGY_LAST_SAVE_MS, 0L)
        energyCounters = EnergyCounters(
            currentWatts = 0.0,
            whToday = prefs.getFloat(KEY_ENERGY_TODAY_WH, 0.0f).toDouble(),
            whYesterday = prefs.getFloat(KEY_ENERGY_YESTERDAY_WH, 0.0f).toDouble(),
            whTotal = prefs.getFloat(KEY_ENERGY_TOTAL_WH, 0.0f).toDouble(),
            bmsWhToday = prefs.getFloat(KEY_BMS_ENERGY_TODAY_WH, 0.0f).toDouble(),
            bmsWhYesterday = prefs.getFloat(KEY_BMS_ENERGY_YESTERDAY_WH, 0.0f).toDouble()
        )

        val today = nowDayKey()
        if (energyDayKey == 0) energyDayKey = today
        if (rolloverEnergyDayIfNeeded(today)) {
            persistEnergy()
        }
    }

    private fun updateEnergyFromPower(watts: Double?) {
        val nowMs = SystemClock.elapsedRealtime()
        val today = nowDayKey()
        if (rolloverEnergyDayIfNeeded(today)) {
            lastEnergyMs = nowMs
            lastEnergyWatts = watts
            persistEnergy()
            return
        }

        val previousMs = lastEnergyMs
        val previousWatts = lastEnergyWatts
        var nextToday = energyCounters.whToday
        var nextTotal = energyCounters.whTotal

        if (previousMs != 0L && previousWatts != null && watts != null) {
            var dtMs = nowMs - previousMs
            if (dtMs > 0L) {
                if (dtMs > ENERGY_MAX_DT_MS) dtMs = ENERGY_MAX_DT_MS
                val addWh = ((previousWatts + watts) * 0.5) * (dtMs.toDouble() / 3_600_000.0)
                if (addWh > 0.0) {
                    nextToday += addWh
                    nextTotal += addWh
                }
            }
        }

        energyCounters = energyCounters.copy(
            currentWatts = watts ?: 0.0,
            whToday = nextToday,
            whTotal = nextTotal
        )
        lastEnergyMs = nowMs
        lastEnergyWatts = watts

        maybePersistEnergy(nowMs)
    }

    private fun updateBmsEnergyFromPower(volts: Double?, amps: Double?) {
        val watts = if (volts != null && amps != null) volts * amps else null
        val nowMs = SystemClock.elapsedRealtime()
        val today = nowDayKey()
        if (rolloverEnergyDayIfNeeded(today)) {
            lastBmsEnergyMs = nowMs
            lastBmsEnergyWatts = watts
            persistEnergy()
            return
        }

        val previousMs = lastBmsEnergyMs
        val previousWatts = lastBmsEnergyWatts
        var nextToday = energyCounters.bmsWhToday

        if (previousMs != 0L && previousWatts != null && watts != null) {
            var dtMs = nowMs - previousMs
            if (dtMs > 0L) {
                if (dtMs > ENERGY_MAX_DT_MS) dtMs = ENERGY_MAX_DT_MS
                val addWh = ((previousWatts + watts) * 0.5) * (dtMs.toDouble() / 3_600_000.0)
                if (addWh > 0.0) {
                    nextToday += addWh
                }
            }
        }

        energyCounters = energyCounters.copy(bmsWhToday = nextToday)
        lastBmsEnergyMs = nowMs
        lastBmsEnergyWatts = watts

        maybePersistEnergy(nowMs)
    }

    private fun resetEnergyTotal() {
        energyCounters = energyCounters.copy(whTotal = 0.0)
        persistEnergy()
    }

    private fun rolloverEnergyDayIfNeeded(today: Int): Boolean {
        if (energyDayKey == today) return false
        energyDayKey = today
        energyCounters = energyCounters.copy(
            whYesterday = energyCounters.whToday,
            whToday = 0.0,
            bmsWhYesterday = energyCounters.bmsWhToday,
            bmsWhToday = 0.0,
            currentWatts = 0.0
        )
        return true
    }

    private fun persistEnergy() {
        lastPersistEnergyMs = SystemClock.elapsedRealtime()
        getSharedPreferences(ENERGY_PREFS, MODE_PRIVATE).edit(commit = true) {
            putInt(KEY_ENERGY_DAY_KEY, energyDayKey)
            putFloat(KEY_ENERGY_TODAY_WH, energyCounters.whToday.toFloat())
            putFloat(KEY_ENERGY_YESTERDAY_WH, energyCounters.whYesterday.toFloat())
            putFloat(KEY_ENERGY_TOTAL_WH, energyCounters.whTotal.toFloat())
            putFloat(KEY_BMS_ENERGY_TODAY_WH, energyCounters.bmsWhToday.toFloat())
            putFloat(KEY_BMS_ENERGY_YESTERDAY_WH, energyCounters.bmsWhYesterday.toFloat())
            putLong(KEY_ENERGY_LAST_SAVE_MS, lastPersistEnergyMs)
        }
    }

    private fun maybePersistEnergy(nowMs: Long) {
        if (lastPersistEnergyMs == 0L || nowMs - lastPersistEnergyMs >= ENERGY_PERSIST_MS) {
            persistEnergy()
        }
    }

    private fun nowDayKey(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
    }

    private fun currentEpochDay(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis / 86_400_000L
    }

    private fun maybeRecordHistory() {
        val nowMs = System.currentTimeMillis()
        if (mpptControlState.recoveryActive) {
            lastHistorySampleMs = nowMs
            historyAccumulator = HistoryAccumulator()
            return
        }
        if (nowMs - lastHistorySampleMs < HISTORY_SAMPLE_MS) return
        if (!historyAccumulator.hasCompleteSample(bmsBleState, ridenUsbState)) return

        val point = historyAccumulator.toPoint(nowMs, nowDayKey(), bmsBleState, ridenUsbState)
        lastHistorySampleMs = nowMs
        historyAccumulator = HistoryAccumulator()

        val trimmed = (historyPoints + point).filter { it.dayKey >= point.dayKey - HISTORY_KEEP_DAYS }
        historyPoints = trimmed
        appendHistoryPoint(point)
        pruneHistoryFile(trimmed)
    }

    private fun loadHistory(): List<HistoryPoint> {
        val file = historyFile()
        if (!file.exists()) return emptyList()
        val minDay = nowDayKey() - HISTORY_KEEP_DAYS
        return file.readLines()
            .mapNotNull { it.toHistoryPointOrNull() }
            .filter { it.dayKey >= minDay }
            .filter { it.isPlausibleHistoryPoint() }
    }

    private fun appendHistoryPoint(point: HistoryPoint) {
        historyFile().appendText(point.toCsvLine() + "\n")
    }

    private fun pruneHistoryFile(points: List<HistoryPoint>) {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastHistoryPruneMs < HISTORY_PRUNE_MS) return
        lastHistoryPruneMs = nowMs
        historyFile().writeText(points.joinToString(separator = "\n", postfix = "\n") { it.toCsvLine() })
    }

    private fun historyFile(): File = File(filesDir, HISTORY_FILE)

    private fun HistoryPoint.toCsvLine(): String {
        return listOf(
            timestampMs,
            dayKey,
            batteryVolts,
            batteryAmps,
            batteryWatts,
            socPercent,
            temperatureF,
            ridenVin,
            ridenVout,
            ridenIout,
            ridenWatts
        ).joinToString(",")
    }

    private fun String.toHistoryPointOrNull(): HistoryPoint? {
        val parts = split(",")
        if (parts.size < 11) return null
        return try {
            HistoryPoint(
                timestampMs = parts[0].toLong(),
                dayKey = parts[1].toInt(),
                batteryVolts = parts[2].toDouble(),
                batteryAmps = parts[3].toDouble(),
                batteryWatts = parts[4].toDouble(),
                socPercent = parts[5].toInt(),
                temperatureF = parts[6].toDouble(),
                ridenVin = parts[7].toDouble(),
                ridenVout = parts[8].toDouble(),
                ridenIout = parts[9].toDouble(),
                ridenWatts = parts[10].toDouble()
            )
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun HistoryPoint.isPlausibleHistoryPoint(): Boolean {
        return batteryVolts > 1.0 &&
            temperatureF > -40.0 &&
            temperatureF < 180.0 &&
            socPercent in 0..100
    }

    override fun onPause() {
        persistEnergy()
        super.onPause()
    }

    override fun onDestroy() {
        stopUsbAlarm()
        stopLowSocAlarm()
        bmsBleScanner.stopScan()
        ridenUsbMonitor.stop()
        persistEnergy()
        applyControllerKeepAlive(false)
        super.onDestroy()
    }

    companion object {
        private const val SETTINGS_PREFS = "controller_settings"
        private const val ENERGY_PREFS = "energy_counters"
        private const val KEY_BALANCE_V = "balance_v"
        private const val KEY_MAX_BATTERY_V = "max_battery_v"
        private const val KEY_BALANCE_EVERY_DAYS = "balance_every_days"
        private const val KEY_LAST_BALANCE_EPOCH_DAY = "last_balance_epoch_day"
        private const val KEY_MAX_CHARGE_A = "max_charge_a"
        private const val KEY_TARGET_PV_V = "target_pv_v"
        private const val KEY_CONTROLLER_ENABLED = "controller_enabled"
        private const val KEY_MAX_CHARGE_PERCENT = "max_charge_percent"
        private const val KEY_MAX_SOC_TRICKLE_A = "max_soc_trickle_a"
        private const val KEY_NORMAL_SOC_CEILING_PERCENT = "normal_soc_ceiling_percent"
        private const val KEY_SOC_HOLD_CURRENT_A = "soc_hold_current_a"
        private const val KEY_BMS_CURRENT_DEADBAND_A = "bms_current_deadband_a"
        private const val KEY_LOW_SOC_ALARM_PERCENT = "low_soc_alarm_percent"
        private const val KEY_KNEE_VARIANCE_V = "knee_variance_v"
        private const val KEY_MIN_TARGET_PV_V = "min_target_pv_v"
        private const val KEY_MAX_TARGET_PV_V = "max_target_pv_v"
        private const val KEY_KNEE_STEP_V = "knee_step_v"
        private const val KEY_KNEE_LEARNING_BINS = "knee_learning_bins"
        private const val KEY_HCC_QUIET_S = "hcc_quiet_s"
        private const val KEY_KNEE_TRACKING_DELAY_S = "knee_tracking_delay_s"
        private const val KEY_CONTROLLER_LOOP_MS = "controller_loop_ms"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_ENERGY_DAY_KEY = "energy_day_key"
        private const val KEY_ENERGY_TODAY_WH = "energy_today_wh"
        private const val KEY_ENERGY_YESTERDAY_WH = "energy_yesterday_wh"
        private const val KEY_ENERGY_TOTAL_WH = "energy_total_wh"
        private const val KEY_BMS_ENERGY_TODAY_WH = "bms_energy_today_wh"
        private const val KEY_BMS_ENERGY_YESTERDAY_WH = "bms_energy_yesterday_wh"
        private const val KEY_ENERGY_LAST_SAVE_MS = "energy_last_save_ms"
        private const val ENERGY_MAX_DT_MS = 60_000L
        private const val ENERGY_PERSIST_MS = 5_000L
        private const val HISTORY_FILE = "controller_history.csv"
        private const val DEBUG_SNAPSHOT_FILE = "debug_snapshot.json"
        private const val HISTORY_SAMPLE_MS = 30_000L
        private const val HISTORY_KEEP_DAYS = 30
        private const val HISTORY_PRUNE_MS = 300_000L
        private const val KNEE_LEARNING_SAMPLE_MS = 5_000L
        private const val KNEE_LEARNING_MAX_ERR_V = 0.50
        private const val KNEE_LEARNING_MIN_WATTS = 10.0
        private const val KNEE_LEARNING_MIN_STABLE_SECONDS = 30.0
        private const val KNEE_LEARNING_HIGH_EXTRA_STABLE_SECONDS = 30.0
        private const val KNEE_LEARNING_HIGH_STABLE_MULTIPLIER = 1.25
        private const val KNEE_LEARNING_KNEE_EPS_V = 0.05
        private const val MAX_CONTROLLER_EVENTS = 80
        private val eventTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}

private const val KNEE_LEARNING_CONFIDENCE_FULL_STABLE_S = 900.0
private const val KNEE_LEARNING_CONFIDENCE_FULL_RUN_S = 180.0
private const val KNEE_LEARNING_CONFIDENCE_FULL_SAMPLES = 300.0

private fun minNullable(a: Double?, b: Double?): Double? = when {
    a == null -> b
    b == null -> a
    else -> minOf(a, b)
}

private fun maxNullable(a: Double?, b: Double?): Double? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
}

private fun defaultKneeLearningBins(): List<KneeLearningBin> {
    val edges = listOf(0.0, 1.0, 2.0, 4.0, 6.0, 8.0, 10.0, 14.0, 18.0, 22.0, 26.0)
    return edges.zipWithNext().mapIndexed { index, (min, max) ->
        KneeLearningBin(
            index = index,
            minIset = min,
            maxIset = max,
            learnedKneeVolts = null,
            confidence = 0.0,
            sampleCount = 0,
            bestWatts = 0.0,
            lastIset = 0.0,
            lastIout = 0.0,
            lastWatts = 0.0,
            lastVinError = 0.0,
            lastUpdatedMs = 0L,
            manual = false
        )
    }
}

private fun JSONObject.toKneeLearningBinOrNull(): KneeLearningBin? {
    val index = optIntOrNull("index") ?: return null
    val default = defaultKneeLearningBins().firstOrNull { it.index == index } ?: return null
    return default.copy(
        learnedKneeVolts = optNullableDouble("learnedKneeVolts"),
        confidence = optDouble("confidence", 0.0),
        sampleCount = optInt("sampleCount", 0),
        stableSeconds = optDouble("stableSeconds", 0.0),
        currentStableRunSeconds = optDouble("currentStableRunSeconds", 0.0),
        longestStableRunSeconds = optDouble("longestStableRunSeconds", 0.0),
        candidateKneeVolts = optNullableDouble("candidateKneeVolts"),
        candidateStableSeconds = optDouble("candidateStableSeconds", 0.0),
        highestStableKneeVolts = optNullableDouble("highestStableKneeVolts"),
        highStableSeconds = optDouble("highStableSeconds", 0.0),
        highCurrentStableRunSeconds = optDouble("highCurrentStableRunSeconds", 0.0),
        highLongestStableRunSeconds = optDouble("highLongestStableRunSeconds", 0.0),
        candidateHighKneeVolts = optNullableDouble("candidateHighKneeVolts"),
        candidateHighStableSeconds = optDouble("candidateHighStableSeconds", 0.0),
        bestWatts = optDouble("bestWatts", 0.0),
        lastIset = optDouble("lastIset", 0.0),
        lastIout = optDouble("lastIout", 0.0),
        lastWatts = optDouble("lastWatts", 0.0),
        lastVinError = optDouble("lastVinError", 0.0),
        lastTemperatureF = optNullableDouble("lastTemperatureF"),
        minTemperatureF = optNullableDouble("minTemperatureF"),
        maxTemperatureF = optNullableDouble("maxTemperatureF"),
        lastUpdatedMs = optLong("lastUpdatedMs", 0L),
        manual = optBoolean("manual", false)
    )
}

private fun JSONObject.optIntOrNull(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}

private fun JSONObject.optNullableDouble(name: String): Double? {
    return if (has(name) && !isNull(name)) optDouble(name) else null
}

private fun JSONArray.optIntOrNull(index: Int): Int? {
    return if (index in 0 until length() && !isNull(index)) optInt(index) else null
}

private fun KneeLearningBin.withDerivedConfidence(): KneeLearningBin {
    val derived = kneeLearningConfidence(stableSeconds, longestStableRunSeconds, sampleCount)
    return copy(confidence = if (manual && learnedKneeVolts != null) derived.coerceAtLeast(0.25) else derived)
}

private fun kneeLearningConfidence(
    stableSeconds: Double,
    longestStableRunSeconds: Double,
    sampleCount: Int
): Double {
    val stableScore = (stableSeconds / KNEE_LEARNING_CONFIDENCE_FULL_STABLE_S).coerceIn(0.0, 1.0)
    val runScore = (longestStableRunSeconds / KNEE_LEARNING_CONFIDENCE_FULL_RUN_S).coerceIn(0.0, 1.0)
    val sampleScore = (sampleCount.toDouble() / KNEE_LEARNING_CONFIDENCE_FULL_SAMPLES).coerceIn(0.0, 1.0)
    return (stableScore * 0.55 + runScore * 0.35 + sampleScore * 0.10).coerceIn(0.0, 1.0)
}

private fun batteryTimeEstimate(
    averageAmps: Double?,
    remainingAh: Double,
    nominalAh: Double,
    socTargetPercent: Int
): String {
    if (averageAmps == null || nominalAh <= 0.0 || remainingAh < 0.0) return ""
    return when {
        averageAmps > BATTERY_TIME_MIN_AVERAGE_A -> {
            val targetAh = nominalAh * socTargetPercent.coerceIn(0, 100) / 100.0
            val ahToTarget = targetAh - remainingAh
            if (ahToTarget <= 0.0) "" else "${formatDurationHms(ahToTarget / averageAmps)} to full"
        }
        averageAmps < -BATTERY_TIME_MIN_AVERAGE_A -> {
            val hoursToEmpty = remainingAh / -averageAmps
            "${formatDurationHms(hoursToEmpty)} to empty"
        }
        else -> ""
    }
}

private fun formatDurationHms(hours: Double): String {
    if (!hours.isFinite() || hours < 0.0) return "--:--:--"
    val totalSeconds = (hours * 3600.0).toLong().coerceAtMost(999L * 3600L + 59L * 60L + 59L)
    val hh = totalSeconds / 3600L
    val mm = (totalSeconds % 3600L) / 60L
    val ss = totalSeconds % 60L
    return "%02d:%02d:%02d".format(hh, mm, ss)
}

private const val BATTERY_TIME_MIN_AVERAGE_A = 0.05
private const val BATTERY_TIME_UPDATE_MS = 2_000L

private class RollingAverageWindow(private val windowMs: Long) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    fun add(nowMs: Long, value: Double?) {
        if (value == null) {
            prune(nowMs)
            return
        }
        samples.addLast(nowMs to value)
        prune(nowMs)
    }

    fun average(nowMs: Long): Double? {
        prune(nowMs)
        if (samples.isEmpty()) return null
        return samples.sumOf { it.second } / samples.size
    }

    private fun prune(nowMs: Long) {
        while (samples.isNotEmpty() && nowMs - samples.first().first > windowMs) {
            samples.removeFirst()
        }
    }
}

private class HistoryAccumulator {
    private var batteryVolts = Average()
    private var batteryAmps = Average()
    private var batteryWatts = Average()
    private var temperatureF = Average()
    private var ridenVin = Average()
    private var ridenVout = Average()
    private var ridenIout = Average()
    private var ridenWatts = Average()

    fun addBms(state: BmsBleUiState) {
        val telemetry = state.telemetry
        val volts = telemetry.packVoltage
        val amps = telemetry.packCurrent
        batteryVolts.add(volts)
        batteryAmps.add(amps)
        if (volts != null && amps != null) batteryWatts.add(volts * amps)
        temperatureF.add(telemetry.temperatureF)
    }

    fun addRiden(state: RidenUsbState) {
        val telemetry = state.telemetry
        ridenVin.add(telemetry.vin)
        ridenVout.add(telemetry.vout)
        ridenIout.add(telemetry.iout)
        ridenWatts.add(telemetry.watts)
    }

    fun hasCompleteSample(
        bmsState: BmsBleUiState,
        ridenState: RidenUsbState
    ): Boolean {
        val bms = bmsState.telemetry
        return bmsState.connectedDeviceAddress != null &&
            ridenState.connected &&
            bms.socPercent != null &&
            batteryVolts.hasValue &&
            batteryAmps.hasValue &&
            temperatureF.hasValue &&
            ridenVin.hasValue &&
            ridenVout.hasValue &&
            ridenIout.hasValue &&
            ridenWatts.hasValue
    }

    fun toPoint(
        timestampMs: Long,
        dayKey: Int,
        bmsState: BmsBleUiState,
        ridenState: RidenUsbState
    ): HistoryPoint {
        val bms = bmsState.telemetry
        val riden = ridenState.telemetry
        return HistoryPoint(
            timestampMs = timestampMs,
            dayKey = dayKey,
            batteryVolts = batteryVolts.valueOr(bms.packVoltage ?: 0.0),
            batteryAmps = batteryAmps.valueOr(bms.packCurrent ?: 0.0),
            batteryWatts = batteryWatts.valueOr((bms.packVoltage ?: 0.0) * (bms.packCurrent ?: 0.0)),
            socPercent = bms.socPercent ?: 0,
            temperatureF = temperatureF.valueOr(bms.temperatureF ?: 0.0),
            ridenVin = ridenVin.valueOr(riden.vin ?: 0.0),
            ridenVout = ridenVout.valueOr(riden.vout ?: 0.0),
            ridenIout = ridenIout.valueOr(riden.iout ?: 0.0),
            ridenWatts = ridenWatts.valueOr(riden.watts ?: 0.0)
        )
    }
}

private class Average {
    private var sum = 0.0
    private var count = 0

    val hasValue: Boolean
        get() = count > 0

    fun add(value: Double?) {
        if (value == null) return
        sum += value
        count += 1
    }

    fun valueOr(fallback: Double): Double {
        return if (count > 0) sum / count else fallback
    }
}

private enum class AppTab(
    val label: String,
    val icon: ImageVector
) {
    Dashboard("Dash", Icons.Filled.Dashboard),
    History("History", Icons.Filled.BarChart),
    Devices("Devices", Icons.Filled.Bluetooth),
    Settings("Settings", Icons.Filled.Settings),
    Tools("Tools", Icons.Filled.Build)
}

@Composable
private fun RidenBmsApp(
    bmsBleState: BmsBleUiState,
    ridenUsbState: RidenUsbState,
    appSettings: AppSettings,
    mpptControlState: MpptControlState,
    energyCounters: EnergyCounters,
    historyPoints: List<HistoryPoint>,
    controllerEvents: List<String>,
    alertState: AlertState,
    kneeLearningBins: List<KneeLearningBin>,
    batteryTimeEstimateText: String,
    onSettingsChanged: (AppSettings) -> Unit,
    onRequestBlePermissions: () -> Unit,
    onStartBmsScan: () -> Unit,
    onStopBmsScan: () -> Unit,
    onConnectBms: (BmsBleDevice) -> Unit,
    onDisconnectBms: () -> Unit,
    balanceDayToday: Boolean,
    daysUntilNextBalance: Int,
    onToggleBalanceToday: () -> Unit,
    onResetEnergyTotal: () -> Unit,
    onResetLearnedKnee: () -> Unit,
    onSetActiveKnee: (Double) -> Unit,
    onSaveDebugSnapshot: (String) -> String,
    onImportKneeData: (String) -> String,
    onSilenceLowSocAlarm: () -> Unit
) {
    val state = remember(
        bmsBleState,
        ridenUsbState,
        appSettings,
        mpptControlState,
        energyCounters,
        historyPoints,
        controllerEvents,
        alertState,
        kneeLearningBins,
        batteryTimeEstimateText
    ) {
        AppState.preview.copy(
            settings = appSettings,
            energy = energyCounters,
            history = historyPoints,
            events = controllerEvents,
            alerts = alertState,
            kneeLearningBins = kneeLearningBins,
            batteryTimeEstimateText = batteryTimeEstimateText
        )
            .withBmsTelemetry(bmsBleState)
            .withRidenTelemetry(ridenUsbState)
            .withMpptControl(mpptControlState)
    }
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Dashboard) }
    val tabStateHolder = rememberSaveableStateHolder()

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        tabStateHolder.SaveableStateProvider(selectedTab.name) {
            when (selectedTab) {
                AppTab.Dashboard -> DashboardScreen(
                    state = state,
                    onSilenceLowSocAlarm = onSilenceLowSocAlarm,
                    onSetActiveKnee = onSetActiveKnee,
                    modifier = Modifier.padding(innerPadding)
                )
                AppTab.History -> HistoryScreen(state, Modifier.padding(innerPadding))
                AppTab.Devices -> DevicesScreen(
                    bleState = bmsBleState,
                    ridenState = ridenUsbState,
                    energy = state.energy,
                    onRequestPermissions = onRequestBlePermissions,
                    onStartScan = onStartBmsScan,
                    onStopScan = onStopBmsScan,
                    onConnect = onConnectBms,
                    onDisconnect = onDisconnectBms,
                    modifier = Modifier.padding(innerPadding)
                )
                AppTab.Settings -> SettingsScreen(
                    settings = state.settings,
                    energy = state.energy,
                    onSettingsChanged = onSettingsChanged,
                    balanceDayToday = balanceDayToday,
                    daysUntilNextBalance = daysUntilNextBalance,
                    onToggleBalanceToday = onToggleBalanceToday,
                    onResetEnergyTotal = onResetEnergyTotal,
                    onResetLearnedKnee = onResetLearnedKnee,
                    modifier = Modifier.padding(innerPadding)
                )
                AppTab.Tools -> ToolsScreen(
                    state = state,
                    modifier = Modifier.padding(innerPadding),
                    onSaveDebugSnapshot = onSaveDebugSnapshot,
                    onImportKneeData = onImportKneeData
                )
            }
        }
    }
}

private fun AppState.withMpptControl(control: MpptControlState): AppState {
    return copy(
        controller = controller.copy(
            enabled = control.enabled,
            pvMode = control.pvMode,
            status = control.status,
            targetChargeCurrent = control.targetChargeCurrent,
            commandIset = control.commandIset,
            targetPvVolts = control.targetPvVolts,
            kneeOffsetVolts = control.kneeOffsetVolts,
            vinErrorVolts = control.vinErrorVolts,
            policyLimitAmps = control.policyLimitAmps,
            recoveryPhase = control.recoveryPhase,
            recoveryCycleCount = control.recoveryCycleCount,
            controlBand = control.controlBand,
            controlStepAmps = control.controlStepAmps,
            recoveryActive = control.recoveryActive,
            socTargetPercent = control.socTargetPercent.takeIf { it > 0 } ?: settings.normalSocCeilingPercent
        ),
        riden = riden.copy(targetVin = control.targetPvVolts.takeIf { it > 0.0 } ?: riden.targetVin)
    )
}

private fun AppState.withRidenTelemetry(usbState: RidenUsbState): AppState {
    val telemetry = usbState.telemetry
    val liveRiden = if (telemetry.vin != null || telemetry.vout != null) {
        RidenState(
            vin = telemetry.vin ?: riden.vin,
            vout = telemetry.vout ?: riden.vout,
            iout = telemetry.iout ?: riden.iout,
            watts = telemetry.watts ?: riden.watts,
            vset = telemetry.vset ?: riden.vset,
            iset = telemetry.iset ?: riden.iset,
            targetVin = riden.targetVin
        )
    } else {
        riden
    }

    return copy(
        riden = liveRiden,
        controller = controller.copy(
            ridenConnected = usbState.connected,
            status = listOf(
                if (controller.bmsConnected) "BMS live" else "BMS disconnected",
                usbState.status
            ).joinToString(" | ")
        ),
        logs = logs + buildList {
            add("Riden status: ${usbState.status}")
            if (usbState.connected) {
                telemetry.vin?.let { add("Riden VIN: %.2f V".format(it)) }
                telemetry.vout?.let { add("Riden VOUT: %.2f V".format(it)) }
                telemetry.iout?.let { add("Riden IOUT: %.2f A".format(it)) }
                telemetry.vset?.let { add("Riden VSET: %.2f V".format(it)) }
                telemetry.iset?.let { add("Riden ISET: %.2f A".format(it)) }
            }
        }
    )
}

private fun AppState.withBmsTelemetry(bleState: BmsBleUiState): AppState {
    val telemetry = bleState.telemetry
    val liveBattery = if (telemetry.packVoltage != null || telemetry.socPercent != null) {
        BatteryState(
            socPercent = telemetry.socPercent ?: battery.socPercent,
            volts = telemetry.packVoltage ?: battery.volts,
            amps = telemetry.packCurrent ?: battery.amps,
            watts = (telemetry.packVoltage ?: battery.volts) * (telemetry.packCurrent ?: battery.amps),
            remainingAh = telemetry.remainingAh ?: battery.remainingAh,
            nominalAh = telemetry.nominalAh ?: battery.nominalAh,
            temperatureF = telemetry.temperatureF ?: battery.temperatureF,
            direction = when {
                (telemetry.packCurrent ?: 0.0) > 0.05 -> PowerDirection.Charging
                (telemetry.packCurrent ?: 0.0) < -0.05 -> PowerDirection.Discharging
                else -> PowerDirection.Idle
            },
            chargeMode = when {
                bleState.connectedDeviceAddress == null -> ChargeMode.Idle
                telemetry.balancingActive == true -> ChargeMode.Balance
                (telemetry.packCurrent ?: 0.0) > 0.05 -> ChargeMode.Bulk
                else -> ChargeMode.Idle
            },
            balancing = telemetry.balancingActive == true,
            cellDeltaMv = telemetry.cellDeltaMv ?: battery.cellDeltaMv
        )
    } else {
        BatteryState(
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
        )
    }

    return copy(
        battery = liveBattery,
        controller = ControllerState(
            enabled = controller.enabled,
            pvMode = controller.pvMode,
            status = if (bleState.connectedDeviceAddress != null) {
                "BMS live: ${bleState.status}"
            } else {
                "BMS disconnected"
            },
            targetChargeCurrent = controller.targetChargeCurrent,
            commandIset = controller.commandIset,
            targetPvVolts = controller.targetPvVolts,
            kneeOffsetVolts = controller.kneeOffsetVolts,
            vinErrorVolts = controller.vinErrorVolts,
            policyLimitAmps = controller.policyLimitAmps,
            recoveryPhase = controller.recoveryPhase,
            recoveryCycleCount = controller.recoveryCycleCount,
            controlBand = controller.controlBand,
            controlStepAmps = controller.controlStepAmps,
            ridenConnected = false,
            bmsConnected = bleState.connectedDeviceAddress != null,
            recoveryActive = controller.recoveryActive,
            socTargetPercent = controller.socTargetPercent
        ),
        logs = buildList {
            add(if (bleState.connectedDeviceAddress != null) "BMS connected: ${bleState.connectedDeviceName}" else "BMS disconnected")
            add("BMS status: ${bleState.status}")
            telemetry.packVoltage?.let { add("BMS voltage: %.2f V".format(it)) }
            telemetry.packCurrent?.let { add("BMS current: %.2f A".format(it)) }
            telemetry.socPercent?.let { add("BMS SOC: $it%") }
            telemetry.cellDeltaMv?.let { add("BMS cell delta: $it mV") }
            telemetry.protectionStatus?.let {
                val alarms = telemetry.protectionAlarmNames.ifEmpty { listOf("none") }.joinToString()
                add("BMS protection: $alarms (0x%04X)".format(it))
            }
        }
    )
}
