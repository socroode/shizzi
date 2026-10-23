package dev.shizzi.accounts

import java.math.BigDecimal
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Read-only presentation of the router's balances and original redeemed voucher snapshots. */
internal object AccountPlanLabel {
    private data class Recharge(val kind: String, val name: String, val time: Long, val currentOfferName: Boolean) {
        val label: String get() {
            val type = when (kind) { "DATA" -> "Data"; "UNLIMITED" -> "Illimité"; else -> "Offre" }
            val value = if (name.isBlank()) type else if (
                (kind == "DATA" && name.contains("data", true)) ||
                (kind == "UNLIMITED" && name.contains("illimit", true))
            ) name else "$type · $name"
            return value + if (currentOfferName) " (nom actuel de l'offre)" else ""
        }
    }

    fun format(
        account: JSONObject?, offers: JSONArray?, vouchers: JSONArray?, nowMillis: Long,
        bytes: (Long) -> String, date: (Long) -> String,
    ): String {
        if (account == null) return "Forfait : information indisponible"
        val enabled = account.optBoolean("enabled", true)
        val hasUnlimited = account.has("unlimitedUntilMillis") && !account.isNull("unlimitedUntilMillis")
        val hasData = account.has("dataBalanceBytes") && !account.isNull("dataBalanceBytes")
        val hasDataDate = account.has("dataValidUntilMillis") && !account.isNull("dataValidUntilMillis")
        val unlimitedUntil = account.optLong("unlimitedUntilMillis")
        val dataUntil = account.optLong("dataValidUntilMillis")
        val balance = account.optLong("dataBalanceBytes").coerceAtLeast(0)
        val unlimited = hasUnlimited && unlimitedUntil > nowMillis
        val data = hasData && balance > 0 && hasDataDate && (dataUntil <= 0 || dataUntil > nowMillis)
        val complete = hasUnlimited && hasData && (balance == 0L || hasDataDate)
        val number = account.optString("number")
        val history = history(number, offers, vouchers)
        val undated = number.isNotBlank() && vouchers != null && (0 until vouchers.length()).any {
            val item = vouchers.optJSONObject(it) ?: return@any false
            item.optString("redeemedByAccount") == number && item.optLong("redeemedAtMillis") <= 0
        }
        val last = history.maxByOrNull { it.time }
        val lastData = history.filter { it.kind == "DATA" }.maxByOrNull { it.time }
        val unlimitedName = account.optString("unlimitedPlanName").trim().let {
            if (it.isBlank()) "Illimité" else if (it.contains("illimit", true)) it else "Illimité · $it"
        }
        val lines = mutableListOf(when {
            !enabled -> "Forfait suspendu"
            unlimited -> "Forfait actif : $unlimitedName"
            data && hasUnlimited -> "Forfait actif : Data"
            !complete -> "Forfait : informations non renseignées par ce Shizzi"
            else -> "Forfait actif : aucun"
        })

        if (unlimited) {
            if (!enabled) lines += "Crédit conservé : $unlimitedName"
            lines += limits(account, "unlimited")
            lines += "Illimité valable jusqu'au " + date(unlimitedUntil)
        } else if (data && hasUnlimited) {
            lines += limits(account, "data")
            if (lastData != null && last?.kind != "DATA") {
                lines += (if (undated) "Dernière offre Data datée connue : " else "Dernière offre Data : ") + lastData.label
            }
        } else if (hasUnlimited && unlimitedUntil > 0) {
            lines += "Illimité terminé le " + date(unlimitedUntil)
        }

        if (hasData && balance > 0) {
            val prefix = when {
                !hasDataDate -> "Solde Data"
                !data -> "Data expirée"
                enabled && unlimited -> "Data en attente"
                enabled && hasUnlimited -> "Data restante"
                else -> "Solde Data"
            }
            val validity = when {
                !hasDataDate -> "validité non renseignée"
                dataUntil <= 0 -> "sans échéance"
                else -> "expiration : " + date(dataUntil)
            }
            lines += "$prefix : ${bytes(balance)} · $validity"
            if (enabled && unlimited && data && dataUntil > 0 && dataUntil <= unlimitedUntil) {
                lines += "Cette Data expire avant ou à la fin de l'Illimité."
            }
        }

        lines += when {
            last != null -> (if (undated) "Dernière recharge datée connue : " else "Dernière recharge : ") + last.label + " · " + date(last.time)
            undated -> "Dernière recharge : date non renseignée dans l'historique"
            vouchers == null -> "Dernière recharge : historique non fourni par ce Shizzi"
            else -> "Dernière recharge : aucune dans l'historique disponible"
        }
        return lines.joinToString("\n")
    }

    private fun limits(account: JSONObject, prefix: String): String {
        fun rate(key: String): String = when (val value = account.optLong(key, -1)) {
            -1L -> "non renseigné"
            0L -> "sans plafond individuel"
            else -> if (value < 0) "non renseigné" else BigDecimal.valueOf(value)
                .divide(BigDecimal.valueOf(1_000_000)).stripTrailingZeros().toPlainString().replace('.', ',') + " Mbit/s"
        }
        return "Plafonds : ↓ ${rate(prefix + "DownloadBps")} · ↑ ${rate(prefix + "UploadBps")}"
    }

    private fun history(number: String, offers: JSONArray?, vouchers: JSONArray?): List<Recharge> {
        if (number.isBlank() || vouchers == null) return emptyList()
        return (0 until vouchers.length()).mapNotNull { index ->
            val voucher = vouchers.optJSONObject(index) ?: return@mapNotNull null
            if (voucher.optString("redeemedByAccount") != number) return@mapNotNull null
            val time = voucher.optLong("redeemedAtMillis")
            if (time <= 0) return@mapNotNull null
            val snapshot = voucher.optInt("snapshotVersion") >= 1
            val offer = if (snapshot || offers == null) null else (0 until offers.length())
                .mapNotNull { offers.optJSONObject(it) }.firstOrNull { it.optString("id") == voucher.optString("offerId") }
            Recharge(
                kind = (if (snapshot) voucher.optString("snapshotKind") else offer?.optString("kind").orEmpty()).uppercase(Locale.ROOT),
                name = if (snapshot) voucher.optString("snapshotName") else offer?.optString("name").orEmpty(),
                time = time,
                currentOfferName = !snapshot && offer?.optString("name")?.isNotBlank() == true,
            )
        }
    }
}
