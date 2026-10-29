package com.tinkercobble.ham.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/** WorkManager receives no account identity, event fields, or authorization material. */
object HaBackgroundSync {
    private const val name = "ham-ha-pending-v1"

    internal fun shouldSchedule(states: List<HaPendingState>, continuation: Boolean): Boolean =
        states.any { it == HaPendingState.QUEUED ||
            !continuation && it == HaPendingState.ACCEPTED }

    fun scheduleIfQueued(context: Context, newMutation: Boolean,
        continuation: Boolean = false): Operation? {
        if (!shouldSchedule(PendingHaMutations(context).list().map { it.state }, continuation))
            return null
        val request = OneTimeWorkRequestBuilder<HaSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(if (continuation) 1 else 5, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        return WorkManager.getInstance(context).enqueueUniqueWork(name,
            if (newMutation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request)
    }
}

class HaSyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        val queue = PendingHaMutations(applicationContext)
        val client = HomeAssistantClient(applicationContext)
        val result = runBlocking {
            HaMutationDispatcher.flush(queue, client.mutationTransport(), maxItems = 2,
                shouldContinue = { !isStopped })
        }
        if (isStopped || result.networkFailure) Result.retry() else {
            if (queue.list().any { it.state == HaPendingState.QUEUED }) {
                HaBackgroundSync.scheduleIfQueued(applicationContext, true, continuation = true)
                    ?.result?.get(10, TimeUnit.SECONDS)
            }
            Result.success()
        }
    } catch (_: Exception) { Result.retry() }
}
