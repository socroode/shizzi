package dev.shizzi

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class SessionWatchdog(
    private val expectedInterface: String,
    private val onRecover: (String) -> Boolean,
    private val onDrift: (String) -> Unit,
) {

    private val inspector = UpstreamInspector()
    private val isRunning = AtomicBoolean(false)
    private var thread: Thread? = null

    private val tolerance = UpstreamTolerance(expectedInterface) { strike ->
        Log.i(TAG, strike)
        SessionLog.warn(strike)
    }

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return

        thread = Thread({ monitor() }, "session-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        isRunning.set(false)

        val watcher = thread
        thread = null
        if (watcher != null && watcher != Thread.currentThread()) watcher.interrupt()
    }

    private fun monitor() {
        while (isRunning.get()) {
            val didSleep = runCatching { Thread.sleep(POLL_INTERVAL_MS) }.isSuccess
            if (!didSleep || !isRunning.get()) return

            val check = checkUpstream()
            if (check.supersededBy != null) {
                isRunning.set(false)
                val message =
                    "watchdog retired: $expectedInterface superseded by ${check.supersededBy}"
                Log.i(TAG, message)
                SessionLog.info(message)
                return
            }

            val problem = check.problem
            if (problem != null) {
                Log.w(TAG, "upstream problem: $problem; attempting recovery")

                val recovered = runCatching { onRecover(problem) }
                    .getOrElse { failure ->
                        Log.e(TAG, "recovery callback failed: ${failure.message}", failure)
                        false
                    }

                // stop() may have interrupted a recovery callback. Do not
                // dispatch a teardown after this watchdog was cancelled.
                if (!isRunning.get()) return

                if (recovered) {
                    tolerance.reset()
                    Log.i(TAG, "recovery succeeded for $expectedInterface")
                    SessionLog.info("watchdog recovery succeeded: $expectedInterface restored")
                    continue
                }

                isRunning.set(false)
                Log.w(TAG, "recovery failed; tearing down: $problem")
                onDrift(problem)
                return
            }
        }
    }

    private fun checkUpstream(): WatchdogCheck {
        val observation = inspector.observe()
        val names = observation.liveInterfaceNames(expectedInterface)
        val supersededBy = newerShizziInterface(expectedInterface, names)
        if (supersededBy != null) {
            return WatchdogCheck(problem = null, supersededBy = supersededBy)
        }

        val reading = classifyUpstream(names, expectedInterface, observation.didTimeout)
        return WatchdogCheck(
            problem = tolerance.judge(reading, names),
            supersededBy = null,
        )
    }

    private companion object {
        const val TAG = "SessionWatchdog"
        const val POLL_INTERVAL_MS = 5_000L
    }
}

internal data class WatchdogCheck(
    val problem: String?,
    val supersededBy: String?,
)

internal fun newerShizziInterface(
    expectedInterface: String,
    observedInterfaces: List<String>,
): String? {
    val expectedOrdinal = shizziInterfaceOrdinal(expectedInterface) ?: return null
    return observedInterfaces
        .mapNotNull { name ->
            val ordinal = shizziInterfaceOrdinal(name) ?: return@mapNotNull null
            if (ordinal > expectedOrdinal) ordinal to name else null
        }
        .maxByOrNull { it.first }
        ?.second
}

private fun shizziInterfaceOrdinal(name: String): Int? {
    if (!name.startsWith("testtun")) return null
    return name.removePrefix("testtun").toIntOrNull()
}
