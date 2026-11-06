package dev.shizzi.admin

import dev.shizzi.history.RechargeHistory
import dev.shizzi.history.RechargeHistory.Kind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RechargeHistoryTest {
    private fun coupon(code: String, kind: String = "DATA", time: Long = 100L, account: String = "1001") =
        JSONObject().put("code", code).put("offerId", "offer").put("redeemedByAccount", account)
            .put("redeemedAtMillis", time).put("createdAtMillis", 1L).put("snapshotVersion", 1)
            .put("snapshotKind", kind).put("snapshotName", "Forfait initial")
            .put("snapshotQuotaBytes", 12_000_000_000L).put("snapshotDurationDays", 30)
            .put("snapshotDurationMinutes", 90L).put("snapshotPriceXpf", 1_000)
            .put("snapshotDownloadBps", 2_000_000L).put("snapshotUploadBps", 1_000_000L)

    private fun state(internet: List<JSONObject> = emptyList(), media: List<JSONObject> = emptyList()) =
        JSONObject().put("vouchers", JSONArray(internet)).put("mediaVouchers", JSONArray(media))

    @Test fun combinesOnlyThisAccountsRedeemedDataUnlimitedAndMediaCoupons() {
        val source = state(listOf(
            coupon("DATA"), coupon("ILLIM", "UNLIMITED", 300L),
            coupon("AUTRE", account = "1002"), coupon("INUTILISE", account = ""),
        ), listOf(coupon("MEDIA", time = 200L), coupon("AUTRE-MEDIA", account = "1002")))
        val history = RechargeHistory.read(" 1001 ", source)
        assertEquals(listOf("ILLIM", "MEDIA", "DATA"), history.entries.map { it.code })
        assertEquals(listOf(Kind.UNLIMITED, Kind.MEDIA, Kind.DATA), history.entries.map { it.kind })
        assertTrue(history.internetAvailable && history.mediaAvailable)
    }

    @Test fun chronologyUsesRedemptionTimeRatherThanCouponCreationTime() {
        val older = coupon("ANCIEN", time = 100L).put("createdAtMillis", 9_999L)
        val newer = coupon("RECENT", time = 500L).put("createdAtMillis", 1L)
        val undated = coupon("SANS-DATE", time = 0L).put("createdAtMillis", 99_999L)
        val history = RechargeHistory.read("1001", state(listOf(older, undated, newer)))
        assertEquals(listOf("RECENT", "ANCIEN", "SANS-DATE"), history.entries.map { it.code })
        assertNull(history.entries.last().redeemedAtMillis)
    }

    @Test fun originalOfferDetailsSurviveCurrentOfferEditsAndDeletion() {
        val source = state(listOf(coupon("A")))
            .put("offers", JSONArray().put(JSONObject().put("id", "offer")
                .put("name", "Nouveau nom").put("kind", "UNLIMITED").put("priceXpf", 9_999)))
        val entry = RechargeHistory.read("1001", source).entries.single()
        assertEquals("Forfait initial", entry.name)
        assertEquals(Kind.DATA, entry.kind)
        assertEquals(1_000L, entry.priceXpf)
        assertEquals(12_000_000_000L, entry.quotaBytes)
        source.remove("offers")
        assertEquals(entry, RechargeHistory.read("1001", source).entries.single())
    }

    @Test fun displaysCodeDateAddedVolumeDurationRatesAndRecordedTariff() {
        val history = RechargeHistory.read("1001", state(
            listOf(coupon("DATA"), coupon("ILLIM", "UNLIMITED")), listOf(coupon("MEDIA"))))
        val data = history.entries.first { it.kind == Kind.DATA }.format { "date=$it" }
        assertTrue(data.contains("Rechargé le date=100"))
        assertTrue(data.contains("Coupon : DATA"))
        assertTrue(data.contains("Volume ajouté : 12 Go"))
        assertTrue(data.contains("Durée du coupon : 30 jour(s)"))
        assertTrue(data.contains("↓ 2 Mbit/s · ↑ 1 Mbit/s"))
        assertTrue(data.contains("Tarif du coupon :"))
        assertTrue(data.contains("F CFP"))
        assertFalse(data.contains("payé"))
        val unlimited = history.entries.first { it.kind == Kind.UNLIMITED }.format { "$it" }
        assertTrue(unlimited.contains("Accès Internet illimité"))
        assertFalse(unlimited.contains("Volume ajouté"))
        val media = history.entries.first { it.kind == Kind.MEDIA }.format { "$it" }
        assertTrue(media.contains("90 minute(s)"))
        assertFalse(media.contains("Débit :"))
    }

    @Test fun legacyRechargesNeverClaimThatCurrentOfferDetailsWereAppliedInThePast() {
        val source = state(listOf(coupon("ANCIEN").put("snapshotVersion", 0)))
            .put("offers", JSONArray().put(JSONObject().put("id", "offer")
                .put("name", "Nom actuel").put("kind", "UNLIMITED").put("priceXpf", 9_999)))
        val entry = RechargeHistory.read("1001", source).entries.single()
        assertEquals(Kind.INTERNET, entry.kind)
        assertFalse(entry.originalDetails)
        assertNull(entry.quotaBytes)
        assertNull(entry.priceXpf)
        val text = entry.format { "$it" }
        assertTrue(text.contains("Détails d'origine non enregistrés"))
        assertTrue(text.contains("Nom actuel de l'offre : Nom actuel"))
        assertTrue(text.contains("Tarif d'origine non renseigné"))
        assertFalse(text.contains("Volume ajouté"))
    }

    @Test fun missingRedemptionDateIsNotReplacedByACreationDate() {
        val record = coupon("DATE-INCONNUE").apply { remove("redeemedAtMillis") }
        val entry = RechargeHistory.read("1001", state(listOf(record))).entries.single()
        assertNull(entry.redeemedAtMillis)
        assertTrue(entry.format { error("No date may be invented") }.contains("Date de recharge non renseignée"))
    }

    @Test fun olderRoutersClearlyReportMissingHistorySources() {
        val old = JSONObject().put("vouchers", JSONArray().put(coupon("A")))
        val history = RechargeHistory.read("1001", old)
        assertTrue(history.internetAvailable)
        assertFalse(history.mediaAvailable)
        assertTrue(history.summary.contains("Historique Media non fourni"))
        val missing = RechargeHistory.read("1001", null)
        assertTrue(missing.entries.isEmpty())
        assertEquals("Historique non fourni par ce Shizzi.", missing.summary)
    }

    @Test fun emptyHistoryAndBlankAccountNeverShowOtherAccountsCoupons() {
        val empty = RechargeHistory.read("1001", state())
        assertTrue(empty.summary.contains("Aucune recharge enregistrée"))
        assertTrue(empty.page(0).isEmpty())
        assertTrue(RechargeHistory.read("", state(listOf(coupon("PAS-UTILISE", account = "")))).entries.isEmpty())
    }

    @Test fun recordedFreeTariffIsDistinctFromMissingHistoricalAmounts() {
        val free = coupon("GRATUIT").put("snapshotPriceXpf", 0L).put("snapshotDurationDays", 0L)
            .put("snapshotUploadBps", 0L)
        val missing = coupon("MANQUANT").apply {
            remove("snapshotPriceXpf"); remove("snapshotDurationDays"); remove("snapshotQuotaBytes")
        }
        val history = RechargeHistory.read("1001", state(listOf(free, missing)))
        val freeText = history.entries.first { it.code == "GRATUIT" }.format { "$it" }
        assertTrue(freeText.contains("Tarif du coupon : 0 F CFP"))
        assertTrue(freeText.contains("Validité : sans échéance"))
        assertTrue(freeText.contains("↑ sans plafond individuel"))
        val missingEntry = history.entries.first { it.code == "MANQUANT" }
        assertNull(missingEntry.priceXpf)
        assertNull(missingEntry.quotaBytes)
        assertTrue(missingEntry.format { "$it" }.contains("Tarif d'origine non renseigné"))
    }

    @Test fun everyOneOf121RechargesRemainsAccessibleAcrossAllPages() {
        val history = RechargeHistory.read("1001", state((1..121).map { coupon("CODE-$it", time = it.toLong()) }))
        assertEquals(121, history.entries.size)
        assertEquals(7, history.pageCount)
        val paged = (0 until history.pageCount).flatMap(history::page)
        assertEquals(history.entries, paged)
        assertEquals(121, paged.map { it.code }.toSet().size)
        assertEquals(history.page(0), history.page(-1))
        assertEquals(history.page(6), history.page(100))
    }

    @Test fun alreadyUsedDisabledCouponsRemainInHistoryAndTiesAreStable() {
        val history = RechargeHistory.read("1001", state(listOf(
            coupon("B").put("enabled", false), coupon("A"),
        )))
        assertEquals(listOf("A", "B"), history.entries.map { it.code })
    }
}
