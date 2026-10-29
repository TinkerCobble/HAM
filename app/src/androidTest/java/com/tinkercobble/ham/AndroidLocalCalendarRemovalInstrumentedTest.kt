package com.tinkercobble.ham

import android.Manifest
import android.content.ContentUris
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import com.tinkercobble.ham.data.AndroidCalendarProvider
import com.tinkercobble.ham.data.DateRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AndroidLocalCalendarRemovalInstrumentedTest {
    @Before fun grantCalendarAccess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.grantRuntimePermission(target, Manifest.permission.READ_CALENDAR)
        instrumentation.uiAutomation.grantRuntimePermission(target, Manifest.permission.WRITE_CALENDAR)
    }

    @Test fun onlyEmptyHamOwnedLocalCalendarCanBeRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = AndroidCalendarProvider(context)
        assertTrue(provider.canRead() && provider.canWrite())
        assumeTrue(provider.localCalendar() == null)
        val before = provider.calendars().map { it.id }.toSet()
        val source = provider.createLocalCalendar()
        val id = source.id.removePrefix("android:").toLong()
        try {
            val date = LocalDate.parse("2026-11-07")
            provider.createAllDay(source, date, "Synthetic removal fixture")
            assertTrue(runCatching { provider.removeEmptyLocalCalendar(source) }.isFailure)
            val event = provider.events(listOf(source), DateRange(date, date.plusDays(1)))
                .single { it.summary == "Synthetic removal fixture" }
            provider.deleteEvent(source, event)
            provider.removeEmptyLocalCalendar(source)
            assertEquals(null, provider.localCalendar())
            assertEquals(before, provider.calendars().map { it.id }.toSet())
        } finally {
            if (provider.localCalendar()?.id == source.id) {
                val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME,
                        "com.tinkercobble.ham.local")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,
                        CalendarContract.ACCOUNT_TYPE_LOCAL).build(), id)
                context.contentResolver.delete(uri, null, null)
            }
        }
    }
}
