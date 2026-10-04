package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFolderCoverPolicyTest {

    @Test
    fun picksCoverOnlyFromRequestedFolder() {
        val candidates = listOf(
            MediaFolderCoverCandidate("b", "films", "Zulu"),
            MediaFolderCoverCandidate("x", "music", "Album"),
            MediaFolderCoverCandidate("a", "films", "Alpha"),
        )

        val selected = MediaFolderCoverPolicy.select("films", candidates)

        assertEquals("a", selected?.id)
        assertEquals("films", selected?.folderId)
    }

    @Test
    fun coverSelectionIsDeterministicByTitleThenId() {
        val candidates = listOf(
            MediaFolderCoverCandidate("2", "series", "Episode 01"),
            MediaFolderCoverCandidate("1", "series", "Episode 01"),
            MediaFolderCoverCandidate("3", "series", "Episode 02"),
        )

        assertEquals(
            "1",
            MediaFolderCoverPolicy.select("series", candidates)?.id,
        )
    }

    @Test
    fun emptyOrUnknownFolderHasNoCover() {
        val candidates = listOf(
            MediaFolderCoverCandidate("1", "films", "Film"),
        )

        assertNull(MediaFolderCoverPolicy.select("", candidates))
        assertNull(MediaFolderCoverPolicy.select("private", candidates))
    }
}
