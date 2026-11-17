package dev.shizzi

/** A replacement may only start once its predecessor is confirmed stopped. */
internal suspend fun <T> runHotspotRecoveryAttempt(
    stillWanted: () -> Boolean,
    confirmStopped: suspend () -> Unit,
    start: suspend () -> T,
): T? {
    if (!stillWanted()) return null
    confirmStopped()
    if (!stillWanted()) return null
    return start()
}
