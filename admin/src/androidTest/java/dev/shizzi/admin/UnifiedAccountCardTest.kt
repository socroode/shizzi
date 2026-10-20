package dev.shizzi.admin

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android views with a six-account simulated admin response. No router/network claims. */
@RunWith(AndroidJUnit4::class)
class UnifiedAccountCardTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun traffic(): JSONObject = JSONObject().put("portalRequired", true).put("portalAuthorizations", JSONArray().apply {
        for (i in 1..6) put(JSONObject().put("accountNumber", "100$i").put("ip", "10.0.0.$i")
            .put("mac", "02:00:00:00:00:0$i").put("presence", "unknown").put("authorized", i % 2 == 1))
    })
    private fun response(): JSONObject {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val accounts = JSONArray()
        for (i in 1..6) {
            val account = JSONObject().put("number", "100$i").put("name", if (i == 6) "" else "Client $i")
                .put("enabled", i != 5).put("dataBalanceBytes", 0L).put("dataValidUntilMillis", 0L)
                .put("dataDownloadBps", 2_000_000L).put("dataUploadBps", 1_000_000L)
                .put("unlimitedUntilMillis", 0L).put("unlimitedDownloadBps", 4_000_000L)
                .put("unlimitedUploadBps", 2_000_000L).put("unlimitedPlanName", "Illimité Confort")
            when (i) {
                1 -> account.put("dataBalanceBytes", 5_000_000_000L).put("dataValidUntilMillis", now + 30 * day)
                2 -> account.put("unlimitedUntilMillis", now + 3 * day).put("dataBalanceBytes", 7_000_000_000L)
                    .put("dataValidUntilMillis", now + 30 * day)
                3 -> account.put("unlimitedUntilMillis", now + 2 * day).put("unlimitedPlanName", "Illimité Éco")
                    .put("unlimitedDownloadBps", 1_000_000L).put("unlimitedUploadBps", 1_000_000L)
                4 -> account.put("dataBalanceBytes", 1_000_000_000L).put("dataValidUntilMillis", now - day)
                5 -> account.put("unlimitedUntilMillis", now + 5 * day)
            }
            accounts.put(account)
        }
        val vouchers = JSONArray().put(JSONObject().put("redeemedByAccount", "1001").put("redeemedAtMillis", now - day)
            .put("snapshotVersion", 1).put("snapshotKind", "DATA").put("snapshotName", "Data 12 Go"))
            .put(JSONObject().put("redeemedByAccount", "1002").put("redeemedAtMillis", now - 1000)
                .put("snapshotVersion", 1).put("snapshotKind", "DATA").put("snapshotName", "Data 20 Go"))
        return JSONObject().put("ok", true).put("routerName", "Routeur de test")
            .put("state", JSONObject().put("accounts", accounts).put("vouchers", vouchers)).put("traffic", traffic())
    }
    private fun invoke(activity: MainActivity, method: String, value: JSONObject?) {
        MainActivity::class.java.getDeclaredMethod(method, JSONObject::class.java).apply { isAccessible = true }.invoke(activity, value)
    }

    @Before fun showSixAccountDashboard() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { invoke(it, "showDashboard", response()) }
        instrumentation.waitForIdleSync()
    }
    @After fun closeActivity() { scenario.close() }

    @Test fun eachOfSixAccountNumbersAndDisconnectButtonsOccursOnce() {
        scenario.onActivity { activity ->
            val all = views(activity.window.decorView)
            val texts = all.filterIsInstance<TextView>().map { it.text.toString() }
            for (i in 1..6) assertEquals("Account 100$i was repeated", 1, texts.sumOf { Regex("100$i").findAll(it).count() })
            assertFalse(texts.contains("Appareils et sessions"))
            assertEquals(6, all.filterIsInstance<Button>().count { it.text.toString() == "Déconnecter" })
            for (i in 1..6) {
                val summary = all.first { it.tag == "account:100$i:session" } as TextView
                assertTrue(summary.text.contains(if (i % 2 == 1) "Internet autorisé" else "Internet bloqué"))
            }
        }
    }

    @Test fun deviceDetailsOpenFromTheAccountAndOnlyShowItsDevice() {
        scenario.onActivity { activity ->
            (views(activity.window.decorView).first { it.tag == "account:1003:details" } as Button).performClick()
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val text = WindowInspector.getGlobalWindowViews().flatMap { views(it) }
                .filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
            assertTrue(text.contains("10.0.0.3"))
            assertTrue(text.contains("02:00:00:00:00:03"))
            assertFalse(text.contains("10.0.0.2"))
            WindowInspector.getGlobalWindowViews().flatMap { views(it) }.filterIsInstance<Button>()
                .first { it.text.toString().equals("Fermer", true) }.performClick()
        }
    }

    @Test fun sessionRefreshPreservesInputAndClearsStaleInformation() {
        scenario.onActivity { activity ->
            val all = views(activity.window.decorView)
            val input = all.filterIsInstance<EditText>().first()
            input.setText("saisie conservée")
            invoke(activity, "updateAccountSessions", JSONObject().put("portalRequired", true))
            assertEquals("saisie conservée", input.text.toString())
            assertTrue((all.first { it.tag == "account:1001:heading" } as TextView).text.contains("Déconnecté"))
            assertFalse((all.first { it.tag == "account:1001:details" } as Button).isEnabled)
            invoke(activity, "updateAccountSessions", null)
            assertTrue((all.first { it.tag == "account:1001:heading" } as TextView).text.contains("État indisponible"))
            invoke(activity, "updateAccountSessions", traffic())
            assertTrue((all.first { it.tag == "account:1001:details" } as Button).isEnabled)
            assertEquals("saisie conservée", input.text.toString())
        }
    }

    @Test fun activePlansAndLastRechargesStayInTheirOwnAccountCards() {
        scenario.onActivity { activity ->
            val all = views(activity.window.decorView)
            fun plan(number: String) = (all.first { it.tag == "account:$number:plan" } as TextView).text.toString()
            assertTrue(plan("1001").contains("Forfait actif : Data"))
            assertTrue(plan("1001").contains("Dernière recharge : Data 12 Go"))
            assertFalse(plan("1001").contains("Data 20 Go"))
            assertTrue(plan("1002").contains("Forfait actif : Illimité Confort"))
            assertTrue(plan("1002").contains("Data en attente"))
            assertTrue(plan("1002").contains("Dernière recharge : Data 20 Go"))
            assertTrue(plan("1003").contains("Forfait actif : Illimité Éco"))
            assertTrue(plan("1003").contains("↓ 1 Mbit/s · ↑ 1 Mbit/s"))
            assertTrue(plan("1004").contains("Forfait actif : aucun"))
            assertTrue(plan("1004").contains("Data expirée"))
            assertTrue(plan("1005").contains("Forfait suspendu"))
            assertTrue(plan("1006").contains("Forfait actif : aucun"))
        }
    }

    @Test fun planRefreshPreservesInputAndInvalidatesOldPlanOnFailure() {
        scenario.onActivity { activity ->
            val all = views(activity.window.decorView)
            val input = all.filterIsInstance<EditText>().first()
            input.setText("modification conservée")
            val state = response().getJSONObject("state")
            state.getJSONArray("accounts").getJSONObject(0).put("unlimitedUntilMillis", System.currentTimeMillis() + 86_400_000L)
                .put("unlimitedPlanName", "Offre spéciale").put("dataBalanceBytes", 0L)
            invoke(activity, "updateAccountPlans", state)
            val plan = all.first { it.tag == "account:1001:plan" } as TextView
            assertTrue(plan.text.contains("Forfait actif : Illimité · Offre spéciale"))
            assertEquals("modification conservée", input.text.toString())
            invoke(activity, "updateAccountPlans", null)
            assertEquals("Forfait : information indisponible", plan.text.toString())
            assertEquals("modification conservée", input.text.toString())
        }
    }
}
