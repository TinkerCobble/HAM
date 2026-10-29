package com.tinkercobble.ham

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.tinkercobble.ham.data.LocalReminderRecord
import com.tinkercobble.ham.data.LocalReminderPlanner
import com.tinkercobble.ham.data.LocalReminderStore
import com.tinkercobble.ham.data.LocalReminderWhen
import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.DateRange
import java.time.Instant

/** No event names, account details or URLs are placed in alarms or lock-screen notifications. */
class LocalReminderAlarms(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun notificationAllowed(): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED)

    /** Exact where Android permits it, otherwise an explicitly disclosed inexact idle-safe alarm. */
    fun schedule(record: LocalReminderRecord): Boolean {
        record.validated()
        check(notificationAllowed()) { "Allow HAM notifications before saving a local reminder" }
        check(record.triggerAt.isAfter(Instant.now())) { "Reminder time has already passed" }
        val pending = pending(record)
        val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        if (exact) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    record.triggerAt.toEpochMilli(), pending)
                return true
            } catch (_: SecurityException) { /* Access may have changed; use inexact delivery. */ }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, record.triggerAt.toEpochMilli(), pending)
        return false
    }

    fun cancel(record: LocalReminderRecord) { alarms.cancel(pending(record)) }

    fun reschedule() {
        if (!notificationAllowed()) return
        val store = LocalReminderStore(context)
        store.retime().forEach { (old, _) -> cancel(old) }
        val now = Instant.now()
        store.list().forEach { record ->
            when {
                !record.active -> Unit
                record.triggerAt.isAfter(now) -> runCatching { schedule(record) }
                now.isBefore(record.triggerAt.plusSeconds(24 * 60 * 60)) ->
                    runCatching { LocalReminderReceiver().onReceive(context,
                        Intent(context, LocalReminderReceiver::class.java)
                            .setAction(ACTION_NOTIFY).putExtra(EXTRA_ID, record.id)) }
                else -> runCatching { store.remove(record.id) }
            }
        }
    }

    /** Rebind matches and suspend missing events only for complete reads of selected sources. */
    fun reconcileFresh(events: List<AgendaEvent>, completeSources: List<CalendarSource>,
        range: DateRange) {
        val store = LocalReminderStore(context)
        val keys = store.list().map { it.eventKey }.toSet()
        if (keys.isEmpty()) return
        events.forEach { event ->
            val eventKey = runCatching {
                LocalReminderPlanner.plan(event, LocalReminderWhen.MORNING_OF).eventKey
            }.getOrNull() ?: return@forEach
            if (eventKey !in keys) return@forEach
            store.refreshEvent(event).forEach { (old, updated) ->
                cancel(old)
                if (updated.triggerAt.isAfter(Instant.now())) {
                    if (notificationAllowed()) runCatching { schedule(updated) }
                } else runCatching { store.remove(updated.id) }
            }
        }
        val completeKeys = completeSources.map {
            LocalReminderPlanner.sourceKey(it.provider, it.id)
        }.toSet()
        val presentKeys = events.mapNotNull { event -> runCatching {
            LocalReminderPlanner.plan(event, LocalReminderWhen.MORNING_OF).eventKey
        }.getOrNull() }.toSet()
        store.suspendMissing(completeKeys, range, presentKeys).forEach { cancel(it) }
    }

    fun removeConfirmedSources(sources: List<CalendarSource>) {
        val store = LocalReminderStore(context)
        sources.forEach { source ->
            val key = LocalReminderPlanner.sourceKey(source.provider, source.id)
            store.removeSource(key).forEach { cancel(it) }
        }
    }

    private fun pending(record: LocalReminderRecord): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent(context, LocalReminderReceiver::class.java)
            .setAction(ACTION_NOTIFY).setData(Uri.parse("ham-local-reminder:${record.id}"))
            .putExtra(EXTRA_ID, record.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val ACTION_NOTIFY = "com.tinkercobble.ham.LOCAL_REMINDER"
        const val EXTRA_ID = "reminder_id"
        const val CHANNEL = "ham-local-reminders"
    }
}

class LocalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LocalReminderAlarms.ACTION_NOTIFY) return
        val id = intent.getStringExtra(LocalReminderAlarms.EXTRA_ID) ?: return
        val store = LocalReminderStore(context)
        val record = runCatching { store.list().singleOrNull { it.id == id && it.active } }
            .getOrNull() ?: return
        val now = Instant.now()
        if (record.triggerAt.isAfter(now.plusSeconds(60))) {
            runCatching { LocalReminderAlarms(context).schedule(record) }
            return
        }
        if (now.isAfter(record.triggerAt.plusSeconds(24 * 60 * 60))) {
            runCatching { store.remove(id) }
            return
        }
        if (!LocalReminderAlarms(context).notificationAllowed()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(LocalReminderAlarms.CHANNEL,
            "HAM local calendar reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, LocalReminderAlarms.CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("HAM calendar reminder")
            .setContentText("Open HAM to review your calendar.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        manager.notify(id.hashCode(), notification)
        runCatching { store.remove(id) }
    }
}

class LocalReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) {
            val result = goAsync()
            Thread {
                try { LocalReminderAlarms(context).reschedule() }
                finally { result.finish() }
            }.start()
        }
    }
}
