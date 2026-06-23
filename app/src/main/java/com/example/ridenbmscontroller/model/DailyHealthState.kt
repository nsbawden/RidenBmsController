package com.example.ridenbmscontroller.model

data class DailyHealthState(
    val unscheduledCrashesToday: Int = 0,
    val minRidenOutputAmpsToday: Double? = null,
    val maxRidenOutputAmpsToday: Double? = null,
    val longestCleanGapMs: Long? = null
)
