package dev.shizzi

import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClientAppDistributionServerHttpTest {
    private fun fixtureApk(name:String):ByteArray{
        val out=java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use{zip->
            zip.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"));zip.write(byteArrayOf(3,0,8,0,0,0,0,0));zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("classes.dex"));zip.write("dex\n035\u0000$name".toByteArray());zip.closeEntry()
        }
        return out.toByteArray()
    }
    @Test fun rejectsHtmlAndTruncatedApkBeforeServing(){
        assertFalse(inspectClientAppBytes("<html>error</html>".toByteArray()).available)
        val complete=fixtureApk("whole")
        assertTrue(inspectClientAppBytes(complete).available)
        assertFalse(inspectClientAppBytes(complete.copyOf(complete.size-8)).available)
    }

    @Test fun freshServerAndPortalDeliverExactlyTheCurrentAPKWhileLegacyPortIsOccupied() {
        val routerPath = System.getenv("SHIZZI_VERIFY_ROUTER_APK")
        val root = File(System.getenv("GITHUB_WORKSPACE") ?: System.getProperty("user.dir")).let {
            if (File(it, "datapath").isDirectory) it else it.parentFile
        }
        val comptes = if (routerPath != null) {
            ZipFile(routerPath).use { zip ->
                zip.getInputStream(zip.getEntry("assets/shizzi/Shizzi-Comptes.apk")).use { it.readBytes() }
            }
        } else fixtureApk("accounts")
        val monitor = if (routerPath != null) {
            ZipFile(routerPath).use { zip ->
                zip.getInputStream(zip.getEntry("assets/shizzi/ShizziCam-Debit.apk")).use { it.readBytes() }
            }
        } else fixtureApk("monitor")
        val payloads = if (routerPath != null) {
            ZipFile(routerPath).use { zip ->
                Triple(
                    zip.getInputStream(zip.getEntry("assets/shizzi/Shizzi-Plus.apk")).use { it.readBytes() },
                    zip.getInputStream(zip.getEntry("assets/shizzi/Shizzi-Admin.apk")).use { it.readBytes() },
                    zip.getInputStream(zip.getEntry("assets/shizzi/ShizziCam-Admin.apk")).use { it.readBytes() },
                )
            }
        } else {
            Triple(fixtureApk("client"),fixtureApk("admin"),fixtureApk("viewer"))
        }
        val (plus, admin, viewer) = payloads
        ServerSocket(8091, 8, InetAddress.getByName("127.0.0.1")).use { legacy ->
            legacy.soTimeout = 100
            val server = ClientAppDistributionServer(plus, admin, viewer, monitor, comptes)
            try {
                assertTrue(server.start())
                assertTrue(server.port in 1..65535)
                assertNotEquals(8091, server.port)
                val url = URL("http://127.0.0.1:${server.port}/shizzi-plus.apk")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 5000
                try {
                    assertEquals(200, connection.responseCode)
                    assertArrayEquals(plus, connection.inputStream.use { it.readBytes() })
                    assertEquals("0.3.5-relay18-test", connection.getHeaderField("X-Shizzi-Version"))
                    assertEquals(server.info.sha256, connection.getHeaderField("X-Shizzi-SHA256"))
                    assertTrue(connection.getHeaderField("Content-Disposition").contains("Shizzi-PlusCam-0.3.5-test.apk"))
                } finally { connection.disconnect() }
                for (row in listOf(
                    Triple("/shizzi-admin.apk",admin,server.adminInfo),
                    Triple("/shizzicam-admin.apk",viewer,server.viewerInfo),
                    Triple("/shizzicam-debit.apk",monitor,server.monitorInfo),
                    Triple("/shizzi-comptes.apk",comptes,server.accountsInfo),
                )) {
                    val (endpoint, bytes, metadata)=row
                    val next=(URL("http://127.0.0.1:${server.port}"+endpoint).openConnection() as HttpURLConnection)
                    try {
                        next.connectTimeout=3000
                        next.readTimeout=10000
                        assertEquals(200,next.responseCode)
                        assertEquals(metadata.sha256,next.getHeaderField("X-Shizzi-SHA256"))
                        assertEquals(metadata.version,next.getHeaderField("X-Shizzi-Version"))
                        assertArrayEquals(bytes,next.inputStream.use{it.readBytes()})
                    } finally {next.disconnect()}
                }
                if (System.getenv("SHIZZI_VERIFY_PORTAL") == "true") {
                    val expected = File(root, "release-output/expected-portal-plus.apk").apply { parentFile.mkdirs(); writeBytes(plus) }
                    val downloaded = File(root, "release-output/Shizzi-PlusCam-0.3.3.1-from-portal.apk")
                    val process = ProcessBuilder("go", "test", "-count=1", "-run", "^TestReleasePortalDownload$", "-v", ".")
                        .directory(File(root, "datapath")).redirectErrorStream(true)
                    process.environment()["SHIZZI_EXPECTED_PLUS_VERSION"] = server.info.version
                    process.environment()["SHIZZI_EXPECTED_PLUS_FILENAME"] = server.info.fileName
                    process.environment()["SHIZZI_DISTRIBUTION_PORT"] = server.port.toString()
                    process.environment()["SHIZZI_EXPECTED_PLUS_APK"] = expected.absolutePath
                    process.environment()["SHIZZI_DOWNLOADED_PLUS_APK"] = downloaded.absolutePath
                    val child = process.start()
                    val output = child.inputStream.bufferedReader().use { it.readText() }
                    assertEquals(output, 0, child.waitFor())
                    assertArrayEquals(plus, downloaded.readBytes())
                    println(output)
                }
                try { legacy.accept().use { fail("The obsolete fixed-port server received a download request") } }
                catch (_: SocketTimeoutException) { /* No client consulted the obsolete server. */ }
            } finally { server.stop() }
        }
    }

    @Test fun privilegedAnnouncementOverridesStaleUIInfoAndDisablesMissingServer() {
        val info = inspectClientAppBytes(fixtureApk("current"))
        val stale = """{"clientApp":{"available":true,"version":"0.2.12-candidate","sha256":"old"},"accounts":[]}"""
        val current = JSONObject(portalConfigWithDistribution(stale, info, info, info, info, info, 12345))
        assertTrue(current.has("accounts"))
        val app = current.getJSONObject("clientApp")
        assertEquals("0.3.5-relay18-test", app.getString("version"))
        assertEquals(info.sha256, app.getString("sha256"))
        assertEquals(12345, app.getInt("bridgePort"))
        assertTrue(app.getBoolean("available"))
        val adminApp=current.getJSONObject("adminApp")
        assertTrue(adminApp.getBoolean("available"))
        assertEquals(info.sha256,adminApp.getString("sha256"))
        assertEquals(12345,adminApp.getInt("bridgePort"))
        assertTrue(current.getJSONObject("camViewerApp").getBoolean("available"))
        assertEquals(info.sha256,current.getJSONObject("camViewerApp").getString("sha256"))
        assertTrue(current.getJSONObject("camMonitorApp").getBoolean("available"))
        assertEquals(info.sha256,current.getJSONObject("camMonitorApp").getString("sha256"))
        assertTrue(current.getJSONObject("accountsApp").getBoolean("available"))
        assertEquals(info.sha256,current.getJSONObject("accountsApp").getString("sha256"))
        assertFalse(JSONObject(portalConfigWithDistribution(stale, info, info, info, info, info, 0)).getJSONObject("clientApp").getBoolean("available"))
    }
}
