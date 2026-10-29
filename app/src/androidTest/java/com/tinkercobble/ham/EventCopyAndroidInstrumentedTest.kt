package com.tinkercobble.ham

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import com.tinkercobble.ham.data.AndroidCalendarProvider
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.data.EventCopyDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EventCopyAndroidInstrumentedTest {
    @Before fun grantCalendarAccess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.grantRuntimePermission(target, Manifest.permission.READ_CALENDAR)
        instrumentation.uiAutomation.grantRuntimePermission(target, Manifest.permission.WRITE_CALENDAR)
    }

    @Test fun copyCreatesOnlyInChosenCalendarAndPreservesSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val created = mutableListOf<Pair<Uri, Long>>()
        fun calendar(name: String): Long {
            val account = "ham-copy-${UUID.randomUUID()}@example.test"
            val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
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
            val id = ContentUris.parseId(resolver.insert(uri, values)
                ?: error("Could not create synthetic calendar"))
            created += uri to id
            return id
        }
        try {
            val sourceId = calendar("HAM synthetic copy source")
            val destinationId = calendar("HAM synthetic copy destination")
            val provider = AndroidCalendarProvider(context)
            val source = provider.calendars().single { it.id == "android:$sourceId" }
            val destination = provider.calendars().single { it.id == "android:$destinationId" }
            val day = LocalDate.parse("2026-11-05")
            val end = day.plusDays(3)
            val range = DateRange(day, end.plusDays(1))
            assertTrue(runCatching { provider.createAllDay(source.copy(
                identityFingerprint = "0".repeat(64)), day, "Wrong calendar write") }.isFailure)
            assertTrue(provider.events(listOf(source), range).isEmpty())
            provider.createAllDay(source, day, "Synthetic copied event", "Synthetic note",
                "Synthetic place", end)
            val original = provider.events(listOf(source), range).single()
            val draft = EventCopyDraft.from(original)
            provider.createAllDay(destination, draft.date, draft.title,
                draft.description, draft.location, draft.endExclusive)
            val sourceAfter = provider.events(listOf(source), range).single()
            val copied = provider.events(listOf(destination), range).single()
            assertEquals(original, sourceAfter)
            assertEquals(original.summary, copied.summary)
            assertEquals(original.start, copied.start)
            assertEquals(original.end, copied.end)
            assertEquals("Synthetic note", copied.description)
            assertEquals("Synthetic place", copied.location)
            assertNotEquals(original.calendarId, copied.calendarId)
            assertNotEquals(original.uid, copied.uid)

            val moveDay = LocalDate.parse("2026-11-12")
            val moveRange = DateRange(moveDay, moveDay.plusDays(1))
            provider.createAllDay(source, moveDay, "Synthetic move", "Keep note", "Keep place")
            val toMove = provider.events(listOf(source), moveRange).single()
            provider.moveSingleEvent(source, destination, toMove, EventCopyDraft.from(toMove))
            assertTrue(provider.events(listOf(source), moveRange).isEmpty())
            assertEquals("Synthetic move", provider.events(listOf(destination), moveRange).single().summary)

            val changedDay = LocalDate.parse("2026-11-13")
            val changedRange = DateRange(changedDay, changedDay.plusDays(1))
            provider.createAllDay(source, changedDay, "Changed source", "Original detail")
            val changed = provider.events(listOf(source), changedRange).single()
            val changedId = changed.uid!!.removePrefix("android-event:").toLong()
            resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, changedId),
                ContentValues().apply { put(CalendarContract.Events.DESCRIPTION, "External change") },
                null, null)
            assertTrue(runCatching { provider.moveSingleEvent(source, destination, changed,
                EventCopyDraft.from(changed)) }.isFailure)
            assertEquals("External change", provider.events(listOf(source), changedRange).single().description)
            assertTrue(provider.events(listOf(destination), changedRange).isEmpty())
        } finally {
            created.forEach { (uri, id) ->
                resolver.delete(CalendarContract.Events.CONTENT_URI,
                    "${CalendarContract.Events.CALENDAR_ID}=?", arrayOf(id.toString()))
                resolver.delete(ContentUris.withAppendedId(uri, id), null, null)
            }
        }
    }
}
