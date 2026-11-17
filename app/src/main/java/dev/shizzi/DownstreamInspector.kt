package dev.shizzi

class DownstreamInspector(private val inspector: UpstreamInspector = UpstreamInspector()) {

    private var cachedCount = 0
    private var lastReadAt = 0L

    private var loggedCount = 0

    fun findTetheredDownstream(): String? {
        val observation = inspector.observe()
        val tethered = observation.tetheredDownstreams()
            ?: error("dumpsys tethering inconclusive; downstream state unknown")

        return when {
            tethered.isEmpty() -> null
            else -> "downstream still tethered: $tethered"
        }
    }

    fun countDevices(): Int {
        val now = System.currentTimeMillis()
        if (now - lastReadAt < REFRESH_INTERVAL_MS) return cachedCount

        val observation = runCatching { inspector.observeWifi() }.getOrNull()
        if (observation?.isUsable != true) return cachedCount

        lastReadAt = now
        cachedCount = parseDeviceCount(observation.rawOutput)
        logCountChange(cachedCount)
        return cachedCount
    }

    private fun logCountChange(count: Int) {
        if (count == loggedCount) return

        val direction = if (count > loggedCount) "connected" else "disconnected"
        loggedCount = count
        SessionLog.info("client $direction: $count now on the hotspot")
    }

    private fun parseDeviceCount(output: String): Int =
        CONNECTED_CLIENTS_PATTERN.findAll(output)
            .mapNotNull { match -> match.groupValues[1].toIntOrNull() }
            .maxOrNull()
            ?: 0

    private companion object {

        private val CONNECTED_CLIENTS_PATTERN =
            Regex("""getConnectedClientList\(\)\.size\(\):\s*(\d+)""")

        private const val REFRESH_INTERVAL_MS = 10_000L
    }
}

/** Null means we could not prove the downstream state, not that it is down. */
internal fun UpstreamObservation.tetheredDownstreams(): List<String>? {
    if (!isUsable) return null
    val lines = rawOutput.lineSequence().toList()
    val tethered = lines.mapNotNull { TETHERED_STATE_PATTERN.find(it)?.groupValues?.get(1) }.distinct()
    if (tethered.isNotEmpty()) return tethered
    val start = lines.indexOfFirst { it.trim().equals("Tether state:", ignoreCase = true) }
    if (start < 0) return null
    val indent = lines[start].takeWhile(Char::isWhitespace).length
    val section = lines.drop(start + 1).takeWhile {
        it.isBlank() || it.takeWhile(Char::isWhitespace).length > indent
    }
    // Require a subsequent, recognized field so a truncated dump cannot
    // masquerade as a valid report with no active downstream.
    val footer = section.indexOfFirst { it.trimStart().startsWith("Upstream wanted:", ignoreCase = true) }
    if (footer < 0 || section.take(footer).any { it.isNotBlank() && !INACTIVE_DOWNSTREAM.matches(it) }) return null
    return emptyList()
}

private val TETHERED_STATE_PATTERN = Regex("""^\s*([\w.-]+)\s+-\s+TetheredState\s+-""")
private val INACTIVE_DOWNSTREAM = Regex("""\s*[\w.-]+\s+-\s+(AvailableState|UnavailableState|LocalHotspotState)\s+-.*""")
