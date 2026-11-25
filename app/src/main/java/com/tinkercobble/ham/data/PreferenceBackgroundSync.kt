package com.tinkercobble.ham.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.tinkercobble.ham.HamAgendaWidget
import java.util.concurrent.TimeUnit

/** WorkManager contains no account, endpoint, calendar, or setting payload. */
object PreferenceBackgroundSync {
    private const val periodic = "ham-preference-sync-periodic-v1"
    private const val soon = "ham-preference-sync-soon-v1"
    @Volatile private var foregroundInProcess = false

    fun foreground() { foregroundInProcess = true }

    fun background(context: Context) {
        foregroundInProcess = false
        schedule(context, immediate = true)
    }

    fun schedule(context: Context, immediate: Boolean = false) {
        if (OfflineMode.enabled(context) ||
            runCatching { ProtectedPreferenceSyncConnection(context).load() }.getOrNull() == null) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniquePeriodicWork(periodic, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PreferenceSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        if (immediate) manager.enqueueUniqueWork(soon, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PreferenceSyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }

    internal fun isForeground() = foregroundInProcess
}

/** Reads protected current state, exchanges it, and writes only approved source choices. */
class PreferenceSyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        if (PreferenceSyncReconciler.runOnce(applicationContext, shouldStop = { isStopped }))
            Result.success() else Result.retry()
    } catch (_: Exception) { Result.retry() }
}

/** Shared worker path exposed to isolated in-process integration tests. */
internal object PreferenceSyncReconciler {
    fun runOnce(context: Context, transportOverride: PreferenceSyncTransport? = null,
        shouldStop: () -> Boolean = { false },
        foreground: () -> Boolean = { PreferenceBackgroundSync.isForeground() }): Boolean {
        if (OfflineMode.enabled(context)) return true
        if (foreground()) return false
        val adapter = PreferenceSyncGlobalSettings(context)
        if (!adapter.paired()) return true
        val scope = HomeAssistantClient(context).server ?: "android-device"
        val prefsStore = CalendarPreferenceStore(context)
        val weekStore = WeekStartPreferenceStore(context)
        val groupStore = SavedCalendarGroupStore(context)
        val prefs = prefsStore.load(scope)
        val week = weekStore.load()
        val groups = groupStore.load(scope)
        val sources = AgendaCache(context).load(scope)?.calendars.orEmpty()
        val approved = adapter.approved(sources)
        val android = context.getSharedPreferences("ham-android-calendars", Context.MODE_PRIVATE)
        val dav = context.getSharedPreferences("ham-dav-selection", Context.MODE_PRIVATE)
        val ics = context.getSharedPreferences("ham-ics-selection", Context.MODE_PRIVATE)
        fun selected(name: String) = when (name) {
            "android" -> android.getStringSet("visible", emptySet()).orEmpty().toSet()
            "dav" -> dav.getStringSet("visible", emptySet()).orEmpty().toSet()
            else -> ics.getStringSet("visible", emptySet()).orEmpty().toSet()
        }
        val oldAndroid = selected("android")
        val oldDav = selected("dav")
        val oldIcs = selected("ics")
        fun visible(source: CalendarSource) = when (source.provider) {
            CalendarProviderKind.ANDROID -> source.id in oldAndroid
            CalendarProviderKind.CALDAV -> source.id in oldDav
            CalendarProviderKind.ICS -> source.id in oldIcs
            CalendarProviderKind.HOME_ASSISTANT -> source.id !in prefs.hidden
        }
        val local = approved.map { (identity, source) ->
            PreferenceSyncGlobalSettings.CalendarLocal(identity, visible(source),
                source.id in prefs.favorites, prefs.colors[source.id],
                source.id in prefs.mirrorHidden, prefs.order[source.id],
                groups.groups.filter { group -> group.members.any { it.sourceId == source.id } }
                    .map { it.id }.sorted())
        }
        val default = approved.entries.firstOrNull { it.value.id == prefs.defaultId &&
            it.value.canCreate }?.key
        val applied = adapter.exchange(week, prefs.view, local, default,
            transportOverride = transportOverride, localGroups = groups)
        if (shouldStop() || OfflineMode.enabled(context) || foreground()) return false
        if (prefsStore.load(scope) != prefs || groupStore.load(scope) != groups ||
            selected("android") != oldAndroid || selected("dav") != oldDav ||
            selected("ics") != oldIcs) return false
        if (weekStore.load() == week && applied.weekStart != null && applied.weekStart != week)
            weekStore.save(applied.weekStart)
        var nextPrefs = prefs
        var nextAndroid = oldAndroid
        var nextDav = oldDav
        var nextIcs = oldIcs
        if (applied.view != null) nextPrefs = nextPrefs.copy(view = applied.view)
        for ((identity, source) in approved) {
            val remote = applied.calendars[identity] ?: continue
            val id = source.id
            remote.visible?.let { show -> when (source.provider) {
                CalendarProviderKind.ANDROID -> nextAndroid = if (show) nextAndroid + id else nextAndroid - id
                CalendarProviderKind.CALDAV -> nextDav = if (show) nextDav + id else nextDav - id
                CalendarProviderKind.ICS -> nextIcs = if (show) nextIcs + id else nextIcs - id
                CalendarProviderKind.HOME_ASSISTANT -> nextPrefs = nextPrefs.copy(hidden =
                    if (show) nextPrefs.hidden - id else nextPrefs.hidden + id)
            } }
            remote.favorite?.let { favorite -> nextPrefs = nextPrefs.copy(favorites =
                if (favorite) nextPrefs.favorites + id else nextPrefs.favorites - id) }
            if (remote.colorKnown) nextPrefs = nextPrefs.setColor(id, remote.color)
            remote.mirrorHidden?.let { hidden -> nextPrefs = nextPrefs.copy(mirrorHidden =
                if (hidden) nextPrefs.mirrorHidden + id else nextPrefs.mirrorHidden - id) }
            if (remote.orderKnown) nextPrefs = nextPrefs.copy(order = if (remote.order == null)
                nextPrefs.order - id else nextPrefs.order + (id to remote.order))
        }
        if (applied.defaultKnown) {
            val source = applied.defaultIdentity?.let { approved[it] }
            if (applied.defaultIdentity == null || source?.canCreate == true)
                nextPrefs = nextPrefs.copy(defaultId = source?.id)
        }
        if (applied.groupOrder != null) {
            val memberships = approved.map { (identity, source) ->
                source to applied.calendars[identity]?.groups
            }.toMap()
            val projected = SavedGroupSyncProjection.apply(groups, applied.groupOrder,
                applied.groupNames, applied.activeGroupKnown, applied.activeGroupId,
                memberships, applied.deletedGroupIds)
            if (projected == null) return false
            if (groupStore.load(scope) != groups) return false
            if (projected != groups) groupStore.save(scope, projected)
        }
        if (nextPrefs != prefs) prefsStore.save(scope, nextPrefs, durable = true)
        if (nextAndroid != oldAndroid) check(android.edit().putStringSet("visible", nextAndroid).commit())
        if (nextDav != oldDav) check(dav.edit().putStringSet("visible", nextDav).commit())
        if (nextIcs != oldIcs) check(ics.edit().putStringSet("visible", nextIcs).commit())
        runCatching { HamAgendaWidget.requestUpdate(context) }
        return true
    }
}
