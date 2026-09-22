package dev.shizzi

enum class HotspotBand {
    AUTO,
    BAND_2_4_GHZ,
    BAND_5_GHZ,
}

fun parseHotspotBand(raw: String?): HotspotBand =
    runCatching { HotspotBand.valueOf(raw.orEmpty()) }
        .getOrDefault(HotspotBand.AUTO)
