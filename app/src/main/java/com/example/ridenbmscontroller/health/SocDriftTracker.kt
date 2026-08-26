package com.example.ridenbmscontroller.health

import android.content.Context
import androidx.core.content.edit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tracks worst-case SOC under-reporting while the BMS current is inside its near-zero deadband.
 *
 * - Accumulates driftAh at [deadbandAmps] whenever |I| <= deadband (including at 100% SOC).
 * - At 100% SOC with charge current above the deadband, pays driftAh down by measured charge Ah
 *   (cumulative path toward EST 100%).
 * - Hard-resets on BMS SOC 0, hold complete (SOC + pack voltage gates), or manual reset.
 */
class SocDriftTracker(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var driftAh: Double = prefs.getFloat(KEY_DRIFT_AH, 0f).toDouble()
        private set

    private var lastSampleMs: Long = 0L
    private var lastPersistMs: Long = 0L

    fun reset(reason: String? = null): String {
        driftAh = 0.0
        lastSampleMs = 0L
        persist(force = true)
        return reason ?: "manual"
    }

    /** True when BMS is at/above 100% and displayed EST rounds to 100% (within ~0.5%). */
    fun isEstAtFull(bmsSocPercent: Int, nominalAh: Double?): Boolean {
        if (bmsSocPercent < 100) return false
        val est = worstCaseSocPercent(bmsSocPercent, nominalAh) ?: return driftAh <= EST_SYNC_EPS_AH
        return est >= 100
    }

    /**
     * Update from a BMS sample. Returns a reset reason if drift was cleared, else null.
     */
    fun update(
        nowMs: Long,
        bmsConnected: Boolean,
        socPercent: Int?,
        packCurrentA: Double?,
        deadbandAmps: Double
    ): String? {
        if (!bmsConnected || socPercent == null || packCurrentA == null) {
            lastSampleMs = 0L
            return null
        }

        if (socPercent <= 0) {
            if (driftAh > 0.0) {
                reset()
                return "BMS SOC 0"
            }
            lastSampleMs = nowMs
            return null
        }

        val deadband = deadbandAmps.coerceAtLeast(0.0)
        val inDeadband = deadband > 0.0 && abs(packCurrentA) <= deadband
        val chargingAboveDeadband = packCurrentA > deadband

        val previousMs = lastSampleMs
        lastSampleMs = nowMs
        if (previousMs <= 0L || nowMs <= previousMs) {
            return null
        }

        val dtHours = (nowMs - previousMs).coerceAtMost(MAX_DT_MS) / MS_PER_HOUR
        when {
            inDeadband -> {
                driftAh += deadband * dtHours
                persist(force = false, nowMs = nowMs)
            }
            socPercent >= 100 && chargingAboveDeadband && driftAh > 0.0 -> {
                driftAh = (driftAh - packCurrentA * dtHours).coerceAtLeast(0.0)
                persist(force = false, nowMs = nowMs)
            }
        }

        return null
    }

    fun worstCaseSocPercent(bmsSocPercent: Int, nominalAh: Double?): Int? {
        val capacity = nominalAh ?: return null
        if (capacity <= 0.0) return null
        val worst = bmsSocPercent - (driftAh / capacity) * 100.0
        return worst.coerceIn(0.0, 100.0).roundToInt()
    }

    private fun persist(force: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (!force && nowMs - lastPersistMs < PERSIST_MS) return
        lastPersistMs = nowMs
        prefs.edit {
            putFloat(KEY_DRIFT_AH, driftAh.toFloat())
        }
    }

    companion object {
        private const val PREFS_NAME = "soc_drift"
        private const val KEY_DRIFT_AH = "drift_ah"
        private const val MS_PER_HOUR = 3_600_000.0
        private const val MAX_DT_MS = 60_000L
        private const val PERSIST_MS = 5_000L
        private const val EST_SYNC_EPS_AH = 0.01
    }
}
