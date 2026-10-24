package dev.shizzi.admin

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ViewerSessionsTest {
    @Test fun concurrentCommandsAndInterphoneShareOneRouterWatch() {
        val sessions = ViewerSessions()
        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val creating = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            val requests = (1..8).map {
                workers.submit<String> {
                    start.await()
                    sessions.ensure("123") {
                        calls.incrementAndGet(); creating.countDown()
                        check(release.await(3, TimeUnit.SECONDS)); "router-session"
                    }
                }
            }
            start.countDown()
            assertTrue(creating.await(3, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(setOf("router-session"), requests.map { it.get(3, TimeUnit.SECONDS) }.toSet())
            assertEquals(1, calls.get())
        } finally { release.countDown(); workers.shutdownNow() }
    }
    @Test fun aLateFailureCannotRemoveTheReplacementOrResetItsCursor() {
        val sessions = ViewerSessions()
        sessions.ensure("123") { "old" }
        assertTrue(sessions.invalidate("123", "old"))
        sessions.ensure("123") { "new" }
        sessions.advance("123", "new", 24)
        sessions.advance("123", "old", 99)
        assertFalse(sessions.invalidate("123", "old"))
        assertEquals("new", sessions.current("123"))
        assertEquals(24L, sessions.after("123", "new"))
    }
    @Test fun anInterruptedConnectionCanRetryWithoutCachingAFailedCreation() {
        val sessions = ViewerSessions()
        try { sessions.ensure("123") { throw IOException("network unavailable") }; fail() }
        catch (_: IOException) { }
        assertEquals("ready", sessions.ensure("123") { "ready" })
    }
    @Test fun transientPollErrorsKeepTheSessionUsedByTheLiveInterphone() {
        val sessions = ViewerSessions()
        sessions.ensure("123") { "live-session" }
        for(status in listOf(null, 500, 503, 401)) {
            assertFalse(sessions.onPollFailure("123", "live-session", status))
            assertEquals("live-session", sessions.ensure("123") { error("Must not re-watch") })
        }
        assertTrue(sessions.onPollFailure("123", "live-session", 403))
        assertNull(sessions.current("123"))
    }
}
