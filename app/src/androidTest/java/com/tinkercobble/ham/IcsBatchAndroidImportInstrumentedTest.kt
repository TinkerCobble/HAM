package com.tinkercobble.ham

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.AndroidCalendarProvider
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.data.IcsSingleEventImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class IcsBatchAndroidImportInstrumentedTest {
    @Test fun reviewedDraftsWriteOnlySelectedSyntheticCalendarAndBlockDuplicate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val created = mutableListOf<Pair<Uri, Long>>()
        fun calendar(name: String): Long {
            val account = "ham-ics-${UUID.randomUUID()}@example.test"
            val syncUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,
                    CalendarContract.ACCOUNT_TYPE_LOCAL).build()
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, account)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
                put(CalendarContract.Calendars.NAME, name)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, name)
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                    CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC")
            }
            val id = ContentUris.parseId(resolver.insert(syncUri, values)
                ?: error("Could not create synthetic calendar"))
            created += syncUri to id
            return id
        }
        try {
            val targetId = calendar("HAM synthetic ICS target")
            val unrelatedId = calendar("HAM synthetic unrelated")
            val provider = AndroidCalendarProvider(context)
            val target = provider.calendars().single { it.id == "android:$targetId" }
            val unrelated = provider.calendars().single { it.id == "android:$unrelatedId" }
            provider.createAllDay(unrelated, LocalDate.parse("2026-11-05"), "Keep unrelated")
            val ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:synthetic-one@example.invalid
                DTSTAMP:20261027T000000Z
                DTSTART;VALUE=DATE:20261105
                DTEND;VALUE=DATE:20261106
                SUMMARY:First synthetic import
                DESCRIPTION:Synthetic notes
                END:VEVENT
                BEGIN:VEVENT
                UID:synthetic-two@example.invalid
                DTSTAMP:20261027T000000Z
                DTSTART;VALUE=DATE:20261106
                DTEND;VALUE=DATE:20261109
                SUMMARY:Second synthetic import
                LOCATION:Synthetic room
                END:VEVENT
                END:VCALENDAR
            """.trimIndent().toByteArray()
            val drafts = IcsSingleEventImport.previewAll(ics)
            assertEquals(2, drafts.size)
            drafts.forEach { draft -> provider.createAllDay(target, draft.date, draft.title,
                draft.description, draft.location, draft.endExclusive) }
            val range = DateRange(LocalDate.parse("2026-11-05"), LocalDate.parse("2026-11-10"))
            assertEquals(listOf("First synthetic import", "Second synthetic import"),
                provider.events(listOf(target), range).map { it.summary })
            assertEquals("2026-11-09", provider.events(listOf(target), range)
                .single { it.summary == "Second synthetic import" }.end)
            assertTrue(runCatching { provider.createAllDay(target, drafts[0].date,
                drafts[0].title, drafts[0].description, drafts[0].location) }.isFailure)
            assertEquals(listOf("Keep unrelated"),
                provider.events(listOf(unrelated), range).map { it.summary })
        } finally {
            created.forEach { (syncUri, id) ->
                resolver.delete(CalendarContract.Events.CONTENT_URI,
                    "${CalendarContract.Events.CALENDAR_ID}=?", arrayOf(id.toString()))
                resolver.delete(ContentUris.withAppendedId(syncUri, id), null, null)
            }
        }
    }
}
