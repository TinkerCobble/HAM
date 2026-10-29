package com.tinkercobble.ham

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import com.tinkercobble.ham.data.AndroidCalendarProvider
import com.tinkercobble.ham.data.DateRange
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRemindersInstrumentedTest {
    @Before fun grantCalendarAccess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.grantRuntimePermission(target, Manifest.permission.READ_CALENDAR)
        instrumentation.uiAutomation.grantRuntimePermission(target, Manifest.permission.WRITE_CALENDAR)
    }

    @Test fun addsAndRemovesExactProviderRemindersWithoutChangingOtherRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val account = "ham-reminder-${UUID.randomUUID()}@example.test"
        val syncUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val calendar = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
            put(CalendarContract.Calendars.NAME, "HAM synthetic reminder")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "HAM synthetic reminder")
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.ALLOWED_REMINDERS, "0,1")
            put(CalendarContract.Calendars.MAX_REMINDERS, 5)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC")
        }
        val created = resolver.insert(syncUri, calendar) ?: error("Could not create synthetic calendar")
        val id = ContentUris.parseId(created)
        try {
            val provider = AndroidCalendarProvider(context)
            val source = provider.calendars().single { it.id == "android:$id" }
            val date = LocalDate.parse("2026-11-20")
            provider.createTimed(source, date, "13:00", "14:00", "Synthetic reminder event")
            val event = provider.events(listOf(source), DateRange(date, date.plusDays(1)))
                .single { it.summary == "Synthetic reminder event" }
            provider.addReminder(source, event, 60)
            provider.addReminder(source, event, 30)
            assertEquals(listOf(30, 60), provider.reminders(source, event).map { it.minutes }.sorted())
            assertTrue(runCatching { provider.addReminder(source, event, 60) }.isFailure)
            val first = provider.reminders(source, event).single { it.minutes == 60 }
            provider.removeReminder(source, event, first)
            assertEquals(listOf(30), provider.reminders(source, event).map { it.minutes })
            assertTrue(runCatching { provider.removeReminder(source, event, first) }.isFailure)
        } finally {
            resolver.delete(ContentUris.withAppendedId(syncUri, id), null, null)
        }
    }
}
