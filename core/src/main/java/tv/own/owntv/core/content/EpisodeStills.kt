package tv.own.owntv.core.content

import tv.own.owntv.core.database.entity.EpisodeEntity

/**
 * Which provider pictures are really pictures of one episode. Panels often fill an episode's picture
 * with the show's poster, the same one for every episode; kept, that would put one poster on every
 * episode tile instead of the show's backdrop. So a picture is kept only when no other episode of the
 * show uses it and it is not the show's own art. Every sync path (Xtream, M3U, Stalker) goes through here.
 */
object EpisodeStills {

    /** [episodes] with every `imageUrl` that is shared by two episodes, or is in [showArt], set to null. */
    fun keepOwn(episodes: List<EpisodeEntity>, showArt: Set<String> = emptySet()): List<EpisodeEntity> {
        val uses = episodes.mapNotNull { it.imageUrl }.groupingBy { it }.eachCount()
        return episodes.map { e ->
            val url = e.imageUrl ?: return@map e
            if (uses.getValue(url) > 1 || url in showArt) e.copy(imageUrl = null) else e
        }
    }

    /** [raw] as a picture URL: trimmed, and only an `http(s)` address — a blank or a panel's relative path is none. */
    fun urlOrNull(raw: String?): String? =
        raw?.trim()?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
}
