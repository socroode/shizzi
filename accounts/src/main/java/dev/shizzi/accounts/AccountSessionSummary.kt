package dev.shizzi.accounts

import org.json.JSONObject

internal data class AccountSession(
    val ip: String,
    val mac: String,
    val presence: String,
    val internetAuthorized: Boolean?,
) {
    val internetLabel: String get() = when (internetAuthorized) {
        true -> "Internet autorisé"
        false -> "Internet bloqué"
        null -> "Accès Internet non renseigné"
    }
    val presenceLabel: String get() = when (presence) {
        "online" -> "Wi-Fi confirmé"
        "missing" -> "Appareil absent du Wi-Fi"
        else -> "Wi-Fi non vérifiable"
    }
}

/** One account card owns its session information; IP/MAC are never used to guess an account. */
internal data class AccountSessionSummary(
    val connection: AccountConnectionStatus,
    val sessions: List<AccountSession>,
) {
    val headingLabel: String get() = when (connection) {
        AccountConnectionStatus.CHECKING -> "Session enregistrée"
        else -> connection.label
    }

    val information: String get() {
        if (connection == AccountConnectionStatus.UNAVAILABLE) return "État des sessions indisponible"
        if (sessions.isEmpty()) return "Aucune session enregistrée"
        val permissions = sessions.map { it.internetAuthorized }.toSet()
        val internet = if (permissions.size == 1) sessions.first().internetLabel else "Autorisations Internet variables selon l'appareil"
        val presence = when (connection) {
            AccountConnectionStatus.CONNECTED -> "Wi-Fi confirmé"
            AccountConnectionStatus.DISCONNECTED -> "Appareil absent du Wi-Fi"
            else -> "Wi-Fi non vérifiable"
        }
        return "$internet · $presence"
    }

    val details: String get() = sessions.mapIndexed { index, session ->
        (if (sessions.size > 1) "Appareil ${index + 1}\n" else "") +
            "IP : " + session.ip.ifBlank { "non renseignée" } +
            "\nMAC : " + session.mac.ifBlank { "non renseignée" } +
            "\n" + session.internetLabel + "\n" + session.presenceLabel
    }.joinToString("\n\n")

    companion object {
        fun fromTraffic(number: String, traffic: JSONObject?): AccountSessionSummary {
            val status = AccountConnectionStatus.fromTraffic(number, traffic)
            val array = traffic?.optJSONArray("portalAuthorizations")
            val sessions = if (number.isBlank() || array == null) emptyList() else (0 until array.length()).mapNotNull {
                val item = array.optJSONObject(it) ?: return@mapNotNull null
                if (item.optString("accountNumber") != number) return@mapNotNull null
                AccountSession(
                    ip = item.optString("ip"),
                    mac = item.optString("mac"),
                    presence = item.optString("presence"),
                    internetAuthorized = if (item.has("authorized") && !item.isNull("authorized")) item.optBoolean("authorized") else null,
                )
            }
            return AccountSessionSummary(status, sessions)
        }
    }
}
