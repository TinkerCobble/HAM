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
import com.tinkercobble.ham.data.RepeatFrequency
import com.tinkercobble.ham.data.RepeatSpec
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidTimedWriteInstrumentedTest {
    @Before fun grantCalendarAccess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.grantRuntimePermission(target, Manifest.permission.READ_CALENDAR)
        instrumentation.uiAutomation.grantRuntimePermission(target, Manifest.permission.WRITE_CALENDAR)
    }

    @Test fun createsFiniteAllDaySeriesOnExactSyntheticCalendar() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val account = "ham-series-${UUID.randomUUID()}@example.test"
        val syncUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val calendar = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
            put(CalendarContract.Calendars.NAME, "HAM synthetic series")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "HAM synthetic series")
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC")
        }
        val created = resolver.insert(syncUri, calendar) ?: error("Could not create synthetic calendar")
        val id = ContentUris.parseId(created)
        try {
            val provider = AndroidCalendarProvider(context)
            val source = provider.calendars().single { it.id == "android:$id" }
            val date = LocalDate.parse("2026-11-02")
            val repeat = RepeatSpec(RepeatFrequency.WEEKLY, 4)
            provider.createAllDay(source, date.plusWeeks(3), "HAM synthetic series event")
            val laterCreateCollision = provider.events(listOf(source),
                DateRange(date.plusWeeks(3), date.plusWeeks(3).plusDays(1)))
                .single { it.summary == "HAM synthetic series event" }
            assertTrue(runCatching { provider.createRecurringAllDay(source, date,
                "HAM synthetic series event", repeat) }.isFailure)
            provider.deleteEvent(source, laterCreateCollision)
            provider.createRecurringAllDay(source, date, "HAM synthetic series event", repeat,
                "Synthetic series description", "Synthetic series room")
            val read = provider.events(listOf(source), DateRange(date, date.plusDays(30)))
            assertTrue(read.all { it.description == "Synthetic series description" &&
                it.location == "Synthetic series room" })
            assertEquals(listOf(date, date.plusWeeks(1), date.plusWeeks(2), date.plusWeeks(3)),
                read.map { it.date })
            assertTrue(read.all { it.recurrenceId != null })
            assertTrue(read.all { it.rrule == repeat.rrule })
            val masterId = read.first().uid!!.removePrefix("android-event:").toLong()
            val masterUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, masterId)
            assertEquals(1, resolver.update(masterUri, ContentValues().apply {
                put(CalendarContract.Events.EVENT_LOCATION, "Externally changed room")
            }, null, null))
            assertTrue(runCatching { provider.updateSeriesTitle(source, read[1],
                "HAM stale title") }.isFailure)
            assertTrue(runCatching { provider.deleteSeries(source, read[1]) }.isFailure)
            assertEquals("Externally changed room", resolver.query(masterUri,
                arrayOf(CalendarContract.Events.EVENT_LOCATION), null, null, null)?.use {
                assertTrue(it.moveToFirst()); it.getString(0)
            })
            assertEquals(1, resolver.update(masterUri, ContentValues().apply {
                put(CalendarContract.Events.EVENT_LOCATION, "Synthetic series room")
            }, null, null))
            assertTrue(runCatching { provider.createRecurringAllDay(source, date,
                "HAM synthetic series event", repeat) }.isFailure)
            assertTrue(runCatching { provider.createRecurringAllDay(source, date.plusWeeks(1),
                "HAM synthetic series event", repeat) }.isFailure)
            assertTrue(runCatching { provider.createAllDay(source, date.plusWeeks(1),
                "HAM synthetic series event") }.isFailure)
            assertEquals(1, resolver.query(CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.TITLE}=?",
                arrayOf(id.toString(), "HAM synthetic series event"), null)?.use { it.count })
            provider.createAllDay(source, date, "HAM unrelated event")
            val unrelated = provider.events(listOf(source), DateRange(date, date.plusDays(1)))
                .single { it.summary == "HAM unrelated event" }
            assertTrue(runCatching { provider.updateAllDayDate(source, unrelated,
                "HAM synthetic series event", date.plusWeeks(1)) }.isFailure)
            provider.updateAllDayDate(source, unrelated, "HAM unrelated event", date.plusDays(2))
            val moved = provider.events(listOf(source), DateRange(date.plusDays(2), date.plusDays(3)))
                .single { it.uid == unrelated.uid }
            assertEquals(date.plusDays(2), moved.date)
            assertTrue(runCatching { provider.updateAllDayDate(source, unrelated,
                "HAM unrelated event", date.plusDays(3)) }.isFailure)
            fun insertTrip(start: LocalDate): com.tinkercobble.ham.data.AgendaEvent {
                val startMs = start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                val endMs = start.plusDays(3).atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant().toEpochMilli()
                resolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, id)
                    put(CalendarContract.Events.TITLE, "HAM synthetic trip")
                    put(CalendarContract.Events.DTSTART, startMs)
                    put(CalendarContract.Events.DTEND, endMs)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                    put(CalendarContract.Events.ALL_DAY, 1)
                }) ?: error("Could not create synthetic trip")
                return provider.events(listOf(source), DateRange(start, start.plusDays(1)))
                    .single { it.summary == "HAM synthetic trip" && it.date == start }
            }
            val trip = insertTrip(date.plusDays(9))
            val destination = date.plusDays(15)
            val collisionTrip = insertTrip(destination)
            assertTrue(runCatching { provider.updateAllDayDate(source, trip,
                "HAM synthetic trip", destination) }.isFailure)
            provider.deleteEvent(source, collisionTrip)
            provider.updateAllDayDate(source, trip, "HAM synthetic trip", destination)
            val movedTrip = provider.events(listOf(source),
                DateRange(destination, destination.plusDays(1)))
                .single { it.uid == trip.uid }
            assertEquals(destination.plusDays(3).toString(), movedTrip.end)
            assertTrue(runCatching { provider.updateAllDayDate(source, trip,
                "HAM synthetic trip", destination.plusDays(1)) }.isFailure)
            provider.deleteEvent(source, movedTrip)
            val createStart = date.plusDays(40)
            provider.createAllDay(source, createStart, "HAM created trip",
                endExclusive = createStart.plusDays(4))
            val createdTrip = provider.events(listOf(source),
                DateRange(createStart, createStart.plusDays(1)))
                .single { it.summary == "HAM created trip" }
            assertEquals(createStart.plusDays(4).toString(), createdTrip.end)
            assertTrue(runCatching { provider.createAllDay(source, createStart,
                "HAM created trip", endExclusive = createStart.plusDays(4)) }.isFailure)
            provider.deleteEvent(source, createdTrip)
            provider.createAllDay(source, date.plusWeeks(3), "HAM renamed series")
            val laterAllDay = provider.events(listOf(source),
                DateRange(date.plusWeeks(3), date.plusWeeks(3).plusDays(1)))
                .single { it.summary == "HAM renamed series" }
            assertTrue(runCatching { provider.updateSeriesTitle(source, read[1],
                "HAM renamed series") }.isFailure)
            provider.deleteEvent(source, laterAllDay)
            provider.updateSeriesTitle(source, read[1], "HAM renamed series")
            val renamed = provider.events(listOf(source), DateRange(date, date.plusDays(30)))
                .filter { it.uid == read[1].uid }
            assertEquals(4, renamed.size)
            assertTrue(renamed.all { it.summary == "HAM renamed series" })
            assertTrue(runCatching { provider.deleteSeries(source, read[1]) }.isFailure)
            provider.deleteSeries(source, renamed[1])
            val remaining = provider.events(listOf(source), DateRange(date, date.plusDays(30)))
            assertEquals(listOf("HAM unrelated event"), remaining.map { it.summary })
        } finally {
            resolver.delete(CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events.CALENDAR_ID}=?", arrayOf(id.toString()))
            resolver.delete(ContentUris.withAppendedId(syncUri, id), null, null)
        }
    }

    @Test fun createsExactSyntheticTimedEventAndBlocksDuplicate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val account = "ham-synthetic-${UUID.randomUUID()}@example.test"
        val syncUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val calendar = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
            put(CalendarContract.Calendars.NAME, "HAM synthetic timed write")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "HAM synthetic timed write")
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC")
        }
        val created = resolver.insert(syncUri, calendar) ?: error("Could not create synthetic calendar")
        val id = ContentUris.parseId(created)
        try {
            val provider = AndroidCalendarProvider(context)
            val source = provider.calendars().single { it.id == "android:$id" }
            val title = "HAM synthetic timed event"
            provider.createTimed(source, LocalDate.parse("2026-11-12"), "09:00", "10:00", title,
                "Synthetic timed description", "Synthetic timed room")
            val count = resolver.query(CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.TITLE}=?",
                arrayOf(id.toString(), title), null)?.use { it.count } ?: -1
            assertEquals(1, count)
            val detailed = provider.events(listOf(source), DateRange(LocalDate.parse("2026-11-12"),
                LocalDate.parse("2026-11-13"))).single { it.summary == title }
            assertEquals("Synthetic timed description", detailed.description)
            assertEquals("Synthetic timed room", detailed.location)
            provider.updateDetails(source, detailed, "Updated synthetic description",
                "Updated synthetic room")
            val updatedDetails = provider.events(listOf(source),
                DateRange(LocalDate.parse("2026-11-12"), LocalDate.parse("2026-11-13")))
                .single { it.summary == title }
            assertEquals("Updated synthetic description", updatedDetails.description)
            assertEquals("Updated synthetic room", updatedDetails.location)
            assertTrue(runCatching { provider.updateDetails(source, detailed,
                "Stale overwrite", "Another room") }.isFailure)
            provider.updateDetails(source, updatedDetails, "", "")
            val clearedDetails = provider.events(listOf(source),
                DateRange(LocalDate.parse("2026-11-12"), LocalDate.parse("2026-11-13")))
                .single { it.summary == title }
            assertEquals("", clearedDetails.description)
            assertEquals("", clearedDetails.location)
            assertTrue(runCatching { provider.createTimed(source, LocalDate.parse("2026-11-12"),
                "09:00", "10:00", title) }.isFailure)
            val seriesStart = LocalDate.parse("2026-11-11")
            provider.createTimed(source, seriesStart.plusDays(2), "14:00", "15:00",
                "HAM recurring timed overlap")
            val laterCreateCollision = provider.events(listOf(source),
                DateRange(seriesStart.plusDays(2), seriesStart.plusDays(3)))
                .single { it.summary == "HAM recurring timed overlap" }
            assertTrue(runCatching { provider.createRecurringTimed(source, seriesStart,
                "14:00", "15:00", "HAM recurring timed overlap",
                RepeatSpec(RepeatFrequency.DAILY, 3)) }.isFailure)
            provider.deleteEvent(source, laterCreateCollision)
            provider.createRecurringTimed(source, seriesStart, "14:00", "15:00",
                "HAM recurring timed overlap", RepeatSpec(RepeatFrequency.DAILY, 3))
            val series = provider.events(listOf(source), DateRange(seriesStart, seriesStart.plusDays(4)))
                .filter { it.summary == "HAM recurring timed overlap" }
            assertEquals(listOf(seriesStart, seriesStart.plusDays(1), seriesStart.plusDays(2)),
                series.map { it.date })
            assertTrue(series.all { it.rrule == "FREQ=DAILY;COUNT=3" && it.recurrenceId != null })
            assertTrue(runCatching { provider.createTimed(source, LocalDate.parse("2026-11-12"),
                "14:00", "15:00", "HAM recurring timed overlap") }.isFailure)
            assertTrue(runCatching { provider.createRecurringTimed(source, LocalDate.parse("2026-11-12"),
                "14:00", "15:00", "HAM recurring timed overlap",
                RepeatSpec(RepeatFrequency.DAILY, 3)) }.isFailure)
            val date = LocalDate.parse("2026-11-12")
            val event = provider.events(listOf(source), DateRange(date, date.plusDays(1)))
                .single { it.summary == title }
            provider.updateDetails(source, event, "HAM timed detail", "HAM timed room")
            val eventWithDetails = provider.events(listOf(source), DateRange(date, date.plusDays(1)))
                .single { it.uid == event.uid }
            assertTrue(runCatching { provider.updateTimed(source, event,
                "HAM stale detail edit", "11:00", "12:00") }.isFailure)
            provider.updateTimed(source, eventWithDetails, "HAM synthetic changed time", "11:00", "12:00")
            val changed = provider.events(listOf(source), DateRange(date, date.plusDays(1)))
                .single { it.uid == event.uid }
            assertEquals("HAM synthetic changed time", changed.summary)
            assertEquals("HAM timed detail", changed.description)
            assertEquals("HAM timed room", changed.location)
            assertEquals(11, com.tinkercobble.ham.data.AgendaEvent.instant(changed.start)!!
                .atZone(java.time.ZoneId.systemDefault()).hour)
            assertTrue(runCatching { provider.updateTimed(source, changed,
                "HAM recurring timed overlap", "14:00", "15:00") }.isFailure)
            assertTrue(runCatching { provider.updateTimed(source, event, title, "13:00", "14:00") }.isFailure)
            provider.updateTitle(source, changed, "HAM synthetic renamed")
            val renamed = provider.events(listOf(source), DateRange(date, date.plusDays(1)))
                .single { it.uid == event.uid }
            assertEquals("HAM synthetic renamed", renamed.summary)
            assertTrue(runCatching { provider.updateTimed(source, renamed,
                "HAM recurring timed overlap", "14:00", "15:00", date.plusDays(1)) }.isFailure)
            provider.updateTimed(source, renamed, "HAM moved timed", "11:00", "12:00",
                date.plusDays(7))
            val moved = provider.events(listOf(source),
                DateRange(date.plusDays(7), date.plusDays(8))).single { it.uid == event.uid }
            assertEquals(date.plusDays(7), moved.date)
            assertTrue(runCatching { provider.updateTimed(source, renamed,
                "HAM stale move", "11:00", "12:00", date.plusDays(8)) }.isFailure)
            assertTrue(runCatching { provider.deleteEvent(source, changed) }.isFailure)
            assertEquals(1, resolver.query(CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events._ID}=?",
                arrayOf(event.uid!!.removePrefix("android-event:")), null)?.use { it.count })
            provider.deleteEvent(source, moved)
            assertEquals(listOf("HAM recurring timed overlap"), provider.events(listOf(source),
                DateRange(date, date.plusDays(1))).map { it.summary })
            provider.createTimed(source, seriesStart.plusDays(2), "14:00", "15:00",
                "HAM renamed timed series")
            val laterTimed = provider.events(listOf(source),
                DateRange(seriesStart.plusDays(2), seriesStart.plusDays(3)))
                .single { it.summary == "HAM renamed timed series" }
            assertTrue(runCatching { provider.updateSeriesTitle(source, series[1],
                "HAM renamed timed series") }.isFailure)
            provider.deleteEvent(source, laterTimed)
            provider.updateSeriesTitle(source, series[1], "HAM renamed timed series")
            val renamedSeries = provider.events(listOf(source),
                DateRange(seriesStart, seriesStart.plusDays(4)))
                .filter { it.uid == series[1].uid }
            assertEquals(3, renamedSeries.size)
            assertTrue(renamedSeries.all { it.summary == "HAM renamed timed series" })
            assertTrue(runCatching { provider.deleteSeries(source, series[1]) }.isFailure)
            provider.createTimed(source, seriesStart.plusDays(2), "16:00", "17:00",
                "HAM shifted timed series")
            val collision = provider.events(listOf(source),
                DateRange(seriesStart.plusDays(2), seriesStart.plusDays(3)))
                .single { it.summary == "HAM shifted timed series" }
            assertTrue(runCatching { provider.updateSeriesTime(source, renamedSeries[1],
                "HAM shifted timed series", "16:00", "17:00") }.isFailure)
            provider.deleteEvent(source, collision)
            provider.updateSeriesTime(source, renamedSeries[1], "HAM shifted timed series",
                "16:00", "17:00")
            val shifted = provider.events(listOf(source),
                DateRange(seriesStart, seriesStart.plusDays(4)))
                .filter { it.uid == renamedSeries[1].uid }
            assertEquals(3, shifted.size)
            assertTrue(shifted.all { it.summary == "HAM shifted timed series" &&
                com.tinkercobble.ham.data.AgendaEvent.instant(it.start)!!
                    .atZone(java.time.ZoneId.systemDefault()).hour == 16 })
            assertTrue(runCatching { provider.updateSeriesTime(source, renamedSeries[1],
                "HAM stale shifted series", "18:00", "19:00") }.isFailure)
            provider.deleteSeries(source, shifted[1])
            val monthlyStart = LocalDate.parse("2026-01-31")
            val monthlyRule = RepeatSpec(RepeatFrequency.MONTHLY, 5)
            provider.createRecurringTimed(source, monthlyStart, "09:00", "10:00",
                "HAM synthetic monthly series", monthlyRule)
            val monthly = provider.events(listOf(source),
                DateRange(monthlyStart, LocalDate.parse("2026-09-01")))
                .filter { it.summary == "HAM synthetic monthly series" }
            assertEquals(monthlyRule.occurrenceDates(monthlyStart), monthly.map { it.date })
            provider.updateSeriesTime(source, monthly[1], "HAM synthetic monthly series",
                "11:00", "12:00")
            val changedMonthly = provider.events(listOf(source),
                DateRange(monthlyStart, LocalDate.parse("2026-09-01")))
                .filter { it.uid == monthly[1].uid }
            assertEquals(5, changedMonthly.size)
            assertTrue(changedMonthly.all { com.tinkercobble.ham.data.AgendaEvent.instant(it.start)!!
                .atZone(java.time.ZoneId.systemDefault()).hour == 11 })
            provider.deleteSeries(source, changedMonthly[1])
            assertTrue(provider.events(listOf(source),
                DateRange(seriesStart, seriesStart.plusDays(4))).isEmpty())
        } finally {
            resolver.delete(CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events.CALENDAR_ID}=?", arrayOf(id.toString()))
            resolver.delete(ContentUris.withAppendedId(syncUri, id), null, null)
        }
    }
}
