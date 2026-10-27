package tv.own.owntv.core.tv

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
        private const val EVERY_MINUTES = 30L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RecentLiveRefreshWorker>(EVERY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }

        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
        }
    }
}
