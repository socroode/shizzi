package dev.shizzi

import androidx.datastore.preferences.core.preferencesOf
import dev.shizzi.ui.theme.AccentChoice
import dev.shizzi.ui.theme.DesignLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsKeysTest {

    @Test
    fun `reads a stored design language`() {
        val stored = preferencesOf(DESIGN to DesignLanguage.NEOBRUTALISM.name)

        assertEquals(DesignLanguage.NEOBRUTALISM, toSettings(stored).design)
    }

    @Test
    fun `defaults to material expressive when no design is stored`() {
        assertEquals(DesignLanguage.MATERIAL_EXPRESSIVE, toSettings(preferencesOf()).design)
    }

    @Test
    fun `falls back to material expressive when the design is unreadable`() {
        val stored = preferencesOf(DESIGN to "wingdings")

        assertEquals(DesignLanguage.MATERIAL_EXPRESSIVE, toSettings(stored).design)
    }

    @Test
    fun `reads a stored accent`() {
        val stored = preferencesOf(ACCENT to "#3B82F6")

        assertEquals(AccentChoice.Custom(0xFF3B82F6.toInt()), toSettings(stored).accent)
    }

    @Test
    fun `reads stored custom accents in order`() {
        val stored = preferencesOf(CUSTOM_ACCENTS to "#3B82F6,#14B8A6")

        assertEquals(
            listOf(0xFF3B82F6.toInt(), 0xFF14B8A6.toInt()),
            toSettings(stored).customAccents,
        )
    }

    @Test
    fun `reads a stored vpn mode`() {
        val stored = preferencesOf(VPN_MODE to VpnMode.NEVER.name)

        assertEquals(VpnMode.NEVER, toSettings(stored).vpnMode)
    }

    @Test
    fun `defaults to auto when no vpn mode is stored`() {
        assertEquals(VpnMode.AUTO, toSettings(preferencesOf()).vpnMode)
    }

    @Test
    fun `falls back to auto when the vpn mode is unreadable`() {
        val stored = preferencesOf(VPN_MODE to "sideways")

        assertEquals(VpnMode.AUTO, toSettings(stored).vpnMode)
    }

    @Test
    fun `absent appearance keys read as defaults`() {
        val settings = toSettings(preferencesOf())

        assertEquals(AccentChoice.Default, settings.accent)
        assertEquals(emptyList<Int>(), settings.customAccents)
    }
    @Test
    fun `v1_6 prepaid accounts remain readable`() {
        val raw = """[
            {
              "number":"25494159",
              "pin":"583921",
              "name":"TAIANA",
              "enabled":true,
              "dataBalanceBytes":1082000000,
              "dataExpiresAtMillis":1790316921000,
              "dataDownloadBps":1000000,
              "dataUploadBps":1000000,
              "unlimitedUntilMillis":0,
              "unlimitedDownloadBps":0,
              "unlimitedUploadBps":0,
              "unlimitedPlanName":"",
              "createdAtMillis":1790313000000,
              "lastAuthorizationStartedAtMillis":1790316000000,
              "lastAuthorizationSessionDataBytes":18000000
            }
        ]""".trimIndent()

        val account = decodePrepaidAccounts(raw).getValue("25494159")

        assertEquals("583921", account.pin)
        assertEquals("TAIANA", account.name)
        assertEquals(1_082_000_000L, account.dataBalanceBytes)
        assertEquals(1_000_000L, account.dataDownloadBps)
        assertEquals(1_000_000L, account.dataUploadBps)
    }

    @Test
    fun `v1_6 redeemed vouchers remain readable`() {
        val raw = """[
            {
              "code":"DATA-1G",
              "name":"Data 1-1",
              "downloadBps":1000000,
              "uploadBps":1000000,
              "downloadUnit":"MBPS",
              "uploadUnit":"MBPS",
              "quotaBytes":1000000000,
              "quotaUnit":"GB",
              "durationMinutes":43200,
              "durationUnit":"DAYS",
              "createdAtMillis":1790313000000,
              "assignedDeviceId":"",
              "activatedAtMillis":0,
              "startTotalBytes":0,
              "usedBytes":0,
              "lastAuthorizationStartedAtMillis":0,
              "lastAuthorizationSessionBytes":0,
              "redeemedAccountNumber":"25494159",
              "redeemedAtMillis":1790316921000,
              "enabled":true
            }
        ]""".trimIndent()

        val pass = decodeAccessPasses(raw).getValue("DATA-1G")

        assertEquals("25494159", pass.redeemedAccountNumber)
        assertEquals(1_000_000L, pass.downloadBps)
        assertEquals(1_000_000_000L, pass.quotaBytes)
        assertEquals(43_200L, pass.durationMinutes)
    }

}
