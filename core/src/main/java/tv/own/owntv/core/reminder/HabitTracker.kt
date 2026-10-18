package tv.own.owntv.core.reminder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.settings.SettingsRepository

/**
 * glcn: learns which programmes the profile watches and turns the next showings of those into ordinary
 * programme reminders (same prompt on the television, same prompt or notification on the phone,
 * according to the *Reminder* setting). The rules are in [HabitRules].
 *
 * Both apps call [watching] when a live channel is tuned and [stopped] when playback ends; the programme
 * is looked up by the app only once the viewer has stayed [HabitRules.DWELL_MS], so zapping learns nothing.
 * [refresh] is called on start and every few hours to arm the coming showings.
 */
class HabitTracker(
    private val settings: SettingsRepository,
    private val epg: EpgDao,
    private val reminders: ReminderManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The programme on a channel right now, in the guide's own time. */
    class Now(val title: String, val startMs: Long, val stopMs: Long)

    private val lock = Mutex()
    private var job: Job? = null

    /**
     * [channelId] was tuned. After the dwell time, [programme] is asked for what is on; that showing is
     * logged and the coming ones are armed. A new call (a zap) or [stopped] cancels the wait.
     */
    fun watching(
        profileId: Long,
        channelId: Long,
        channelName: String,
        epgChannelId: String?,
        programme: suspend () -> Now?,
    ) {
        job?.cancel()
        val epgId = epgChannelId?.takeIf { it.isNotBlank() } ?: return
        job = scope.launch {
            if (!settings.habitLearning.first()) return@launch
            delay(HabitRules.DWELL_MS)
            val now = runCatching { programme() }.getOrNull() ?: return@launch
            val key = HabitRules.titleKey(now.title)
            if (key.isEmpty()) return@launch
            record(HabitRules.Viewing(profileId, channelId, channelName, epgId, now.title, key, now.startMs, clock()))
            refresh(profileId)
        }
    }

    /** Playback ended before the dwell time: nothing is learned. */
    fun stopped() {
        job?.cancel()
        job = null
    }

    private suspend fun record(v: HabitRules.Viewing) = lock.withLock {
        val log = HabitRules.decode(settings.habitLog.first())
        // One entry per showing: watching the same programme again later does not count twice.
        if (log.any { it.profileId == v.profileId && it.channelId == v.channelId && it.startMs == v.startMs }) return@withLock
        settings.setHabitLog(HabitRules.encode(HabitRules.prune(log + v, clock())))
    }

    /** What the profile usually watches (empty when the feature is off). */
    suspend fun habits(profileId: Long): List<HabitRules.Habit> {
        if (!settings.habitLearning.first()) return emptyList()
        return HabitRules.habits(HabitRules.decode(settings.habitLog.first()), profileId, clock())
    }

    /** Arms a reminder for every coming showing of a habit that has none yet. */
    suspend fun refresh(profileId: Long) = lock.withLock {
        if (!settings.habitLearning.first()) return@withLock
        val now = clock()
        val habits = HabitRules.habits(HabitRules.decode(settings.habitLog.first()), profileId, now)
        if (habits.isEmpty()) return@withLock
        val done = settings.habitDone.first().lineSequence().filter { it.isNotBlank() }.toMutableSet()
        var changed = false
        for (habit in habits) {
            val coming = runCatching {
                epg.programmesForChannel(HabitRules.epgKey(habit.epgChannelId), now, now + HabitRules.LOOKAHEAD_MS)
            }.getOrDefault(emptyList())
            for (p in coming.distinctBy { it.startMs }) {
                if (p.startMs <= now || HabitRules.titleKey(p.title) != habit.key) continue
                val marker = HabitRules.doneKey(habit.channelId, p.startMs)
                if (marker in done) continue
                // Marked first: a prompt the viewer dismisses must never be armed again.
                done += marker
                changed = true
                reminders.add(profileId, habit.channelId, habit.channelName, habit.epgChannelId, p.title, p.startMs, p.stopMs)
            }
        }
        if (changed) settings.setHabitDone(done.filter { it.substringAfter(':').toLongOrNull()?.let { s -> s > now - 86_400_000L } == true }.joinToString("\n"))
    }

    /** "Forget what you learned" — the log and the markers. */
    suspend fun forget() = lock.withLock {
        settings.setHabitLog("")
        settings.setHabitDone("")
    }
}
