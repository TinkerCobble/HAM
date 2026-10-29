package com.tinkercobble.ham

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.tinkercobble.ham.data.AgendaCache
import com.tinkercobble.ham.data.AndroidCalendarProvider
import com.tinkercobble.ham.data.CalendarPreferenceStore
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.DavAccounts
import com.tinkercobble.ham.data.IcsSubscriptions
import com.tinkercobble.ham.data.WidgetAgenda
import com.tinkercobble.ham.data.HomeAssistantClient

/** Only the launcher receives these bounded presentation strings; no raw cache or credentials. */
class HamAgendaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                val snapshot = runCatching { snapshot(context) }.getOrNull()
                ids.forEach { id -> runCatching { update(context, manager, id, snapshot) } }
            } finally { pending.finish() }
        }.start()
    }

    private fun snapshot(context: Context): WidgetAgenda? {
        val client = HomeAssistantClient(context)
        val scope = client.server ?: "android-device"
        val cached = AgendaCache(context).load(scope) ?: return null
        val androidPrefs = context.getSharedPreferences("ham-android-calendars", Context.MODE_PRIVATE)
        val androidEnabled = androidPrefs.getBoolean("enabled", false)
        val canRead = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        val androidIds = if (androidEnabled && canRead) {
            val current = AndroidCalendarProvider(context).calendars().map { it.id }.toSet()
            androidPrefs.getStringSet("visible", emptySet()).orEmpty().intersect(current)
        } else emptySet()
        val accountIds = DavAccounts(context).summaries().map { it.id }.toSet()
        val davIds = context.getSharedPreferences("ham-dav-selection", Context.MODE_PRIVATE)
            .getStringSet("visible", emptySet()).orEmpty().filterTo(mutableSetOf()) { id ->
                accountIds.any { account -> id.startsWith("dav:$account:") }
            }
        val subscriptionIds = IcsSubscriptions(context).summaries().map { "ics:${it.id}" }.toSet()
        val icsIds = context.getSharedPreferences("ham-ics-selection", Context.MODE_PRIVATE)
            .getStringSet("visible", emptySet()).orEmpty().intersect(subscriptionIds)
        val details = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(SHOW_DETAILS, false)
        return WidgetAgenda.forDay(cached.events.filter {
            it.provider != CalendarProviderKind.HOME_ASSISTANT || client.connected
        }, CalendarPreferenceStore(context).load(scope), androidEnabled, canRead, androidIds,
            davIds, icsIds, details)
    }

    private fun update(context: Context, manager: AppWidgetManager, id: Int, agenda: WidgetAgenda?) {
        synchronized(updateLock) {
            val views = RemoteViews(context.packageName, R.layout.ham_agenda_widget)
            val open = Intent(context, MainActivity::class.java)
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val count = when {
                agenda == null -> "Open HAM to load your agenda"
                agenda.total == 0 -> "No selected events today"
                else -> "${agenda.total} ${if (agenda.total == 1) "event" else "events"} today · ${agenda.allDay} all-day"
            }
            views.setTextViewText(R.id.widget_count, count)
            val allowedDetails = if (detailsEnabled(context)) agenda?.details else null
            listOf(R.id.widget_event_1, R.id.widget_event_2, R.id.widget_event_3).forEachIndexed { index, viewId ->
                val line = allowedDetails?.getOrNull(index)
                views.setViewVisibility(viewId, if (line == null) View.GONE else View.VISIBLE)
                if (line != null) views.setTextViewText(viewId, line)
            }
            manager.updateAppWidget(id, views)
        }
    }

    companion object {
        private const val PREFS = "ham-widget-privacy"
        private const val SHOW_DETAILS = "show-details"
        private val updateLock = Any()
        fun detailsEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(SHOW_DETAILS, false)
        fun setDetailsEnabled(context: Context, enabled: Boolean) {
            synchronized(updateLock) {
                check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(SHOW_DETAILS, enabled).commit()) { "Could not save widget preference" }
                if (!enabled) clearDetailsNow(context)
                requestUpdate(context)
            }
        }
        private fun clearDetailsNow(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, HamAgendaWidget::class.java)).forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.ham_agenda_widget)
                views.setTextViewText(R.id.widget_count, "Open HAM to view your agenda")
                listOf(R.id.widget_event_1, R.id.widget_event_2, R.id.widget_event_3).forEach { viewId ->
                    views.setViewVisibility(viewId, View.GONE)
                }
                manager.updateAppWidget(id, views)
            }
        }
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HamAgendaWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, HamAgendaWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
