package dev.shizzi

import java.text.Normalizer
import java.util.Locale

data class MediaSeriesPathCandidate(
    val id: String,
    val relativePath: String,
    val containerName: String,
)

data class MediaSeriesSeasonGroup(
    val key: String,
    val label: String,
    val number: Int?,
    val entryIds: List<String>,
)

data class MediaSeriesGroup(
    val key: String,
    val title: String,
    val seasons: List<MediaSeriesSeasonGroup>,
    val entryIds: List<String>,
)

data class MediaSeriesCatalog(
    val series: List<MediaSeriesGroup>,
    val rootEntryIds: List<String>,
)

object MediaSeriesNavigationPolicy {

    fun build(candidates: List<MediaSeriesPathCandidate>): MediaSeriesCatalog {
        val rootEntries = mutableListOf<String>()
        val grouped = linkedMapOf<String, MutableSeries>()

        candidates
            .sortedWith(compareBy({ it.relativePath.lowercase(Locale.ROOT) }, { it.id }))
            .forEach { candidate ->
                val descriptor = describe(candidate)
                if (descriptor == null) {
                    rootEntries += candidate.id
                    return@forEach
                }

                val series = grouped.getOrPut(descriptor.seriesKey) {
                    MutableSeries(
                        key = descriptor.seriesKey,
                        title = descriptor.seriesTitle,
                    )
                }
                series.entryIds += candidate.id

                val seasonKey = descriptor.seasonNumber?.let { "season-$it" } ?: "episodes"
                val season = series.seasons.getOrPut(seasonKey) {
                    MutableSeason(
                        key = seasonKey,
                        label = descriptor.seasonNumber?.let { "Saison $it" } ?: "Épisodes",
                        number = descriptor.seasonNumber,
                    )
                }
                season.entryIds += candidate.id
            }

        val series = grouped.values
            .map { item ->
                MediaSeriesGroup(
                    key = item.key,
                    title = item.title,
                    seasons = item.seasons.values
                        .sortedWith(
                            compareBy<MutableSeason>(
                                { it.number == null },
                                { it.number ?: Int.MAX_VALUE },
                                { it.label.lowercase(Locale.ROOT) },
                            ),
                        )
                        .map { season ->
                            MediaSeriesSeasonGroup(
                                key = season.key,
                                label = season.label,
                                number = season.number,
                                entryIds = season.entryIds.toList(),
                            )
                        },
                    entryIds = item.entryIds.toList(),
                )
            }
            .sortedBy { it.title.lowercase(Locale.ROOT) }

        return MediaSeriesCatalog(
            series = series,
            rootEntryIds = rootEntries,
        )
    }

    private fun describe(candidate: MediaSeriesPathCandidate): Descriptor? {
        val segments = candidate.relativePath
            .split('/')
            .map(String::trim)
            .filter(String::isNotBlank)

        if (segments.size < 2) return null

        parseSeriesAndSeason(segments[0])?.let { parsed ->
            return Descriptor(
                seriesKey = slug(parsed.first),
                seriesTitle = cleanTitle(parsed.first),
                seasonNumber = parsed.second,
            )
        }

        parseStandaloneSeason(segments[0])?.let { season ->
            val title = candidate.containerName.trim().ifBlank { "Série" }
            return Descriptor(
                seriesKey = slug(title),
                seriesTitle = cleanTitle(title),
                seasonNumber = season,
            )
        }

        if (segments.size >= 3) {
            parseStandaloneSeason(segments[1])?.let { season ->
                return Descriptor(
                    seriesKey = slug(segments[0]),
                    seriesTitle = cleanTitle(segments[0]),
                    seasonNumber = season,
                )
            }
        }

        val title = cleanTitle(segments[0])
        return Descriptor(
            seriesKey = slug(title),
            seriesTitle = title,
            seasonNumber = null,
        )
    }

    private fun parseSeriesAndSeason(value: String): Pair<String, Int>? {
        val match = SERIES_SEASON.find(value.trim()) ?: return null
        val title = cleanTitle(match.groupValues[1])
        val number = (match.groupValues[2].ifBlank { match.groupValues[3] })
            .toIntOrNull()
            ?: return null
        if (title.isBlank()) return null
        return title to number
    }

    private fun parseStandaloneSeason(value: String): Int? {
        val match = STANDALONE_SEASON.matchEntire(value.trim()) ?: return null
        return (match.groupValues[1].ifBlank { match.groupValues[2] }).toIntOrNull()
    }

    private fun cleanTitle(value: String): String =
        value
            .replace(Regex("[._]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '_', '.')

    private fun slug(value: String): String {
        val ascii = Normalizer.normalize(cleanTitle(value), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
        return ascii
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "serie" }
    }

    private data class Descriptor(
        val seriesKey: String,
        val seriesTitle: String,
        val seasonNumber: Int?,
    )

    private data class MutableSeries(
        val key: String,
        val title: String,
        val seasons: LinkedHashMap<String, MutableSeason> = linkedMapOf(),
        val entryIds: MutableList<String> = mutableListOf(),
    )

    private data class MutableSeason(
        val key: String,
        val label: String,
        val number: Int?,
        val entryIds: MutableList<String> = mutableListOf(),
    )

    private val SERIES_SEASON = Regex(
        pattern = """(?i)^(.*?)(?:[\s._-]+(?:saison|season)\s*0*(\d{1,3})|[\s._-]+s0*(\d{1,3}))\s*$""",
    )

    private val STANDALONE_SEASON = Regex(
        pattern = """(?i)^(?:(?:saison|season)\s*0*(\d{1,3})|s0*(\d{1,3}))$""",
    )
}
