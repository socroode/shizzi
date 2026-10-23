package dev.shizzi.accounts

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountLiveTrafficTest {
    private fun usage(down: Long, up: Long, number: String = "1001") = JSONObject()
        .put("accountNumber", number).put("downBytes", down).put("upBytes", up)
    private fun traffic(down: Long, up: Long, epoch: Long = 1) = JSONObject().put("epoch", epoch)
        .put("accountUsage", JSONArray().put(usage(down, up)))

    @Test fun measuresBytesOverActualElapsedTimeRatherThanPlanLimits() {
        val sampler = AccountLiveTraffic()
        assertNull(sampler.sample(traffic(10L, 20L), setOf("1001"), 1000L)["1001"])
        val data = traffic(1_000_010L, 250_020L).put("downloadBps", 999_000_000L)
        val result = sampler.sample(data, setOf("1001"), 3000L).getValue("1001")!!
        assertEquals(4.0, result.downloadMbps, 0.0001)
        assertEquals(1.0, result.uploadMbps, 0.0001)
        assertEquals("Débit mesuré : ↓ 4,00 Mbit/s · ↑ 1,00 Mbit/s", result.label())
    }

    @Test fun sixAccountsNeverShareTheirCounters() {
        val sampler = AccountLiveTraffic()
        val numbers = (1..6).map { "100$it" }.toSet()
        val data = JSONObject().put("epoch", 1).put("accountUsage", JSONArray().apply {
            for (i in 1..6) put(usage(0, 0, "100$i"))
        })
        sampler.sample(data, numbers, 1000)
        data.put("accountUsage", JSONArray().apply {
            for (i in 6 downTo 1) put(usage(i * 125_000L, i * 62_500L, "100$i"))
        })
        val rates = sampler.sample(data, numbers, 2000)
        for (i in 1..6) {
            assertEquals(i.toDouble(), rates["100$i"]!!.downloadMbps, 0.0001)
            assertEquals(i * 0.5, rates["100$i"]!!.uploadMbps, 0.0001)
        }
    }

    @Test fun resetEpochAndFallingCountersRequireNewBaseline() {
        val sampler = AccountLiveTraffic()
        val ids = setOf("1001")
        sampler.sample(traffic(1_000_000, 500_000), ids, 1000)
        assertNull(sampler.sample(traffic(100, 50), ids, 2000)["1001"])
        assertNotNull(sampler.sample(traffic(200, 100), ids, 3000)["1001"])
        assertNull(sampler.sample(traffic(1_000_000, 500_000, epoch = 2), ids, 4000)["1001"])
    }

    @Test fun failurePauseAndLongGapsCannotReuseAnOldRate() {
        val sampler = AccountLiveTraffic()
        val ids = setOf("1001")
        sampler.sample(traffic(0, 0), ids, 1000)
        assertNotNull(sampler.sample(traffic(100, 50), ids, 2000)["1001"])
        assertNull(sampler.sample(null, ids, 3000)["1001"])
        assertNull(sampler.sample(traffic(200, 100), ids, 4000)["1001"])
        assertNull(sampler.sample(traffic(300, 150), ids, 30_000)["1001"])
        sampler.clear()
        assertNull(sampler.sample(traffic(400, 200), ids, 31_000)["1001"])
    }

    @Test fun missingInvalidOrDuplicateCountersAreUnavailableRatherThanZero() {
        val sampler = AccountLiveTraffic()
        val ids = setOf("1001")
        val missing = JSONObject().put("accountUsage", JSONArray().put(JSONObject().put("accountNumber", "1001")
            .put("downloadBps", 4_000_000).put("uploadBps", 2_000_000)))
        assertFalse(sampler.hasCounters(missing, "1001"))
        assertNull(sampler.sample(missing, ids, 1000)["1001"])
        assertFalse(sampler.hasCounters(traffic(-1, 0), "1001"))
        val duplicates = JSONObject().put("accountUsage", JSONArray().put(usage(1, 1)).put(usage(2, 2)))
        assertFalse(sampler.hasCounters(duplicates, "1001"))
    }

    @Test fun olderSessionCountersWorkAndAccountTransferStartsANewMeasurement() {
        val sampler = AccountLiveTraffic()
        val ids = setOf("1001")
        fun session(down: Long, start: Long) = JSONObject().put("portalAuthorizations", JSONArray().put(
            usage(down, 0).put("ip", "192.168.1.2").put("startedAtMillis", start)))
        sampler.sample(session(0, 100), ids, 1000)
        assertEquals(8.0, sampler.sample(session(1_000_000, 100), ids, 2000)["1001"]!!.downloadMbps, 0.001)
        assertNull(sampler.sample(session(2_000_000, 200), ids, 3000)["1001"])
    }

    @Test fun authoritativeIdleAccountsMeasureZeroAfterTwoSamples() {
        val sampler = AccountLiveTraffic()
        val ids = setOf("1001")
        val idle = JSONObject().put("portalRequired", true)
        sampler.sample(idle, ids, 1000)
        assertEquals(0.0, sampler.sample(idle, ids, 2000)["1001"]!!.downloadMbps, 0.0)
        assertFalse(sampler.hasCounters(JSONObject(), "1001"))
    }
}
