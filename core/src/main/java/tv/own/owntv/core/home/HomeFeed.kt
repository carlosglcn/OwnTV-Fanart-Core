package tv.own.owntv.core.home

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ChannelWithWatchedAt
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.TrendingDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ContentOrderEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.TrendingItemEntity
import tv.own.owntv.core.launcher.LauncherContinuationItem
import tv.own.owntv.core.launcher.LauncherContinuationKind
import tv.own.owntv.core.launcher.LauncherRecommendationPlanner
import tv.own.owntv.core.launcher.LauncherWatchNextType
import tv.own.owntv.core.live.GuideReader
import tv.own.owntv.core.model.HomeConfig
import tv.own.owntv.core.model.HomeLiveRowMode
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeSourceIds
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.trending.TrendingMatcher

/** The item behind the big card at the top of Home — the last thing worth carrying on with. */
sealed interface HeroItem {
    val streamUrl: String
    val seekToMs: Long
    val positionMs: Long
    val durationMs: Long
    val watchNextType: LauncherWatchNextType
    val lastEngagementAt: Long

    /** Playlist this item came from — used to give the preview the same source-wide User-Agent the
     *  player uses. */
    val sourceId: Long

    /** This item's own `Key: Value` request headers (M3U), or null. Same reason as [sourceId]. */
    val httpHeaders: String?

    data class MovieHero(
        val movie: MovieEntity,
        val item: LauncherContinuationItem,
    ) : HeroItem {
        override val sourceId: Long = movie.sourceId
        override val httpHeaders: String? = movie.httpHeaders
        override val streamUrl: String = movie.streamUrl
        override val seekToMs: Long = (item.positionMs - HERO_REWIND_MS).coerceAtLeast(0L)
        override val positionMs: Long = item.positionMs
        override val durationMs: Long = item.durationMs
        override val watchNextType: LauncherWatchNextType = item.watchNextType
        override val lastEngagementAt: Long = item.lastEngagementAt
    }

    data class SeriesHero(
        val series: SeriesEntity,
        val episode: EpisodeEntity,
        val item: LauncherContinuationItem,
    ) : HeroItem {
        override val sourceId: Long = series.sourceId // episodes hang off the series, which owns the source
        override val httpHeaders: String? = episode.httpHeaders
        override val streamUrl: String = episode.streamUrl
        override val seekToMs: Long = if (item.watchNextType == LauncherWatchNextType.NEXT) {
            0L
        } else {
            (item.positionMs - HERO_REWIND_MS).coerceAtLeast(0L)
        }
        override val positionMs: Long = item.positionMs
        override val durationMs: Long = item.durationMs
        override val watchNextType: LauncherWatchNextType = item.watchNextType
        override val lastEngagementAt: Long = item.lastEngagementAt
    }

    data class LiveHero(
        val channel: ChannelEntity,
        val watchedAt: Long,
        /** glcn: what is on the channel when Home was read — its title and picture (XMLTV <icon>). */
        val now: tv.own.owntv.core.database.entity.EpgProgrammeEntity? = null,
    ) : HeroItem {
        override val sourceId: Long = channel.sourceId
        override val httpHeaders: String? = channel.httpHeaders
        override val streamUrl: String = channel.streamUrl
        override val seekToMs: Long = 0L
        override val positionMs: Long = 0L
        override val durationMs: Long = 0L
        override val watchNextType: LauncherWatchNextType = LauncherWatchNextType.CONTINUE
        override val lastEngagementAt: Long = watchedAt
    }
}

/** Identity of a hero item across reloads — what a metadata or artwork cache is keyed by. */
val HeroItem.homeKey: String
    get() = when (this) {
        is HeroItem.MovieHero -> "movie:${movie.id}"
        is HeroItem.SeriesHero -> "episode:${episode.id}"
        is HeroItem.LiveHero -> "live:${channel.id}"
    }

/** A trending title from TMDB that was matched to something the user's own playlist actually has. */
@Immutable
sealed interface TrendingHomeItem {
    val snapshot: TrendingItemEntity
    val stableKey: String get() = "${snapshot.mediaType.name}:${snapshot.tmdbId}"

    data class Movie(
        override val snapshot: TrendingItemEntity,
        val movie: MovieEntity,
    ) : TrendingHomeItem

    data class Series(
        override val snapshot: TrendingItemEntity,
        val series: SeriesEntity,
    ) : TrendingHomeItem
}

/** glcn: one slide of Home's featured billboard. */
@Immutable
sealed interface FeaturedItem {
    val art: String
    val key: String

    /** On air now: a match, or what a favourite channel is showing. */
    data class Live(val channel: ChannelEntity, val programme: EpgProgrammeEntity, val isLiveEvent: Boolean) : FeaturedItem {
        override val art: String get() = programme.imageUrl.orEmpty()
        override val key: String get() = "live:${channel.id}:${programme.startMs}"
    }

    data class Movie(val movie: MovieEntity) : FeaturedItem {
        override val art: String get() = movie.backdropUrl.orEmpty()
        override val key: String get() = "movie:${movie.id}"
    }

    data class Series(val series: SeriesEntity) : FeaturedItem {
        override val art: String get() = series.backdropUrl.orEmpty()
        override val key: String get() = "series:${series.id}"
    }
}

/** glcn: a programme later tonight on one of the user's channels. */
@Immutable
data class TonightItem(val channel: ChannelEntity, val programme: EpgProgrammeEntity)

/** glcn: one genre row of films. */
@Immutable
data class GenreRow(val categoryId: Long, val name: String, val movies: List<MovieEntity>)

/** The guide behind a live rail in "On now" mode: the channels, and their programmes in the window. */
@Immutable
data class GuideSliceState(
    val channels: List<ChannelEntity> = emptyList(),
    val programmes: Map<Long, List<EpgProgrammeEntity>> = emptyMap(),
    val windowStart: Long = 0L,
    val windowEnd: Long = 0L,
    val now: Long = 0L,
) {
    val hasContent: Boolean
        get() = channels.isNotEmpty()
}

/** Everything Home shows, for one profile, at one moment. */
@Immutable
data class HomeFeed(
    val trendingItems: List<TrendingHomeItem> = emptyList(),
    val trendingPreferredLanguage: String = "EN",
    val trendingSeasonCounts: Map<Long, Int> = emptyMap(),
    val heroItems: List<HeroItem> = emptyList(),
    val continueMovies: List<LauncherContinuationItem> = emptyList(),
    val continueSeries: List<LauncherContinuationItem> = emptyList(),
    val recentLive: List<ChannelEntity> = emptyList(),
    val favoriteLive: List<ChannelEntity> = emptyList(),
    val favoriteMovies: List<MovieEntity> = emptyList(),
    val favoriteSeries: List<SeriesEntity> = emptyList(),
    val config: HomeConfig = HomeConfig(),
    val recentGuide: GuideSliceState = GuideSliceState(),
    val favoriteGuide: GuideSliceState = GuideSliceState(),
    /** glcn: channels showing sport right now, live events first. */
    val sportsNow: List<tv.own.owntv.core.live.SportsNow.Entry> = emptyList(),
    /** glcn: newest arrivals, for playlists that say when an item arrived (Emby, Xtream). */
    val recentMovies: List<MovieEntity> = emptyList(),
    val recentSeries: List<SeriesEntity> = emptyList(),
    val featured: List<FeaturedItem> = emptyList(),
    val tonight: List<TonightItem> = emptyList(),
    val genres: List<GenreRow> = emptyList(),
    /** The active profile's name, for the greeting. */
    val profileName: String? = null,
) {
    /** glcn: [sportsNow] in the shape the "On now" live rails draw. */
    val sportsGuide: GuideSliceState
        get() {
            if (sportsNow.isEmpty()) return GuideSliceState()
            val now = System.currentTimeMillis()
            return GuideSliceState(
                channels = sportsNow.map { it.channel },
                programmes = sportsNow.associate { it.channel.id to listOf(it.programme) },
                windowStart = sportsNow.minOf { it.programme.startMs },
                windowEnd = sportsNow.maxOf { it.programme.stopMs },
                now = now,
            )
        }
}

/**
 * Building Home's rails.
 *
 * This is fifteen dependent reads over history, progress, both catalogues, the channel list, the
 * customizations and the guide, and the *rules* between them are the app's, not any one screen's:
 * which playlists count, what a kids profile may not see, what the user hid, how trending titles are
 * deduplicated across playlists, and which items are eligible to be the hero. A television and a
 * phone lay Home out completely differently and must still agree, item for item, on what is in it.
 *
 * The independent reads are started together rather than one after another. WAL lets SQLite serve
 * concurrent readers, so that is real overlap: the longest of them, `buildContinuationItems`
 * (150–400 ms on a TCL), finishes while the source-id, config and favourites reads happen.
 */
class HomeFeedReader(
    private val planner: LauncherRecommendationPlanner,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val customize: CustomizationStore,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
    private val profileDao: ProfileDao,
    private val trendingDao: TrendingDao,
    private val guide: GuideReader,
) {
    suspend fun load(profileId: Long): HomeFeed = withContext(Dispatchers.IO) {
        coroutineScope {
            val configAsync = async { settings.homeConfig(profileId).first() }
            val metadataAsync = async { settings.metadataConfig() }
            // Active-playlist filter + per-section enabledScope: Home rails never show Off sections.
            val liveIdsAsync = async { activeSourceIds(settings, sourceDao, profileId, MediaType.LIVE).toSet() }
            val movieIdsAsync = async { activeSourceIds(settings, sourceDao, profileId, MediaType.MOVIE).toSet() }
            val seriesIdsAsync = async { activeSourceIds(settings, sourceDao, profileId, MediaType.SERIES).toSet() }
            val allIdsAsync = async { sourceDao.sourceIdsForProfile(profileId).toSet() }
            val continuationAsync = async { planner.buildContinuationItems(profileId) }
            val favouritesAsync = async { channelDao.favoritesListAlpha(profileId).first() }

            val config = configAsync.await()
            val metadataConfig = metadataAsync.await()
            val preferredLanguage = metadataConfig.resolvedLanguage.substringBefore('-').ifBlank { "en" }.uppercase()
            val liveIds = liveIdsAsync.await()
            val movieIds = movieIdsAsync.await()
            val seriesIds = seriesIdsAsync.await()

            // Hidden items / hidden categories (per profile) never surface on Home either.
            val hidden = hiddenState(profileId, allIdsAsync.await().toList())
            // Trending and the recent-live query are independent of each other too.
            val recentLiveAsync = async {
                channelDao.recentlyWatchedWithTimestampFiltered(profileId, liveIds.toList(), RECENT_LIVE_ROW_LIMIT).first()
            }
            val trending = buildTrendingItems(
                movieSourceIds = movieIds,
                seriesSourceIds = seriesIds,
                hidden = hidden,
                sourceOrder = (movieIds + seriesIds).distinct().toList(),
                metadataEnabled = metadataConfig.enabled,
                preferredLanguage = preferredLanguage,
            )
            val trendingSeriesIds = trending.filterIsInstance<TrendingHomeItem.Series>().map { it.series.id }
            val trendingSeasonCounts = if (trendingSeriesIds.isEmpty()) emptyMap() else {
                seriesDao.storedSeasonCounts(trendingSeriesIds).associate { it.seriesId to it.seasonCount }
            }

            val items = continuationAsync.await()
                .filter { item ->
                    val sid = continuationSourceId(item) ?: return@filter false
                    when (item.kind) {
                        LauncherContinuationKind.MOVIE -> sid in movieIds
                        LauncherContinuationKind.EPISODE -> sid in seriesIds
                        LauncherContinuationKind.LIVE -> sid in liveIds
                    }
                }
                .filterNot { isContinuationHidden(it, hidden) }
            val movies = items.filter { it.kind == LauncherContinuationKind.MOVIE }
            val series = items.filter { it.kind == LauncherContinuationKind.EPISODE }
            val liveWithTs = recentLiveAsync.await().filterNot { isChannelHidden(it.channel, hidden) }
            val live = liveWithTs.map { it.channel }
            val favLive = favouritesAsync.await()
                .filter { c -> c.sourceId in liveIds }
                .filterNot { isChannelHidden(it, hidden) }
            // Favourites in the user's own Favorites order, so Home and the Favorites folder agree.
            val favMoviesAsync = async {
                if (HomeRow.FAVORITE_MOVIES !in config.visibleOrder || movieIds.isEmpty()) emptyList()
                else movieDao.snapshotFavoritesManual(profileId, ContentOrderEntity.FAV_CONTEXT, movieIds.toList(), FAVORITE_VOD_ROW_LIMIT)
                    .filterNot { CustomizeKeys.movie(it) in hidden.movie.hiddenItems || (it.categoryId != null && it.categoryId in hidden.movieCats) }
            }
            val favSeriesAsync = async {
                if (HomeRow.FAVORITE_SERIES !in config.visibleOrder || seriesIds.isEmpty()) emptyList()
                else seriesDao.snapshotFavoritesManual(profileId, ContentOrderEntity.FAV_CONTEXT, seriesIds.toList(), FAVORITE_VOD_ROW_LIMIT)
                    .filterNot { CustomizeKeys.series(it) in hidden.series.hiddenItems || (it.categoryId != null && it.categoryId in hidden.seriesCats) }
            }
            // glcn rows.
            val sportsAsync = async {
                if ((HomeRow.SPORTS_NOW !in config.visibleOrder && HomeRow.FEATURED !in config.visibleOrder) || liveIds.isEmpty()) emptyList()
                else runCatching {
                    tv.own.owntv.core.live.SportsNow(channelDao, guide)
                        .find(liveIds.toList(), hidden.live, settings.epgOffsetMinutes.first())
                        .filterNot { isChannelHidden(it.channel, hidden) || isBarChannel(it.channel) }
                        .take(SPORTS_ROW_LIMIT)
                }.getOrDefault(emptyList())
            }
            val recentMoviesAsync = async {
                if ((HomeRow.RECENT_MOVIES !in config.visibleOrder && HomeRow.FEATURED !in config.visibleOrder) || movieIds.isEmpty()) emptyList()
                else movieDao.recentlyAdded(movieIds.toList(), RECENT_VOD_ROW_LIMIT * 2)
                    .filterNot { CustomizeKeys.movie(it) in hidden.movie.hiddenItems || (it.categoryId != null && it.categoryId in hidden.movieCats) }
                    .take(RECENT_VOD_ROW_LIMIT)
            }
            val recentSeriesAsync = async {
                if ((HomeRow.RECENT_SERIES !in config.visibleOrder && HomeRow.FEATURED !in config.visibleOrder) || seriesIds.isEmpty()) emptyList()
                else seriesDao.recentlyAdded(seriesIds.toList(), RECENT_VOD_ROW_LIMIT * 2)
                    .filterNot { CustomizeKeys.series(it) in hidden.series.hiddenItems || (it.categoryId != null && it.categoryId in hidden.seriesCats) }
                    .take(RECENT_VOD_ROW_LIMIT)
            }
            val shiftAsync = async { settings.epgOffsetMinutes.first() }
            // glcn: what the favourite channels are showing now, for the billboard.
            val favNowAsync = async {
                if (HomeRow.FEATURED !in config.visibleOrder || favLive.isEmpty()) emptyMap()
                else runCatching {
                    val t = System.currentTimeMillis()
                    guide.slice(favLive.filterNot(::isBarChannel).take(60), hidden.live, shiftAsync.await(), t, t + 1)
                        .mapNotNull { (id, rows) -> rows.firstOrNull { t in it.startMs until it.stopMs }?.let { id to it } }
                        .toMap()
                }.getOrDefault(emptyMap())
            }
            // glcn: what the viewer usually watches, if it is on air right now (see HabitTracker).
            val habitNowAsync = async {
                if (HomeRow.FEATURED !in config.visibleOrder) emptyList()
                else runCatching {
                    val habits = org.koin.core.context.GlobalContext.get()
                        .get<tv.own.owntv.core.reminder.HabitTracker>().habits(profileId)
                    val channels = habits.take(20).mapNotNull { h ->
                        channelDao.getById(h.channelId)?.takeIf { it.sourceId in liveIds && !isChannelHidden(it, hidden) }
                    }
                    if (channels.isEmpty()) emptyList()
                    else {
                        val t = System.currentTimeMillis()
                        guide.slice(channels, hidden.live, shiftAsync.await(), t, t + 1).mapNotNull { (id, rows) ->
                            val p = rows.firstOrNull { t in it.startMs until it.stopMs } ?: return@mapNotNull null
                            val ch = channels.firstOrNull { it.id == id } ?: return@mapNotNull null
                            if (tv.own.owntv.core.reminder.HabitRules.isHabit(habits, ch.id, p.title)) ch to p else null
                        }
                    }
                }.getOrDefault(emptyList())
            }
            // glcn: tonight's programmes (21:00–24:00) on the favourite channels, else the recent ones.
            val tonightAsync = async {
                if (HomeRow.TONIGHT !in config.visibleOrder) emptyList()
                else runCatching {
                    // Favourites and the recently watched together: either alone is often too few
                    // channels with a guide for the evening to show anything.
                    val channels = (favLive + live).distinctBy { it.id }.filterNot(::isBarChannel).take(80)
                    if (channels.isEmpty()) return@runCatching emptyList()
                    val t = System.currentTimeMillis()
                    val cal = java.util.Calendar.getInstance().apply {
                        timeInMillis = t; set(java.util.Calendar.HOUR_OF_DAY, 21)
                        set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    }
                    val from = maxOf(t, cal.timeInMillis)
                    val to = cal.timeInMillis + 3 * 3_600_000L
                    if (from >= to) return@runCatching emptyList()
                    val shift = shiftAsync.await()
                    val byId = channels.associateBy { it.id }
                    fun window(rows: List<EpgProgrammeEntity>) =
                        rows.filter { it.startMs in from until to && it.stopMs - it.startMs >= 20 * 60_000L }
                    // The user's own channels: their evening, with a series' back-to-back episodes once.
                    val mine = guide.slice(channels, hidden.live, shift, from, to).flatMap { (id, rows) ->
                        val ch = byId[id] ?: return@flatMap emptyList()
                        window(rows)
                            .distinctBy { tv.own.owntv.core.metadata.ProgrammeArtResolver.fold(tv.own.owntv.core.metadata.ProgrammeArtResolver.clean(it.title)) }
                            .take(2).map { TonightItem(ch, it) }
                    }
                    // Every other channel: only the evening's highlights — a live match, a premiere, a film.
                    val others = runCatching {
                        channelDao.allForSources(liveIds.toList(), 5_000)
                            .filter { it.id !in byId && !isChannelHidden(it, hidden) && !isBarChannel(it) && TONIGHT_CHANNEL.containsMatchIn(it.name) }
                    }.getOrDefault(emptyList())
                    val othersById = others.associateBy { it.id }
                    val highlights = if (others.isEmpty()) emptyList() else guide.slice(others, hidden.live, shift, from, to)
                        .flatMap { (id, rows) ->
                            val ch = othersById[id] ?: return@flatMap emptyList()
                            window(rows).filter { tonightRank(it) <= 2 }.take(1).map { TonightItem(ch, it) }
                        }
                        .sortedWith(compareBy<TonightItem> { tonightRank(it.programme) }.thenBy { it.programme.startMs })
                        .take(TONIGHT_HIGHLIGHTS)
                    (mine + highlights)
                        // The same programme on an HD and an SD copy of a channel shows once.
                        .distinctBy { tv.own.owntv.core.metadata.ProgrammeArtResolver.fold(it.programme.title) + "|" + it.programme.startMs }
                        .sortedWith(
                            // Highlights first, then the evening in order, news and talk last.
                            compareBy<TonightItem> { tonightRank(it.programme).let { r -> if (r <= 2) 0 else if (r == 3) 1 else 2 } }
                                .thenBy { it.programme.startMs }
                                .thenBy { it.programme.imageUrl.isNullOrBlank() },
                        )
                        .take(TONIGHT_LIMIT)
                }.getOrDefault(emptyList())
            }
            // glcn: the biggest film genres (Emby's genres are its categories).
            val genresAsync = async {
                if (HomeRow.GENRES !in config.visibleOrder || movieIds.isEmpty()) emptyList()
                else runCatching {
                    val names = categoryDao.observe(movieIds.toList(), MediaType.MOVIE).first().associate { it.id to it.name }
                    movieDao.biggestCategories(movieIds.toList(), 12)
                        .filter { it.categoryId !in hidden.movieCats && it.count >= 4 }
                        .take(GENRE_ROWS)
                        .mapNotNull { c ->
                            val name = names[c.categoryId] ?: return@mapNotNull null
                            val movies = movieDao.inCategory(c.categoryId, 24)
                                .filterNot { CustomizeKeys.movie(it) in hidden.movie.hiddenItems }
                            if (movies.size < 4) null else GenreRow(c.categoryId, name, movies)
                        }
                }.getOrDefault(emptyList())
            }
            val profileNameAsync = async { runCatching { profileDao.getById(profileId)?.name }.getOrNull() }
            val heroItems = withLiveNow(profileId, buildHeroItems(items, liveWithTs, config))
            // The two guide slices read different channel sets and never depend on each other.
            val recentGuideAsync = async {
                if (HomeRow.RECENT_CHANNELS in config.visibleOrder && config.recentLiveMode == HomeLiveRowMode.ON_NOW) {
                    liveGuide(profileId, liveIds, live)
                } else {
                    GuideSliceState()
                }
            }
            val favoriteGuideAsync = async {
                if (HomeRow.FAVORITE_CHANNELS in config.visibleOrder && config.favoriteLiveMode == HomeLiveRowMode.ON_NOW) {
                    liveGuide(profileId, liveIds, favLive)
                } else {
                    GuideSliceState()
                }
            }

            HomeFeed(
                trendingItems = trending,
                trendingPreferredLanguage = preferredLanguage,
                trendingSeasonCounts = trendingSeasonCounts,
                heroItems = heroItems,
                continueMovies = movies,
                continueSeries = series,
                recentLive = live,
                favoriteLive = favLive,
                favoriteMovies = favMoviesAsync.await(),
                favoriteSeries = favSeriesAsync.await(),
                config = config,
                recentGuide = recentGuideAsync.await(),
                favoriteGuide = favoriteGuideAsync.await(),
                sportsNow = sportsAsync.await(),
                recentMovies = recentMoviesAsync.await(),
                recentSeries = recentSeriesAsync.await(),
                featured = buildFeatured(
                    sports = sportsAsync.await(),
                    habitNow = habitNowAsync.await(),
                    favourites = favLive,
                    favNow = favNowAsync.await(),
                    movies = recentMoviesAsync.await(),
                    series = recentSeriesAsync.await(),
                    enabled = HomeRow.FEATURED in config.visibleOrder,
                ),
                tonight = tonightAsync.await(),
                genres = genresAsync.await(),
                profileName = profileNameAsync.await(),
            )
        }
    }

    /** glcn: the billboard's slides — live matches, then what the viewer usually watches, then favourites on air, then the newest films and shows. */
    private fun buildFeatured(
        sports: List<tv.own.owntv.core.live.SportsNow.Entry>,
        habitNow: List<Pair<ChannelEntity, EpgProgrammeEntity>>,
        favourites: List<ChannelEntity>,
        favNow: Map<Long, EpgProgrammeEntity>,
        movies: List<MovieEntity>,
        series: List<SeriesEntity>,
        enabled: Boolean,
    ): List<FeaturedItem> {
        if (!enabled) return emptyList()
        val out = ArrayList<FeaturedItem>()
        val seen = HashSet<Long>()
        sports.filter { !it.programme.imageUrl.isNullOrBlank() }.take(3).forEach {
            if (seen.add(it.channel.id)) out += FeaturedItem.Live(it.channel, it.programme, tv.own.owntv.core.live.SportsNow.isLive(it.programme))
        }
        // glcn: what the viewer usually watches, on air now — right after live matches.
        habitNow.filter { (_, p) -> !p.imageUrl.isNullOrBlank() }
            .filter { (ch, _) -> ch.id !in seen }
            .take(2)
            .forEach { (ch, p) -> seen += ch.id; out += FeaturedItem.Live(ch, p, tv.own.owntv.core.live.SportsNow.isLive(p)) }
        favourites.mapNotNull { ch -> favNow[ch.id]?.takeIf { !it.imageUrl.isNullOrBlank() }?.let { ch to it } }
            .filter { (ch, _) -> ch.id !in seen }
            .take(3)
            .forEach { (ch, p) -> seen += ch.id; out += FeaturedItem.Live(ch, p, tv.own.owntv.core.live.SportsNow.isLive(p)) }
        movies.filter { !it.backdropUrl.isNullOrBlank() }.take(2).forEach { out += FeaturedItem.Movie(it) }
        series.filter { !it.backdropUrl.isNullOrBlank() }.take(2).forEach { out += FeaturedItem.Series(it) }
        return out
    }

    /** This profile's hide customizations across all three sections, with category keys resolved to ids. */
    private data class HiddenState(
        val live: SectionCustomizations,
        val movie: SectionCustomizations,
        val series: SectionCustomizations,
        val liveCats: Set<Long>,
        val movieCats: Set<Long>,
        val seriesCats: Set<Long>,
    ) {
        val isEmpty: Boolean
            get() = live.hiddenItems.isEmpty() && movie.hiddenItems.isEmpty() && series.hiddenItems.isEmpty() &&
                liveCats.isEmpty() && movieCats.isEmpty() && seriesCats.isEmpty()
    }

    private suspend fun hiddenState(profileId: Long, sourceIds: List<Long>): HiddenState {
        val live = customize.observe(profileId, MediaType.LIVE).first()
        val movie = customize.observe(profileId, MediaType.MOVIE).first()
        val series = customize.observe(profileId, MediaType.SERIES).first()
        val isKidsProfile = profileDao.getById(profileId)?.isKids == true
        suspend fun catIds(type: MediaType, hiddenKeys: Set<String>): Set<Long> {
            if ((hiddenKeys.isEmpty() && !isKidsProfile) || sourceIds.isEmpty()) return emptySet()
            return AdultCategoryClassifier.hiddenCategoryIds(
                categoryDao.observe(sourceIds, type).first(),
                hiddenKeys,
                isKidsProfile,
            )
        }
        return HiddenState(
            live = live, movie = movie, series = series,
            liveCats = catIds(MediaType.LIVE, live.hiddenCategories),
            movieCats = catIds(MediaType.MOVIE, movie.hiddenCategories),
            seriesCats = catIds(MediaType.SERIES, series.hiddenCategories),
        )
    }

    private fun isChannelHidden(ch: ChannelEntity, h: HiddenState): Boolean =
        CustomizeKeys.channel(ch) in h.live.hiddenItems || (ch.categoryId != null && ch.categoryId in h.liveCats)

    private suspend fun buildTrendingItems(
        movieSourceIds: Set<Long>,
        seriesSourceIds: Set<Long>,
        hidden: HiddenState,
        sourceOrder: List<Long>,
        metadataEnabled: Boolean,
        preferredLanguage: String,
    ): List<TrendingHomeItem> {
        if (!metadataEnabled) return emptyList()
        val sourceIds = (movieSourceIds + seriesSourceIds).toList()
        if (sourceIds.isEmpty()) return emptyList()
        val snapshots = trendingDao.getItemsForSources(sourceIds)
        if (snapshots.isEmpty()) return emptyList()

        val movieRows = movieDao.getByIds(snapshots.filter { it.mediaType == MediaType.MOVIE }.map { it.providerItemId })
            .associateBy { it.id }
        val seriesRows = seriesDao.getSeriesByIds(snapshots.filter { it.mediaType == MediaType.SERIES }.map { it.providerItemId })
            .associateBy { it.id }
        val resolved = snapshots.mapNotNull { snapshot ->
            when (snapshot.mediaType) {
                MediaType.MOVIE -> movieRows[snapshot.providerItemId]
                    ?.takeIf { it.sourceId in movieSourceIds }
                    ?.takeUnless { CustomizeKeys.movie(it) in hidden.movie.hiddenItems || (it.categoryId != null && it.categoryId in hidden.movieCats) }
                    ?.let { TrendingHomeItem.Movie(snapshot, it) }
                MediaType.SERIES -> seriesRows[snapshot.providerItemId]
                    ?.takeIf { it.sourceId in seriesSourceIds }
                    ?.takeUnless { CustomizeKeys.series(it) in hidden.series.hiddenItems || (it.categoryId != null && it.categoryId in hidden.seriesCats) }
                    ?.let { TrendingHomeItem.Series(snapshot, it) }
                else -> null
            }
        }
        val sourceRanks = sourceOrder.withIndex().associate { it.value to it.index }
        val deduplicated = resolved
            .groupBy { it.snapshot.mediaType to it.snapshot.tmdbId }
            .values
            .map { variants ->
                variants.minWith(
                    compareBy<TrendingHomeItem> { homeLanguageRank(it.snapshot.providerLanguage, preferredLanguage) }
                        .thenByDescending { homeQualityRank(it.snapshot.advertisedQuality) }
                        .thenBy { sourceRanks[it.snapshot.sourceId] ?: Int.MAX_VALUE }
                        .thenBy { it.snapshot.position },
                )
            }
        val movies = deduplicated.filter { it.snapshot.mediaType == MediaType.MOVIE }
            .sortedBy { it.snapshot.trendingRank }
            .take(TrendingMatcher.MAX_PER_MEDIA_TYPE)
        val series = deduplicated.filter { it.snapshot.mediaType == MediaType.SERIES }
            .sortedBy { it.snapshot.trendingRank }
            .take(TrendingMatcher.MAX_PER_MEDIA_TYPE)
        val seriesTarget = if (movies.size >= 5) 5 else TrendingMatcher.MAX_TOTAL - movies.size
        val selectedSeries = series.take(seriesTarget)
        val selectedMovies = movies.take(TrendingMatcher.MAX_TOTAL - selectedSeries.size)
        val interleaved = buildList {
            for (index in 0 until maxOf(selectedMovies.size, selectedSeries.size)) {
                selectedMovies.getOrNull(index)?.let(::add)
                selectedSeries.getOrNull(index)?.let(::add)
            }
        }.take(TrendingMatcher.MAX_TOTAL)
        return interleaved.takeIf { it.size >= TrendingDao.MIN_ELIGIBLE_ITEMS }.orEmpty()
    }

    private fun homeLanguageRank(language: String?, preferred: String): Int = when {
        language == preferred -> 0
        language == "EN" -> 1
        language == null -> 2
        else -> 3
    }

    private fun homeQualityRank(quality: String?): Int = when (quality) {
        "8K" -> 5
        "4K", "4K UHD" -> 4
        "FHD", "1080p FHD" -> 3
        "HD", "720p HD" -> 2
        "SD" -> 1
        else -> 0
    }

    private suspend fun isContinuationHidden(item: LauncherContinuationItem, h: HiddenState): Boolean {
        if (h.isEmpty) return false
        return when (item.kind) {
            LauncherContinuationKind.MOVIE -> movieDao.getById(item.sourceItemId)?.let {
                CustomizeKeys.movie(it) in h.movie.hiddenItems || (it.categoryId != null && it.categoryId in h.movieCats)
            } ?: false
            LauncherContinuationKind.EPISODE -> seriesDao.getEpisodeById(item.targetItemId)?.let { ep ->
                seriesDao.getSeriesById(ep.seriesId)?.let { s ->
                    CustomizeKeys.series(s) in h.series.hiddenItems || (s.categoryId != null && s.categoryId in h.seriesCats)
                }
            } ?: false
            LauncherContinuationKind.LIVE -> channelDao.getById(item.sourceItemId)?.let { isChannelHidden(it, h) } ?: false
        }
    }

    /** The playlist a continuation item belongs to, for the active-playlist filter (null if it's gone). */
    private suspend fun continuationSourceId(item: LauncherContinuationItem): Long? = when (item.kind) {
        LauncherContinuationKind.MOVIE -> movieDao.getById(item.sourceItemId)?.sourceId
        LauncherContinuationKind.EPISODE ->
            seriesDao.getEpisodeById(item.targetItemId)?.let { seriesDao.getSeriesById(it.seriesId)?.sourceId }
        LauncherContinuationKind.LIVE -> channelDao.getById(item.sourceItemId)?.sourceId
    }

    private suspend fun buildHeroItems(
        continuationItems: List<LauncherContinuationItem>,
        liveChannels: List<ChannelWithWatchedAt>,
        config: HomeConfig,
    ): List<HeroItem> {
        data class Candidate(val engagedAt: Long, val resolve: suspend () -> HeroItem?)

        val candidates = mutableListOf<Candidate>()

        continuationItems.forEach { item ->
            when (item.kind) {
                LauncherContinuationKind.MOVIE -> if (config.heroIncludeMovies) {
                    candidates += Candidate(item.lastEngagementAt) {
                        val movie = movieDao.getById(item.sourceItemId) ?: return@Candidate null
                        HeroItem.MovieHero(movie, item)
                    }
                }
                LauncherContinuationKind.EPISODE -> if (config.heroIncludeSeries) {
                    candidates += Candidate(item.lastEngagementAt) {
                        val episode = seriesDao.getEpisodeById(item.targetItemId) ?: return@Candidate null
                        val series = seriesDao.getSeriesById(episode.seriesId) ?: return@Candidate null
                        HeroItem.SeriesHero(series, episode, item)
                    }
                }
                LauncherContinuationKind.LIVE -> Unit
            }
        }

        if (config.heroIncludeLive) {
            liveChannels.forEach { watched ->
                candidates += Candidate(watched.watchedAt) {
                    HeroItem.LiveHero(watched.channel, watched.watchedAt)
                }
            }
        }

        val result = mutableListOf<HeroItem>()
        for (candidate in candidates.sortedByDescending { it.engagedAt }) {
            if (result.size >= MAX_HERO_ITEMS) break
            candidate.resolve()?.let { result += it }
        }
        return result
    }

    /** glcn: the programme on now for each live hero card, so the card can show its picture and title. */
    private suspend fun withLiveNow(profileId: Long, items: List<HeroItem>): List<HeroItem> {
        val live = items.filterIsInstance<HeroItem.LiveHero>().map { it.channel }
        if (live.isEmpty()) return items
        val now = System.currentTimeMillis()
        val found = runCatching {
            guide.slice(
                channels = live,
                cust = customize.observe(profileId, MediaType.LIVE).first(),
                globalShiftMinutes = settings.epgOffsetMinutes.first(),
                from = now,
                to = now + 1,
            )
        }.getOrNull() ?: return items
        return items.map { item ->
            if (item is HeroItem.LiveHero) {
                item.copy(now = found[item.channel.id]?.firstOrNull { now in it.startMs until it.stopMs })
            } else item
        }
    }

    /** A live rail in "On now" mode: six hours of guide for the channels it is about to draw. */
    private suspend fun liveGuide(
        profileId: Long,
        activeIds: Set<Long>,
        channels: List<ChannelEntity>,
    ): GuideSliceState {
        val now = System.currentTimeMillis()
        val windowStart = now / HALF_HOUR_MS * HALF_HOUR_MS
        val windowEnd = windowStart + SLICE_WINDOW_MS
        if (channels.isEmpty()) return GuideSliceState(now = now, windowStart = windowStart, windowEnd = windowEnd)

        return GuideSliceState(
            channels = channels,
            programmes = guide.slice(
                channels = channels,
                cust = customize.observe(profileId, MediaType.LIVE).first(),
                globalShiftMinutes = settings.epgOffsetMinutes.first(),
                from = windowStart,
                to = windowEnd,
            ),
            windowStart = windowStart,
            windowEnd = windowEnd,
            now = now,
        )
    }
}

/** How far a resumed item is rewound, so it starts just before where the user stopped. */
private const val HERO_REWIND_MS = 10_000L

private const val MAX_HERO_ITEMS = 10
private const val SLICE_WINDOW_MS = 360 * 60_000L
private const val HALF_HOUR_MS = 30 * 60_000L
private const val RECENT_LIVE_ROW_LIMIT = 20
private const val FAVORITE_VOD_ROW_LIMIT = 50
private const val RECENT_VOD_ROW_LIMIT = 30
private const val SPORTS_ROW_LIMIT = 20
private const val TONIGHT_LIMIT = 24
private const val GENRE_ROWS = 4

private val TONIGHT_PREMIERE = Regex("""\b(estreno|premiere|stream premiere)\b""")
private val TONIGHT_FILM = Regex("""\b(cine|pelicula|peliculas|film|movie)\b""")
private val TONIGHT_LOW = Regex("""\b(informativo|informativos|noticias|news|telediario|tiempo|magazine|tertulia|debate|concurso|teletienda|tarot|late night)\b""")
private const val TONIGHT_HIGHLIGHTS = 10

/** Channels worth scanning for the evening's highlights — sport and film channels — so Home stays quick. */
private val TONIGHT_CHANNEL = Regex(
    """(?i)(deport|sport|liga|futbol|fútbol|champions|dazn|eurosport|gol|f1|motor|cine|cinema|movie|film|estreno|accion|acción|comedia|drama|xtrm|hollywood|tcm|paramount|amc|axn|tnt|fox|syfy|scifi|calle 13|cosmo|dark|warner|star|clan)""",
)

/**
 * glcn: how "Tonight" weighs a programme: 0 a live match, 1 a premiere, 2 a film, 3 a series or
 * anything else, 4 news, talk and quiz shows.
 */
internal fun tonightRank(p: EpgProgrammeEntity): Int {
    val title = tv.own.owntv.core.metadata.ProgrammeArtResolver.fold(p.title)
    val f = title + " " + (p.categories?.let(tv.own.owntv.core.metadata.ProgrammeArtResolver::fold) ?: "")
    val match = tv.own.owntv.core.live.SportsNow.isLive(p) || TONIGHT_MATCH.containsMatchIn(p.title)
    return when {
        tv.own.owntv.core.live.SportsNow.isSport(p) && match -> 0
        TONIGHT_PREMIERE.containsMatchIn(f) -> 1
        TONIGHT_FILM.containsMatchIn(f) -> 2
        TONIGHT_LOW.containsMatchIn(f) -> 4
        else -> 3
    }
}

/** "Real Madrid - Barcelona", "Lakers vs Celtics". */
private val TONIGHT_MATCH = Regex("""\S\s+(-|–|vs\.?|v)\s+\S""", RegexOption.IGNORE_CASE)

/**
 * glcn: the bar/pub feeds of a playlist ("M+ LALIGA BAR", "VAMOS BAR") — the same matches as the home
 * channels, licensed for venues. Home never suggests them: not in Featured, Sports now or Tonight.
 */
private val BAR_CHANNEL = Regex("""(?i)(^|[^\p{L}])bar([^\p{L}]|$)""")

internal fun isBarChannel(ch: ChannelEntity): Boolean = BAR_CHANNEL.containsMatchIn(ch.name)
