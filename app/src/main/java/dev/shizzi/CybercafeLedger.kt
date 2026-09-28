package dev.shizzi

/**
 * Per-account usage counters reported by the datapath. They are cumulative
 * since the datapath instance [epoch] started and shared by every session
 * (device) logged into the account.
 *
 * [dataBytes] only counts bytes carried while the account was in Data mode;
 * it is exactly what must be deducted from the Data balance. The datapath
 * decides the mode with its own clock, so Android never second-guesses it.
 */
data class LiveAccountUsage(
    val accountNumber: String,
    val upBytes: Long,
    val downBytes: Long,
    val dataBytes: Long,
)

data class UsageDelta(
    val accountNumber: String,
    val upBytes: Long,
    val downBytes: Long,
    val dataBytes: Long,
)

/**
 * What Android has already folded into an account's stored balance/totals,
 * expressed in the datapath's own counters. Sent back with every policy push
 * so the datapath computes the live balance as
 * `balance - (dataBytes - consumedMarkerBytes)` without Android re-pushing
 * the policy on every consumption tick.
 */
data class AccountMarker(
    val consumedMarkerBytes: Long,
    val usageMarkerBytes: Long,
)

/**
 * Turns cumulative datapath counters into deltas to apply to the durable
 * accounts, exactly once each.
 *
 * Not thread-safe by design: it must only be used from the single sync loop
 * that also pushes the policy, so that a pushed balance and its marker always
 * describe the same instant.
 */
class UsageLedger {

    var epoch: Long = 0L
        private set

    private val applied = mutableMapOf<String, LiveAccountUsage>()

    /** Returns true when the datapath instance changed (counters restarted). */
    fun absorb(epoch: Long, usage: List<LiveAccountUsage>): Pair<Boolean, List<UsageDelta>> {
        val restarted = epoch != this.epoch
        if (restarted) {
            this.epoch = epoch
            applied.clear()
        }

        val deltas = usage.mapNotNull { current ->
            val previous = applied[current.accountNumber]
            applied[current.accountNumber] = current
            // First sight of an account: its counters started at zero in this
            // datapath instance, so everything so far is new usage.
            current.toDelta(base = previous)
        }.filter { it.upBytes > 0L || it.downBytes > 0L || it.dataBytes > 0L }

        return restarted to deltas
    }

    fun markers(): Map<String, AccountMarker> =
        applied.mapValues { (_, usage) ->
            AccountMarker(
                consumedMarkerBytes = usage.dataBytes,
                usageMarkerBytes = usage.upBytes + usage.downBytes,
            )
        }

    private fun LiveAccountUsage.toDelta(base: LiveAccountUsage?): UsageDelta =
        UsageDelta(
            accountNumber = accountNumber,
            upBytes = (upBytes - (base?.upBytes ?: 0L)).coerceAtLeast(0L),
            downBytes = (downBytes - (base?.downBytes ?: 0L)).coerceAtLeast(0L),
            dataBytes = (dataBytes - (base?.dataBytes ?: 0L)).coerceAtLeast(0L),
        )
}

/**
 * Measures each session's real throughput from successive byte counters.
 * Keyed by ip + login time so a new login on a reused IP starts fresh.
 */
class SessionRateMeter {
    private data class Sample(val bytesUp: Long, val bytesDown: Long, val atMillis: Long)

    private val samples = mutableMapOf<String, Sample>()

    fun measure(
        key: String,
        upBytes: Long,
        downBytes: Long,
        nowMillis: Long,
    ): Pair<Long, Long> {
        val previous = samples.put(key, Sample(upBytes, downBytes, nowMillis))
            ?: return 0L to 0L
        val elapsed = nowMillis - previous.atMillis
        if (elapsed <= 0L) return 0L to 0L
        val up = (upBytes - previous.bytesUp).coerceAtLeast(0L) * 8_000L / elapsed
        val down = (downBytes - previous.bytesDown).coerceAtLeast(0L) * 8_000L / elapsed
        return up to down
    }

    fun retain(keys: Set<String>) {
        samples.keys.retainAll(keys)
    }
}

/** One device logged into an account, for Connected Devices / Account Editor. */
data class LiveSession(
    val ip: String,
    val mac: String,
    val accountNumber: String,
    val accountName: String,
    val plan: String,
    val authorized: Boolean,
    val limitDownloadBps: Long,
    val limitUploadBps: Long,
    val measuredDownloadBps: Long,
    val measuredUploadBps: Long,
    val sessionUpBytes: Long,
    val sessionDownBytes: Long,
    val startedAtMillis: Long,
)

fun PrepaidAccount.planLabel(nowMillis: Long): String = when {
    !enabled -> "Compte suspendu"
    hasUnlimited(nowMillis) -> unlimitedPlanName.ifBlank { "Illimité" }
    hasData(nowMillis) -> "Data"
    else -> "Aucun forfait actif"
}
