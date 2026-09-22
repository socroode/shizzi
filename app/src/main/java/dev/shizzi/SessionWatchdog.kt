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

            val problem = checkUpstream()
            if (problem != null) {
                Log.w(TAG, "upstream problem: $problem; attempting recovery")

                val recovered = runCatching { onRecover(problem) }
                    .getOrElse { failure ->
                        Log.e(TAG, "recovery callback failed: ${failure.message}", failure)
                        false
                    }

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

    private fun checkUpstream(): String? {
        val observation = inspector.observe()
        val names = observation.liveInterfaceNames(expectedInterface)
        val reading = classifyUpstream(names, expectedInterface, observation.didTimeout)

        return tolerance.judge(reading, names)
    }

    private companion object {
        const val TAG = "SessionWatchdog"
        const val POLL_INTERVAL_MS = 5_000L
    }
}
