package com.example.ridenbmscontroller.health

import android.content.Context
import androidx.core.content.edit
import com.example.ridenbmscontroller.model.DailyHealthState

class DailyHealthStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var dayKey = 0
    private var minRidenAmps = Double.NaN
    private var maxRidenAmps = Double.NaN

    var state: DailyHealthState = DailyHealthState()
        private set

    fun load(today: Int) {
        dayKey = prefs.getInt(KEY_DAY, today)
        minRidenAmps = readStoredAmps(prefs.getFloat(KEY_MIN_RIDEN_AMPS, UNSET).toDouble())
        maxRidenAmps = readStoredAmps(prefs.getFloat(KEY_MAX_RIDEN_AMPS, UNSET).toDouble())
        if (dayKey != today) {
            resetForDay(today)
        } else {
            publishState()
        }
    }

    fun rolloverDayIfNeeded(today: Int): Boolean {
        if (dayKey == today) return false
        resetForDay(today)
        persist()
        return true
    }

    fun recordRidenOutputAmps(amps: Double?) {
        if (amps == null || amps < MIN_SAMPLE_AMPS) return
        var changed = false
        if (!hasRecordedAmps(minRidenAmps) || amps < minRidenAmps) {
            minRidenAmps = amps
            changed = true
        }
        if (!hasRecordedAmps(maxRidenAmps) || amps > maxRidenAmps) {
            maxRidenAmps = amps
            changed = true
        }
        if (changed) {
            publishState()
            persist()
        }
    }

    private fun resetForDay(today: Int) {
        dayKey = today
        minRidenAmps = Double.NaN
        maxRidenAmps = Double.NaN
        publishState()
    }

    private fun publishState() {
        state = DailyHealthState(
            minRidenOutputAmpsToday = minRidenAmps.takeIf { hasRecordedAmps(it) },
            maxRidenOutputAmpsToday = maxRidenAmps.takeIf { hasRecordedAmps(it) }
        )
    }

    private fun persist() {
        prefs.edit(commit = true) {
            putInt(KEY_DAY, dayKey)
            putFloat(KEY_MIN_RIDEN_AMPS, if (hasRecordedAmps(minRidenAmps)) minRidenAmps.toFloat() else UNSET)
            putFloat(KEY_MAX_RIDEN_AMPS, if (hasRecordedAmps(maxRidenAmps)) maxRidenAmps.toFloat() else UNSET)
        }
    }

    private fun readStoredAmps(value: Double): Double {
        return if (hasRecordedAmps(value)) value else Double.NaN
    }

    private fun hasRecordedAmps(value: Double): Boolean {
        return !value.isNaN() && value >= 0.0
    }

    companion object {
        private const val PREFS_NAME = "daily_health"
        private const val KEY_DAY = "day_key"
        private const val KEY_MIN_RIDEN_AMPS = "min_riden_amps"
        private const val KEY_MAX_RIDEN_AMPS = "max_riden_amps"
        private const val UNSET = -1f
        private const val MIN_SAMPLE_AMPS = 0.05
    }
}
