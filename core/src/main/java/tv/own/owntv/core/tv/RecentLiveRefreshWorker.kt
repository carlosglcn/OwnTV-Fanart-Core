package tv.own.owntv.core.tv

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import tv.own.owntv.core.settings.SettingsRepository
import java.util.concurrent.TimeUnit

/**
 * glcn: keeps the launcher's "Recent channels" row current while the app is closed — the programme on
 * air and its fanart change through the day. Every ~30 minutes (WorkManager's own floor is 15).
 */
class RecentLiveRefreshWorker(
    context: Context,
    params: WorkerParameters,
    private val repository: TvHomeRepository,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching {
            val profile = settings.activeProfileIdNow()
            if (profile >= 0) repository.refreshRecentLive(profile, force = true)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "recent-live-refresh"
        private const val PROGRAMME_END_WORK_NAME = "recent-live-programme-end"
        private const val EVERY_MINUTES = 30L
        private const val AFTER_END_MS = 60_000L
        private const val MIN_DELAY_MS = 60_000L
        private const val MAX_DELAY_MS = 6L * 60 * 60 * 1000

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RecentLiveRefreshWorker>(EVERY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }

        /**
         * glcn: one refresh a minute after [endMs], when the first programme in the row ends, so the card shows
         * what airs next. Each call replaces the previous one; the periodic refresh above stays as the backstop.
         */
        fun scheduleAt(context: Context, endMs: Long) {
            val delay = (endMs + AFTER_END_MS - System.currentTimeMillis()).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
            val request = OneTimeWorkRequestBuilder<RecentLiveRefreshWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(PROGRAMME_END_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            }
        }

        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(PROGRAMME_END_WORK_NAME) }
        }
    }
}
