package com.example.ridenbmscontroller.health

/** Telemetry from the last stable tracking tick immediately before an unscheduled collapse. */
data class SkyDisturbanceSnapshot(
    val timestampMs: Long,
    val ioutA: Double?,
    val voutV: Double?,
    val vinV: Double?,
    val vtranV: Double?
)
