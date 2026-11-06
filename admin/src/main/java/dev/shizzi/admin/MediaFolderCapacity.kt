package dev.shizzi.admin

import org.json.JSONObject

/** New routers allow any folder count; older routers still advertise their own limit. */
internal data class MediaFolderCapacity(private val maximum: Int?) {
    fun canAdd(count: Int): Boolean = maximum == null || count < maximum

    fun label(count: Int): String = if (maximum == null) {
        "Dossiers : $count · sans limite"
    } else {
        "Dossiers : $count/$maximum"
    }

    companion object {
        fun from(media: JSONObject): MediaFolderCapacity {
            val maximum = media.optInt("maxFolders", 10)
            val unlimited = media.optBoolean("unlimitedFolders", false) || maximum == Int.MAX_VALUE
            return MediaFolderCapacity(if (unlimited) null else maximum)
        }
    }
}
