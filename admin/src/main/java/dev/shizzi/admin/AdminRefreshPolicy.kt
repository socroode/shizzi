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

    fun dashboardSignature(
        routerName: String,
        stateJson: String,
        portalAuthorizationsJson: String,
        mediaDiagnosticsJson: String,
    ): String =
        listOf(
            routerName,
            stateJson,
            portalAuthorizationsJson,
            mediaDiagnosticsJson,
        ).joinToString("\u001f")

    fun shouldRenderDashboard(
        manual: Boolean,
        hasWindowFocus: Boolean,
        currentSignature: String,
        lastRenderedSignature: String,
    ): Boolean =
        manual ||
            (hasWindowFocus && currentSignature != lastRenderedSignature)
}
