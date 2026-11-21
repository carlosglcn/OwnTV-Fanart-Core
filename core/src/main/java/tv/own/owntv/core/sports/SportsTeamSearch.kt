package tv.own.owntv.core.sports

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import tv.own.owntv.core.sports.model.Team
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The My teams search: the teams of every catalogue league in the sports the user follows, not only
 * the followed leagues — following the NFL finds a college team too. Each league's list is fetched
 * once and kept for this object's life; a league that failed is tried again on a later search, after
 * [RETRY_MS], so one bad answer does not hide its teams until the page is reopened.
 */
class SportsTeamSearch(
    private val source: SportsSource,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val teams = ConcurrentHashMap<String, List<Team>>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    suspend fun search(config: SportsConfig, query: String): List<FollowedTeam> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.length < 2) return emptyList()
        val sports = config.followedLeagues().mapNotNullTo(HashSet()) { SportsCatalog.league(it)?.sport }
        val leagues = SportsCatalog.leagues.filter { it.sport in sports }
        lock.withLock {
            val now = clock()
            val missing = leagues.filter { !teams.containsKey(it.key) && failedAt[it.key]?.let { t -> now - t < RETRY_MS } != true }
            val gate = Semaphore(MAX_PARALLEL)
            coroutineScope {
                missing.map { league ->
                    async {
                        gate.withPermit {
                            try {
                                teams[league.key] = source.teams(league)
                                failedAt.remove(league.key)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                failedAt[league.key] = now
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        return leagues.asSequence()
            .flatMap { league -> teams[league.key].orEmpty().asSequence().map { league to it } }
            .filter { (_, t) -> t.name.lowercase(Locale.ROOT).contains(q) }
            .map { (league, t) -> FollowedTeam(league.sport, t.id, t.name, t.abbreviation, league.key) }
            .distinctBy { it.key }
            .take(MAX_RESULTS)
            .toList()
    }

    private companion object {
        const val MAX_PARALLEL = 4
        const val MAX_RESULTS = 30
        const val RETRY_MS = 30_000L
    }
}
