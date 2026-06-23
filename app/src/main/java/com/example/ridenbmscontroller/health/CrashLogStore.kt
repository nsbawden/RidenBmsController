package com.example.ridenbmscontroller.health

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Append-only crash episode traces: ops_logs/{date}_crash_episodes.csv
 * One row per controller tick from collapse through stable tracking + tail.
 */
class CrashLogStore(context: Context) {
    private val appContext = context.applicationContext
    private val logDir = File(appContext.filesDir, LOG_DIR_NAME).also { it.mkdirs() }
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private var dayKey = 0
    private var nextEpisodeId = 1

    fun load(today: Int) {
        dayKey = today
        nextEpisodeId = readNextEpisodeId(today)
    }

    fun rolloverDayIfNeeded(today: Int): Boolean {
        if (dayKey == today) return false
        dayKey = today
        nextEpisodeId = 1
        persistNextEpisodeId()
        return true
    }

    fun startEpisode(kind: String, preCrash: SkyDisturbanceSnapshot?, nowMs: Long): Int {
        val episodeId = nextEpisodeId++
        persistNextEpisodeId()
        return episodeId
    }

    fun appendSample(sample: CrashLogSample) {
        val file = csvFileForDayKey(dayKey)
        ensureHeader(file)
        file.appendText(sample.toCsvLine() + "\n")
    }

    private fun readNextEpisodeId(today: Int): Int {
        if (prefs.getInt(KEY_DAY, 0) != today) return 1
        return prefs.getInt(KEY_NEXT_EPISODE_ID, 1).coerceAtLeast(1)
    }

    private fun persistNextEpisodeId() {
        prefs.edit(commit = true) {
            putInt(KEY_DAY, dayKey)
            putInt(KEY_NEXT_EPISODE_ID, nextEpisodeId)
        }
    }

    private fun csvFileForDayKey(today: Int): File {
        return File(logDir, "${dayLabelForDayKey(today)}_crash_episodes.csv")
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

    private fun ensureHeader(file: File) {
        if (!file.exists() || file.length() == 0L) {
            file.writeText(CSV_HEADER + "\n")
        }
    }

    private fun CrashLogSample.toCsvLine(): String {
        fun d(value: Double?, decimals: Int): String =
            value?.let { "%.${decimals}f".format(it) } ?: ""
        fun b(value: Boolean): String = if (value) "1" else "0"
        return listOf(
            timestampMs,
            episodeId,
            msSinceEpisode,
            crashKind,
            episodeKind,
            if (episodeStart) "1" else "0",
            d(preIoutA, 3),
            d(preVoutV, 3),
            d(preVinV, 2),
            d(preVtranV, 2),
            d(ioutA, 3),
            d(voutV, 3),
            "%.2f".format(vinV),
            "%.2f".format(vtranV),
            "%.2f".format(vinErrorV),
            "%.3f".format(commandIsetA),
            d(ridenIsetA, 3),
            pvMode,
            recoveryPhase,
            "%.1f".format(poutW),
            "%.1f".format(pinEstW),
            "%.2f".format(kneeOffsetV),
            recoveryCycleCount,
            controlBand,
            "%.3f".format(controlStepA),
            "%.3f".format(policyLimitA),
            "%.3f".format(probeCrashIsetA),
            b(probeRecoveryActive),
            b(kneeProbeFast)
        ).joinToString(",")
    }

    companion object {
        private const val PREFS_NAME = "crash_log"
        private const val KEY_DAY = "day_key"
        private const val KEY_NEXT_EPISODE_ID = "next_episode_id"
        private const val LOG_DIR_NAME = "ops_logs"
        const val CSV_HEADER =
            "timestamp_ms,episode_id,ms_since_episode,crash_kind,episode_kind,episode_start," +
            "pre_iout_a,pre_vout_v,pre_vin_v,pre_vtran_v," +
            "iout_a,vout_v,vin_v,vtran_v,vin_error_v,command_iset_a,riden_iset_a," +
            "pv_mode,recovery_phase,pout_w,pin_est_w,knee_offset_v,recovery_cycle_count," +
            "control_band,control_step_a,policy_limit_a,probe_crash_iset_a," +
            "probe_recovery_active,knee_probe_fast"

        fun dayLabel(timestampMs: Long): String {
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestampMs))
        }
    }
}
