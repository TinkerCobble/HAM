package com.tinkercobble.ham

import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.LocalReminderPlanner
import com.tinkercobble.ham.data.LocalReminderStore
import com.tinkercobble.ham.data.LocalReminderWhen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** Run seed and verify as separate explicit instrumentation calls with an emulator reboot between. */
@RunWith(AndroidJUnit4::class)
class PinnedRebootInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("pinned-reboot-fixture", 0)

    @Test fun seedDeliveredPinForReboot() {
        check(prefs.getString("id", null) == null) { "Prior reboot fixture needs cleanup" }
        val start = Instant.now().minusSeconds(10)
        val event = AgendaEvent("ics:reboot-fixture", "Synthetic", "Fictional reboot reminder",
            start.toString(), start.plusSeconds(3600).toString(), false, "",
            "123 Imaginary Avenue", UUID.randomUUID().toString(), start.toString(),
            CalendarProviderKind.ICS)
        val store = LocalReminderStore(context)
        val record = store.add(LocalReminderPlanner.plan(event,
            LocalReminderWhen.MINUTES_BEFORE), event, true)
        LocalReminderReceiver().onReceive(context, Intent(context, LocalReminderReceiver::class.java)
            .setAction(LocalReminderAlarms.ACTION_NOTIFY)
            .putExtra(LocalReminderAlarms.EXTRA_ID, record.id))
        assertTrue(store.list().single { it.id == record.id }.delivered)
        assertTrue(prefs.edit().putString("id", record.id).commit())
    }

    @Test fun verifyRebootPinNoDuplicateAndCleanup() {
        val id = requireNotNull(prefs.getString("id", null)) { "Run seed before reboot" }
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            val store = LocalReminderStore(context)
            assertTrue(store.list().single { it.id == id }.delivered)
            val before = manager.activeNotifications.count { it.id == id.hashCode() }
            assertTrue(before <= 1)
            LocalReminderAlarms(context).reschedule()
            LocalReminderReceiver().onReceive(context, Intent(context, LocalReminderReceiver::class.java)
                .setAction(LocalReminderAlarms.ACTION_NOTIFY)
                .putExtra(LocalReminderAlarms.EXTRA_ID, id))
            assertEquals(before, manager.activeNotifications.count { it.id == id.hashCode() })
        } finally {
            LocalReminderAlarms(context).done(id)
            prefs.edit().remove("id").commit()
            assertFalse(LocalReminderStore(context).list().any { it.id == id })
        }
    }
}
