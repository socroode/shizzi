package dev.shizzi.history

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Read-only history built from redeemed coupons, never from the current account balance. */
object RechargeHistory {
    const val PAGE_SIZE = 20

    enum class Kind(val label: String) {
        DATA("DATA"), UNLIMITED("ILLIMITÉ"), MEDIA("MEDIA"), INTERNET("INTERNET"),
    }

    data class Entry(
        val code: String,
        val kind: Kind,
        val redeemedAtMillis: Long?,
        val originalDetails: Boolean,
        val name: String,
        val currentOfferName: String,
        val quotaBytes: Long?,
        val durationDays: Long?,
        val durationMinutes: Long?,
        val priceXpf: Long?,
        val downloadBps: Long?,
        val uploadBps: Long?,
    ) {
        fun format(date: (Long) -> String): String = buildList {
            add(kind.label + if (name.isNotBlank()) " · $name" else "")
            add(redeemedAtMillis?.let { "Rechargé le " + date(it) } ?: "Date de recharge non renseignée")
            add("Coupon : " + code.ifBlank { "non renseigné" })
            if (!originalDetails) {
                add("Détails d'origine non enregistrés")
                if (currentOfferName.isNotBlank()) add("Nom actuel de l'offre : $currentOfferName")
            }
            if (kind == Kind.DATA && quotaBytes != null) add("Volume ajouté : " + volume(quotaBytes))
            if (kind == Kind.UNLIMITED) add("Accès Internet illimité")
            if (durationDays != null) add(if (durationDays == 0L && kind == Kind.DATA) {
                "Validité : sans échéance"
            } else {
                "Durée du coupon : $durationDays jour(s)"
            })
            if (durationMinutes != null) add("Durée du coupon : " + duration(durationMinutes))
            if (downloadBps != null || uploadBps != null) {
                add("Débit : ↓ ${rate(downloadBps)} · ↑ ${rate(uploadBps)}")
            }
            add(if (priceXpf == null) "Tarif d'origine non renseigné" else {
                "Tarif du coupon : " + NumberFormat.getIntegerInstance(Locale.FRANCE).format(priceXpf) + " F CFP"
            })
        }.joinToString("\n")
    }

    data class Snapshot(
        val entries: List<Entry>,
        val internetAvailable: Boolean,
        val mediaAvailable: Boolean,
    ) {
        val pageCount: Int get() = entries.lastIndex.coerceAtLeast(0) / PAGE_SIZE + 1

        val summary: String get() = buildList {
            add(when {
                !internetAvailable && !mediaAvailable -> "Historique non fourni par ce Shizzi."
                entries.isEmpty() -> "Aucune recharge enregistrée pour ce compte."
                else -> "${entries.size} recharge(s) enregistrée(s). Plus récentes en premier."
            })
            if (!internetAvailable && mediaAvailable) add("Historique Internet non fourni par ce Shizzi.")
            if (!mediaAvailable && internetAvailable) add("Historique Media non fourni par ce Shizzi.")
        }.joinToString("\n")

        fun page(index: Int): List<Entry> {
            val start = index.coerceIn(0, pageCount - 1) * PAGE_SIZE
            return entries.subList(start, minOf(start + PAGE_SIZE, entries.size))
        }
    }

    fun read(accountNumber: String, state: JSONObject?): Snapshot {
        val number = accountNumber.trim().uppercase(Locale.ROOT)
        val internet = state?.optJSONArray("vouchers")
        val media = state?.optJSONArray("mediaVouchers")
        fun offers(key: String): Map<String, String> = state?.optJSONArray(key).objects()
            .associate { it.optString("id") to it.optString("name") }
        val internetNames = offers("offers")
        val mediaNames = offers("mediaOffers")
        fun entries(source: JSONArray?, isMedia: Boolean): List<Entry> = source.objects()
            .filter { number.isNotBlank() && it.optString("redeemedByAccount").trim().uppercase(Locale.ROOT) == number }
            .map { voucher ->
                val original = voucher.optInt("snapshotVersion") >= 1
                fun detail(key: String): Long? = if (original) voucher.amount(key) else null
                Entry(
                    code = voucher.optString("code"),
                    kind = if (isMedia) Kind.MEDIA else if (!original) Kind.INTERNET else {
                        when (voucher.optString("snapshotKind").uppercase(Locale.ROOT)) {
                            "DATA" -> Kind.DATA
                            "UNLIMITED" -> Kind.UNLIMITED
                            else -> Kind.INTERNET
                        }
                    },
                    redeemedAtMillis = voucher.amount("redeemedAtMillis")?.takeIf { it > 0L },
                    originalDetails = original,
                    name = if (original) voucher.optString("snapshotName") else "",
                    currentOfferName = if (original) "" else {
                        (if (isMedia) mediaNames else internetNames)[voucher.optString("offerId")].orEmpty()
                    },
                    quotaBytes = if (isMedia) null else detail("snapshotQuotaBytes"),
                    durationDays = if (isMedia) null else detail("snapshotDurationDays"),
                    durationMinutes = if (isMedia) detail("snapshotDurationMinutes") else null,
                    priceXpf = detail("snapshotPriceXpf"),
                    downloadBps = if (isMedia) null else detail("snapshotDownloadBps"),
                    uploadBps = if (isMedia) null else detail("snapshotUploadBps"),
                )
            }
        return Snapshot(
            entries = (entries(internet, false) + entries(media, true)).sortedWith(
                compareByDescending<Entry> { it.redeemedAtMillis ?: Long.MIN_VALUE }
                    .thenBy { it.code }.thenBy { it.kind.name },
            ),
            internetAvailable = internet != null,
            mediaAvailable = media != null,
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else {
        (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun JSONObject.amount(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key, -1L).takeIf { it >= 0L }

    private fun decimal(value: Long, divisor: Long): String = BigDecimal.valueOf(value)
        .divide(BigDecimal.valueOf(divisor)).stripTrailingZeros().toPlainString().replace('.', ',')

    private fun volume(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> decimal(bytes, 1_000_000_000L) + " Go"
        bytes >= 1_000_000L -> decimal(bytes, 1_000_000L) + " Mo"
        else -> "$bytes o"
    }

    private fun rate(bps: Long?): String = when (bps) {
        null -> "non renseigné"
        0L -> "sans plafond individuel"
        else -> decimal(bps, 1_000_000L) + " Mbit/s"
    }

    private fun duration(minutes: Long): String = when {
        minutes > 0L && minutes % 1440L == 0L -> "${minutes / 1440L} jour(s)"
        minutes > 0L && minutes % 60L == 0L -> "${minutes / 60L} heure(s)"
        else -> "$minutes minute(s)"
    }
}
