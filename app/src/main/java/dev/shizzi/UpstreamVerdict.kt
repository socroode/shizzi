package dev.shizzi

/**
 * How a single upstream reading compares to the interface the session owns.
 *
 * An empty reading means tethering currently forwards nowhere, so no traffic can
 * leak off the tun while it lasts. A drifted reading names a different live
 * interface and does leak, so it is far less tolerable. See
 * https://github.com/carlelieser/shizzi/issues/22
 */
enum class UpstreamReading { HEALTHY, ABSENT, UNKNOWN, DRIFTED, DOWNSTREAM_STOPPED }

fun classifyUpstream(
    liveNames: List<String>,
    expectedInterface: String,
    isKnown: Boolean,
    downstreamStopped: Boolean = false,
): UpstreamReading = when {
    downstreamStopped -> UpstreamReading.DOWNSTREAM_STOPPED
    !isKnown -> UpstreamReading.UNKNOWN
    liveNames.isEmpty() -> UpstreamReading.ABSENT
    liveNames.all { it == expectedInterface } -> UpstreamReading.HEALTHY
    else -> UpstreamReading.DRIFTED
}

/**
 * Tracks consecutive bad readings and decides when the session must end.
 *
 * Kept free of Android types so the tolerances are directly testable.
 */
class UpstreamTolerance(
    private val expectedInterface: String,
    private val onStrike: (String) -> Unit,
) {

    private var strikes = 0
    private var unknownChecks = 0
    private var lastReading = UpstreamReading.HEALTHY

    fun judge(reading: UpstreamReading, names: List<String>): String? {
        if (reading == UpstreamReading.UNKNOWN) {
            unknownChecks++
            if (unknownChecks == 1 || unknownChecks % 12 == 0) {
                onStrike("watchdog: upstream check inconclusive ($unknownChecks); monitoring without stopping")
            }
            // A timeout is neither proof of failure nor proof of recovery.
            // Keep prior observations until we have fresh, usable evidence.
            return null
        }
        if (unknownChecks > 0) {
            onStrike("watchdog: tethering checks responding after $unknownChecks inconclusive checks")
            unknownChecks = 0
        }
        if (reading == UpstreamReading.HEALTHY) {
            strikes = 0
            lastReading = reading
            return null
        }

        if (reading != lastReading) strikes = 0
        lastReading = reading
        strikes++

        val allowed = allowedStrikes(reading)
        if (strikes < allowed) {
            onStrike("watchdog strike $strikes/$allowed: ${describe(reading, names)}")
            return null
        }
        return describe(reading, names)
    }

    private fun allowedStrikes(reading: UpstreamReading): Int = when (reading) {
        UpstreamReading.ABSENT -> ABSENT_STRIKES
        else -> DRIFT_STRIKES
    }

    private fun describe(reading: UpstreamReading, names: List<String>): String = when (reading) {
        UpstreamReading.DOWNSTREAM_STOPPED -> "downstream reported stopped for $strikes checks"
        UpstreamReading.ABSENT ->
            "upstream reported no interface for $strikes checks, expected $expectedInterface"

        else -> "upstream drifted from $expectedInterface to $names"
    }

    private companion object {

        const val DRIFT_STRIKES = 2

        const val ABSENT_STRIKES = 12
    }
}
