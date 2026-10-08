package dev.shizzi.conso

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaWebSupportTest {

    @Test
    fun wifiWithoutInternetStillCountsAsLocalNetworkAvailable() {
        assertTrue(LocalMediaWebSupport.shouldKeepNetworkAvailable(true))
        assertFalse(LocalMediaWebSupport.shouldKeepNetworkAvailable(false))
    }

    @Test
    fun fullscreenFallbackDoesNotDependOnInternet() {
        val script = LocalMediaWebSupport.fullscreenScript

        listOf(
            "requestFullscreen",
            "webkitRequestFullscreen",
            "webkitEnterFullscreen",
            "Plein écran",
            "MutationObserver",
            "querySelectorAll('video')",
        ).forEach { expected ->
            assertTrue("missing $expected", script.contains(expected))
        }

        assertFalse(script.contains("fetch("))
        assertFalse(script.contains("http://"))
        assertFalse(script.contains("https://"))
        assertFalse(script.contains("navigator.onLine"))
    }
}
