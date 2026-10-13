package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminFileTransferProtocolTest {

    @Test
    fun sanitizesFileNameWithoutChangingNormalMediaName() {
        assertEquals(
            "Mortal Kombat 2021.mp4",
            AdminFileTransferProtocol.sanitizeFileName("Mortal Kombat 2021.mp4"),
        )
        assertEquals(
            "video.mkv",
            AdminFileTransferProtocol.sanitizeFileName("../folder/video.mkv"),
        )
    }

    @Test
    fun validatesOnlySequentialBoundedChunks() {
        assertTrue(AdminFileTransferProtocol.validChunkOffset(1_048_576L, 1_048_576L, 1_048_576))
        assertFalse(AdminFileTransferProtocol.validChunkOffset(1_048_576L, 0L, 1_048_576))
        assertFalse(
            AdminFileTransferProtocol.validChunkOffset(
                0L,
                0L,
                AdminFileTransferProtocol.MAX_CHUNK_BYTES + 1,
            ),
        )
    }

    @Test
    fun normalizesUnsafeMimeType() {
        assertEquals("video/mp4", AdminFileTransferProtocol.normalizeMimeType("Video/MP4"))
        assertEquals(
            "application/octet-stream",
            AdminFileTransferProtocol.normalizeMimeType("text/plain\nInjected: yes"),
        )
    }
}
