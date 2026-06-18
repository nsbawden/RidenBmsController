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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import com.example.ridenbmscontroller.logging.OpsLogger
import com.example.ridenbmscontroller.logging.OpsLogStorageSummary
import com.example.ridenbmscontroller.logging.OpsTelemetrySample
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.padding

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
    private var lastOpsSampleMs = 0L
    private var lastOpsStorageCheckMs = 0L
    private var opsLogSummary by mutableStateOf(OpsLogStorageSummary(0L, emptyList(), ""))
    private var logStorageWarning by mutableStateOf<String?>(null)
    private var logStorageWarningDismissed = false
    private var lastLoggedKneeOffset = Double.NaN
    private var lastLoggedRecoveryCycle = -1
    private var lastLoggedRecoveryPhase = ""
    private var lastOpsTelemetryBand = ""
    private var lastOpsTelemetryPvMode = ""
    private var opsBurstLogging = false
    private var opsBurstStableSinceMs = 0L
    private var lastLoggedKneeProbeFast = false
    private var lastLoggedVtuneProbePhase = "--"
    private var lastLoggedVtuneDescentBlocked = false
    private lateinit var opsLogger: OpsLogger
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
        opsLogger = OpsLogger(filesDir)
        refreshOpsLogSummary()
        opsLogger.logEvent(System.currentTimeMillis(), "App started")
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
            onState = { handleMpptControlState(it) },
            onEvent = { addControllerEvent(it) }
        )
        bmsBleScanner = BmsBleScanner(this) {
            bmsBleState = it
            batteryCurrentAverage.add(System.currentTimeMillis(), it.telemetry.packCurrent)
            updateBatteryTimeEstimate()
            historyAccumulator.addBms(it)
            updateBmsEnergyFromPower(it.telemetry.packVoltage, it.telemetry.packCurrent)
            maybeRecordHistory()
            maybeRecordOpsTelemetry()
            updateLowSocAlarm()
            tickController()
        }
        ridenUsbMonitor = RidenUsbMonitor(this, lifecycleScope) {
            ridenUsbState = it
            historyAccumulator.addRiden(it)
            updateEnergyFromPower(it.telemetry.watts)
            maybeRecordHistory()
            maybeRecordOpsTelemetry()
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
                    opsLogSummary = opsLogSummary,
                    logStorageWarning = logStorageWarning,
                    batteryTimeEstimateText = batteryTimeEstimateText,
                    onSettingsChanged = {
                        val previous = appSettings
                        if (it != previous) {
                            appSettings = it
                            saveSettings(it)
                            if (it.powerBasedVtuneStop != previous.powerBasedVtuneStop) {
                                addControllerEvent(
                                    if (it.powerBasedVtuneStop) {
                                        "Power-based VTune stop enabled"
                                    } else {
                                        "Power-based VTune stop disabled (collapse only)"
                                    }
                                )
                            }
                            applyKeepScreenOn(it.keepScreenOn)
                            applyControllerKeepAlive(it.controllerEnabled)
                            updateLowSocAlarm()
                            tickController()
                        }
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
                    onDeleteOpsLogsBeforeDate = { cutoff -> deleteOpsLogsBeforeDate(cutoff) },
                    onRefreshOpsLogSummary = { refreshOpsLogSummary() },
                    onDismissLogStorageWarning = {
                        logStorageWarningDismissed = true
                        logStorageWarning = null
                    },
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
        logControllerTransitions(previous, state)
        updateOpsBurstLogging(state)
        maybeRecordOpsTelemetry()

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

    private fun logControllerTransitions(previous: MpptControlState, state: MpptControlState) {
        var forceTelemetry = false

        if (state.recoveryActive && !previous.recoveryActive) {
            opsBurstLogging = true
            opsBurstStableSinceMs = 0L
            addControllerEvent("Recovery entered: ${state.status}")
            forceTelemetry = true
        }
        if (state.controlBand != lastOpsTelemetryBand && lastOpsTelemetryBand.isNotEmpty()) {
            addControllerEvent("Control band ${lastOpsTelemetryBand} -> ${state.controlBand}")
            forceTelemetry = true
        }
        if (state.kneeProbeFast != lastLoggedKneeProbeFast) {
            if (state.kneeProbeFast) {
                addControllerEvent("VTune fast acquire started")
            } else {
                addControllerEvent("VTune fast acquire ended")
            }
            lastLoggedKneeProbeFast = state.kneeProbeFast
            forceTelemetry = true
        }
        if (state.vtuneProbePhase != lastLoggedVtuneProbePhase && state.vtuneProbePhase != "--") {
            addControllerEvent("VTune probe phase: $lastLoggedVtuneProbePhase -> ${state.vtuneProbePhase}")
            lastLoggedVtuneProbePhase = state.vtuneProbePhase
            forceTelemetry = true
        } else if (state.vtuneProbePhase == "--" && lastLoggedVtuneProbePhase != "--") {
            lastLoggedVtuneProbePhase = "--"
        }
        if (state.vtuneDescentBlocked != lastLoggedVtuneDescentBlocked) {
            if (state.vtuneDescentBlocked) {
                addControllerEvent("VTune descent blocked (power limit)")
            } else {
                addControllerEvent("VTune descent unblocked")
            }
            lastLoggedVtuneDescentBlocked = state.vtuneDescentBlocked
            forceTelemetry = true
        }
        if (state.kneeOffsetVolts != lastLoggedKneeOffset) {
            if (!lastLoggedKneeOffset.isNaN()) {
                val delta = state.kneeOffsetVolts - lastLoggedKneeOffset
                if (delta < -OPS_KNEE_DOWN_EPS_V) {
                    opsBurstLogging = true
                    opsBurstStableSinceMs = 0L
                    addControllerEvent(
                        "VTune down probe ${"%+.2f".format(delta)}V -> ${"%.2f".format(state.targetPvVolts)}V target"
                    )
                } else {
                    addControllerEvent(
                        "Knee offset ${"%+.2f".format(delta)}V -> ${"%.2f".format(state.targetPvVolts)}V target"
                    )
                }
                forceTelemetry = true
            }
            lastLoggedKneeOffset = state.kneeOffsetVolts
        }
        if (state.recoveryCycleCount != lastLoggedRecoveryCycle && state.recoveryCycleCount > 0) {
            addControllerEvent("Recovery cycle ${state.recoveryCycleCount}")
            forceTelemetry = true
            lastLoggedRecoveryCycle = state.recoveryCycleCount
        }
        if (state.recoveryPhase != lastLoggedRecoveryPhase &&
            state.recoveryPhase != "--" &&
            previous.recoveryPhase != state.recoveryPhase
        ) {
            addControllerEvent("Recovery phase: ${state.recoveryPhase}")
            forceTelemetry = true
            lastLoggedRecoveryPhase = state.recoveryPhase
        }
        if (!state.recoveryActive && previous.recoveryActive) {
            addControllerEvent("Recovery cleared")
            forceTelemetry = true
            lastLoggedRecoveryCycle = state.recoveryCycleCount
        }
        if (forceTelemetry) {
            maybeRecordOpsTelemetry(force = true)
        }
    }

    private fun updateOpsBurstLogging(control: MpptControlState) {
        if (control.recoveryActive || control.pvMode == "Recover") {
            opsBurstLogging = true
            opsBurstStableSinceMs = 0L
            return
        }
        if (control.kneeProbeFast || control.vtuneProbePhase == "WaitLock" || control.vtuneProbePhase == "Pause") {
            opsBurstLogging = true
            opsBurstStableSinceMs = 0L
            return
        }
        if (!opsBurstLogging) return

        if (isOpsTrackingStable(control)) {
            val now = System.currentTimeMillis()
            if (opsBurstStableSinceMs == 0L) opsBurstStableSinceMs = now
            if (now - opsBurstStableSinceMs >= OPS_BURST_STABLE_CLEAR_MS) {
                opsBurstLogging = false
                opsBurstStableSinceMs = 0L
                addControllerEvent("Burst logging ended: stable tracking")
            }
        } else {
            opsBurstStableSinceMs = 0L
        }
    }

    private fun isOpsTrackingStable(control: MpptControlState): Boolean {
        return control.enabled &&
            control.pvMode == "Tracking" &&
            !control.recoveryActive &&
            control.controlBand == "FI" &&
            abs(control.vinErrorVolts) <= OPS_STABLE_VIN_ERROR_V
    }

    private fun addControllerEvent(text: String) {
        val now = System.currentTimeMillis()
        val event = "${eventTimeFormat.format(now)}  $text"
        controllerEvents = (listOf(event) + controllerEvents).take(MAX_CONTROLLER_EVENTS)
        if (::opsLogger.isInitialized) {
            opsLogger.logEvent(now, text)
        }
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

    private fun applyKeepScreenOn(enabled: Boolean) {
        window.decorView.keepScreenOn = enabled
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun maybeRecordOpsTelemetry(force: Boolean = false) {
        if (!::opsLogger.isInitialized) return
        if (!opsTelemetryReady()) return

        val control = mpptControlState
        val nowMs = System.currentTimeMillis()
        val intervalMs = opsTelemetryIntervalMs(control)
        if (!force && nowMs - lastOpsSampleMs < intervalMs) return

        val bms = bmsBleState.telemetry
        val riden = ridenUsbState.telemetry
        val batteryVolts = bms.packVoltage ?: return
        val batteryAmps = bms.packCurrent ?: return
        val soc = bms.socPercent ?: return

        lastOpsSampleMs = nowMs
        lastOpsTelemetryBand = control.controlBand
        lastOpsTelemetryPvMode = control.pvMode
        val ridenVin = riden.vin ?: 0.0
        val ridenVout = riden.vout ?: 0.0
        val ridenPout = riden.watts ?: 0.0
        val ridenPinEst = if (ridenVin > 0.0 && ridenVout > 0.0 && ridenPout > 0.0) {
            ridenPout * (ridenVin / ridenVout)
        } else {
            0.0
        }
        opsLogger.logTelemetry(
            OpsTelemetrySample(
                timestampMs = nowMs,
                pvMode = control.pvMode,
                controlBand = control.controlBand,
                recoveryPhase = control.recoveryPhase,
                recoveryCycleCount = control.recoveryCycleCount,
                targetPvVolts = control.targetPvVolts,
                kneeOffsetVolts = control.kneeOffsetVolts,
                vinErrorVolts = control.vinErrorVolts,
                commandIset = control.commandIset,
                controlStepAmps = control.controlStepAmps,
                policyLimitAmps = control.policyLimitAmps,
                ridenVin = ridenVin,
                ridenVout = ridenVout,
                ridenIout = riden.iout ?: 0.0,
                ridenWatts = ridenPout,
                batteryVolts = batteryVolts,
                batteryAmps = batteryAmps,
                batteryWatts = batteryVolts * batteryAmps,
                socPercent = soc,
                temperatureF = bms.temperatureF ?: 0.0,
                ridenConnected = ridenUsbState.connected,
                bmsConnected = bmsBleState.connectedDeviceAddress != null,
                kneeProbeFast = control.kneeProbeFast,
                vtuneProbePhase = control.vtuneProbePhase,
                huntLockTicks = control.huntLockTicks,
                ridenPinEstW = ridenPinEst,
                ridenPoutW = ridenPout,
                acceptedProbePinW = control.acceptedProbePinWatts,
                vtuneDescentBlocked = control.vtuneDescentBlocked,
                powerBasedVtuneStop = appSettings.powerBasedVtuneStop
            )
        )
        maybeCheckLogStorage(nowMs)
    }

    private fun opsTelemetryIntervalMs(control: MpptControlState): Long {
        if (opsBurstLogging || control.recoveryActive || control.pvMode == "Recover" ||
            control.kneeProbeFast || control.vtuneProbePhase == "WaitLock" || control.vtuneProbePhase == "Pause"
        ) {
            return OPS_SAMPLE_BURST_MS
        }
        if (!control.enabled) return OPS_SAMPLE_IDLE_MS
        return when (control.controlBand) {
            "HU", "FA", "MD" -> OPS_SAMPLE_AGGRESSIVE_MS
            "NE" -> OPS_SAMPLE_NEAR_MS
            "FI" -> OPS_SAMPLE_STABLE_MS
            else -> OPS_SAMPLE_OTHER_MS
        }
    }

    private fun opsTelemetryReady(): Boolean {
        val bms = bmsBleState.telemetry
        val riden = ridenUsbState.telemetry
        return bmsBleState.connectedDeviceAddress != null &&
            ridenUsbState.connected &&
            bms.packVoltage != null &&
            bms.packCurrent != null &&
            bms.socPercent != null &&
            riden.vin != null &&
            riden.vout != null &&
            riden.iout != null &&
            riden.watts != null
    }

    private fun maybeCheckLogStorage(nowMs: Long) {
        if (nowMs - lastOpsStorageCheckMs < OPS_STORAGE_CHECK_MS) return
        lastOpsStorageCheckMs = nowMs
        refreshOpsLogSummary()
        val total = opsLogSummary.totalBytes
        if (total < OpsLogger.STORAGE_WARN_BYTES) {
            logStorageWarningDismissed = false
            return
        }
        if (!logStorageWarningDismissed) {
            logStorageWarning =
                "Operational logs are using ${OpsLogger.formatSize(total)}. " +
                    "Delete older logs from Tools > Logs when you are ready."
        }
    }

    private fun refreshOpsLogSummary() {
        if (!::opsLogger.isInitialized) return
        opsLogSummary = opsLogger.refreshSummary()
    }

    private fun deleteOpsLogsBeforeDate(cutoffDateLabel: String): String {
        if (!::opsLogger.isInitialized) return "Logger not ready"
        if (!cutoffDateLabel.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            return "Use date format YYYY-MM-DD"
        }
        val parsed = try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(cutoffDateLabel)
        } catch (_: Exception) {
            null
        }
        if (parsed == null) return "Invalid date: $cutoffDateLabel"
        return try {
            val removed = opsLogger.deleteLogsBeforeDate(cutoffDateLabel)
            refreshOpsLogSummary()
            if (opsLogSummary.totalBytes < OpsLogger.STORAGE_WARN_BYTES) {
                logStorageWarningDismissed = false
                logStorageWarning = null
            }
            if (removed == 0) {
                "No log files found before $cutoffDateLabel"
            } else {
                addControllerEvent("Deleted $removed ops log file(s) before $cutoffDateLabel")
                "Deleted $removed file(s) before $cutoffDateLabel"
            }
        } catch (error: IOException) {
            "Delete failed: ${error.message ?: "IO error"}"
        }
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
            fastAcquireSuccessCount = prefs.getInt(
                KEY_FAST_ACQUIRE_SUCCESS_COUNT,
                defaults.fastAcquireSuccessCount
            ).coerceIn(1, 5),
            powerBasedVtuneStop = prefs.getBoolean(
                KEY_POWER_BASED_VTUNE_STOP,
                defaults.powerBasedVtuneStop
            ),
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
            putInt(KEY_FAST_ACQUIRE_SUCCESS_COUNT, settings.fastAcquireSuccessCount.coerceIn(1, 5))
            putBoolean(KEY_POWER_BASED_VTUNE_STOP, settings.powerBasedVtuneStop)
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
        private const val KEY_HCC_QUIET_S = "hcc_quiet_s"
        private const val KEY_KNEE_TRACKING_DELAY_S = "knee_tracking_delay_s"
        private const val KEY_FAST_ACQUIRE_SUCCESS_COUNT = "fast_acquire_success_count"
        private const val KEY_POWER_BASED_VTUNE_STOP = "power_based_vtune_stop"
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
        private const val HISTORY_SAMPLE_MS = 30_000L
        private const val HISTORY_KEEP_DAYS = 30
        private const val HISTORY_PRUNE_MS = 300_000L
        private const val OPS_SAMPLE_BURST_MS = 200L
        private const val OPS_BURST_STABLE_CLEAR_MS = 5_000L
        private const val OPS_STABLE_VIN_ERROR_V = 0.05
        private const val OPS_KNEE_DOWN_EPS_V = 0.001
        private const val OPS_SAMPLE_AGGRESSIVE_MS = 5_000L
        private const val OPS_SAMPLE_NEAR_MS = 30_000L
        private const val OPS_SAMPLE_STABLE_MS = 90_000L
        private const val OPS_SAMPLE_OTHER_MS = 60_000L
        private const val OPS_SAMPLE_IDLE_MS = 120_000L
        private const val OPS_STORAGE_CHECK_MS = 60_000L
        private const val MAX_CONTROLLER_EVENTS = 80
        private val eventTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
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
    opsLogSummary: OpsLogStorageSummary,
    logStorageWarning: String?,
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
    onDeleteOpsLogsBeforeDate: (String) -> String,
    onRefreshOpsLogSummary: () -> Unit,
    onDismissLogStorageWarning: () -> Unit,
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
        opsLogSummary,
        batteryTimeEstimateText
    ) {
        AppState.preview.copy(
            settings = appSettings,
            energy = energyCounters,
            history = historyPoints,
            events = controllerEvents,
            alerts = alertState,
            opsLogSummary = opsLogSummary,
            batteryTimeEstimateText = batteryTimeEstimateText
        )
            .withBmsTelemetry(bmsBleState)
            .withRidenTelemetry(ridenUsbState)
            .withMpptControl(mpptControlState)
    }
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Dashboard) }
    val tabStateHolder = rememberSaveableStateHolder()

    if (logStorageWarning != null) {
        AlertDialog(
            onDismissRequest = onDismissLogStorageWarning,
            title = { Text("Log Storage Warning") },
            text = { Text(logStorageWarning) },
            confirmButton = {
                TextButton(onClick = onDismissLogStorageWarning) {
                    Text("Dismiss")
                }
            }
        )
    }

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
                    onDeleteOpsLogsBeforeDate = onDeleteOpsLogsBeforeDate,
                    onRefreshOpsLogSummary = onRefreshOpsLogSummary
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
