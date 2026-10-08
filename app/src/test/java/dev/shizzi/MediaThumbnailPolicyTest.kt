package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaThumbnailPolicyTest {

    @Test
    fun frameUsesQuarterOfVideoDuration() {
        assertEquals(30_000_000L, MediaThumbnailPolicy.frameTimeUs(120_000L))
        assertEquals(0L, MediaThumbnailPolicy.frameTimeUs(0L))
    }

    @Test
    fun cacheKeyChangesWhenMediaChanges() {
        val first = MediaThumbnailPolicy.cacheFileName(
            id = "abcdef123456",
            size = 1_000L,
            mimeType = "video/mp4",
        )
        val changedSize = MediaThumbnailPolicy.cacheFileName(
            id = "abcdef123456",
            size = 2_000L,
            mimeType = "video/mp4",
        )
        assertNotEquals(first, changedSize)
    }

    @Test
    fun displayTitleRemovesOnlyLastExtension() {
        assertEquals(
            "Mon Film 2026",
            MediaThumbnailPolicy.displayTitle("Mon Film 2026.mp4"),
        )
        assertEquals(
            "Episode.01",
            MediaThumbnailPolicy.displayTitle("Episode.01.mkv"),
        )
        assertEquals("Sans titre", MediaThumbnailPolicy.displayTitle("   "))
    }
}
