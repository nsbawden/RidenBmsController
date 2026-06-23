package com.example.ridenbmscontroller.logging

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OpsLogDaySummary(
    val dateLabel: String,
    val telemetryBytes: Long,
    val eventsBytes: Long,
    val skyBytes: Long = 0L,
    val crashBytes: Long = 0L
) {
    val totalBytes: Long get() = telemetryBytes + eventsBytes + skyBytes + crashBytes
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
    val powerBasedVtuneStop: Boolean = false,
    val pastMppActive: Boolean = false,
    val pastMppWrongWay: Boolean = false,
    val pastMppVinBelowKneeV: Double = 0.0,
    val pastMppIsetDeltaA: Double = 0.0,
    val pastMppPoutDeltaW: Double = 0.0,
    val pastMppMissedW: Double = 0.0,
    val pastMppEpisodeCount: Int = 0,
    val pastMppCumulativeMissedW: Double = 0.0,
    val effectiveKneeDelaySeconds: Double = 30.0
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
        pruneSupersededTelemetryFiles()
        purgeLogsOlderThanDays(PHONE_LOG_RETAIN_DAYS)
        val byDay = linkedMapOf<String, OpsLogDaySummary>()
        logDir.listFiles()?.forEach { file ->
            val dateLabel = file.name.substringBefore('_')
            if (!dateLabel.matches(DATE_PATTERN)) return@forEach
            val current = byDay[dateLabel] ?: OpsLogDaySummary(dateLabel, 0L, 0L, 0L, 0L)
            byDay[dateLabel] = when {
                file.name.endsWith("_telemetry.csv") ||
                    file.name.endsWith("_telemetry_v2.csv") ||
                    file.name.endsWith("_telemetry_v3.csv") ||
                    file.name.endsWith("_telemetry_v4.csv") ||
                    file.name.endsWith("_telemetry_v5.csv") ->
                    current.copy(telemetryBytes = current.telemetryBytes + file.length())
                file.name.endsWith("_events.csv") ->
                    current.copy(eventsBytes = current.eventsBytes + file.length())
                file.name.endsWith("_sky_disturbances.csv") ->
                    current.copy(skyBytes = current.skyBytes + file.length())
                file.name.endsWith("_crash_episodes.csv") ->
                    current.copy(crashBytes = current.crashBytes + file.length())
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

    /** Drop older telemetry schema files when a newer one exists for the same day. */
    fun pruneSupersededTelemetryFiles(): Int {
        val dayLabels = logDir.listFiles()
            ?.mapNotNull { file ->
                val dateLabel = file.name.substringBefore('_')
                dateLabel.takeIf { it.matches(DATE_PATTERN) && file.name.contains("_telemetry") }
            }
            ?.distinct()
            ?: return 0

        var removed = 0
        for (dayLabel in dayLabels) {
            val active = activeTelemetryFileForDay(dayLabel) ?: continue
            logDir.listFiles()?.forEach { file ->
                if (!file.name.startsWith("${dayLabel}_telemetry")) return@forEach
                if (file.absolutePath == active.absolutePath) return@forEach
                if (file.delete()) removed += 1
            }
        }
        return removed
    }

    /** Remove daily log files on the phone older than [retainDays] (today always kept). */
    fun purgeLogsOlderThanDays(retainDays: Int): Int {
        if (retainDays <= 0) return 0
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.DAY_OF_YEAR, -retainDays)
        val cutoffLabel = dayFormat.format(calendar.time)
        return deleteLogsBeforeDate(cutoffLabel)
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
            if (!header.contains("past_mpp_active")) {
                return File(logDir, "${day}_telemetry_v4.csv")
            }
            if (!header.contains("effective_knee_delay_s")) {
                val v4 = File(logDir, "${day}_telemetry_v4.csv")
                if (v4.exists() && v4.length() > 0L) {
                    val v4Header = v4.bufferedReader().use { it.readLine() }.orEmpty()
                    if (!v4Header.contains("effective_knee_delay_s")) {
                        return File(logDir, "${day}_telemetry_v5.csv")
                    }
                }
                return File(logDir, "${day}_telemetry_v5.csv")
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

    private fun dayStartMs(dayLabel: String): Long {
        return dayFormat.parse(dayLabel)?.time ?: System.currentTimeMillis()
    }

    private fun activeTelemetryFileForDay(dayLabel: String): File? {
        return logDir.listFiles()
            ?.filter { file ->
                file.name.startsWith("${dayLabel}_telemetry") && file.length() > 0L
            }
            ?.maxByOrNull { telemetrySchemaRank(it.name) }
    }

    private fun telemetrySchemaRank(fileName: String): Int {
        return when {
            fileName.endsWith("_telemetry_v5.csv") -> 5
            fileName.endsWith("_telemetry_v4.csv") -> 4
            fileName.endsWith("_telemetry_v3.csv") -> 3
            fileName.endsWith("_telemetry_v2.csv") -> 2
            fileName.endsWith("_telemetry.csv") -> 1
            else -> 0
        }
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
            if (powerBasedVtuneStop) 1 else 0,
            if (pastMppActive) 1 else 0,
            if (pastMppWrongWay) 1 else 0,
            pastMppVinBelowKneeV,
            pastMppIsetDeltaA,
            pastMppPoutDeltaW,
            pastMppMissedW,
            pastMppEpisodeCount,
            pastMppCumulativeMissedW,
            effectiveKneeDelaySeconds
        ).joinToString(",")
    }

    companion object {
        const val LOG_DIR_NAME = "ops_logs"
        const val STORAGE_WARN_BYTES = 100L * 1024L * 1024L
        const val PHONE_LOG_RETAIN_DAYS = 7
        private val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")

        const val TELEMETRY_HEADER =
            "timestamp_ms,pv_mode,control_band,recovery_phase,recovery_cycle_count," +
                "target_pv_v,knee_offset_v,vin_error_v,command_iset_a,control_step_a,policy_limit_a," +
                "riden_vin_v,riden_vout_v,riden_iout_a,riden_watts_w," +
                "battery_v,battery_a,battery_w,soc_pct,temp_f,riden_connected,bms_connected," +
                "knee_probe_fast,vtune_probe_phase,hunt_lock_ticks,riden_pin_est_w,riden_pout_w," +
                "accepted_probe_pin_w,vtune_descent_blocked,power_based_vtune_stop," +
                "past_mpp_active,past_mpp_wrong_way,past_mpp_vin_below_knee_v," +
                "past_mpp_iset_delta_a,past_mpp_pout_delta_w,past_mpp_missed_w," +
                "past_mpp_episodes_total,past_mpp_cumulative_missed_w,effective_knee_delay_s"

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
