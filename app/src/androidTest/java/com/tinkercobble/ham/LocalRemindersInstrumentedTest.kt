package com.tinkercobble.ham

import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.app.AlarmManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.data.LocalReminderPlanner
import com.tinkercobble.ham.data.LocalReminderStore
import com.tinkercobble.ham.data.LocalReminderWhen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalRemindersInstrumentedTest {
    @Test fun exactAlarmActuallyDeliversGenericNotification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 31) assumeTrue(
            "Exact alarm special access is required for this delivery test",
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val uid = UUID.randomUUID().toString()
        val start = Instant.now().plusSeconds(8).truncatedTo(ChronoUnit.MILLIS)
        val event = AgendaEvent("ics:synthetic-$uid", "Synthetic", "Fixture event",
            start.toString(), start.plusSeconds(3600).toString(), false, "", "",
            uid, null, CalendarProviderKind.ICS)
        val store = LocalReminderStore(context)
        val record = store.add(LocalReminderPlanner.plan(event,
            LocalReminderWhen.MINUTES_BEFORE))
        try {
            assertTrue(LocalReminderAlarms(context).schedule(record))
            Thread.sleep(25000)
            assertFalse(store.list().any { it.id == record.id })
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications
                .any { it.id == record.id.hashCode() &&
                    it.notification.extras.getString("android.title") == "HAM calendar reminder" })
        } finally {
            LocalReminderAlarms(context).cancel(record)
            runCatching { store.remove(record.id) }
            context.getSystemService(NotificationManager::class.java).cancel(record.id.hashCode())
        }
    }

    @Test fun encryptedAllDayReminderRetimesAfterTimeZoneChange() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val date = LocalDate.now().plusDays(3)
        val uid = UUID.randomUUID().toString()
        val event = AgendaEvent("ics:synthetic-$uid", "Synthetic", "Fixture event",
            date.toString(), date.plusDays(1).toString(), true, "", "",
            uid, null, CalendarProviderKind.ICS)
        val east = ZoneId.of("America/New_York")
        val west = ZoneId.of("America/Los_Angeles")
        val store = LocalReminderStore(context)
        var id: String? = null
        try {
            val saved = store.add(LocalReminderPlanner.plan(event,
                LocalReminderWhen.MORNING_OF, zone = east))
            id = saved.id
            val change = store.retime(west).single { it.first.id == saved.id }
            assertEquals(LocalReminderPlanner.plan(event,
                LocalReminderWhen.MORNING_OF, zone = west).triggerAt, change.second.triggerAt)
            assertEquals(saved.id, store.list().single { it.id == saved.id }.id)
        } finally {
            id?.let { runCatching { store.remove(it) } }
        }
    }

    @Test fun suspendedMissingEventCannotNotifyFromAnAlreadyQueuedAlarm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val uid = UUID.randomUUID().toString()
        val start = Instant.now().plusSeconds(3600)
        val event = AgendaEvent("ics:synthetic-$uid", "Synthetic", "Fixture event",
            start.toString(), start.plusSeconds(3600).toString(), false, "", "",
            uid, null, CalendarProviderKind.ICS)
        val plan = LocalReminderPlanner.plan(event, LocalReminderWhen.MINUTES_BEFORE)
            .copy(triggerAt = Instant.now().minusSeconds(1))
        val store = LocalReminderStore(context)
        var id: String? = null
        try {
            val record = store.add(plan)
            id = record.id
            val range = DateRange(event.date, event.date.plusDays(1))
            assertEquals(listOf(record.id), store.suspendMissing(setOf(plan.sourceKey),
                range, emptySet()).map { it.id })
            LocalReminderReceiver().onReceive(context,
                Intent(context, LocalReminderReceiver::class.java)
                    .setAction(LocalReminderAlarms.ACTION_NOTIFY)
                    .putExtra(LocalReminderAlarms.EXTRA_ID, record.id))
            assertFalse(store.list().single { it.id == record.id }.active)
            assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications
                .any { it.id == record.id.hashCode() })
        } finally {
            id?.let { runCatching { store.remove(it) } }
        }
    }

    @Test fun receiverDeliversGenericNotificationAndConsumesExactReminder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val store = LocalReminderStore(context)
        val uid = UUID.randomUUID().toString()
        val start = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MILLIS)
        val event = AgendaEvent("ics:synthetic-$uid", "Synthetic", "Fixture event",
            start.toString(), start.plusSeconds(3600).toString(), false, "", "", uid, null,
            CalendarProviderKind.ICS)
        val plan = LocalReminderPlanner.plan(event, LocalReminderWhen.MINUTES_BEFORE, 0)
        var id: String? = null
        var notificationId: Int? = null
        try {
            val record = store.add(plan)
            id = record.id
            notificationId = record.id.hashCode()
            LocalReminderAlarms(context).reschedule()
            assertFalse(store.list().any { it.id == record.id })
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications
                .any { it.id == record.id.hashCode() &&
                    it.notification.extras.getString("android.title") == "HAM calendar reminder" })
            id = null
        } finally {
            id?.let { runCatching { store.remove(it) } }
            notificationId?.let { context.getSystemService(NotificationManager::class.java).cancel(it) }
        }
    }

    @Test fun encryptedStoreDeduplicatesRebindsAndRemovesOnlyExactFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uid = UUID.randomUUID().toString()
        val source = "ics:synthetic-$uid"
        val start = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(4 * 3600)
        val event = AgendaEvent(source, "Synthetic", "Fixture event", start.toString(),
            start.plusSeconds(3600).toString(), false, "", "", uid, null,
            CalendarProviderKind.ICS)
        val store = LocalReminderStore(context)
        val plan = LocalReminderPlanner.plan(event, LocalReminderWhen.MINUTES_BEFORE, 60)
        var id: String? = null
        var otherId: String? = null
        try {
            val saved = store.add(plan)
            id = saved.id
            assertEquals(saved, store.list().single { it.id == saved.id })
            val encoded = context.getSharedPreferences("ham-local-reminders", Context.MODE_PRIVATE)
                .getString("records", "").orEmpty()
            assertFalse(encoded.contains(uid))
            assertFalse(encoded.contains("Fixture event"))
            assertThrows(IllegalStateException::class.java) { store.add(plan) }
            val duplicate = LocalReminderPlanner.plan(event.copy(calendarId = "ics:other-$uid"),
                LocalReminderWhen.MINUTES_BEFORE, 60)
            assertNotEquals(plan.eventKey, duplicate.eventKey)
            assertEquals(plan.duplicateKey, duplicate.duplicateKey)
            assertThrows(IllegalStateException::class.java) { store.add(duplicate) }
            val moved = event.copy(start = start.plusSeconds(3600).toString())
            val changes = store.refreshEvent(moved)
            assertEquals(1, changes.size)
            assertEquals(saved.id, changes.single().second.id)
            assertEquals(start, changes.single().second.triggerAt)
            assertTrue(store.list().any { it.id == saved.id && it.triggerAt == start })
            val other = store.add(LocalReminderPlanner.plan(event.copy(
                calendarId = "ics:unrelated-$uid", uid = UUID.randomUUID().toString()),
                LocalReminderWhen.MINUTES_BEFORE, 60))
            otherId = other.id
            val day = moved.date
            val range = DateRange(day, day.plusDays(1))
            assertTrue(store.suspendMissing(emptySet(), range, emptySet()).isEmpty())
            assertTrue(store.suspendMissing(setOf(plan.sourceKey),
                DateRange(day.plusDays(1), day.plusDays(2)), emptySet()).isEmpty())
            assertEquals(listOf(saved.id), store.suspendMissing(setOf(plan.sourceKey),
                range, emptySet()).map { it.id })
            assertFalse(store.list().single { it.id == saved.id }.active)
            assertTrue(store.list().single { it.id == other.id }.active)
            assertEquals(1, store.refreshEvent(moved).size)
            assertTrue(store.list().single { it.id == saved.id }.active)
            assertEquals(listOf(saved.id), store.removeSource(plan.sourceKey).map { it.id })
            id = null
            assertFalse(store.list().any { it.id == saved.id })
            assertTrue(store.list().any { it.id == other.id })
            store.remove(other.id)
            otherId = null
        } finally {
            id?.let { runCatching { store.remove(it) } }
            otherId?.let { runCatching { store.remove(it) } }
        }
    }
}
