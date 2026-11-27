package com.tinkercobble.ham

import android.Manifest
import android.app.NotificationManager
import android.app.AlarmManager
import android.service.notification.StatusBarNotification
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PinnedRemindersInstrumentedTest {
    private fun awaitNotification(manager: NotificationManager, id: Int): StatusBarNotification {
        repeat(20) {
            manager.activeNotifications.firstOrNull { it.id == id }?.let { found -> return found }
            Thread.sleep(100)
        }
        error("Synthetic pinned notification was not posted")
    }
    private fun visibleTexts(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        val result = mutableListOf<String>()
        node.text?.toString()?.let(result::add)
        for (index in 0 until node.childCount) result += visibleTexts(node.getChild(index))
        return result
    }
    private fun event(uid: String, start: Instant = Instant.now().minusSeconds(5)) = AgendaEvent(
        "ics:synthetic-$uid", "Synthetic", "Fictional long title $uid",
        start.toString(), start.plusSeconds(3600).toString(), false, "",
        "123 Imaginary Street, Suite 400, Fictional City", uid, start.toString(),
        CalendarProviderKind.ICS)

    @Test fun pinnedAlertStaysUntilDoneAndNeverRepostsAfterDismissal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val store = LocalReminderStore(context)
        val event = event(UUID.randomUUID().toString())
        val record = store.add(LocalReminderPlanner.plan(event,
            LocalReminderWhen.MINUTES_BEFORE), event, true)
        val intent = Intent(context, LocalReminderReceiver::class.java)
            .setAction(LocalReminderAlarms.ACTION_NOTIFY)
            .putExtra(LocalReminderAlarms.EXTRA_ID, record.id)
        try {
            LocalReminderReceiver().onReceive(context, intent)
            assertTrue(store.list().single { it.id == record.id }.delivered)
            val notification = awaitNotification(manager, record.id.hashCode()).notification
            assertEquals(event.summary, notification.extras.getString("android.title"))
            assertTrue(notification.extras.getCharSequence("android.bigText").toString()
                .contains(event.location))
            assertEquals("HAM reminder", notification.publicVersion.extras.getString("android.title"))
            LocalReminderReceiver().onReceive(context, intent)
            assertEquals(1, manager.activeNotifications.count { it.id == record.id.hashCode() })
            manager.cancel(record.id.hashCode())
            LocalReminderReceiver().onReceive(context, Intent(context, LocalReminderReceiver::class.java)
                .setAction(LocalReminderAlarms.ACTION_DISMISSED)
                .putExtra(LocalReminderAlarms.EXTRA_ID, record.id))
            LocalReminderAlarms(context).reschedule()
            assertFalse(manager.activeNotifications.any { it.id == record.id.hashCode() })
            assertTrue(store.list().any { it.id == record.id })
            LocalReminderReceiver().onReceive(context, Intent(context, LocalReminderReceiver::class.java)
                .setAction(LocalReminderAlarms.ACTION_DONE)
                .putExtra(LocalReminderAlarms.EXTRA_ID, record.id))
            assertFalse(store.list().any { it.id == record.id })
        } finally {
            LocalReminderAlarms(context).done(record.id)
            manager.cancel(record.id.hashCode())
        }
    }

    @Test fun encryptedSnapshotUpdatesAndOfflineSourceCannotRemovePin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LocalReminderStore(context)
        val event = event(UUID.randomUUID().toString(), Instant.now().plusSeconds(86400))
        val record = store.add(LocalReminderPlanner.plan(event,
            LocalReminderWhen.MINUTES_BEFORE), event, true)
        try {
            val raw = context.getSharedPreferences("ham-local-reminders", 0)
                .getString("records", "").orEmpty()
            assertFalse(raw.contains(event.summary))
            assertFalse(raw.contains(event.location))
            val moved = event.copy(summary = "Fictional revised title",
                start = Instant.now().plusSeconds(172800).toString(),
                end = Instant.now().plusSeconds(176400).toString())
            assertEquals(record.id, store.refreshEvent(moved).single().second.id)
            assertEquals(moved.summary, store.list().single { it.id == record.id }.details?.title)
            val range = DateRange(event.date, event.date.plusDays(1))
            assertTrue(store.suspendMissing(emptySet(), range, emptySet()).isEmpty())
            assertTrue(store.list().single { it.id == record.id }.active)
        } finally { LocalReminderAlarms(context).done(record.id) }
    }

    @Test fun allDayCardAndRecurringOccurrencesKeepTheirOwnIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation()
            .uiAutomation.grantRuntimePermission(context.packageName,
                Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val store = LocalReminderStore(context)
        val uid = UUID.randomUUID().toString()
        val day = LocalDate.now()
        val allDay = event(uid).copy(summary = "Fictional all-day workshop",
            start = day.toString(), end = day.plusDays(1).toString(), allDay = true)
        val first = event(uid, Instant.now().minusSeconds(5))
            .copy(summary = "Fictional first occurrence")
        val second = event(uid, Instant.now().plusSeconds(86400))
            .copy(summary = "Fictional later occurrence")
        val allDayRecord = store.add(LocalReminderPlanner.plan(allDay,
            LocalReminderWhen.MORNING_OF), allDay, true)
        val firstRecord = store.add(LocalReminderPlanner.plan(first,
            LocalReminderWhen.MINUTES_BEFORE), first, true)
        val secondRecord = store.add(LocalReminderPlanner.plan(second,
            LocalReminderWhen.MINUTES_BEFORE), second, true)
        try {
            assertTrue(firstRecord.eventKey != secondRecord.eventKey)
            assertTrue(firstRecord.details?.recurrenceId != secondRecord.details?.recurrenceId)
            LocalReminderReceiver().onReceive(context, Intent(context, LocalReminderReceiver::class.java)
                .setAction(LocalReminderAlarms.ACTION_NOTIFY)
                .putExtra(LocalReminderAlarms.EXTRA_ID, allDayRecord.id))
            val card = awaitNotification(manager, allDayRecord.id.hashCode()).notification
            assertTrue(card.extras.getCharSequence("android.bigText").toString()
                .contains("All day"))
            assertFalse(store.list().single { it.id == secondRecord.id }.delivered)
            LocalReminderReceiver().onReceive(context, Intent(context, LocalReminderReceiver::class.java)
                .setAction(LocalReminderAlarms.ACTION_NOTIFY)
                .putExtra(LocalReminderAlarms.EXTRA_ID, firstRecord.id))
            awaitNotification(manager, firstRecord.id.hashCode()).notification.contentIntent.send()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            var visible = emptyList<String>()
            repeat(30) {
                visible = visibleTexts(instrumentation.uiAutomation.rootInActiveWindow)
                if (visible.any { it.contains(first.summary) }) return@repeat
                Thread.sleep(500)
            }
            assertTrue("Visible screen: ${visible.take(20)}",
                visible.any { it.contains(first.summary) })
            assertFalse(visible.any { it.contains(second.summary) })
        } finally {
            listOf(allDayRecord, firstRecord, secondRecord).forEach {
                LocalReminderAlarms(context).done(it.id)
            }
        }
    }

    @Test fun notificationTapOpensExactDetailAndDoneClearsPin() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val store = LocalReminderStore(context)
        val event = event(UUID.randomUUID().toString())
        val record = store.add(LocalReminderPlanner.plan(event,
            LocalReminderWhen.MINUTES_BEFORE), event, true)
        try {
            LocalReminderReceiver().onReceive(context, Intent(context, LocalReminderReceiver::class.java)
                .setAction(LocalReminderAlarms.ACTION_NOTIFY)
                .putExtra(LocalReminderAlarms.EXTRA_ID, record.id))
            val notification = awaitNotification(manager, record.id.hashCode()).notification
            notification.contentIntent.send()
            instrumentation.waitForIdleSync()
            var texts = emptyList<String>()
            repeat(30) {
                texts = visibleTexts(instrumentation.uiAutomation.rootInActiveWindow)
                if (texts.any { it.contains(event.summary) }) return@repeat
                Thread.sleep(500)
            }
            assertTrue("Visible screen: ${texts.take(20)}",
                texts.any { it.contains(event.summary) })
            assertTrue(store.list().any { it.id == record.id && it.delivered })
            notification.actions.single { it.title.toString() == "Done" }.actionIntent.send()
            repeat(20) {
                if (store.list().none { it.id == record.id }) return@repeat
                Thread.sleep(100)
            }
            assertFalse(store.list().any { it.id == record.id })
            assertFalse(manager.activeNotifications.any { it.id == record.id.hashCode() })
        } finally {
            LocalReminderAlarms(context).done(record.id)
            manager.cancel(record.id.hashCode())
        }
    }

    @Test fun deniedNotificationPermissionLeavesUndeliveredPinForLater() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Permission is revoked by the test driver before launching instrumentation.
        // Revoking it in this process makes Android terminate the test runner.
        assertFalse(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
        val store = LocalReminderStore(context)
        val event = event(UUID.randomUUID().toString())
        val record = store.add(LocalReminderPlanner.plan(event,
            LocalReminderWhen.MINUTES_BEFORE), event, true)
        try {
            assertFalse(LocalReminderAlarms(context).notificationAllowed())
            LocalReminderReceiver().onReceive(context, Intent(context, LocalReminderReceiver::class.java)
                .setAction(LocalReminderAlarms.ACTION_NOTIFY)
                .putExtra(LocalReminderAlarms.EXTRA_ID, record.id))
            assertFalse(store.list().single { it.id == record.id }.delivered)
        } finally {
            LocalReminderAlarms(context).done(record.id)
        }
    }

    @Test fun deniedExactAlarmAccessUsesInexactFallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        assertFalse(context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val event = event(UUID.randomUUID().toString(), Instant.now().plusSeconds(7200))
        val store = LocalReminderStore(context)
        val record = store.add(LocalReminderPlanner.plan(event,
            LocalReminderWhen.MINUTES_BEFORE), event, true)
        try { assertFalse(LocalReminderAlarms(context).schedule(record)) }
        finally { LocalReminderAlarms(context).done(record.id) }
    }
}
