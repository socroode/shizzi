package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSeriesNavigationPolicyTest {

    @Test
    fun configuredSeasonFoldersShareOneLogicalSeriesParent() {
        val folders = listOf(
            "Entretien avec un vampire saison 1",
            "ENTRETIEN avec vampire saison 2",
            "Entretien avec un vampire SAISON 3",
        ).mapNotNull(MediaSeriesNavigationPolicy::describeConfiguredFolderName)

        assertEquals(3, folders.size)
        assertEquals(1, folders.map { it.seriesKey }.distinct().size)
        assertEquals(listOf(1, 2, 3), folders.map { it.seasonNumber })
        assertEquals(
            "entretien-avec-un-vampire",
            folders.first().seriesKey,
        )
    }

    @Test
    fun siblingSeasonFoldersAreGroupedIntoOneSeries() {
        val catalog = MediaSeriesNavigationPolicy.build(
            listOf(
                MediaSeriesPathCandidate(
                    id = "s1e1",
                    relativePath = "Entretien avec un vampire Saison 1/Episode 01.mp4",
                    containerName = "Séries",
                ),
                MediaSeriesPathCandidate(
                    id = "s2e1",
                    relativePath = "Entretien avec un vampire Saison 2/Episode 01.mp4",
                    containerName = "Séries",
                ),
                MediaSeriesPathCandidate(
                    id = "s3e1",
                    relativePath = "Entretien avec un vampire Saison 3/Episode 01.mp4",
                    containerName = "Séries",
                ),
            ),
        )

        assertEquals(1, catalog.series.size)
        val show = catalog.series.single()
        assertEquals("Entretien avec un vampire", show.title)
        assertEquals("entretien-avec-un-vampire", show.key)
        assertEquals(listOf(1, 2, 3), show.seasons.map { it.number })
        assertEquals(
            listOf("Saison 1", "Saison 2", "Saison 3"),
            show.seasons.map { it.label },
        )
        assertEquals(3, show.entryIds.size)
        assertTrue(catalog.rootEntryIds.isEmpty())
    }

    @Test
    fun nestedSeriesThenSeasonFoldersAreDetected() {
        val catalog = MediaSeriesNavigationPolicy.build(
            listOf(
                MediaSeriesPathCandidate(
                    id = "a",
                    relativePath = "Vikings/Saison 1/Episode 01.mkv",
                    containerName = "Séries",
                ),
                MediaSeriesPathCandidate(
                    id = "b",
                    relativePath = "Vikings/Saison 2/Episode 01.mkv",
                    containerName = "Séries",
                ),
            ),
        )

        val show = catalog.series.single()
        assertEquals("Vikings", show.title)
        assertEquals(listOf(1, 2), show.seasons.map { it.number })
    }

    @Test
    fun configuredSeriesFolderCanContainBareSeasonFolders() {
        val catalog = MediaSeriesNavigationPolicy.build(
            listOf(
                MediaSeriesPathCandidate(
                    id = "a",
                    relativePath = "Saison 1/Episode 01.mp4",
                    containerName = "The Last of Us",
                ),
                MediaSeriesPathCandidate(
                    id = "b",
                    relativePath = "Saison 2/Episode 01.mp4",
                    containerName = "The Last of Us",
                ),
            ),
        )

        val show = catalog.series.single()
        assertEquals("The Last of Us", show.title)
        assertEquals(listOf(1, 2), show.seasons.map { it.number })
    }

    @Test
    fun rootFilesRemainVisibleOutsideSeriesGroups() {
        val catalog = MediaSeriesNavigationPolicy.build(
            listOf(
                MediaSeriesPathCandidate(
                    id = "root",
                    relativePath = "Episode special.mp4",
                    containerName = "Séries",
                ),
                MediaSeriesPathCandidate(
                    id = "nested",
                    relativePath = "Dark/Episode 01.mp4",
                    containerName = "Séries",
                ),
            ),
        )

        assertEquals(listOf("root"), catalog.rootEntryIds)
        assertEquals("Dark", catalog.series.single().title)
        assertEquals("Épisodes", catalog.series.single().seasons.single().label)
    }

    @Test
    fun rootEpisodeFileNamesAreGroupedBySeriesAndSeason() {
        val catalog = MediaSeriesNavigationPolicy.build(
            listOf(
                MediaSeriesPathCandidate(
                    id = "e1",
                    relativePath = "Entretien avec un vampire Saison 1 Episode 01.mp4",
                    containerName = "Séries",
                ),
                MediaSeriesPathCandidate(
                    id = "e2",
                    relativePath = "Entretien avec un vampire S01E02.mp4",
                    containerName = "Séries",
                ),
                MediaSeriesPathCandidate(
                    id = "e3",
                    relativePath = "Entretien avec un vampire Saison 2 Episode 01.mp4",
                    containerName = "Séries",
                ),
            ),
        )

        assertEquals(1, catalog.series.size)
        val show = catalog.series.single()
        assertEquals("Entretien avec un vampire", show.title)
        assertEquals(listOf(1, 2), show.seasons.map { it.number })
        assertEquals(listOf(2, 1), show.seasons.map { it.entryIds.size })
        assertTrue(catalog.rootEntryIds.isEmpty())
    }

    @Test
    fun configuredSeasonFolderGroupsRootEpisodes() {
        val catalog = MediaSeriesNavigationPolicy.build(
            listOf(
                MediaSeriesPathCandidate(
                    id = "e1",
                    relativePath = "Episode 01.mp4",
                    containerName = "Entretien avec un vampire Saison 1",
                ),
                MediaSeriesPathCandidate(
                    id = "e2",
                    relativePath = "Episode 02.mp4",
                    containerName = "Entretien avec un vampire Saison 1",
                ),
            ),
        )

        val show = catalog.series.single()
        assertEquals("Entretien avec un vampire", show.title)
        assertEquals(listOf(1), show.seasons.map { it.number })
        assertEquals(2, show.entryIds.size)
        assertTrue(catalog.rootEntryIds.isEmpty())
    }


    @Test
    fun separateConfiguredSeriesFoldersStaySeparateFromGlobalSeriesTab() {
        val catalog = MediaSeriesNavigationPolicy.build(
            listOf(
                MediaSeriesPathCandidate(
                    id = "dark-e1",
                    relativePath = "Episode 01.mp4",
                    containerName = "Dark",
                ),
                MediaSeriesPathCandidate(
                    id = "vikings-e1",
                    relativePath = "Episode 01.mp4",
                    containerName = "Vikings",
                ),
            ),
        )

        assertEquals(listOf("Dark", "Vikings"), catalog.series.map { it.title })
        assertTrue(catalog.rootEntryIds.isEmpty())
    }

}
