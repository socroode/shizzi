package dev.shizzi.accounts

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RouterBatteryLabelTest {
    private fun response(available: Boolean, percent: Any?, charging: Boolean): JSONObject {
        val battery = JSONObject().put("available", available)
            .put("charging", charging).put("percent", percent)
        return JSONObject().put("state", JSONObject().put("battery", battery))
    }

    @Test fun chargingAndDischargingBatteryFromRouter() {
        assertEquals("Batterie du routeur : 78 % · En charge",
            RouterBatteryLabel.fromResponse(response(true, 78, true)))
        assertEquals("Batterie du routeur : 42 % · Sur batterie",
            RouterBatteryLabel.fromResponse(response(true, 42, false)))
    }

    @Test fun unavailableOnOldRouterOrInvalidPercent() {
        assertEquals(RouterBatteryLabel.UNAVAILABLE,
            RouterBatteryLabel.fromResponse(JSONObject().put("state", JSONObject())))
        assertEquals(RouterBatteryLabel.UNAVAILABLE,
            RouterBatteryLabel.fromResponse(response(false, 83, false)))
        assertEquals(RouterBatteryLabel.UNAVAILABLE,
            RouterBatteryLabel.fromResponse(response(true, -1, true)))
        assertEquals(RouterBatteryLabel.UNAVAILABLE,
            RouterBatteryLabel.fromResponse(response(true, 101, true)))
        assertEquals(RouterBatteryLabel.UNAVAILABLE,
            RouterBatteryLabel.fromResponse(response(true, "wrong", true)))
    }

    @Test fun connectionFailureCannotShowStaleChargeLevel() {
        assertEquals("Batterie du routeur : connexion indisponible",
            RouterBatteryLabel.fromResponse(null))
    }
}
