package dev.shizzi.accounts

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountPlanLabelTest {
    private val now = 1_800_000_000_000L
    private fun account() = JSONObject().put("number", "1001").put("enabled", true)
        .put("dataBalanceBytes", 0L).put("dataValidUntilMillis", 0L)
        .put("dataDownloadBps", 2_000_000L).put("dataUploadBps", 1_000_000L)
        .put("unlimitedUntilMillis", 0L).put("unlimitedDownloadBps", 4_000_000L)
        .put("unlimitedUploadBps", 2_000_000L).put("unlimitedPlanName", "Illimité Confort")
    private fun voucher(kind: String, name: String, time: Long = now - 1000) = JSONObject()
        .put("redeemedByAccount", "1001").put("redeemedAtMillis", time).put("createdAtMillis", now - 50_000)
        .put("snapshotVersion", 1).put("snapshotKind", kind).put("snapshotName", name).put("offerId", "offer")
    private fun label(a: JSONObject?, vouchers: JSONArray? = JSONArray(), offers: JSONArray? = null) =
        AccountPlanLabel.format(a, offers, vouchers, now, { "$it octets" }, { "date($it)" })

    @Test fun unlimitedWithZeroDataIsStillAnActiveNamedPlan() {
        val text = label(account().put("unlimitedUntilMillis", now + 5000))
        assertTrue(text.contains("Forfait actif : Illimité Confort"))
        assertTrue(text.contains("↓ 4 Mbit/s · ↑ 2 Mbit/s"))
        assertTrue(text.contains("date(${now + 5000})"))
        assertFalse(text.contains("Forfait actif : aucun"))
    }

    @Test fun activeDataShowsItsBalanceValidityAndRechargeName() {
        val a = account().put("dataBalanceBytes", 5_000_000_000L).put("dataValidUntilMillis", now + 5000)
        val text = label(a, JSONArray().put(voucher("DATA", "Data 12 Go")))
        assertTrue(text.contains("Forfait actif : Data"))
        assertTrue(text.contains("Data restante : 5000000000 octets"))
        assertTrue(text.contains("date(${now + 5000})"))
        assertTrue(text.contains("Dernière recharge : Data 12 Go"))
    }

    @Test fun aDataRechargeDoesNotReplaceTheStillActiveUnlimitedPlan() {
        val a = account().put("unlimitedUntilMillis", now + 1000)
            .put("dataBalanceBytes", 7_000_000_000L).put("dataValidUntilMillis", now + 5000)
        val text = label(a, JSONArray().put(voucher("DATA", "Data 20 Go")))
        assertTrue(text.contains("Forfait actif : Illimité Confort"))
        assertTrue(text.contains("Data en attente : 7000000000 octets"))
        assertTrue(text.contains("Dernière recharge : Data 20 Go"))
        assertFalse(text.contains("Forfait actif : Data"))
    }

    @Test fun expiredPositiveDataCannotBeShownAsUsableCredit() {
        val text = label(account().put("dataBalanceBytes", 1000).put("dataValidUntilMillis", now))
        assertTrue(text.contains("Forfait actif : aucun"))
        assertTrue(text.contains("Data expirée : 1000 octets"))
        assertFalse(text.contains("Data restante"))
        assertFalse(text.contains("Data en attente"))
    }

    @Test fun dataTakesOverAtTheUnlimitedExpiryBoundary() {
        val a = account().put("unlimitedUntilMillis", now).put("dataBalanceBytes", 1000)
            .put("dataValidUntilMillis", now + 2000)
        val history = JSONArray().put(voucher("DATA", "Data 12 Go", now - 5000))
            .put(voucher("UNLIMITED", "Illimité Éco", now - 1000))
        val text = label(a, history)
        assertTrue(text.contains("Forfait actif : Data"))
        assertTrue(text.contains("Dernière offre Data : Data 12 Go"))
        assertFalse(text.contains("Forfait actif : Illimité"))
    }

    @Test fun aZeroDataExpiryMeansNoDeadlineInsteadOfAnEpochDate() {
        val text = label(account().put("dataBalanceBytes", 1000))
        assertTrue(text.contains("Forfait actif : Data"))
        assertTrue(text.contains("sans échéance"))
        assertFalse(text.contains("date(0)"))
    }

    @Test fun suspensionKeepsCreditVisibleWithoutClaimingItIsActive() {
        val text = label(account().put("enabled", false).put("unlimitedUntilMillis", now + 1000))
        assertTrue(text.startsWith("Forfait suspendu"))
        assertTrue(text.contains("Crédit conservé : Illimité Confort"))
        assertFalse(text.contains("Forfait actif :"))
    }

    @Test fun missingLegacyFieldsRemainUnknownRatherThanInventingAPlan() {
        val text = label(JSONObject().put("number", "1001"), null)
        assertTrue(text.contains("informations non renseignées"))
        assertTrue(text.contains("historique non fourni"))
        assertFalse(text.contains("Forfait actif : aucun"))
        assertEquals("Forfait : information indisponible", label(null))
    }

    @Test fun lastRechargeUsesRedemptionTimeAndTheExactAccount() {
        val old = voucher("DATA", "Ancienne", now - 5000).put("createdAtMillis", now + 50_000)
        val latest = voucher("UNLIMITED", "Confort récente", now - 1000)
        val other = voucher("DATA", "Autre compte", now).put("redeemedByAccount", "10010")
        val text = label(account(), JSONArray().put(old).put(latest).put(other))
        assertTrue(text.contains("Dernière recharge : Illimité · Confort récente"))
        assertFalse(text.contains("Autre compte"))
        assertFalse(text.contains("Ancienne"))
    }

    @Test fun originalVoucherSnapshotSurvivesAnEditedOffer() {
        val offers = JSONArray().put(JSONObject().put("id", "offer").put("kind", "UNLIMITED").put("name", "Nom modifié"))
        val text = label(account(), JSONArray().put(voucher("DATA", "Data originale")), offers)
        assertTrue(text.contains("Dernière recharge : Data originale"))
        assertFalse(text.contains("Nom modifié"))
    }

    @Test fun legacyOfferNamesAreClearlyIdentifiedAsCurrentNames() {
        val old = voucher("DATA", "").put("snapshotVersion", 0)
        val offers = JSONArray().put(JSONObject().put("id", "offer").put("kind", "DATA").put("name", "Data actuelle"))
        assertTrue(label(account(), JSONArray().put(old), offers).contains("Data actuelle (nom actuel de l'offre)"))
    }

    @Test fun voucherCreationTimeIsNeverInventedAsARechargeDate() {
        val unused = voucher("DATA", "Non utilisée").apply { remove("redeemedAtMillis") }
        val text = label(account(), JSONArray().put(unused))
        assertTrue(text.contains("date non renseignée dans l'historique"))
        assertFalse(text.contains("Non utilisée"))
    }

    @Test fun anUndatedRechargeMakesTheKnownDatedHistoryExplicitlyPartial() {
        val known = voucher("DATA", "Data 12 Go")
        val undated = voucher("UNLIMITED", "Date inconnue").put("redeemedAtMillis", 0L)
        val text = label(account(), JSONArray().put(known).put(undated))
        assertTrue(text.contains("Dernière recharge datée connue : Data 12 Go"))
        assertFalse(text.contains("Dernière recharge : Data 12 Go"))
    }

    @Test fun queuedDataWarnsWhenItWillExpireByTheEndOfUnlimited() {
        val a = account().put("unlimitedUntilMillis", now + 5000).put("dataBalanceBytes", 1000)
            .put("dataValidUntilMillis", now + 5000)
        assertTrue(label(a).contains("Cette Data expire avant ou à la fin de l'Illimité."))
    }

    @Test fun customRatesAndMissingRatesAreNotHardcodedAsEcoOrConfort() {
        val a = account().put("unlimitedUntilMillis", now + 1000).put("unlimitedPlanName", "Offre spéciale")
            .put("unlimitedDownloadBps", 1_500_000L).put("unlimitedUploadBps", 0L)
        val text = label(a)
        assertTrue(text.contains("Illimité · Offre spéciale"))
        assertTrue(text.contains("↓ 1,5 Mbit/s · ↑ sans plafond individuel"))
        a.remove("unlimitedUploadBps")
        assertTrue(label(a).contains("↑ non renseigné"))
    }
}
