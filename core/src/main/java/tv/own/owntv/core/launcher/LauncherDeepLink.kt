package tv.own.owntv.core.launcher

import android.net.Uri
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity

/** Internal deep links used by launcher rows and external launch intents. */
sealed interface LauncherDeepLink {
    fun toUri(): Uri

    data object OpenLiveSection : LauncherDeepLink {
        override fun toUri(): Uri = Uri.parse("owntvfanart://open/live")
    }

    data class Movie(
        val sourceId: Long? = null,
        val remoteId: String? = null,
        val name: String? = null,
        val itemId: Long? = null,
    ) : LauncherDeepLink {
        override fun toUri(): Uri = Uri.parse("owntvfanart://play/movie").buildUpon()
            .appendOptionalQueryParameter("sourceId", sourceId?.toString())
            .appendOptionalQueryParameter("remoteId", remoteId)
            .appendOptionalQueryParameter("name", name)
            .appendOptionalQueryParameter("itemId", itemId?.toString())
            .build()
    }

    data class Episode(
        val seriesSourceId: Long? = null,
        val seriesRemoteId: String? = null,
        val seriesName: String? = null,
        val episodeRemoteId: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val seriesItemId: Long? = null,
        val episodeItemId: Long? = null,
    ) : LauncherDeepLink {
        override fun toUri(): Uri = Uri.parse("owntvfanart://play/episode").buildUpon()
            .appendOptionalQueryParameter("seriesSourceId", seriesSourceId?.toString())
            .appendOptionalQueryParameter("seriesRemoteId", seriesRemoteId)
            .appendOptionalQueryParameter("seriesName", seriesName)
            .appendOptionalQueryParameter("episodeRemoteId", episodeRemoteId)
            .appendOptionalQueryParameter("season", season?.toString())
            .appendOptionalQueryParameter("episode", episode?.toString())
            .appendOptionalQueryParameter("seriesId", seriesItemId?.toString())
            .appendOptionalQueryParameter("itemId", episodeItemId?.toString())
            .build()
    }

    data class Live(
        val sourceId: Long? = null,
        val remoteId: String? = null,
        val name: String? = null,
        val itemId: Long? = null,
    ) : LauncherDeepLink {
        override fun toUri(): Uri = Uri.parse("owntvfanart://play/live").buildUpon()
            .appendOptionalQueryParameter("sourceId", sourceId?.toString())
            .appendOptionalQueryParameter("remoteId", remoteId)
            .appendOptionalQueryParameter("name", name)
            .appendOptionalQueryParameter("itemId", itemId?.toString())
            .build()
    }

    /** glcn: a key pressed on the phone's remote ([RemoteKeys]), for the TV to act on as its own remote's. */
    data class RemoteKey(val name: String) : LauncherDeepLink {
        override fun toUri(): Uri = Uri.parse("owntvfanart://key/" + name)
    }

    /** glcn: words typed on the phone's keyboard: the TV searches for them. */
    data class RemoteText(val text: String) : LauncherDeepLink {
        override fun toUri(): Uri = Uri.parse("owntvfanart://text").buildUpon().appendQueryParameter("q", text).build()
    }

    companion object {
        fun parse(uri: Uri?): LauncherDeepLink? {
            if (uri == null || uri.scheme != "owntvfanart") return null
            return when (uri.host) {
                "key" -> uri.pathSegments.firstOrNull()?.takeIf { it in RemoteKeys.ALL }?.let { RemoteKey(it) }
                "text" -> uri.getQueryParameter("q")?.takeIf { it.isNotBlank() }?.let { RemoteText(it.take(RemoteKeys.TEXT_MAX)) }
                "open" -> when (uri.pathSegments.firstOrNull()) {
                    "live" -> OpenLiveSection
                    else -> null
                }
                "play" -> when (uri.pathSegments.firstOrNull()) {
                    "movie" -> Movie(
                        sourceId = uri.queryLong("sourceId"),
                        remoteId = uri.queryString("remoteId"),
                        name = uri.queryString("name"),
                        itemId = uri.queryLong("itemId"),
                    )
                    "episode" -> Episode(
                        seriesSourceId = uri.queryLong("seriesSourceId"),
                        seriesRemoteId = uri.queryString("seriesRemoteId"),
                        seriesName = uri.queryString("seriesName"),
                        episodeRemoteId = uri.queryString("episodeRemoteId"),
                        season = uri.queryInt("season"),
                        episode = uri.queryInt("episode"),
                        seriesItemId = uri.queryLong("seriesId"),
                        episodeItemId = uri.queryLong("itemId"),
                    )
                    "live" -> Live(
                        sourceId = uri.queryLong("sourceId"),
                        remoteId = uri.queryString("remoteId"),
                        name = uri.queryString("name"),
                        itemId = uri.queryLong("itemId"),
                    )
                    else -> null
                }
                else -> null
            }
        }

        private fun Uri.queryString(name: String): String? =
            getQueryParameter(name)?.trim()?.takeIf { it.isNotEmpty() }

        private fun Uri.queryLong(name: String): Long? =
            getQueryParameter(name)?.toLongOrNull()

        private fun Uri.queryInt(name: String): Int? =
            getQueryParameter(name)?.toIntOrNull()
    }
}

private fun Uri.Builder.appendOptionalQueryParameter(name: String, value: String?): Uri.Builder = apply {
    if (!value.isNullOrBlank()) appendQueryParameter(name, value)
}

sealed interface LauncherLaunch {
    data class Movie(val movie: MovieEntity, val startPositionMs: Long) : LauncherLaunch
    data class Episode(val show: SeriesEntity, val episode: EpisodeEntity, val queue: List<EpisodeEntity>, val startPositionMs: Long) : LauncherLaunch
    data class Series(val show: SeriesEntity) : LauncherLaunch
    data class Live(val channel: ChannelEntity) : LauncherLaunch
}

/** glcn: the keys the phone's remote sends, by name. */
object RemoteKeys {
    const val UP = "up"
    const val DOWN = "down"
    const val LEFT = "left"
    const val RIGHT = "right"
    const val OK = "ok"
    const val BACK = "back"
    const val PLAY_PAUSE = "playpause"
    const val CHANNEL_UP = "chup"
    const val CHANNEL_DOWN = "chdown"
    const val VOLUME_UP = "volup"
    const val VOLUME_DOWN = "voldown"
    const val MUTE = "mute"
    const val INFO = "info"
    const val GUIDE = "guide"
    val ALL = setOf(UP, DOWN, LEFT, RIGHT, OK, BACK, PLAY_PAUSE, CHANNEL_UP, CHANNEL_DOWN, VOLUME_UP, VOLUME_DOWN, MUTE, INFO, GUIDE)
    const val TEXT_MAX = 120
}
