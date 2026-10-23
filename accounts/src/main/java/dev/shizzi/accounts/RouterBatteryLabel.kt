package dev.shizzi.accounts

import org.json.JSONObject

/** Shows only the router battery reported by the authenticated Shizzi Admin API. */
internal object RouterBatteryLabel {
    const val UNAVAILABLE = "Batterie du routeur : indisponible"

    fun fromResponse(response: JSONObject?): String {
        if (response == null) return "Batterie du routeur : connexion indisponible"
        val battery = response.optJSONObject("state")?.optJSONObject("battery")
            ?: return UNAVAILABLE
        val percent = battery.optInt("percent", -1)
        if (!battery.optBoolean("available", false) || percent !in 0..100) {
            return UNAVAILABLE
        }
        val state = if (battery.optBoolean("charging", false)) "En charge" else "Sur batterie"
        return "Batterie du routeur : $percent % · $state"
    }
}
