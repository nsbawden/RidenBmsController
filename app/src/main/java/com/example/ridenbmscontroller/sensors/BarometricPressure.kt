package com.example.ridenbmscontroller.sensors

import kotlin.math.pow

/** Station / sea-level pressure helpers for the dashboard barometer tile. */
object BarometricPressure {
    const val HPA_TO_INHG = 0.029529983071445

    fun inHgFromHpa(hPa: Double): Double = hPa * HPA_TO_INHG

    /**
     * Reduce station pressure to mean sea level using the ISA barometric formula
     * (inverse of [android.hardware.SensorManager.getAltitude]).
     */
    fun seaLevelInHg(stationInHg: Double, altitudeMeters: Double): Double {
        if (altitudeMeters <= 0.0) return stationInHg
        val stationHpa = stationInHg / HPA_TO_INHG
        val ratio = 1.0 - (altitudeMeters / 44330.0)
        if (ratio <= 0.01) return stationInHg
        val seaLevelHpa = stationHpa / ratio.pow(5.255)
        return inHgFromHpa(seaLevelHpa)
    }
}
