package com.tinkercobble.ham

import android.content.ContentUris
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.AndroidCalendarProvider
import com.tinkercobble.ham.data.DateRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class LocalAndroidCalendarInstrumentedTest {
    @Test fun localCalendarCreationIsExplicitIdempotentAndWritableOnExactSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = AndroidCalendarProvider(context)
        assertTrue(provider.canRead() && provider.canWrite())
        val calendarIdsBefore = provider.calendars().map { it.id }.toSet()
        val before = provider.localCalendar()
        var createdId: Long? = null
        try {
            val source = provider.createLocalCalendar()
            assertEquals("HAM local · this device", source.source)
            assertTrue(source.canCreate && source.canUpdate && source.canDelete)
            assertEquals(source.id, provider.createLocalCalendar().id)
            assertEquals(source.id, provider.localCalendar()?.id)
            if (before == null) {
                createdId = source.id.removePrefix("android:").toLong()
                val date = LocalDate.parse("2026-11-05")
                provider.createAllDay(source, date, "Synthetic local event")
                val visible = provider.events(listOf(source), DateRange(date, date.plusDays(1)))
                assertEquals(1, visible.count { it.summary == "Synthetic local event" })
                assertFalse(provider.calendars().any { it.id != source.id &&
                    it.source == "HAM local · this device" })
            }
        } finally {
            createdId?.let { id ->
                val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME,
                        "com.tinkercobble.ham.local")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,
                        CalendarContract.ACCOUNT_TYPE_LOCAL).build()
                assertEquals(1, context.contentResolver.delete(ContentUris.withAppendedId(uri, id),
                    null, null))
                assertEquals(null, provider.localCalendar())
            }
            assertEquals(calendarIdsBefore, provider.calendars().map { it.id }.toSet())
        }
    }
}
