package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CybercafeTrafficBridgeTest {

    @Test
    fun mediaDiagnosticsAreParsedForHotspotUi() {
        val snapshot = parseLiveTrafficSnapshot(
            """{
              "epoch": 42,
              "mediaDiagnostics": [{
                "atMillis": 123456789,
                "clientIp": "192.168.7.66",
                "path": "/media/",
                "accountAuthenticated": true,
                "accountNumber": "RONIU",
                "proxyTarget": "/",
                "backend": "127.0.0.1:8088",
                "backendConnected": true,
                "bytesCopied": 2048,
                "result": "proxied"
              }]
            }""",
        )

        assertEquals(42L, snapshot.epoch)
        assertEquals(1, snapshot.mediaDiagnostics.size)

        val event = snapshot.mediaDiagnostics.single()
        assertEquals("192.168.7.66", event.clientIp)
        assertEquals("/media/", event.path)
        assertTrue(event.accountAuthenticated)
        assertEquals("RONIU", event.accountNumber)
        assertEquals("/", event.proxyTarget)
        assertEquals("127.0.0.1:8088", event.backend)
        assertTrue(event.backendConnected)
        assertEquals(2048L, event.bytesCopied)
        assertEquals("proxied", event.result)
        assertFalse(event.error.isNotBlank())
    }

    @Test
    fun adminStateContainsMediaFoldersAndAccessRules() {
        val state = CybercafeState()
        val folders = listOf(
            MediaFolderConfig(
                id = "films-family",
                name = "Films famille",
                kind = MediaKind.FILMS,
                treeUri = "content://test/tree/primary%3AMovies",
                allowedAccounts = setOf("1001", "1003"),
                writable = true,
            ),
            MediaFolderConfig(
                id = "music",
                name = "Musique",
                kind = MediaKind.MUSIC,
                treeUri = null,
                enabled = false,
            ),
        )

        val root = org.json.JSONObject(
            state.toPortalConfigJson(
                mediaEnabled = true,
                mediaFolders = folders,
                mediaSummary = MediaIndexSummary(
                    films = 12,
                    series = 3,
                    music = 5,
                    total = 20,
                    updatedAt = 1234L,
                ),
            ),
        )

        val media = root
            .getJSONObject("adminState")
            .getJSONObject("media")
        assertTrue(media.getBoolean("enabled"))
        assertEquals(10, media.getInt("maxFolders"))
        assertEquals(20, media.getJSONObject("summary").getInt("total"))

        val serialized = media.getJSONArray("folders")
        assertEquals(2, serialized.length())
        val first = serialized.getJSONObject(0)
        assertEquals("films-family", first.getString("id"))
        assertEquals("Films famille", first.getString("name"))
        assertEquals("films", first.getString("kind"))
        assertTrue(first.getBoolean("writable"))
        assertEquals(2, first.getJSONArray("allowedAccounts").length())
    }

    @Test
    fun adminStateContainsRouterBatteryTelemetry() {
        val root = org.json.JSONObject(
            CybercafeState().toPortalConfigJson(
                routerBattery = RouterBatteryState(
                    available = true,
                    percent = 78,
                    charging = true,
                ),
            ),
        )

        val battery = root
            .getJSONObject("adminState")
            .getJSONObject("battery")

        assertTrue(battery.getBoolean("available"))
        assertEquals(78, battery.getInt("percent"))
        assertTrue(battery.getBoolean("charging"))
    }
    @Test
    fun adminAndPortalStateExposeIndependentMediaPass() {
        val account = PrepaidAccount(
            number = "1001",
            name = "Client",
            pinSalt = "salt",
            pinHash = "hash",
            mediaUntilMillis = 9_999_999L,
        )
        val offer = MediaOffer(
            id = "media-12h",
            name = "Media 12 heures",
            durationMinutes = 12L * 60L,
            priceXpf = 500,
        )
        val voucher = MediaVoucher(
            code = "MEDIA12H",
            offerId = offer.id,
            createdAtMillis = 1L,
            snapshotName = offer.name,
            snapshotDurationMinutes = offer.durationMinutes,
            snapshotPriceXpf = offer.priceXpf,
        )
        val root = org.json.JSONObject(
            CybercafeState(
                accounts = mapOf(account.number to account),
                mediaOffers = mapOf(offer.id to offer),
                mediaVouchers = mapOf(voucher.code to voucher),
            ).toPortalConfigJson(),
        )

        val adminState = root.getJSONObject("adminState")
        assertEquals(
            9_999_999L,
            adminState.getJSONArray("accounts").getJSONObject(0).getLong("mediaUntilMillis"),
        )
        assertEquals(
            720L,
            adminState.getJSONArray("mediaOffers").getJSONObject(0).getLong("durationMinutes"),
        )
        assertEquals(
            "MEDIA12H",
            adminState.getJSONArray("mediaVouchers").getJSONObject(0).getString("code"),
        )
        assertEquals(
            9_999_999L,
            root.getJSONArray("accounts").getJSONObject(0).getLong("mediaUntilMillis"),
        )
    }


}
