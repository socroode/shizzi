package dev.shizzi

/** Retry selection on the first missing upstream, then every third reading.
 * Keep the existing terminal watchdog limits; never excuse a live drift. */
class UpstreamRecovery {
    private var absentReadings = 0

    fun shouldReselect(reading: UpstreamReading): Boolean {
        if (reading != UpstreamReading.ABSENT) {
            absentReadings = 0
            return false
        }
        absentReadings++
        return absentReadings == 1 || absentReadings == 4 || absentReadings == 7
    }
}
