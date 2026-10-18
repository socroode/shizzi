package dev.shizzi.conso

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CallReceptionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val pending = AtomicReference<String?>(null)
    private val rejected = AtomicReference<String?>(null)

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + 12_000
        while (android.os.SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue(message, condition())
    }
    private fun incomingNotification() = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.firstOrNull { it.id == CallReceptionService.NOTIFICATION_INCOMING }?.notification

    @Before fun startReceptionWhileActivityIsVisible() {
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        CallReceptionService.transportForTests = object : CallReceptionTransport {
            override fun poll(): JSONObject = JSONObject().put("ok", true).put("accountNumber", "1002")
                .put("serverTimeMillis", System.currentTimeMillis())
                .put("incomingCall", pending.get()?.let { JSONObject().put("callId", it).put("from", "1001")
                    .put("fromName", "Alice").put("kind", "audio").put("expiresAtMillis", System.currentTimeMillis() + 45_000) } ?: JSONObject.NULL)
            override fun reject(callId: String): JSONObject {
                rejected.set(callId); pending.set(null)
                return JSONObject().put("ok", true)
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity {
            CallReceptionService.setEnabled(it, true)
            CallReceptionService.start(it)
        }
        await("Foreground reception service did not start") { CallReceptionService.running }
    }

    @After fun cleanup() {
        pending.set(null)
        CallReceptionService.setEnabled(context, false)
        CallReceptionService.stop(context)
        await("Reception service did not stop") { !CallReceptionService.running }
        scenario?.close()
        CallReceptionService.transportForTests = null
        shell("dumpsys deviceidle unforce")
        shell("dumpsys battery reset")
        shell("dumpsys deviceidle whitelist -" + context.packageName)
        shell("input keyevent 224")
    }

    @Test fun incomingCallNotifiesInBackgroundAndCanBeDeclined() {
        shell("input keyevent 3")
        pending.set("call:background")
        await("No incoming notification with Activity in background") { incomingNotification() != null }
        val notification = incomingNotification()!!
        assertEquals(Notification.CATEGORY_CALL, notification.category)
        assertEquals("Alice", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(notification.actions.size >= 2)
        val decline = notification.actions.first { it.title.toString().contains("Decline", true) || it.title.toString().contains("Refuser", true) }
        decline.actionIntent.send()
        await("Decline did not reach signaling transport") { rejected.get() == "call:background" }
        await("Declined call notification remained visible") { incomingNotification() == null }
    }

    @Test fun exemptReceptionNotifiesWithScreenOffAndForcedDoze() {
        shell("dumpsys deviceidle whitelist +" + context.packageName)
        shell("input keyevent 3")
        shell("dumpsys battery unplug")
        shell("input keyevent 223")
        shell("dumpsys deviceidle force-idle")
        pending.set("call:doze")
        await("No incoming notification during exempt Doze") { incomingNotification() != null }
        assertTrue(CallReceptionService.running)
        pending.set(null)
        await("Cancelled call continued ringing in Doze") { incomingNotification() == null }
    }

    @Test fun answerActionReturnsToShizziPlusAndStopRemovesNotifications() {
        shell("input keyevent 3")
        pending.set("call:answer")
        await("No incoming call") { incomingNotification() != null }
        val answer = incomingNotification()!!.actions.first { it.title.toString().contains("Answer", true) || it.title.toString().contains("Répondre", true) }
        answer.actionIntent.send()
        await("Answer did not return to Shizzi+") {
            var action: String? = null
            instrumentation.runOnMainSync {
                val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                action = resumed.filterIsInstance<MainActivity>().firstOrNull()?.intent?.action
            }
            action == CallReceptionService.ACTION_ANSWER
        }
        CallReceptionService.setEnabled(context, false)
        CallReceptionService.stop(context)
        await("Notifications survived stopping reception") {
            !CallReceptionService.running && context.getSystemService(NotificationManager::class.java).activeNotifications.none {
                it.id == CallReceptionService.NOTIFICATION_INCOMING || it.id == CallReceptionService.NOTIFICATION_CONNECTION
            }
        }
    }
}
