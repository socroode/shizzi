package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientAppDownloadRangeTest {

    @Test
    fun parsesAndroidResumeRanges() {
        assertEquals(0L..999L, parseClientDownloadRange("bytes=0-999", 10_000L))
        assertEquals(5_000L..9_999L, parseClientDownloadRange("bytes=5000-", 10_000L))
        assertEquals(9_000L..9_999L, parseClientDownloadRange("bytes=-1000", 10_000L))
        assertEquals(9_500L..9_999L, parseClientDownloadRange("bytes=9500-15000", 10_000L))
    }

    @Test
    fun rejectsInvalidOrMultiRanges() {
        assertNull(parseClientDownloadRange("bytes=10000-", 10_000L))
        assertNull(parseClientDownloadRange("bytes=800-200", 10_000L))
        assertNull(parseClientDownloadRange("bytes=0-10,20-30", 10_000L))
        assertNull(parseClientDownloadRange("items=0-10", 10_000L))
        assertNull(parseClientDownloadRange("bytes=-0", 10_000L))
    }

    @Test
    fun computesPartialContentLength() {
        assertEquals(1_000L, (5_000L..5_999L).byteLength())
        assertEquals(1L, (42L..42L).byteLength())
    }
}
