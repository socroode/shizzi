package dev.shizzi

import java.util.Locale

data class MediaFolderCoverCandidate(
    val id: String,
    val folderId: String,
    val title: String,
)

object MediaFolderCoverPolicy {
    fun select(
        folderId: String,
        candidates: List<MediaFolderCoverCandidate>,
    ): MediaFolderCoverCandidate? {
        val wanted = folderId.trim()
        if (wanted.isBlank()) return null

        return candidates
            .asSequence()
            .filter { it.folderId == wanted && it.id.isNotBlank() }
            .sortedWith(
                compareBy<MediaFolderCoverCandidate>(
                    { it.title.trim().lowercase(Locale.ROOT) },
                    { it.id },
                ),
            )
            .firstOrNull()
    }
}
