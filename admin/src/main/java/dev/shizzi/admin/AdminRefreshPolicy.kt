package dev.shizzi.admin

internal object AdminRefreshPolicy {
    const val INTERVAL_MS = 5_000L

    fun shouldPoll(
        activityResumed: Boolean,
        hasToken: Boolean,
        hasWindowFocus: Boolean,
        refreshInFlight: Boolean,
    ): Boolean =
        activityResumed &&
            hasToken &&
            hasWindowFocus &&
            !refreshInFlight
}
