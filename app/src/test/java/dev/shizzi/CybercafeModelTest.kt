package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class CybercafeModelTest {

    private val day = 86_400_000L

    private fun account(number: String = "1001"): PrepaidAccount {
        val salt = "salt"
        return PrepaidAccount(
            number = number,
            name = "Test",
            pinSalt = salt,
            pinHash = CybercafeSecurity.hashPin(salt, "1234"),
        )
    }

    @Test
    fun version2StateMigratesWithoutLosingAccountsOrVouchers() {
        val raw = """
            {
              "schemaVersion":2,
              "offers":[{
                "id":"eco-12","name":"Eco 12 Go","kind":"DATA",
                "downloadBps":2000000,"uploadBps":1000000,
                "quotaBytes":12000000000,"durationDays":30,"priceXpf":1000
              }],
              "accounts":[{
                "number":"1001","name":"RONIU","pinSalt":"salt","pinHash":"hash",
                "enabled":true,"dataBalanceBytes":5000000000,
                "dataValidUntilMillis":123456789,"dataDownloadBps":2000000,
                "dataUploadBps":1000000,"unlimitedUntilMillis":0,
                "unlimitedDownloadBps":0,"unlimitedUploadBps":0,
                "unlimitedPlanName":"","totalUpBytes":10,"totalDownBytes":20,
                "createdAtMillis":1
              }],
              "vouchers":[{
                "code":"SHZ-ABCD-EFGH","offerId":"eco-12","createdAtMillis":2,
                "enabled":true,"redeemedByAccount":"","redeemedAtMillis":0,
                "snapshotVersion":1,"snapshotName":"Eco 12 Go","snapshotKind":"DATA",
                "snapshotDownloadBps":2000000,"snapshotUploadBps":1000000,
                "snapshotQuotaBytes":12000000000,"snapshotDurationDays":30,
                "snapshotPriceXpf":1000
              }],
              "devices":[]
            }
        """.trimIndent()

        val migrated = decodeCybercafeState(raw)

        assertEquals(5, migrated.schemaVersion)
        assertEquals("RONIU", migrated.accounts.getValue("1001").name)
        assertTrue(migrated.vouchers.containsKey("SHZ-ABCD-EFGH"))
        assertEquals("Shizzi Hotspot", migrated.portal.title)
        assertEquals("", migrated.portal.html)
        assertFalse(migrated.remoteAdmin.enabled)
        assertEquals("admin", migrated.remoteAdmin.username)
    }

    @Test
    fun version3StateMigratesToRemoteAdminWithoutLosingCurrent041Data() {
        val raw = """
            {
              "schemaVersion":3,
              "portal":{
                "title":"TEKOMOPAO WIFI1",
                "message":"Bienvenue",
                "html":"<html><body>{{CONTENT}}</body></html>"
              },
              "offers":[{
                "id":"eco-12","name":"Eco 12 Go","kind":"DATA",
                "downloadBps":2000000,"uploadBps":1000000,
                "quotaBytes":12000000000,"durationDays":30,"priceXpf":1000
              }],
              "accounts":[{
                "number":"1001","name":"RONIU","pinSalt":"salt","pinHash":"hash",
                "enabled":true,"dataBalanceBytes":7000000000,
                "dataValidUntilMillis":987654321,"dataDownloadBps":2000000,
                "dataUploadBps":1000000,"unlimitedUntilMillis":0,
                "unlimitedDownloadBps":0,"unlimitedUploadBps":0,
                "unlimitedPlanName":"","totalUpBytes":123,"totalDownBytes":456,
                "createdAtMillis":1
              }],
              "vouchers":[{
                "code":"ABCDEFGH23","offerId":"eco-12","createdAtMillis":2,
                "enabled":true,"redeemedByAccount":"","redeemedAtMillis":0,
                "snapshotVersion":1,"snapshotName":"Eco 12 Go","snapshotKind":"DATA",
                "snapshotDownloadBps":2000000,"snapshotUploadBps":1000000,
                "snapshotQuotaBytes":12000000000,"snapshotDurationDays":30,
                "snapshotPriceXpf":1000
              }],
              "devices":[{
                "deviceKey":"aa:bb","accountNumber":"1001",
                "ip":"192.168.243.2","mac":"aa:bb","lastSeenMillis":5
              }]
            }
        """.trimIndent()

        val migrated = decodeCybercafeState(raw)

        assertEquals(5, migrated.schemaVersion)
        assertEquals("TEKOMOPAO WIFI1", migrated.portal.title)
        assertEquals("<html><body>{{CONTENT}}</body></html>", migrated.portal.html)
        assertEquals(7_000_000_000L, migrated.accounts.getValue("1001").dataBalanceBytes)
        assertEquals(579L, migrated.accounts.getValue("1001").totalUpBytes + migrated.accounts.getValue("1001").totalDownBytes)
        assertTrue(migrated.vouchers.containsKey("ABCDEFGH23"))
        assertTrue(migrated.devices.containsKey("aa:bb"))
        assertFalse(migrated.remoteAdmin.enabled)
        assertEquals("admin", migrated.remoteAdmin.username)
    }

    @Test
    fun remoteAdminRoundTripsWithoutChangingAccounts() {
        val salt = "admin-salt"
        val state = CybercafeState(
            accounts = mapOf("1001" to account()),
            remoteAdmin = RemoteAdminConfig(
                enabled = true,
                username = "roniu",
                passwordSalt = salt,
                passwordHash = CybercafeSecurity.hashSecret(salt, "password123"),
            ),
        )

        val decoded = decodeCybercafeState(encodeCybercafeState(state))

        assertTrue(decoded.remoteAdmin.enabled)
        assertEquals("roniu", decoded.remoteAdmin.username)
        assertEquals(state.remoteAdmin.passwordHash, decoded.remoteAdmin.passwordHash)
        assertEquals(1_000_000L, decoded.remoteAdmin.downloadBps)
        assertEquals(1_000_000L, decoded.remoteAdmin.uploadBps)
        assertTrue(decoded.accounts.containsKey("1001"))
    }

    @Test
    fun portalCustomizationRoundTripsWithoutChangingAccountData() {
        val state = CybercafeState(
            accounts = mapOf("1001" to account()),
            portal = PortalCustomization(
                title = "TEKOMOPAO WIFI",
                message = "Bienvenue",
                html = "<html><body>{{CONTENT}}</body></html>",
            ),
        )

        val decoded = decodeCybercafeState(encodeCybercafeState(state))

        assertEquals("TEKOMOPAO WIFI", decoded.portal.title)
        assertEquals("Bienvenue", decoded.portal.message)
        assertEquals("<html><body>{{CONTENT}}</body></html>", decoded.portal.html)
        assertTrue(decoded.accounts.containsKey("1001"))
    }

    @Test
    fun dataVouchersAccumulateBytesButResetValidityFromRechargeTime() {
        val first = Voucher("A", "eco-12", 0L)
        val second = Voucher("B", "eco-20", 0L)
        val initial = CybercafeState(
            accounts = mapOf("1001" to account()),
            vouchers = mapOf("A" to first, "B" to second),
        )

        val at = 1_000_000L
        val afterFirst = CybercafeRules.redeemVoucher(initial, "1001", "A", at)
        assertTrue(afterFirst.success)
        val firstAccount = afterFirst.state.accounts.getValue("1001")
        assertEquals(12_000_000_000L, firstAccount.dataBalanceBytes)
        assertEquals(at + 30L * day, firstAccount.dataValidUntilMillis)

        val later = at + 5L * day
        val afterSecond = CybercafeRules.redeemVoucher(afterFirst.state, "1001", "B", later)
        assertTrue(afterSecond.success)
        val secondAccount = afterSecond.state.accounts.getValue("1001")
        assertEquals(32_000_000_000L, secondAccount.dataBalanceBytes)
        assertEquals(later + 30L * day, secondAccount.dataValidUntilMillis)
    }

    @Test
    fun unlimitedDurationAccumulates() {
        val state = CybercafeState(
            accounts = mapOf("1001" to account()),
            vouchers = mapOf(
                "A" to Voucher("A", "unlimited-eco", 0L),
                "B" to Voucher("B", "unlimited-comfort", 0L),
            ),
        )
        val at = 2_000_000L

        val first = CybercafeRules.redeemVoucher(state, "1001", "A", at)
        val second = CybercafeRules.redeemVoucher(first.state, "1001", "B", at + day)

        assertTrue(second.success)
        assertEquals(
            at + 60L * day,
            second.state.accounts.getValue("1001").unlimitedUntilMillis,
        )
    }

    @Test
    fun mediaVoucherAddsOnlyMediaTimeAndDoesNotTouchInternet() {
        val mediaOffer = MediaOffer(
            id = "media-36h",
            name = "Media 36 heures",
            durationMinutes = 36L * 60L,
            priceXpf = 750,
        )
        val mediaVoucher = MediaVoucher(
            code = "MEDIAPASS1",
            offerId = mediaOffer.id,
            createdAtMillis = 0L,
            snapshotName = mediaOffer.name,
            snapshotDurationMinutes = mediaOffer.durationMinutes,
            snapshotPriceXpf = mediaOffer.priceXpf,
        )
        val original = account().copy(
            dataBalanceBytes = 12_000_000_000L,
            dataValidUntilMillis = 50L * day,
            dataDownloadBps = 2_000_000L,
            dataUploadBps = 1_000_000L,
        )
        val state = CybercafeState(
            accounts = mapOf("1001" to original),
            mediaOffers = mapOf(mediaOffer.id to mediaOffer),
            mediaVouchers = mapOf(mediaVoucher.code to mediaVoucher),
        )
        val at = 1_000_000L

        val result = CybercafeRules.redeemAnyVoucher(state, "1001", "mediapass1", at)

        assertTrue(result.success)
        val updated = result.state.accounts.getValue("1001")
        assertEquals(at + 36L * 60L * 60_000L, updated.mediaUntilMillis)
        assertEquals(original.dataBalanceBytes, updated.dataBalanceBytes)
        assertEquals(original.dataValidUntilMillis, updated.dataValidUntilMillis)
        assertEquals(original.dataDownloadBps, updated.dataDownloadBps)
        assertEquals(original.dataUploadBps, updated.dataUploadBps)
        assertEquals(
            "1001",
            result.state.mediaVouchers.getValue("MEDIAPASS1").redeemedByAccount,
        )
    }

    @Test
    fun mediaVoucherDurationAccumulatesFromCurrentExpiry() {
        val offer = MediaOffer(
            id = "media-10d",
            name = "Media 10 jours",
            durationMinutes = 10L * 24L * 60L,
            priceXpf = 1_000,
        )
        val state = CybercafeState(
            accounts = mapOf(
                "1001" to account().copy(mediaUntilMillis = 20L * day),
            ),
            mediaOffers = mapOf(offer.id to offer),
            mediaVouchers = mapOf(
                "M1" to MediaVoucher(
                    code = "M1",
                    offerId = offer.id,
                    createdAtMillis = 0L,
                    snapshotName = offer.name,
                    snapshotDurationMinutes = offer.durationMinutes,
                ),
            ),
        )

        val result = CybercafeRules.redeemAnyVoucher(state, "1001", "M1", 5L * day)

        assertTrue(result.success)
        assertEquals(
            30L * day,
            result.state.accounts.getValue("1001").mediaUntilMillis,
        )
    }

    @Test
    fun mediaVoucherStateRoundTripsIndependently() {
        val mediaOffer = MediaOffer(
            id = "media-custom",
            name = "Media personnalisé",
            durationMinutes = 90L,
            priceXpf = 300,
        )
        val state = CybercafeState(
            accounts = mapOf(
                "1001" to account().copy(mediaUntilMillis = 123_456L),
            ),
            mediaOffers = mapOf(mediaOffer.id to mediaOffer),
            mediaVouchers = mapOf(
                "MEDIA90" to MediaVoucher(
                    code = "MEDIA90",
                    offerId = mediaOffer.id,
                    createdAtMillis = 42L,
                    snapshotName = mediaOffer.name,
                    snapshotDurationMinutes = mediaOffer.durationMinutes,
                    snapshotPriceXpf = mediaOffer.priceXpf,
                ),
            ),
        )

        val decoded = decodeCybercafeState(encodeCybercafeState(state))

        assertEquals(5, decoded.schemaVersion)
        assertEquals(123_456L, decoded.accounts.getValue("1001").mediaUntilMillis)
        assertEquals(90L, decoded.mediaOffers.getValue("media-custom").durationMinutes)
        assertEquals(90L, decoded.mediaVouchers.getValue("MEDIA90").snapshotDurationMinutes)
        assertTrue(decoded.vouchers.isEmpty())
    }

    @Test
    fun dataBalanceIsNotConsumedWhileUnlimitedIsActive() {
        val acct = account().copy(
            dataBalanceBytes = 1_000_000_000L,
            dataValidUntilMillis = 100L * day,
            unlimitedUntilMillis = 10L * day,
        )
        val state = CybercafeState(
            accounts = mapOf("1001" to acct),
            devices = mapOf(
                "device-a" to DeviceBinding("device-a", "1001"),
            ),
        )

        val updated = CybercafeRules.recordTraffic(
            state,
            "device-a",
            uploadBytes = 10_000L,
            downloadBytes = 20_000L,
            nowMillis = day,
        )

        assertEquals(
            1_000_000_000L,
            updated.accounts.getValue("1001").dataBalanceBytes,
        )
    }

    @Test
    fun oneAccountIsPortableButKeepsOnlyOneActiveDevice() {
        val state = CybercafeState(accounts = mapOf("1001" to account()))
        val first = CybercafeRules.bindDevice(
            state,
            "1001",
            "device-a",
            "192.168.1.2",
            "aa:bb:cc:dd:ee:ff",
            1L,
        )
        assertTrue(first.success)

        val moved = CybercafeRules.bindDevice(
            first.state,
            "1001",
            "device-b",
            "192.168.1.3",
            "11:22:33:44:55:66",
            2L,
        )
        assertTrue(moved.success)
        assertEquals(1, moved.state.devices.size)
        assertFalse(moved.state.devices.containsKey("device-a"))
        assertEquals("1001", moved.state.devices.getValue("device-b").accountNumber)
    }

    @Test
    fun generatedVoucherKeepsItsOwnOfferSnapshot() {
        val customized = Offer(
            id = "weekend",
            name = "Week-end",
            kind = VoucherKind.DATA,
            downloadBps = 10_000_000L,
            uploadBps = 5_000_000L,
            quotaBytes = 25_000_000_000L,
            durationDays = 3,
            priceXpf = 2_500,
        )
        val voucher = Voucher(
            code = "SNAP",
            offerId = customized.id,
            createdAtMillis = 0L,
            snapshotVersion = 1,
            snapshotName = customized.name,
            snapshotKind = customized.kind,
            snapshotDownloadBps = customized.downloadBps,
            snapshotUploadBps = customized.uploadBps,
            snapshotQuotaBytes = customized.quotaBytes,
            snapshotDurationDays = customized.durationDays,
            snapshotPriceXpf = customized.priceXpf,
        )
        val changedOffer = customized.copy(
            name = "Week-end modifié",
            quotaBytes = 1_000_000_000L,
            durationDays = 1,
        )
        val state = CybercafeState(
            offers = mapOf(changedOffer.id to changedOffer),
            accounts = mapOf("1001" to account()),
            vouchers = mapOf("SNAP" to voucher),
        )

        val result = CybercafeRules.redeemVoucher(state, "1001", "SNAP", 1_000L)

        assertTrue(result.success)
        val updated = result.state.accounts.getValue("1001")
        assertEquals(25_000_000_000L, updated.dataBalanceBytes)
        assertEquals(1_000L + 3L * day, updated.dataValidUntilMillis)
        assertEquals(10_000_000L, updated.dataDownloadBps)
        assertEquals(5_000_000L, updated.dataUploadBps)
    }

    @Test
    fun generatedVoucherCodesAvoidAmbiguousCharacters() {
        val random = SecureRandom()
        repeat(1_000) {
            val code = generateVoucherCode(random)
            assertEquals(10, code.length)
            assertTrue(code.all(VOUCHER_ALPHABET::contains))
            assertFalse(code.any { it in "ILO01" })
        }
    }

    @Test
    fun legacyVoucherFormatRemainsRedeemable() {
        val code = "SHZ-ABCD-EFGH"
        val legacy = Voucher(
            code = code,
            offerId = "eco-12",
            createdAtMillis = 0L,
            snapshotVersion = 1,
            snapshotName = "Eco 12 Go",
            snapshotKind = VoucherKind.DATA,
            snapshotDownloadBps = 2_000_000L,
            snapshotUploadBps = 1_000_000L,
            snapshotQuotaBytes = 12_000_000_000L,
            snapshotDurationDays = 30,
            snapshotPriceXpf = 1_000,
        )
        val state = CybercafeState(
            accounts = mapOf("1001" to account()),
            vouchers = mapOf(code to legacy),
        )

        val result = CybercafeRules.redeemVoucher(
            state,
            "1001",
            "shz-abcd-efgh",
            1_000L,
        )

        assertTrue(result.success)
        assertEquals("1001", result.state.vouchers.getValue(code).redeemedByAccount)
    }

    @Test
    fun voucherCannotBeRedeemedTwice() {
        val state = CybercafeState(
            accounts = mapOf("1001" to account(), "1002" to account("1002")),
            vouchers = mapOf("A" to Voucher("A", "eco-12", 0L)),
        )

        val first = CybercafeRules.redeemVoucher(state, "1001", "A", 1L)
        val second = CybercafeRules.redeemVoucher(first.state, "1002", "A", 2L)

        assertTrue(first.success)
        assertFalse(second.success)
    }
}
