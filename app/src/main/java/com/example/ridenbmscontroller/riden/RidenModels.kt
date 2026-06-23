package com.example.ridenbmscontroller.riden

data class RidenTelemetry(
    val vset: Double? = null,
    val iset: Double? = null,
    val vout: Double? = null,
    val iout: Double? = null,
    val watts: Double? = null,
    val vin: Double? = null,
    val outputOn: Boolean? = null,
    val internalTempF: Double? = null,
    val protectionError: Int? = null
)

data class RidenUsbState(
    val connected: Boolean = false,
    val permissionNeeded: Boolean = false,
    val status: String = "USB idle",
    val deviceName: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val telemetry: RidenTelemetry = RidenTelemetry(),
    /** Incremented on each successful Modbus telemetry read (fresh Riden sample). */
    val telemetrySampleSeq: Long = 0L
)
