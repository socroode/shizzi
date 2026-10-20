package dev.shizzi.accounts

import org.json.JSONObject

internal enum class AccountConnectionStatus(val label: String) {
    CONNECTED("Connecté"),
    DISCONNECTED("Déconnecté"),
    CHECKING("À vérifier"),
    UNAVAILABLE("État indisponible");

    companion object {
        fun fromTraffic(accountNumber: String, traffic: JSONObject?): AccountConnectionStatus {
            if (accountNumber.isBlank() || traffic == null) return UNAVAILABLE
            val sessions = traffic.optJSONArray("portalAuthorizations")
            // Go omits the empty session array. portalRequired identifies a valid traffic snapshot.
            if (sessions == null) return if (traffic.has("portalRequired")) DISCONNECTED else UNAVAILABLE
            var uncertain = false
            for (index in 0 until sessions.length()) {
                val session = sessions.optJSONObject(index) ?: continue
                if (session.optString("accountNumber") != accountNumber) continue
                when (session.optString("presence")) {
                    "online" -> return CONNECTED
                    "missing" -> Unit
                    else -> uncertain = true
                }
            }
            return if (uncertain) CHECKING else DISCONNECTED
        }
    }
}
