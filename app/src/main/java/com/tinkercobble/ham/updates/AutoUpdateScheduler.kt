package com.tinkercobble.ham.updates

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Automatic installs run only on unmetered networks after HAM leaves the foreground. */
object AutoUpdateScheduler {
    private const val PREFS = "ham_auto_update"
    private const val ENABLED = "enabled"
    private const val MANUAL_VERSION = "manual_version"
    private const val PERIODIC = "ham-self-update-periodic"
    private const val SOON = "ham-self-update-soon"

    // Activity and WorkManager share HAM's default process. A process-local flag
    // cannot become stale after a process death, and does not expire while the
    // calendar stays visible for hours.
    @Volatile private var foregroundInProcess = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = Build.VERSION.SDK_INT >= 31 &&
        prefs(context).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, enabled).apply()
        if (enabled) schedule(context) else {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(SOON)
        }
    }

    fun schedule(context: Context) {
        if (!isEnabled(context)) return
        val request = PeriodicWorkRequestBuilder<AutoUpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(unmetered())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun foreground() { foregroundInProcess = true }

    fun background(context: Context) {
        foregroundInProcess = false
        if (!isEnabled(context)) return
        val request = OneTimeWorkRequestBuilder<AutoUpdateWorker>()
            .setConstraints(unmetered())
            .setInitialDelay(5, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.KEEP, request)
    }

    fun isForeground(): Boolean = foregroundInProcess

    fun canTrySilently(context: Context, code: Int): Boolean =
        isEnabled(context) && UpdateService.canInstall(context) &&
            prefs(context).getInt(MANUAL_VERSION, 0) != code

    fun requireManualFallback(context: Context, code: Int) {
        prefs(context).edit().putInt(MANUAL_VERSION, code).apply()
    }

    fun clearManualFallback(context: Context, code: Int) {
        if (prefs(context).getInt(MANUAL_VERSION, 0) == code)
            prefs(context).edit().remove(MANUAL_VERSION).apply()
    }

    private fun unmetered() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED).build()
}

/** No account, event or device identifier is passed to or stored by WorkManager. */
class AutoUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (Build.VERSION.SDK_INT < 31 || !AutoUpdateScheduler.isEnabled(context) ||
            !UpdateService.canInstall(context))
            return Result.success()
        if (AutoUpdateScheduler.isForeground()) return Result.retry()
        val update = UpdateService.check(context, manual = false).update ?: return Result.success()
        if (!AutoUpdateScheduler.canTrySilently(context, update.versionCode)) return Result.success()
        return try {
            val file = UpdateService.downloadAndVerify(context, update) { }
            if (isStopped || !AutoUpdateScheduler.isEnabled(context) ||
                AutoUpdateScheduler.isForeground()) return Result.retry()
            try {
                UpdateService.commitSelfUpdate(context, file, update)
                Result.success()
            } catch (_: Exception) {
                AutoUpdateScheduler.requireManualFallback(context, update.versionCode)
                Result.success()
            }
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}
