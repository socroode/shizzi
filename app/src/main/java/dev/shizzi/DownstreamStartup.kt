package dev.shizzi

data class DownstreamStartAttempt(
    val accepted: Boolean,
    val detail: String,
)

data class DownstreamStartOutcome(
    val success: Boolean,
    val detail: String,
    val attempts: Int,
)

/**
 * Starts the Wi-Fi hotspot and verifies that Android actually exposes a
 * tethered downstream. A callback saying "started" is not enough on every OEM:
 * the real downstream interface must also appear.
 */
fun startDownstreamWith(
    start: () -> DownstreamStartAttempt,
    isTethered: () -> Boolean,
    onRetry: (String) -> Unit = {},
): DownstreamStartOutcome {
    var lastDetail = "hotspot not started"

    for (attempt in 1..DOWNSTREAM_START_ATTEMPTS) {
        val started = start()
        lastDetail = started.detail

        if (isTethered()) {
            return DownstreamStartOutcome(
                success = true,
                detail = if (started.accepted) started.detail else "downstream became tethered",
                attempts = attempt,
            )
        }

        if (attempt < DOWNSTREAM_START_ATTEMPTS) {
            onRetry(
                "hotspot start attempt $attempt/$DOWNSTREAM_START_ATTEMPTS " +
                    "did not expose a tethered downstream (${started.detail}); retrying",
            )
        }
    }

    return DownstreamStartOutcome(
        success = false,
        detail = lastDetail,
        attempts = DOWNSTREAM_START_ATTEMPTS,
    )
}

const val DOWNSTREAM_START_ATTEMPTS = 3
