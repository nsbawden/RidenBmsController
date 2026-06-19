package com.example.ridenbmscontroller.riden

/** RD6024 internal OTP is fixed at 80 °C per Riden manual; fan starts around 45 °C. */
object RidenTemp {
    const val OTP_LIMIT_C = 80.0
    const val OTP_LIMIT_F = 176.0
    const val WARN_START_F = 113.0
    const val COLOR_RED_ABOVE_F = 165.0
    const val ALARM_ABOVE_F = 170.0

    fun otpTripped(tempF: Double?): Boolean {
        return tempF != null && tempF >= OTP_LIMIT_F
    }

    fun alarmActive(tempF: Double?): Boolean {
        return tempF != null && tempF > ALARM_ABOVE_F
    }
}
