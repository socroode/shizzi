package dev.shizzi

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterBatteryStateTest {

    @Test
    fun convertsAndroidBatteryLevelToPercentAndChargingState() {
        val charging = RouterBatteryReader.fromRaw(
            level = 39,
            scale = 50,
            status = BatteryManager.BATTERY_STATUS_CHARGING,
        )
        assertTrue(charging.available)
        assertEquals(78, charging.percent)
        assertTrue(charging.charging)

        val unplugged = RouterBatteryReader.fromRaw(
            level = 64,
            scale = 100,
            status = BatteryManager.BATTERY_STATUS_DISCHARGING,
        )
        assertTrue(unplugged.available)
        assertEquals(64, unplugged.percent)
        assertFalse(unplugged.charging)
    }

    @Test
    fun invalidBatteryReadingIsReportedUnavailable() {
        val state = RouterBatteryReader.fromRaw(
            level = -1,
            scale = -1,
            status = BatteryManager.BATTERY_STATUS_UNKNOWN,
        )
        assertFalse(state.available)
        assertEquals(-1, state.percent)
    }
}
