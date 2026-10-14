package dev.shizzi

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.ContextWrapper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class AttributionClockTest {
    @Test
    fun clockIsPublishedWithoutStationCallbacksAndUpdatesOnTimezoneBroadcast() {
        val previous = TimeZone.getDefault()
        var receiver: BroadcastReceiver? = null
        var unregistered = false
        val context = object : ContextWrapper(null) {
            override fun getSystemService(name: String): Any? = null
            override fun registerReceiver(value: BroadcastReceiver?, filter: IntentFilter?): Intent? {
                receiver = value
                return null
            }
            override fun unregisterReceiver(value: BroadcastReceiver?) { unregistered = value === receiver }
        }
        val snapshots = mutableListOf<JSONObject>()
        val monitor = ClientPresenceMonitor(context) { snapshots += JSONObject(it) }
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Tahiti"))
            monitor.start()
            assertEquals("Pacific/Tahiti", snapshots.first().getString("androidTimezone"))
            assertEquals(-36000, snapshots.first().getInt("androidUtcOffsetSeconds"))
            assertFalse(snapshots.first().getBoolean("wifiKnown"))
            assertFalse(snapshots.first().getBoolean("leasesKnown"))
            assertNotNull(receiver)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kathmandu"))
            receiver!!.onReceive(context, null)
            assertEquals("Asia/Kathmandu", snapshots.last().getString("androidTimezone"))
            assertEquals(20700, snapshots.last().getInt("androidUtcOffsetSeconds"))
            monitor.stop()
            assertTrue(unregistered)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun clockAndPersistentFailureDiagnosticsReachHotspotUi() {
        val d = parseLiveTrafficSnapshot("""{"attribution":{
            "androidTimezone":"Pacific/Tahiti","androidTimezoneKnown":true,
            "androidTimezoneSource":"android-zone","bpfObservedFailures":3,
            "bpfRejectedTimestamps":1,"bpfClockSkipped":0,"dumpFailures":2,
            "lastDumpError":"timeout","lastDumpErrorMillis":123,
            "lastUnresolvedFlow":"tcp 192.0.2.2:1234 -> 192.0.2.1:80",
            "lastUnresolvedMillis":456
        }}""").attribution
        assertEquals("Pacific/Tahiti", d.androidTimezone)
        assertTrue(d.androidTimezoneKnown)
        assertEquals("android-zone", d.androidTimezoneSource)
        assertEquals(3, d.bpfObservedFailures)
        assertEquals(1, d.bpfRejectedTimestamps)
        assertEquals(0, d.bpfClockSkipped)
        assertEquals(2L, d.dumpFailures)
        assertEquals("timeout", d.lastDumpError)
        assertEquals(123L, d.lastDumpErrorMillis)
        assertEquals("tcp 192.0.2.2:1234 -> 192.0.2.1:80", d.lastUnresolvedFlow)
        assertEquals(456L, d.lastUnresolvedMillis)
        assertFalse(parseLiveTrafficSnapshot("{}").attribution.androidTimezoneKnown)
    }
}
