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
import java.util.concurrent.TimeUnit

/** WorkManager stores only its job state; account and event data stay in HAM's encrypted stores. */
object DavBackgroundSync {
    private const val name = "ham-dav-pending-v1"

    fun scheduleIfQueued(context: Context, newMutation: Boolean,
        continuation: Boolean = false): Operation? {
        if (PendingDavMutations(context).list().none { it.state == DavPendingState.QUEUED }) return null
        val request = OneTimeWorkRequestBuilder<DavSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(if (continuation) 1 else 5, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        return WorkManager.getInstance(context).enqueueUniqueWork(name,
            if (newMutation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request)
    }
}

/** Bounded retry of already reviewed operations. It never creates or approves a mutation. */
class DavSyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        return try {
            val queue = PendingDavMutations(applicationContext)
            val accounts = DavAccounts(applicationContext)
            val ids = queue.list().filter { it.state == DavPendingState.QUEUED }
                .map { it.accountId }.distinct().take(4)
            var networkFailure = false
            for (id in ids) {
                if (isStopped) return Result.retry()
                val account = accounts.account(id) ?: continue
                val result = DavMutationDispatcher.flush(account, queue, maxItems = 2,
                    accountStillCurrent = { accounts.account(id) == account },
                    shouldContinue = { !isStopped })
                networkFailure = networkFailure || result.networkFailure
            }
            if (isStopped) return Result.retry()
            val queuedForCurrentAccount = queue.list().any {
                it.state == DavPendingState.QUEUED && accounts.account(it.accountId) != null
            }
            when {
                networkFailure -> Result.retry()
                queuedForCurrentAccount -> {
                    DavBackgroundSync.scheduleIfQueued(applicationContext, true, continuation = true)
                        ?.result?.get(10, TimeUnit.SECONDS)
                    Result.success()
                }
                else -> Result.success()
            }
        } catch (_: Exception) {
            // Keep the encrypted queue unchanged for WorkManager's network-constrained backoff.
            Result.retry()
        }
    }
}
