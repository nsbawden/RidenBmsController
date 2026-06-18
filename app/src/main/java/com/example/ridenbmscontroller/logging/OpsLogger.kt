package com.example.ridenbmscontroller.logging

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OpsLogDaySummary(
    val dateLabel: String,
    val telemetryBytes: Long,
    val eventsBytes: Long
) {
    val totalBytes: Long get() = telemetryBytes + eventsBytes
}

data class OpsLogStorageSummary(
    val totalBytes: Long,
    val days: List<OpsLogDaySummary>,
    val logDirectory: String
)

data class OpsTelemetrySample(
    val timestampMs: Long,
    val pvMode: String,
    val controlBand: String,
    val recoveryPhase: String,
    val recoveryCycleCount: Int,
    val targetPvVolts: Double,
    val kneeOffsetVolts: Double,
    val vinErrorVolts: Double,
    val commandIset: Double,
    val controlStepAmps: Double,
    val policyLimitAmps: Double,
    val ridenVin: Double,
    val ridenVout: Double,
    val ridenIout: Double,
    val ridenWatts: Double,
    val batteryVolts: Double,
    val batteryAmps: Double,
    val batteryWatts: Double,
    val socPercent: Int,
    val temperatureF: Double,
    val ridenConnected: Boolean,
    val bmsConnected: Boolean,
    val kneeProbeFast: Boolean = false,
    val vtuneProbePhase: String = "--",
    val huntLockTicks: Int = 0,
    val ridenPinEstW: Double = 0.0,
    val ridenPoutW: Double = 0.0,
    val acceptedProbePinW: Double = 0.0,
    val vtuneDescentBlocked: Boolean = false,
    val powerBasedVtuneStop: Boolean = false
)

class OpsLogger(baseDir: File) {
    private val logDir = File(baseDir, LOG_DIR_NAME).also { it.mkdirs() }
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun logTelemetry(sample: OpsTelemetrySample) {
        val file = telemetryFileFor(sample.timestampMs)
        ensureHeader(file, TELEMETRY_HEADER)
        file.appendText(sample.toCsvLine() + "\n")
    }

    fun logEvent(timestampMs: Long, event: String) {
        val sanitized = event.replace('\n', ' ').replace(',', ';')
        val file = eventsFileFor(timestampMs)
        ensureHeader(file, EVENTS_HEADER)
        file.appendText("$timestampMs,$sanitized\n")
    }

    fun refreshSummary(): OpsLogStorageSummary {
        val byDay = linkedMapOf<String, OpsLogDaySummary>()
        logDir.listFiles()?.forEach { file ->
            val dateLabel = file.name.substringBefore('_')
            if (!dateLabel.matches(DATE_PATTERN)) return@forEach
            val current = byDay[dateLabel] ?: OpsLogDaySummary(dateLabel, 0L, 0L)
            byDay[dateLabel] = when {
                file.name.endsWith("_telemetry.csv") ||
                    file.name.endsWith("_telemetry_v2.csv") ||
                    file.name.endsWith("_telemetry_v3.csv") ->
                    current.copy(telemetryBytes = current.telemetryBytes + file.length())
                file.name.endsWith("_events.csv") ->
                    current.copy(eventsBytes = current.eventsBytes + file.length())
                else -> current
            }
        }
        val days = byDay.values.sortedByDescending { it.dateLabel }
        return OpsLogStorageSummary(
            totalBytes = days.sumOf { it.totalBytes },
            days = days,
            logDirectory = logDir.absolutePath
        )
    }

    /** Deletes daily log files strictly before [cutoffDateLabel] (YYYY-MM-DD). Returns files removed. */
    fun deleteLogsBeforeDate(cutoffDateLabel: String): Int {
        if (!cutoffDateLabel.matches(DATE_PATTERN)) return 0
        var removed = 0
        logDir.listFiles()?.forEach { file ->
            val dateLabel = file.name.substringBefore('_')
            if (dateLabel.matches(DATE_PATTERN) && dateLabel < cutoffDateLabel && file.delete()) {
                removed += 1
            }
        }
        return removed
    }

    private fun telemetryFileFor(timestampMs: Long): File {
        val day = dayLabel(timestampMs)
        val legacy = File(logDir, "${day}_telemetry.csv")
        if (legacy.exists() && legacy.length() > 0L) {
            val header = legacy.bufferedReader().use { it.readLine() }.orEmpty()
            if (!header.contains("riden_pin_est_w")) {
                return File(logDir, "${day}_telemetry_v2.csv")
            }
            if (!header.contains("power_based_vtune_stop")) {
                return File(logDir, "${day}_telemetry_v3.csv")
            }
        }
        return legacy
    }

    private fun eventsFileFor(timestampMs: Long): File {
        return File(logDir, "${dayLabel(timestampMs)}_events.csv")
    }

    private fun dayLabel(timestampMs: Long): String {
        return dayFormat.format(Date(timestampMs))
    }

    private fun ensureHeader(file: File, header: String) {
        if (!file.exists() || file.length() == 0L) {
            file.writeText(header + "\n")
        }
    }

    private fun OpsTelemetrySample.toCsvLine(): String {
        return listOf(
            timestampMs,
            pvMode,
            controlBand,
            recoveryPhase,
            recoveryCycleCount,
            targetPvVolts,
            kneeOffsetVolts,
            vinErrorVolts,
            commandIset,
            controlStepAmps,
            policyLimitAmps,
            ridenVin,
            ridenVout,
            ridenIout,
            ridenWatts,
            batteryVolts,
            batteryAmps,
            batteryWatts,
            socPercent,
            temperatureF,
            if (ridenConnected) 1 else 0,
            if (bmsConnected) 1 else 0,
            if (kneeProbeFast) 1 else 0,
            vtuneProbePhase,
            huntLockTicks,
            ridenPinEstW,
            ridenPoutW,
            acceptedProbePinW,
            if (vtuneDescentBlocked) 1 else 0,
            if (powerBasedVtuneStop) 1 else 0
        ).joinToString(",")
    }

    companion object {
        const val LOG_DIR_NAME = "ops_logs"
        const val STORAGE_WARN_BYTES = 50L * 1024L * 1024L
        private val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")

        const val TELEMETRY_HEADER =
            "timestamp_ms,pv_mode,control_band,recovery_phase,recovery_cycle_count," +
                "target_pv_v,knee_offset_v,vin_error_v,command_iset_a,control_step_a,policy_limit_a," +
                "riden_vin_v,riden_vout_v,riden_iout_a,riden_watts_w," +
                "battery_v,battery_a,battery_w,soc_pct,temp_f,riden_connected,bms_connected," +
                "knee_probe_fast,vtune_probe_phase,hunt_lock_ticks,riden_pin_est_w,riden_pout_w," +
                "accepted_probe_pin_w,vtune_descent_blocked,power_based_vtune_stop"

        const val EVENTS_HEADER = "timestamp_ms,event"

        fun formatSize(bytes: Long): String {
            return when {
                bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
                bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
                else -> "$bytes B"
            }
        }
    }
}
