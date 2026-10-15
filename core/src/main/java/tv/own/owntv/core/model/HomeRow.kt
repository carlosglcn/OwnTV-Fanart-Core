package tv.own.owntv.core.model

import androidx.compose.runtime.Immutable

import org.json.JSONArray
import org.json.JSONObject

enum class HomeRow {
    TRENDING,
    HERO,
    RECENT_CHANNELS,
    FAVORITE_CHANNELS,
    FAVORITE_MOVIES,
    FAVORITE_SERIES,
    CONTINUE_MOVIES,
    CONTINUE_SERIES,
    // glcn
    SPORTS_NOW,
    RECENT_MOVIES,
    RECENT_SERIES,
    FEATURED,
    TONIGHT,
    GENRES;

    val implemented: Boolean get() = true
}

enum class HomeLiveRowMode {
    CARDS,
    ON_NOW;

    fun toggled(): HomeLiveRowMode = when (this) {
        CARDS -> ON_NOW
        ON_NOW -> CARDS
    }
}

/**
 * How the Trending row is drawn: the full card with the artwork, badges and reasons, or the plain
 * strip of posters. [HERO] is the default, so nothing changes for anyone who never opens the setting.
 */
enum class HomeTrendingStyle {
    HERO,
    POSTERS,
}

enum class HeroKind {
    LIVE, MOVIES, SERIES,
}

@Immutable
data class HomeConfig(
    val order: List<HomeRow> = GLCN_ORDER,
    val hidden: Set<HomeRow> = setOf(HomeRow.GENRES),
    val heroIncludeLive: Boolean = false,
    val heroIncludeMovies: Boolean = true,
    val heroIncludeSeries: Boolean = true,
    val recentLiveMode: HomeLiveRowMode = HomeLiveRowMode.ON_NOW,
    val favoriteLiveMode: HomeLiveRowMode = HomeLiveRowMode.ON_NOW,
    val trendingStyle: HomeTrendingStyle = HomeTrendingStyle.HERO,
) {
    val visibleOrder: List<HomeRow>
        get() = buildList {
            // Trending is a fixed, optional Home feature rather than a reorderable content row.
            // When enabled it always owns the top position, regardless of any previously saved order.
            if (HomeRow.TRENDING !in hidden) add(HomeRow.TRENDING)
            addAll(order.filter { it != HomeRow.TRENDING && it.implemented && it !in hidden })
        }

    val settingsRows: List<HomeRow>
        get() = order.filter { it != HomeRow.TRENDING && it.implemented }

    fun toJson(): JSONObject = JSONObject().apply {
        put("order", JSONArray(order.map { it.name }))
        put("hidden", JSONArray(hidden.map { it.name }))
        put("heroLive", heroIncludeLive)
        put("heroMovies", heroIncludeMovies)
        put("heroSeries", heroIncludeSeries)
        put("recentLiveMode", recentLiveMode.name)
        put("favoriteLiveMode", favoriteLiveMode.name)
        put("trendingStyle", trendingStyle.name)
        put("glcnGenresDefault", true)
        put("glcnLayoutV2", true)
    }

    companion object {
        fun fromJson(raw: String?): HomeConfig {
            if (raw.isNullOrBlank()) return HomeConfig()
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return HomeConfig()
            // glcn: the agreed Home layout, applied once over whatever was saved before it existed —
            // Featured, Keep watching (films and series only), Recent channels as pictures, Sports now,
            // Tonight, then the newest films and series. Changed afterwards in Settings, it stays changed.
            if (!obj.has("glcnLayoutV2")) {
                val old = fromJsonInner(obj)
                return old.copy(
                    order = GLCN_ORDER,
                    hidden = (old.hidden - HomeRow.RECENT_CHANNELS - HomeRow.FEATURED - HomeRow.SPORTS_NOW - HomeRow.TONIGHT) + HomeRow.GENRES,
                    heroIncludeLive = false,
                    recentLiveMode = HomeLiveRowMode.ON_NOW,
                )
            }
            return fromJsonInner(obj)
        }

        private fun fromJsonInner(obj: JSONObject): HomeConfig {
            val storedOrder = obj.optJSONArray("order").toHomeRows()
            val hidden = (if (obj.has("hidden")) obj.optJSONArray("hidden").toHomeRows().toSet()
                else HomeConfig().hidden)
                // glcn: the genre rows start hidden; a config saved before they existed hides them too.
                .let { if (!obj.has("glcnGenresDefault")) it + HomeRow.GENRES else it }
            return HomeConfig(
                order = mergeOrder(storedOrder),
                hidden = hidden,
                heroIncludeLive = readBool(obj, "heroLive", "heroIncludeLive", default = true),
                heroIncludeMovies = readBool(obj, "heroMovies", "heroIncludeMovies", default = true),
                heroIncludeSeries = readBool(obj, "heroSeries", "heroIncludeSeries", default = true),
                recentLiveMode = readLiveMode(obj, "recentLiveMode", HomeLiveRowMode.CARDS),
                favoriteLiveMode = readLiveMode(obj, "favoriteLiveMode", HomeLiveRowMode.ON_NOW),
                // Absent in every config written before the setting existed, and in every backup
                // taken then — those all read as the hero, which is what they were showing.
                trendingStyle = runCatching { HomeTrendingStyle.valueOf(obj.optString("trendingStyle")) }
                    .getOrDefault(HomeTrendingStyle.HERO),
            )
        }

        private fun readBool(obj: JSONObject, primary: String, fallback: String, default: Boolean): Boolean =
            when {
                obj.has(primary) -> obj.optBoolean(primary, default)
                obj.has(fallback) -> obj.optBoolean(fallback, default)
                else -> default
            }

        private fun readLiveMode(obj: JSONObject, key: String, default: HomeLiveRowMode): HomeLiveRowMode =
            runCatching { HomeLiveRowMode.valueOf(obj.optString(key)) }.getOrDefault(default)
    }
}

fun mergeOrder(stored: List<HomeRow>): List<HomeRow> {
    val result = LinkedHashSet<HomeRow>()
    // glcn: the featured billboard opens Home; "Tonight" sits beside the live rows.
    if (HomeRow.FEATURED !in stored) result += HomeRow.FEATURED
    stored.forEach { result += it }
    if (HomeRow.TONIGHT !in result) {
        val anchor = listOf(HomeRow.SPORTS_NOW, HomeRow.FAVORITE_CHANNELS).firstOrNull { it in result }
        if (anchor != null) {
            val list = result.toMutableList()
            list.add(list.indexOf(anchor) + 1, HomeRow.TONIGHT)
            result.clear(); result += list
        }
    }
    HomeRow.entries.forEachIndexed { index, row ->
        if (row !in result) {
            val current = result.toMutableList()
            current.add(index.coerceAtMost(current.size), row)
            result.clear()
            result += current
        }
    }
    return result.toList()
}

private fun JSONArray?.toHomeRows(): List<HomeRow> {
    if (this == null) return emptyList()
    val out = ArrayList<HomeRow>(length())
    for (i in 0 until length()) {
        val row = runCatching { HomeRow.valueOf(optString(i)) }.getOrNull() ?: continue
        if (row !in out) out += row
    }
    return out
}

/** glcn: Home's default order. */
val GLCN_ORDER: List<HomeRow> = mergeOrder(
    listOf(
        HomeRow.TRENDING, HomeRow.FEATURED, HomeRow.HERO, HomeRow.RECENT_CHANNELS, HomeRow.SPORTS_NOW,
        HomeRow.TONIGHT, HomeRow.RECENT_MOVIES, HomeRow.RECENT_SERIES, HomeRow.FAVORITE_CHANNELS,
        HomeRow.CONTINUE_MOVIES, HomeRow.CONTINUE_SERIES, HomeRow.FAVORITE_MOVIES, HomeRow.FAVORITE_SERIES, HomeRow.GENRES,
    ),
)
