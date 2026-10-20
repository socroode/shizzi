package dev.shizzi.accounts

internal object AdminRefreshPolicy {
    const val INTERVAL_MS = 5_000L
    const val LIVE_INTERVAL_MS = 1_000L

    fun interval(accountsOnly: Boolean): Long = if (accountsOnly) LIVE_INTERVAL_MS else INTERVAL_MS

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
