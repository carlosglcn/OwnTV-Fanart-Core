package tv.own.owntv.core.epg

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.sync.work.EpgSyncScheduler

/**
 * Every guide match a user makes goes through here, in both apps: a "Match EPG" pick in Live TV or
 * the Guide, Auto-match's confident hits, and a review's Accept / Accept all.
 *
 * A match is only a name pointing at a guide channel. A guide sync stores programmes only for the ids
 * something uses, and keeps the past days only for ids a catch-up channel uses, so a newly matched id
 * may have nothing stored, or only its last few hours. The cached download is read first, with no
 * network; whatever it cannot give is fetched by one re-sync of just the feeds that list the id. A
 * full guide download takes minutes on a television, so every other feed is left alone.
 */
class GuideMatches(
    private val customize: CustomizationStore,
    private val epgRepository: EpgRepository,
    private val epgDao: EpgDao,
    private val feeds: EpgSourceStore,
    private val scheduler: EpgSyncScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pendingFeeds = HashSet<Long>()
    private var resyncJob: Job? = null

    /**
     * Save [matches] (channel key → guide id) for [profileId] in one write, then fill their programmes
     * from the cached guide. Returns once that is done; a re-sync, when one is needed, is queued and
     * runs later, shared by every match made within [RESYNC_DEBOUNCE_MS] of each other. True when one
     * was queued, so a caller does not report an empty row the re-sync is about to fill.
     */
    suspend fun match(profileId: Long, matches: Map<String, String>): Boolean {
        val clean = matches.mapNotNull { (key, id) -> id.trim().lowercase().takeIf { it.isNotEmpty() }?.let { key to it } }.toMap()
        if (clean.isEmpty()) return false
        val keptBefore = runCatching { epgRepository.catchupGuideIds() }.getOrNull()
        customize.update(profileId, MediaType.LIVE) { it.copy(epgMatches = it.epgMatches + clean) }
        return runCatching {
            val ids = clean.values.toSet()
            val filled = epgRepository.fillFromCache(ids)
            val unfilled = (ids - filled).toList()
            val withProgrammes = unfilled.chunked(QUERY_CHUNK).flatMapTo(HashSet()) { epgDao.epgIdsWithProgrammes(it) }
            val fetch = GuideResync.idsToFetch(ids, filled, withProgrammes, keptBefore, epgRepository.catchupGuideIds())
            val known = if (fetch.isEmpty()) emptySet() else feeds.getAll().mapTo(HashSet()) { it.id }
            val carrying = fetch.toList().chunked(QUERY_CHUNK).flatMapTo(HashSet()) { epgDao.feedsCarrying(it) }.filterTo(HashSet()) { it in known }
            Log.i(TAG, "guide match ids=${ids.size} fromCache=${filled.size} toFetch=${fetch.size} feeds=$carrying")
            requestResync(carrying)
            carrying.isNotEmpty()
        }.onFailure { Log.w(TAG, "guide match fill failed", it) }.getOrDefault(false)
    }

    private fun requestResync(feedIds: Set<Long>) {
        if (feedIds.isEmpty()) return
        synchronized(pendingFeeds) {
            pendingFeeds += feedIds
            resyncJob?.cancel()
            resyncJob = scope.launch {
                delay(RESYNC_DEBOUNCE_MS)
                // Past the wait nothing may cancel it: the feeds are taken off the list here.
                withContext(NonCancellable) {
                    val due = synchronized(pendingFeeds) { pendingFeeds.toList().also { pendingFeeds.clear() } }
                    for (id in due) {
                        runCatching { scheduler.enqueueAfterRunning(id, REASON, epgDao.countForSources(listOf(id))) }
                            .onFailure { Log.w(TAG, "guide re-sync not queued feed=$id", it) }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "GuideMatches"
        const val REASON = "match"
        const val QUERY_CHUNK = 500

        /** Long enough for a few matches in a row to share one download per feed. */
        const val RESYNC_DEBOUNCE_MS = 15_000L
    }
}

/** Which newly matched guide ids still need a download after the cached guide was read. */
internal object GuideResync {
    /**
     * The [ids] the cache did not fill ([filledFromCache]) that a download can fill: those with no
     * programme stored at all ([withProgrammes] lists the rest), and those a catch-up channel has only
     * just started reading — while no catch-up channel used them, their past was pruned to the last
     * few hours. [keptBefore] and [keptAfter] are the kept-history sets before and after the match;
     * null means it could not be read. Before, that is harmless — the prune then keeps everything — and
     * after, an id that was not kept before is assumed to need its past.
     */
    fun idsToFetch(
        ids: Set<String>,
        filledFromCache: Set<String>,
        withProgrammes: Set<String>,
        keptBefore: Set<String>?,
        keptAfter: Set<String>?,
    ): Set<String> = ids.filterTo(HashSet()) { id ->
        id !in filledFromCache && (id !in withProgrammes || gainedHistory(id, keptBefore, keptAfter))
    }

    private fun gainedHistory(id: String, keptBefore: Set<String>?, keptAfter: Set<String>?): Boolean =
        keptBefore != null && id !in keptBefore && (keptAfter == null || id in keptAfter)
}
