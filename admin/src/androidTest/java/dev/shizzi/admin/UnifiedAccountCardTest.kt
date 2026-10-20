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
    private fun response() = JSONObject().put("ok", true).put("routerName", "Routeur de test")
        .put("state", JSONObject().put("accounts", JSONArray().apply {
            for (i in 1..6) put(JSONObject().put("number", "100$i").put("name", if (i == 6) "" else "Client $i").put("enabled", true))
        })).put("traffic", traffic())
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
}
