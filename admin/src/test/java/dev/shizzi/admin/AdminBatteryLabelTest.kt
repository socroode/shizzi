package dev.shizzi.admin

import org.junit.Assert.assertEquals
import org.junit.Test

class AdminBatteryLabelTest {

    @Test
    fun formatsChargingAndBatteryOnlyStates() {
        assertEquals(
            "Batterie du routeur : 78 % · En charge",
            AdminBatteryLabel.format(true, 78, true),
        )
        assertEquals(
            "Batterie du routeur : 42 % · Sur batterie",
            AdminBatteryLabel.format(true, 42, false),
        )
    }

    @Test
    fun formatsUnavailableBatterySafely() {
        assertEquals(
            "Batterie du routeur : indisponible",
            AdminBatteryLabel.format(false, -1, false),
        )
    }
}
