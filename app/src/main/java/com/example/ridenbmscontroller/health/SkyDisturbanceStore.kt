package com.example.ridenbmscontroller.health

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

data class SkyDisturbance(
    val timestampMs: Long,
    val ioutA: Double?,
    val voutV: Double?,
    val vinV: Double? = null,
    val vtranV: Double? = null
)

/**
 * In-memory list of unscheduled cloud/shade collapses for the current day, mirrored to
 * ops_logs/{date}_sky_disturbances.csv on each event for reload survival.
 */
class SkyDisturbanceStore(context: Context) {
    private val appContext = context.applicationContext
    private val logDir = File(appContext.filesDir, LOG_DIR_NAME).also { it.mkdirs() }
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private var dayKey = 0
    private var dayStartMs = 0L
    private var solarDayStartMs: Long? = null
    private val disturbances = mutableListOf<SkyDisturbance>()

    val entries: List<SkyDisturbance> get() = disturbances
    val disturbanceCount: Int get() = disturbances.size

    fun load(today: Int, dayStartMs: Long) {
        this.dayKey = today
        this.dayStartMs = dayStartMs
        disturbances.clear()
        solarDayStartMs = readSolarDayStartMs(today)
        val file = csvFileForDayKey(today)
        if (!file.exists()) return
        file.readLines().drop(1).forEach { line ->
            parseLine(line)?.let { disturbances.add(it) }
        }
        disturbances.sortBy { it.timestampMs }
    }

    fun noteRidenOutputAmps(ioutA: Double?, nowMs: Long) {
        if (solarDayStartMs != null) return
        if (ioutA != null && ioutA > SOLAR_DAY_IOUT_THRESHOLD_A) {
            solarDayStartMs = nowMs
            persistSolarDayStartMs()
        }
    }

    fun rolloverDayIfNeeded(today: Int, dayStartMs: Long): Boolean {
        if (dayKey == today) return false
        resetForDay(today, dayStartMs)
        return true
    }

    fun recordDisturbance(snapshot: SkyDisturbanceSnapshot) {
        val entry = SkyDisturbance(
            timestampMs = snapshot.timestampMs,
            ioutA = snapshot.ioutA,
            voutV = snapshot.voutV,
            vinV = snapshot.vinV,
            vtranV = snapshot.vtranV
        )
        disturbances.add(entry)
        appendToCsv(entry)
    }

    fun longestCleanGapMs(nowMs: Long): Long? {
        val effectiveStart = solarDayStartMs ?: return null
        if (nowMs <= effectiveStart) return null
        val relevant = disturbances.filter { it.timestampMs >= effectiveStart }
        if (relevant.isEmpty()) {
            return nowMs - effectiveStart
        }
        var longest = relevant.first().timestampMs - effectiveStart
        for (index in 1 until relevant.size) {
            longest = max(
                longest,
                relevant[index].timestampMs - relevant[index - 1].timestampMs
            )
        }
        longest = max(longest, nowMs - relevant.last().timestampMs)
        return longest.coerceAtLeast(0L)
    }

    private fun resetForDay(today: Int, dayStartMs: Long) {
        dayKey = today
        this.dayStartMs = dayStartMs
        solarDayStartMs = null
        disturbances.clear()
        persistSolarDayStartMs()
    }

    private fun readSolarDayStartMs(today: Int): Long? {
        if (prefs.getInt(KEY_DAY, 0) != today) return null
        val stored = prefs.getLong(KEY_SOLAR_DAY_START_MS, UNSET)
        return stored.takeIf { it > 0L }
    }

    private fun persistSolarDayStartMs() {
        prefs.edit(commit = true) {
            putInt(KEY_DAY, dayKey)
            putLong(KEY_SOLAR_DAY_START_MS, solarDayStartMs ?: UNSET)
        }
    }

    private fun csvFileForDayKey(today: Int): File {
        return File(logDir, "${dayLabelForDayKey(today)}_sky_disturbances.csv")
    }

    private fun dayLabelForDayKey(today: Int): String {
        val year = today / 1000
        val dayOfYear = today % 1000
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(Calendar.YEAR, year)
        calendar.set(Calendar.DAY_OF_YEAR, dayOfYear)
        return dayFormat.format(calendar.time)
    }

    private fun appendToCsv(entry: SkyDisturbance) {
        val file = csvFileForDayKey(dayKey)
        ensureHeader(file)
        file.appendText(entry.toCsvLine() + "\n")
    }

    private fun ensureHeader(file: File) {
        if (!file.exists() || file.length() == 0L) {
            file.writeText(CSV_HEADER + "\n")
            return
        }
        val lines = file.readLines()
        if (lines.firstOrNull() == LEGACY_CSV_HEADER) {
            val body = lines.drop(1).joinToString("\n")
            file.writeText(CSV_HEADER + "\n" + body + if (body.isNotEmpty()) "\n" else "")
        }
    }

    private fun parseLine(line: String): SkyDisturbance? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed == CSV_HEADER || trimmed == LEGACY_CSV_HEADER) return null
        val parts = trimmed.split(',')
        if (parts.size < 3) return null
        val timestampMs = parts[0].toLongOrNull() ?: return null
        val ioutA = parts[1].trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        val voutV = parts[2].trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        val vinV = parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        val vtranV = parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        return SkyDisturbance(timestampMs, ioutA, voutV, vinV, vtranV)
    }

    private fun SkyDisturbance.toCsvLine(): String {
        return listOf(
            timestampMs,
            ioutA?.let { "%.3f".format(it) } ?: "",
            voutV?.let { "%.3f".format(it) } ?: "",
            vinV?.let { "%.2f".format(it) } ?: "",
            vtranV?.let { "%.2f".format(it) } ?: ""
        ).joinToString(",")
    }

    companion object {
        private const val PREFS_NAME = "sky_disturbance"
        private const val KEY_DAY = "day_key"
        private const val KEY_SOLAR_DAY_START_MS = "solar_day_start_ms"
        private const val UNSET = -1L
        private const val LOG_DIR_NAME = "ops_logs"
        const val CSV_HEADER = "timestamp_ms,iout_a,vout_v,vin_v,vtran_v"
        private const val LEGACY_CSV_HEADER = "timestamp_ms,iout_a,vout_v"
        const val SOLAR_DAY_IOUT_THRESHOLD_A = 1.0

        fun dayStartMs(calendar: Calendar = Calendar.getInstance()): Long {
            val day = calendar.clone() as Calendar
            day.set(Calendar.HOUR_OF_DAY, 0)
            day.set(Calendar.MINUTE, 0)
            day.set(Calendar.SECOND, 0)
            day.set(Calendar.MILLISECOND, 0)
            return day.timeInMillis
        }

        fun dayLabel(timestampMs: Long): String {
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestampMs))
        }
    }
}
