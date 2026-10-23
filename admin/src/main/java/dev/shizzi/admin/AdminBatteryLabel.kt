package dev.shizzi.admin

internal object AdminBatteryLabel {
    fun format(available: Boolean, percent: Int, charging: Boolean): String {
        if (!available || percent !in 0..100) {
            return "Batterie du routeur : indisponible"
        }
        val state = if (charging) "En charge" else "Sur batterie"
        return "Batterie du routeur : $percent % · $state"
    }
}
