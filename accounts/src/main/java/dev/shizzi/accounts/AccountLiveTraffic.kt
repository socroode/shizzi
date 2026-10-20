package dev.shizzi.accounts

import java.util.Locale
import org.json.JSONObject

internal data class AccountLiveRate(val downloadMbps: Double, val uploadMbps: Double) {
    fun label(): String = String.format(Locale.FRANCE,
        "Débit mesuré : ↓ %.2f Mbit/s · ↑ %.2f Mbit/s", downloadMbps, uploadMbps)
}

/** Differences of byte counters, never the configured downloadBps/uploadBps limits. */
internal class AccountLiveTraffic {
    private data class Counter(val source: String, val down: Long, val up: Long)
    private data class Sample(val counter: Counter, val time: Long)
    private val samples = mutableMapOf<String, Sample>()

    fun clear() = samples.clear()

    fun sample(traffic: JSONObject?, numbers: Set<String>, elapsedMillis: Long): Map<String, AccountLiveRate?> {
        samples.keys.retainAll(numbers)
        return numbers.associateWith { number ->
            val counter = traffic?.let { counter(it, number) }
            val previous = samples.remove(number)
            if (counter == null) return@associateWith null
            samples[number] = Sample(counter, elapsedMillis)
            val duration = previous?.let { elapsedMillis - it.time } ?: 0L
            if (previous == null || duration !in 1L..10_000L ||
                counter.source != previous.counter.source ||
                counter.down < previous.counter.down || counter.up < previous.counter.up) return@associateWith null
            // Convert bytes to bits, then milliseconds to seconds, then to decimal Mbit/s.
            AccountLiveRate((counter.down - previous.counter.down).toDouble() * 0.008 / duration,
                (counter.up - previous.counter.up).toDouble() * 0.008 / duration)
        }
    }

    fun hasCounters(traffic: JSONObject?, number: String): Boolean = traffic?.let { counter(it, number) } != null

    private fun counter(traffic: JSONObject, number: String): Counter? {
        val epoch = traffic.optLong("epoch")
        val usage = traffic.optJSONArray("accountUsage")
        if (usage != null) {
            val matches = (0 until usage.length()).mapNotNull { usage.optJSONObject(it) }
                .filter { it.optString("accountNumber") == number }
            if (matches.size == 1) {
                val item = matches.single()
                return bytes(item)?.let { Counter("usage:$epoch", it.first, it.second) }
            }
            if (matches.size > 1) return null
        }
        val sessions = traffic.optJSONArray("portalAuthorizations")
        val matches = if (sessions == null) emptyList() else (0 until sessions.length())
            .mapNotNull { sessions.optJSONObject(it) }.filter { it.optString("accountNumber") == number }
        if (matches.isEmpty()) {
            // Recent routers omit an empty authorization array. Do not assume this for unknown protocols.
            return if (traffic.optBoolean("portalRequired")) Counter("idle:$epoch", 0L, 0L) else null
        }
        var down = 0L
        var up = 0L
        val identities = mutableListOf<String>()
        for (session in matches) {
            val pair = bytes(session) ?: return null
            if (pair.first > Long.MAX_VALUE - down || pair.second > Long.MAX_VALUE - up) return null
            down += pair.first
            up += pair.second
            identities += session.optString("ip") + "/" + session.optLong("startedAtMillis")
        }
        return Counter("session:$epoch:" + identities.sorted().joinToString("|"), down, up)
    }

    private fun bytes(item: JSONObject): Pair<Long, Long>? {
        if (!item.has("downBytes") || !item.has("upBytes") || item.isNull("downBytes") || item.isNull("upBytes")) return null
        val down = item.optLong("downBytes", -1L)
        val up = item.optLong("upBytes", -1L)
        return if (down >= 0L && up >= 0L) down to up else null
    }
}
