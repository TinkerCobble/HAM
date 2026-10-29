package com.tinkercobble.ham.data

import android.Manifest
import android.content.Context
import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import android.content.ContentUris
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.security.MessageDigest
import java.util.UUID

/** Device-owned calendars, including accounts already synchronized by Android. No account password is read. */
class AndroidCalendarProvider(private val context: Context) {
    private companion object {
        const val LOCAL_ACCOUNT = "com.tinkercobble.ham.local"
        const val LOCAL_NAME = "com.tinkercobble.ham.local.calendar"
    }
    fun canRead(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED
    fun canWrite(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

    /** The app-created Android local calendar has no remote sync account. */
    fun localCalendar(): CalendarSource? {
        if (!canRead()) return null
        val where = "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND " +
            "${CalendarContract.Calendars.ACCOUNT_TYPE}=? AND ${CalendarContract.Calendars.NAME}=?"
        val ids = context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID), where,
            arrayOf(LOCAL_ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL, LOCAL_NAME), null)?.use { cursor ->
            val found = mutableListOf<Long>()
            while (cursor.moveToNext()) found += cursor.getLong(0)
            found
        } ?: error("Could not check the HAM local calendar")
        check(ids.size <= 1) { "More than one HAM local calendar exists; review before creating another" }
        val id = ids.singleOrNull() ?: return null
        return calendars().singleOrNull { it.id == "android:$id" }
            ?: error("HAM local calendar is unavailable; review it in Android calendars")
    }

    /** Called only after explicit user action; existing calendars are never auto-selected. */
    @Synchronized fun createLocalCalendar(): CalendarSource {
        check(canRead() && canWrite()) { "Allow Android calendar read and write access first" }
        localCalendar()?.let {
            check(it.canCreate) { "HAM local calendar is no longer writable" }
            return it
        }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT)
            put(CalendarContract.Calendars.NAME, LOCAL_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "HAM on this device")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF00645C.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
            put(CalendarContract.Calendars.CAL_SYNC1, UUID.randomUUID().toString())
        }
        val inserted = context.contentResolver.insert(uri, values)
            ?: error("Android did not create the HAM local calendar")
        val insertedId = ContentUris.parseId(inserted)
        return localCalendar()?.takeIf { it.id == "android:$insertedId" && it.canCreate }
            ?: error("Android did not confirm the HAM local calendar")
    }

    /** Delete only HAM's own empty local Provider calendar after explicit user confirmation. */
    @Synchronized fun removeEmptyLocalCalendar(source: CalendarSource) {
        check(canRead() && canWrite()) { "Android calendar read and write access is required" }
        val current = localCalendar()
        check(current != null && current.id == source.id &&
            current.identityFingerprint == source.identityFingerprint) {
            "HAM local calendar identity changed; refresh before removing it"
        }
        val id = providerId(source.id)
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.NAME, CalendarContract.Calendars.OWNER_ACCOUNT,
                CalendarContract.Calendars.CAL_SYNC1),
            "${CalendarContract.Calendars._ID}=?", arrayOf(id.toString()), null)?.use { cursor ->
            check(cursor.moveToFirst() && !cursor.moveToNext()) {
                "HAM local calendar is no longer available"
            }
            cursor.moveToFirst()
            check(cursor.getString(0) == LOCAL_ACCOUNT &&
                cursor.getString(1) == CalendarContract.ACCOUNT_TYPE_LOCAL &&
                cursor.getString(2) == LOCAL_NAME && cursor.getString(3) == LOCAL_ACCOUNT &&
                !cursor.getString(4).isNullOrBlank()) {
                "This is not a verified HAM local calendar"
            }
        } ?: error("Could not verify the HAM local calendar")
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.DELETED}=0",
            arrayOf(id.toString()), null)?.use { cursor ->
            check(!cursor.moveToFirst()) { "Remove this calendar's events before deleting it" }
        } ?: error("Could not verify that the HAM local calendar is empty")
        val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.ACCOUNT_TYPE_LOCAL).build(), id)
        check(context.contentResolver.delete(uri, null, null) == 1 && localCalendar() == null) {
            "Android did not confirm HAM local calendar removal"
        }
    }

    private fun providerId(id: String): Long = id.removePrefix("android:").toLongOrNull()
        ?.takeIf { id == "android:$it" } ?: error("Invalid Android calendar ID")

    private fun writableNow(source: CalendarSource): Long {
        check(canRead() && canWrite()) { "Android calendar write permission is required" }
        check(source.provider == CalendarProviderKind.ANDROID && source.identityFingerprint != null) {
            "Refresh Android calendars before writing"
        }
        val current = calendars().singleOrNull { it.id == source.id }
        check(current?.identityFingerprint == source.identityFingerprint && current.canCreate) {
            "Android calendar identity or write access changed. Refresh and choose it again."
        }
        return providerId(source.id)
    }

    private fun putDetails(values: ContentValues, details: EventDraftDetails) {
        if (details.description.isNotEmpty())
            values.put(CalendarContract.Events.DESCRIPTION, details.description)
        if (details.location.isNotEmpty())
            values.put(CalendarContract.Events.EVENT_LOCATION, details.location)
    }

    private fun verifyCreatedDetails(uri: android.net.Uri, details: EventDraftDetails,
        verifyEmpty: Boolean = false) {
        if (!verifyEmpty && details.description.isEmpty() && details.location.isEmpty()) return
        context.contentResolver.query(uri, arrayOf(CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION), null, null, null)?.use { cursor ->
            check(cursor.moveToFirst() &&
                (if (verifyEmpty) cursor.getString(0).orEmpty() == details.description else
                    details.description.isEmpty() || cursor.getString(0) == details.description) &&
                (if (verifyEmpty) cursor.getString(1).orEmpty() == details.location else
                    details.location.isEmpty() || cursor.getString(1) == details.location)) {
                "Android calendar did not confirm the event details"
            }
        } ?: error("Could not confirm the event details")
    }

    /** Recheck the provider immediately before an all-day insert; never write to a stale or fallback ID. */
    fun createAllDay(source: CalendarSource, date: LocalDate, title: String,
        description: String = "", location: String = "",
        endExclusive: LocalDate = date.plusDays(1)) {
        require(source.provider == CalendarProviderKind.ANDROID)
        require(endExclusive.isAfter(date)) { "All-day end must follow the start date" }
        val details = EventDraftDetails.of(description, location)
        val trimmed = title.trim()
        require(trimmed.isNotEmpty()) { "Event title is required" }
        val id = writableNow(source)
        check(events(listOf(source), DateRange(date, date.plusDays(1))).none {
            it.summary.equals(trimmed, true) && it.allDay &&
                (if (endExclusive == date.plusDays(1)) it.occursOn(date) else
                    it.start == date.toString() && it.end == endExclusive.toString())
        }) { "An event with this title already appears on that calendar and date" }
        val start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val columns = arrayOf(CalendarContract.Events._ID)
        val match = "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.TITLE}=? AND " +
            "${CalendarContract.Events.DTSTART}=? AND ${CalendarContract.Events.DTEND}=? AND " +
            "${CalendarContract.Events.ALL_DAY}=1 AND ${CalendarContract.Events.DELETED}=0"
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI, columns, match,
            arrayOf(id.toString(), trimmed, start.toString(), end.toString()), null)?.use {
            check(!it.moveToFirst()) { "An event with this title already exists on that calendar and date" }
        } ?: error("Could not check for an existing event")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, id)
            put(CalendarContract.Events.TITLE, trimmed)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            put(CalendarContract.Events.ALL_DAY, 1)
            putDetails(this, details)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: error("Android calendar did not create the event")
        context.contentResolver.query(uri, arrayOf(CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND, CalendarContract.Events.ALL_DAY), null, null, null)?.use {
            check(it.moveToFirst() && it.getLong(0) == id && it.getString(1) == trimmed &&
                it.getLong(2) == start && it.getLong(3) == end && it.getInt(4) == 1) {
                "Android calendar did not confirm the created event"
            }
        } ?: error("Could not confirm the created event")
        verifyCreatedDetails(uri, details)
    }

    /** Insert one finite all-day series on the exact writable calendar. */
    fun createRecurringAllDay(source: CalendarSource, date: LocalDate, title: String,
        repeat: RepeatSpec, description: String = "", location: String = "") {
        require(source.provider == CalendarProviderKind.ANDROID)
        val details = EventDraftDetails.of(description, location)
        val trimmed = title.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 200) { "Enter a valid event title" }
        val id = writableNow(source)
        repeat.occurrenceDates(date).forEach { occurrence ->
            check(events(listOf(source), DateRange(occurrence, occurrence.plusDays(1))).none {
                it.summary.equals(trimmed, true) && it.occursOn(occurrence)
            }) { "An event with this title already appears on that calendar and date" }
        }
        val start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val duplicate = "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.TITLE}=? AND " +
            "${CalendarContract.Events.DTSTART}=? AND ${CalendarContract.Events.ALL_DAY}=1 AND " +
            "${CalendarContract.Events.DELETED}=0"
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID), duplicate,
            arrayOf(id.toString(), trimmed, start.toString()), null)?.use {
            check(!it.moveToFirst()) { "An event with this title already starts on that calendar and date" }
        } ?: error("Could not check for an existing event")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, id)
            put(CalendarContract.Events.TITLE, trimmed)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            put(CalendarContract.Events.ALL_DAY, 1)
            put(CalendarContract.Events.DURATION, "P1D")
            put(CalendarContract.Events.RRULE, repeat.rrule)
            putDetails(this, details)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: error("Android calendar did not create the series")
        context.contentResolver.query(uri, arrayOf(CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART,
            CalendarContract.Events.DURATION, CalendarContract.Events.RRULE), null, null, null)?.use { cursor ->
            check(cursor.moveToFirst() && cursor.getLong(0) == id &&
                cursor.getString(1) == trimmed && cursor.getLong(2) == start &&
                cursor.getString(3) == "P1D" && cursor.getString(4) == repeat.rrule) {
                "Android calendar did not confirm the series"
            }
        } ?: error("Could not confirm the series")
        verifyCreatedDetails(uri, details)
    }

    private data class SeriesRow(val id: Long, val calendarId: Long, val title: String,
        val start: Long, val rule: String, val duration: String, val allDay: Boolean,
        val timeZone: String, val description: String, val location: String)

    /** Resolve one displayed occurrence back to an unchanged, exception-free finite master. */
    private fun verifiedSeries(source: CalendarSource, event: AgendaEvent): SeriesRow {
        require(source.provider == CalendarProviderKind.ANDROID &&
            event.provider == CalendarProviderKind.ANDROID && source.id == event.calendarId &&
            event.recurrenceId != null && RepeatSpec.supported(event.rrule) != null) {
            "This event is not a supported finite series"
        }
        val calendarId = writableNow(source)
        val eventId = event.uid?.removePrefix("android-event:")?.toLongOrNull()
            ?.takeIf { event.uid == "android-event:$it" } ?: error("Invalid Android event ID")
        val live = events(listOf(source), DateRange(event.date, event.date.plusDays(1)))
        check(live.count { it.identity == event.identity } == 1) {
            "The series occurrence changed. Refresh before editing."
        }
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val columns = arrayOf(CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART, CalendarContract.Events.DURATION,
            CalendarContract.Events.RRULE, CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.DELETED, CalendarContract.Events.ORIGINAL_ID,
            CalendarContract.Events.RDATE, CalendarContract.Events.EXDATE,
            CalendarContract.Events.EXRULE, CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.DESCRIPTION, CalendarContract.Events.EVENT_LOCATION)
        val row = context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            val present = cursor.moveToFirst()
            val duration = if (present) cursor.getString(3) else null
            val timeZone = if (present) cursor.getString(11) else null
            val validDuration = if (event.allDay) duration == "P1D" else
                runCatching { Duration.parse(checkNotNull(duration)).let { !it.isNegative && !it.isZero &&
                    it < Duration.ofDays(1) } }.getOrDefault(false)
            check(present && cursor.getLong(0) == calendarId &&
                cursor.getString(1) == event.summary && validDuration &&
                cursor.getString(4) == event.rrule && RepeatSpec.supported(cursor.getString(4)) != null &&
                (cursor.getInt(5) == 1) == event.allDay && cursor.getInt(6) == 0 && cursor.isNull(7) &&
                cursor.getString(8).isNullOrBlank() && cursor.getString(9).isNullOrBlank() &&
                cursor.getString(10).isNullOrBlank() && !timeZone.isNullOrBlank() &&
                (!event.allDay || timeZone == "UTC") &&
                cursor.getString(12).orEmpty() == event.description &&
                cursor.getString(13).orEmpty() == event.location) {
                "The series changed or has unsupported recurrence data"
            }
            SeriesRow(eventId, calendarId, cursor.getString(1), cursor.getLong(2),
                cursor.getString(4), checkNotNull(duration), event.allDay, checkNotNull(timeZone),
                cursor.getString(12).orEmpty(), cursor.getString(13).orEmpty())
        } ?: error("The series is unavailable")
        val exceptions = "${CalendarContract.Events.ORIGINAL_ID}=? AND ${CalendarContract.Events.DELETED}=0"
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID), exceptions, arrayOf(eventId.toString()), null)?.use {
            check(!it.moveToFirst()) { "This series has changed occurrences. Edit it in the calendar app." }
        } ?: error("Could not check series exceptions")
        return row
    }

    private fun exactSeriesMutation(row: SeriesRow): Pair<String, Array<String>> {
        val where = "${CalendarContract.Events._ID}=? AND ${CalendarContract.Events.CALENDAR_ID}=? AND " +
            "${CalendarContract.Events.TITLE}=? AND ${CalendarContract.Events.DTSTART}=? AND " +
            "${CalendarContract.Events.DURATION}=? AND ${CalendarContract.Events.RRULE}=? AND " +
            "${CalendarContract.Events.ALL_DAY}=? AND ${CalendarContract.Events.EVENT_TIMEZONE}=? AND " +
            "COALESCE(${CalendarContract.Events.DESCRIPTION},'')=? AND " +
            "COALESCE(${CalendarContract.Events.EVENT_LOCATION},'')=? AND " +
            "${CalendarContract.Events.DELETED}=0 AND " +
            "${CalendarContract.Events.ORIGINAL_ID} IS NULL AND " +
            "(${CalendarContract.Events.RDATE} IS NULL OR ${CalendarContract.Events.RDATE}='') AND " +
            "(${CalendarContract.Events.EXDATE} IS NULL OR ${CalendarContract.Events.EXDATE}='') AND " +
            "(${CalendarContract.Events.EXRULE} IS NULL OR ${CalendarContract.Events.EXRULE}='')"
        return where to arrayOf(row.id.toString(), row.calendarId.toString(), row.title,
            row.start.toString(), row.duration, row.rule, if (row.allDay) "1" else "0", row.timeZone,
            row.description, row.location)
    }

    fun updateSeriesTitle(source: CalendarSource, event: AgendaEvent, title: String) {
        val trimmed = title.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 200 && trimmed != event.summary) {
            "Enter a different series title (up to 200 characters)"
        }
        val row = verifiedSeries(source, event)
        val zone = ZoneId.of(row.timeZone)
        val masterDate = Instant.ofEpochMilli(row.start).atZone(zone).toLocalDate()
        checkNotNull(RepeatSpec.supported(row.rule)).occurrenceDates(masterDate).forEach { date ->
            val visible = events(listOf(source), DateRange(date.minusDays(2), date.plusDays(3)))
            val matching = visible.filter { it.uid == event.uid && it.rrule == row.rule &&
                (if (row.allDay) it.date == date else
                    AgendaEvent.instant(it.start)?.atZone(zone)?.toLocalDate() == date) }
            check(matching.size == 1) {
                "The calendar series is incomplete or changed; refresh before editing"
            }
            val current = matching.single()
            check(visible.none { it.uid != event.uid && it.summary.equals(trimmed, true) &&
                (if (row.allDay) it.allDay && it.start == current.start && it.end == current.end
                else !it.allDay && AgendaEvent.instant(it.start) == AgendaEvent.instant(current.start) &&
                    AgendaEvent.instant(it.end) == AgendaEvent.instant(current.end))
            }) { "An event with this title already appears on that calendar and date" }
        }
        val (where, args) = exactSeriesMutation(row)
        check(context.contentResolver.update(CalendarContract.Events.CONTENT_URI,
            ContentValues().apply { put(CalendarContract.Events.TITLE, trimmed) }, where, args) == 1) {
            "The series changed during editing. Refresh before saving."
        }
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, row.id)
        context.contentResolver.query(uri, arrayOf(CalendarContract.Events.TITLE,
            CalendarContract.Events.RRULE), null, null, null)?.use {
            check(it.moveToFirst() && it.getString(0) == trimmed && it.getString(1) == row.rule) {
                "Android calendar did not confirm the series title"
            }
        } ?: error("Could not confirm the series title")
    }

    /** Shift the time of every occurrence of one unchanged finite timed master. */
    fun updateSeriesTime(source: CalendarSource, event: AgendaEvent, title: String,
        startText: String, endText: String) {
        require(!event.allDay) { "Only timed series can change time here" }
        val trimmed = title.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 200) { "Enter a valid series title" }
        val row = verifiedSeries(source, event)
        val zone = ZoneId.systemDefault()
        require(row.timeZone == zone.id) { "This series uses another time zone; edit it in its calendar app" }
        val duration = Duration.parse(row.duration)
        val occurrenceStart = AgendaEvent.instant(event.start) ?: error("Series time is unavailable")
        val occurrenceEnd = AgendaEvent.instant(event.end) ?: error("Series time is unavailable")
        require(occurrenceStart.atZone(zone).toLocalDate() == event.date &&
            occurrenceEnd.atZone(zone).toLocalDate() == event.date &&
            Duration.between(occurrenceStart, occurrenceEnd) == duration) {
            "This series occurrence needs a different editor"
        }
        val masterDate = Instant.ofEpochMilli(row.start).atZone(zone).toLocalDate()
        val repeat = checkNotNull(RepeatSpec.supported(row.rule))
        repeat.occurrenceDates(masterDate).forEach { date ->
            val proposed = TimedEventTime.parse(date, startText, endText, zone)
            val visible = events(listOf(source), DateRange(date, date.plusDays(1)))
            check(visible.count { it.uid == event.uid && it.date == date &&
                it.rrule == row.rule } == 1) {
                "The calendar series is incomplete or changed; refresh before editing"
            }
            check(visible.none { it.uid != event.uid && !it.allDay &&
                it.summary.equals(trimmed, true) &&
                AgendaEvent.instant(it.start) == proposed.start.toInstant() &&
                AgendaEvent.instant(it.end) == proposed.end.toInstant()
            }) { "An event with this title and time already appears on that calendar" }
        }
        val masterTime = TimedEventTime.parse(masterDate, startText, endText, zone)
        val masterStart = masterTime.start.toInstant().toEpochMilli()
        val newDuration = "PT${Duration.between(masterTime.start, masterTime.end).seconds}S"
        val (where, args) = exactSeriesMutation(row)
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, trimmed)
            put(CalendarContract.Events.DTSTART, masterStart)
            put(CalendarContract.Events.DURATION, newDuration)
            put(CalendarContract.Events.RRULE, row.rule)
            put(CalendarContract.Events.EVENT_TIMEZONE, row.timeZone)
            put(CalendarContract.Events.ALL_DAY, 0)
        }
        check(context.contentResolver.update(CalendarContract.Events.CONTENT_URI,
            values, where, args) == 1) {
            "The series changed during editing. Refresh before saving."
        }
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, row.id)
        context.contentResolver.query(uri, arrayOf(CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART, CalendarContract.Events.DURATION,
            CalendarContract.Events.RRULE, CalendarContract.Events.EVENT_TIMEZONE), null, null, null)?.use {
            check(it.moveToFirst() && it.getString(0) == trimmed &&
                it.getLong(1) == masterStart && it.getString(2) == newDuration &&
                it.getString(3) == row.rule && it.getString(4) == row.timeZone) {
                "Android calendar did not confirm the series time"
            }
        } ?: error("Could not confirm the series time")
    }

    fun deleteSeries(source: CalendarSource, event: AgendaEvent) {
        val row = verifiedSeries(source, event)
        val (where, args) = exactSeriesMutation(row)
        check(context.contentResolver.delete(CalendarContract.Events.CONTENT_URI, where, args) == 1) {
            "The series changed during removal. Refresh before trying again."
        }
        val stillLive = "${CalendarContract.Events._ID}=? AND ${CalendarContract.Events.DELETED}=0"
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID), stillLive, arrayOf(row.id.toString()), null)?.use {
            check(!it.moveToFirst()) { "Android calendar did not confirm series removal" }
        } ?: error("Could not confirm series removal")
    }

    /** Insert one finite same-day timed series in the device time zone. */
    fun createRecurringTimed(source: CalendarSource, date: LocalDate, startText: String,
        endText: String, title: String, repeat: RepeatSpec,
        description: String = "", location: String = "") {
        require(source.provider == CalendarProviderKind.ANDROID)
        val details = EventDraftDetails.of(description, location)
        val trimmed = title.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 200) { "Enter a valid event title" }
        val time = TimedEventTime.parse(date, startText, endText)
        val calendarId = writableNow(source)
        val start = time.start.toInstant().toEpochMilli()
        val end = time.end.toInstant().toEpochMilli()
        repeat.occurrenceDates(date).forEach { occurrence ->
            val proposed = TimedEventTime.parse(occurrence, startText, endText, time.start.zone)
            check(events(listOf(source), DateRange(occurrence, occurrence.plusDays(1))).none {
                !it.allDay && it.summary.equals(trimmed, true) &&
                    AgendaEvent.instant(it.start) == proposed.start.toInstant() &&
                    AgendaEvent.instant(it.end) == proposed.end.toInstant()
            }) { "An event with this title and time already appears on that calendar" }
        }
        val duplicate = "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.TITLE}=? AND " +
            "${CalendarContract.Events.DTSTART}=? AND ${CalendarContract.Events.ALL_DAY}=0 AND " +
            "${CalendarContract.Events.DELETED}=0"
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID), duplicate,
            arrayOf(calendarId.toString(), trimmed, start.toString()), null)?.use {
            check(!it.moveToFirst()) { "An event with this title and time already starts on that calendar" }
        } ?: error("Could not check for an existing event")
        val duration = "PT${Duration.between(time.start, time.end).seconds}S"
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, trimmed)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.EVENT_TIMEZONE, time.start.zone.id)
            put(CalendarContract.Events.ALL_DAY, 0)
            put(CalendarContract.Events.DURATION, duration)
            put(CalendarContract.Events.RRULE, repeat.rrule)
            putDetails(this, details)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: error("Android calendar did not create the timed series")
        context.contentResolver.query(uri, arrayOf(CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART,
            CalendarContract.Events.DURATION, CalendarContract.Events.RRULE,
            CalendarContract.Events.EVENT_TIMEZONE), null, null, null)?.use { cursor ->
            check(cursor.moveToFirst() && cursor.getLong(0) == calendarId &&
                cursor.getString(1) == trimmed && cursor.getLong(2) == start &&
                cursor.getString(3) == duration && cursor.getString(4) == repeat.rrule &&
                cursor.getString(5) == time.start.zone.id) {
                "Android calendar did not confirm the timed series"
            }
        } ?: error("Could not confirm the timed series")
        verifyCreatedDetails(uri, details)
    }

    /** Creates one same-day timed event on the exact writable provider calendar. */
    fun createTimed(source: CalendarSource, date: LocalDate, startText: String,
        endText: String, title: String, description: String = "", location: String = "") {
        require(source.provider == CalendarProviderKind.ANDROID)
        val details = EventDraftDetails.of(description, location)
        val trimmed = title.trim()
        require(trimmed.isNotEmpty()) { "Event title is required" }
        val time = TimedEventTime.parse(date, startText, endText)
        val id = writableNow(source)
        val start = time.start.toInstant().toEpochMilli()
        val end = time.end.toInstant().toEpochMilli()
        check(events(listOf(source), DateRange(date, date.plusDays(1))).none {
            !it.allDay && it.summary.equals(trimmed, true) &&
                AgendaEvent.instant(it.start)?.toEpochMilli() == start &&
                AgendaEvent.instant(it.end)?.toEpochMilli() == end
        }) { "An event with this title and time already appears on that calendar" }
        val match = "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.TITLE}=? AND " +
            "${CalendarContract.Events.DTSTART}=? AND ${CalendarContract.Events.DTEND}=? AND " +
            "${CalendarContract.Events.ALL_DAY}=0 AND ${CalendarContract.Events.DELETED}=0"
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID), match,
            arrayOf(id.toString(), trimmed, start.toString(), end.toString()), null)?.use {
            check(!it.moveToFirst()) { "An event with this title and time already exists on that calendar" }
        } ?: error("Could not check for an existing event")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, id)
            put(CalendarContract.Events.TITLE, trimmed)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, time.start.zone.id)
            put(CalendarContract.Events.ALL_DAY, 0)
            putDetails(this, details)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: error("Android calendar did not create the event")
        context.contentResolver.query(uri, arrayOf(CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND),
            null, null, null)?.use { cursor ->
            check(cursor.moveToFirst() && cursor.getLong(0) == id && cursor.getString(1) == trimmed &&
                cursor.getLong(2) == start && cursor.getLong(3) == end) {
                "Android calendar did not confirm the created event"
            }
        } ?: error("Could not confirm the created event")
        verifyCreatedDetails(uri, details)
    }

    private fun verifiedEventUri(source: CalendarSource, event: AgendaEvent): android.net.Uri {
        require(source.provider == CalendarProviderKind.ANDROID && event.provider == CalendarProviderKind.ANDROID &&
            source.id == event.calendarId && event.recurrenceId == null)
        val calendarId = writableNow(source)
        val eventId = event.uid?.removePrefix("android-event:")?.toLongOrNull()
            ?.takeIf { event.uid == "android-event:$it" } ?: error("Invalid Android event ID")
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val columns = arrayOf(CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.RRULE, CalendarContract.Events.RDATE, CalendarContract.Events.ORIGINAL_ID,
            CalendarContract.Events.DESCRIPTION, CalendarContract.Events.EVENT_LOCATION)
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            check(cursor.moveToFirst()) { "Event changed or disappeared" }
            val start = if (event.allDay) LocalDate.parse(event.start).atStartOfDay(ZoneOffset.UTC)
                .toInstant().toEpochMilli() else AgendaEvent.instant(event.start)?.toEpochMilli()
            val end = if (event.allDay) LocalDate.parse(event.end).atStartOfDay(ZoneOffset.UTC)
                .toInstant().toEpochMilli() else AgendaEvent.instant(event.end)?.toEpochMilli()
            check(cursor.getLong(0) == calendarId && cursor.getString(1) == event.summary &&
                cursor.getLong(2) == start && cursor.getLong(3) == end &&
                (cursor.getInt(4) == 1) == event.allDay && cursor.getString(5).isNullOrBlank() &&
                cursor.getString(6).isNullOrBlank() && cursor.isNull(7) &&
                cursor.getString(8).orEmpty() == event.description &&
                cursor.getString(9).orEmpty() == event.location) {
                "Event changed or is recurring; refresh before editing"
            }
        } ?: error("Could not verify the event")
        return uri
    }

    /** Read a selected, unchanged nonrecurring Android event's provider-backed reminders. */
    fun reminders(source: CalendarSource, event: AgendaEvent): List<AndroidReminder> {
        val eventId = ContentUris.parseId(verifiedEventUri(source, event))
        val where = "${CalendarContract.Reminders.EVENT_ID}=?"
        return (context.contentResolver.query(CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders._ID, CalendarContract.Reminders.MINUTES,
                CalendarContract.Reminders.METHOD), where, arrayOf(eventId.toString()), null)
            ?: error("Could not read Android calendar reminders")).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(AndroidReminder(cursor.getLong(0), cursor.getInt(1),
                    cursor.getInt(2)))
            }
        }
    }

    /** Add one offset only after checking the selected calendar's declared method and limit. */
    fun addReminder(source: CalendarSource, event: AgendaEvent, minutes: Int) {
        require(minutes in 0..AndroidReminders.MAX_MINUTES) { "Choose a reminder within seven days" }
        check(source.canUpdate) { "This Android calendar cannot edit reminders" }
        val eventId = ContentUris.parseId(verifiedEventUri(source, event))
        val calendarId = providerId(source.id)
        val policy = context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars.ALLOWED_REMINDERS,
                CalendarContract.Calendars.MAX_REMINDERS), "${CalendarContract.Calendars._ID}=?",
            arrayOf(calendarId.toString()), null)?.use { cursor ->
            check(cursor.moveToFirst()) { "Android calendar changed. Refresh before editing reminders." }
            cursor.getString(0) to if (cursor.isNull(1)) null else cursor.getInt(1)
        } ?: error("Could not check Android calendar reminder support")
        val allowed = policy.first?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
        val method = when {
            allowed == null || CalendarContract.Reminders.METHOD_DEFAULT in allowed ->
                CalendarContract.Reminders.METHOD_DEFAULT
            CalendarContract.Reminders.METHOD_ALERT in allowed -> CalendarContract.Reminders.METHOD_ALERT
            else -> error("This calendar does not support device alerts")
        }
        val current = reminders(source, event)
        check(current.size < (policy.second ?: 5).coerceAtMost(5)) {
            "This calendar has reached its reminder limit"
        }
        check(current.none { it.minutes == minutes && it.method in setOf(
            CalendarContract.Reminders.METHOD_DEFAULT, CalendarContract.Reminders.METHOD_ALERT) }) {
            "A device alert at this time already exists"
        }
        val values = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutes)
            put(CalendarContract.Reminders.METHOD, method)
        }
        val inserted = context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
            ?: error("Android did not create the reminder; refresh before trying again")
        val reminderId = ContentUris.parseId(inserted)
        check(reminders(source, event).any { it.id == reminderId && it.minutes == minutes &&
            it.method == method }) { "Reminder result is uncertain; refresh before trying again" }
    }

    /** Remove only the exact selected reminder row; other event reminders remain untouched. */
    fun removeReminder(source: CalendarSource, event: AgendaEvent, reminder: AndroidReminder) {
        check(source.canUpdate) { "This Android calendar cannot edit reminders" }
        val eventId = ContentUris.parseId(verifiedEventUri(source, event))
        check(reminders(source, event).any { it == reminder }) {
            "Reminder changed. Refresh before removing it."
        }
        val where = "${CalendarContract.Reminders._ID}=? AND ${CalendarContract.Reminders.EVENT_ID}=? AND " +
            "${CalendarContract.Reminders.MINUTES}=? AND ${CalendarContract.Reminders.METHOD}=?"
        check(context.contentResolver.delete(CalendarContract.Reminders.CONTENT_URI, where,
            arrayOf(reminder.id.toString(), eventId.toString(), reminder.minutes.toString(),
                reminder.method.toString())) == 1) { "Reminder changed during removal" }
        check(reminders(source, event).none { it.id == reminder.id }) {
            "Reminder removal could not be confirmed"
        }
    }

    /** Repeat the fresh-read predicates in the mutation to close the read/write race. */
    private fun exactMutation(source: CalendarSource, event: AgendaEvent): Pair<String, Array<String>> {
        val id = providerId(source.id)
        val eventId = event.uid?.removePrefix("android-event:")?.toLongOrNull()
            ?.takeIf { event.uid == "android-event:$it" } ?: error("Invalid Android event ID")
        val start = if (event.allDay) LocalDate.parse(event.start).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli() else AgendaEvent.instant(event.start)?.toEpochMilli()
        val end = if (event.allDay) LocalDate.parse(event.end).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli() else AgendaEvent.instant(event.end)?.toEpochMilli()
        require(start != null && end != null) { "Event time is unavailable" }
        val where = "${CalendarContract.Events._ID}=? AND ${CalendarContract.Events.CALENDAR_ID}=? AND " +
            "${CalendarContract.Events.TITLE}=? AND ${CalendarContract.Events.DTSTART}=? AND " +
            "${CalendarContract.Events.DTEND}=? AND ${CalendarContract.Events.ALL_DAY}=? AND " +
            "${CalendarContract.Events.DELETED}=0 AND " +
            "COALESCE(${CalendarContract.Events.DESCRIPTION},'')=? AND " +
            "COALESCE(${CalendarContract.Events.EVENT_LOCATION},'')=? AND " +
            "(${CalendarContract.Events.RRULE} IS NULL OR ${CalendarContract.Events.RRULE}='') AND " +
            "(${CalendarContract.Events.RDATE} IS NULL OR ${CalendarContract.Events.RDATE}='') AND " +
            "${CalendarContract.Events.ORIGINAL_ID} IS NULL"
        return where to arrayOf(eventId.toString(), id.toString(), event.summary,
            start.toString(), end.toString(), if (event.allDay) "1" else "0",
            event.description, event.location)
    }

    /** Title-only edit with a fresh provider read; recurring instances and changed records are rejected. */
    fun updateTitle(source: CalendarSource, event: AgendaEvent, title: String) {
        val trimmed = title.trim()
        require(trimmed.isNotEmpty()) { "Event title is required" }
        verifiedEventUri(source, event)
        val (where, args) = exactMutation(source, event)
        val values = ContentValues().apply { put(CalendarContract.Events.TITLE, trimmed) }
        check(context.contentResolver.update(CalendarContract.Events.CONTENT_URI, values, where, args) == 1) {
            "Event changed during editing. Refresh before saving."
        }
    }

    /** Change optional fields on one exact nonrecurring row; preserve unrelated event fields. */
    fun updateDetails(source: CalendarSource, event: AgendaEvent,
        description: String, location: String) {
        val details = EventDraftDetails.of(description, location)
        require(details.description != event.description || details.location != event.location) {
            "Change the location or description before saving"
        }
        val uri = verifiedEventUri(source, event)
        context.contentResolver.query(uri, arrayOf(CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION), null, null, null)?.use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0).orEmpty() == event.description &&
                cursor.getString(1).orEmpty() == event.location) {
                "Event details changed; refresh before saving"
            }
        } ?: error("Could not verify the event details")
        val (baseWhere, baseArgs) = exactMutation(source, event)
        val values = ContentValues().apply {
            put(CalendarContract.Events.DESCRIPTION, details.description)
            put(CalendarContract.Events.EVENT_LOCATION, details.location)
        }
        check(context.contentResolver.update(CalendarContract.Events.CONTENT_URI, values,
            baseWhere, baseArgs) == 1) {
            "Event changed during editing. Refresh before saving."
        }
        verifyCreatedDetails(uri, details, verifyEmpty = true)
    }

    /** Move one nonrecurring all-day event after an exact row and target-date check. */
    fun updateAllDayDate(source: CalendarSource, event: AgendaEvent, title: String,
        newDate: LocalDate) {
        require(event.allDay) { "Only all-day events can change date here" }
        val days = java.time.temporal.ChronoUnit.DAYS.between(event.date, LocalDate.parse(event.end))
        require(days > 0) { "All-day event must have a positive duration" }
        val trimmed = title.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 200) { "Enter a valid event title" }
        require(newDate != event.date) { "Choose a different date" }
        val uri = verifiedEventUri(source, event)
        val calendarId = providerId(source.id)
        val start = newDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val newEnd = newDate.plusDays(days)
        val end = newEnd.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        check(events(listOf(source), DateRange(newDate, newDate.plusDays(1))).none {
            it.uid != event.uid && it.allDay && it.summary.equals(trimmed, true) &&
                (if (days == 1L) it.occursOn(newDate) else
                    it.start == newDate.toString() && it.end == newEnd.toString())
        }) { "An event with this title already appears on that calendar and date" }
        val (where, args) = exactMutation(source, event)
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, trimmed)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        }
        check(context.contentResolver.update(CalendarContract.Events.CONTENT_URI, values, where, args) == 1) {
            "Event changed during editing. Refresh before saving."
        }
        context.contentResolver.query(uri, arrayOf(CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND, CalendarContract.Events.ALL_DAY), null, null, null)?.use {
            check(it.moveToFirst() && it.getLong(0) == calendarId && it.getString(1) == trimmed &&
                it.getLong(2) == start && it.getLong(3) == end && it.getInt(4) == 1) {
                "Android calendar did not confirm the event change"
            }
        } ?: error("Could not confirm the event change")
    }

    /** Edit or move one nonrecurring same-day timed record after exact provider and duplicate checks. */
    fun updateTimed(source: CalendarSource, event: AgendaEvent, title: String,
        startText: String, endText: String, newDate: LocalDate = event.date) {
        require(!event.allDay) { "Only timed events can change time here" }
        val zone = ZoneId.systemDefault()
        val originalStart = AgendaEvent.instant(event.start)?.toEpochMilli()
            ?: error("Event time is unavailable")
        val originalEnd = AgendaEvent.instant(event.end)?.toEpochMilli()
            ?: error("Event time is unavailable")
        require(Instant.ofEpochMilli(originalEnd).atZone(zone).toLocalDate() == event.date) {
            "Overnight events need a separate editor"
        }
        val trimmed = title.trim()
        require(trimmed.isNotEmpty()) { "Event title is required" }
        val time = TimedEventTime.parse(newDate, startText, endText, zone)
        val uri = verifiedEventUri(source, event)
        val calendarId = providerId(source.id)
        val eventId = ContentUris.parseId(uri)
        val start = time.start.toInstant().toEpochMilli()
        val end = time.end.toInstant().toEpochMilli()
        check(events(listOf(source), DateRange(newDate, newDate.plusDays(1))).none {
            it.uid != event.uid && !it.allDay && it.summary.equals(trimmed, true) &&
                AgendaEvent.instant(it.start)?.toEpochMilli() == start &&
                AgendaEvent.instant(it.end)?.toEpochMilli() == end
        }) { "An event with this title and time already appears on that calendar" }
        val duplicate = "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.TITLE}=? AND " +
            "${CalendarContract.Events.DTSTART}=? AND ${CalendarContract.Events.DTEND}=? AND " +
            "${CalendarContract.Events.ALL_DAY}=0 AND ${CalendarContract.Events.DELETED}=0 AND " +
            "${CalendarContract.Events._ID}<>?"
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID), duplicate,
            arrayOf(calendarId.toString(), trimmed, start.toString(), end.toString(), eventId.toString()), null)?.use {
            check(!it.moveToFirst()) { "An event with this title and time already exists on that calendar" }
        } ?: error("Could not check for an existing event")
        val (where, args) = exactMutation(source, event)
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, trimmed)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
        }
        check(context.contentResolver.update(CalendarContract.Events.CONTENT_URI, values, where,
            args) == 1) {
            "Event changed during editing. Refresh before saving."
        }
        context.contentResolver.query(uri, arrayOf(CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND),
            null, null, null)?.use { cursor ->
            check(cursor.moveToFirst() && cursor.getLong(0) == calendarId && cursor.getString(1) == trimmed &&
                cursor.getLong(2) == start && cursor.getLong(3) == end) {
                "Android calendar did not confirm the event change"
            }
        } ?: error("Could not confirm the event change")
    }

    /** Delete only the exact nonrecurring event after a fresh read and separate user confirmation. */
    fun deleteEvent(source: CalendarSource, event: AgendaEvent) {
        verifiedEventUri(source, event)
        val (where, args) = exactMutation(source, event)
        check(context.contentResolver.delete(CalendarContract.Events.CONTENT_URI, where, args) == 1) {
            "Event changed during removal. Refresh before trying again."
        }
    }

    /** Copy and confirm the destination first. A failed source removal leaves both events for review. */
    fun moveSingleEvent(source: CalendarSource, destination: CalendarSource,
        event: AgendaEvent, draft: IcsImportDraft) {
        require(source.provider == CalendarProviderKind.ANDROID &&
            destination.provider == CalendarProviderKind.ANDROID && source.id != destination.id)
        require(source.canDelete && destination.canCreate) { "Choose writable Android calendars" }
        require(event.calendarId == source.id && event.recurrenceId == null &&
            event.rrule.isNullOrBlank() && draft.repeat == null) { "Only one nonrepeating event can be moved" }
        require(draft == EventCopyDraft.from(event)) { "Event copy changed; review the event again" }
        verifiedEventUri(source, event)
        try {
            if (draft.allDay) createAllDay(destination, draft.date, draft.title,
                draft.description, draft.location, draft.endExclusive)
            else createTimed(destination, draft.date, draft.startText, draft.endText,
                draft.title, draft.description, draft.location)
        } catch (_: Exception) {
            error("The original was not removed. The destination copy may have been created; refresh and review both calendars before trying again.")
        }
        try {
            deleteEvent(source, event)
        } catch (_: Exception) {
            error("The destination copy was created, but the original could not be removed. Both events may be present. Refresh and review them before trying again.")
        }
    }

    fun calendars(): List<CalendarSource> {
        if (!canRead()) return emptyList()
        val columns = arrayOf(CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.SYNC_EVENTS, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT, CalendarContract.Calendars._SYNC_ID,
            CalendarContract.Calendars.CAL_SYNC1)
        val result = mutableListOf<CalendarSource>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, columns, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accountCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
            val colorCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_COLOR)
            val syncCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.SYNC_EVENTS)
            val accessCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            val typeCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE)
            val internalNameCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.NAME)
            while (cursor.moveToNext()) {
                if (cursor.getInt(syncCol) != 1) continue
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)?.ifBlank { null } ?: "Android calendar $id"
                val account = cursor.getString(accountCol)?.ifBlank { null } ?: "device"
                val color = if (cursor.isNull(colorCol)) null else
                    String.format("#%06X", cursor.getInt(colorCol) and 0xFFFFFF)
                val features = if (canWrite() && cursor.getInt(accessCol) >=
                    CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) 1 or 2 or 4 else 0
                val local = account == LOCAL_ACCOUNT &&
                    cursor.getString(typeCol) == CalendarContract.ACCOUNT_TYPE_LOCAL &&
                    cursor.getString(internalNameCol) == LOCAL_NAME
                val fingerprint = MessageDigest.getInstance("SHA-256").digest(
                    listOf(cursor.getString(accountCol), cursor.getString(typeCol),
                        cursor.getString(internalNameCol),
                        cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Calendars.OWNER_ACCOUNT)),
                        cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Calendars._SYNC_ID)),
                        if (local) cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CAL_SYNC1))
                        else null).joinToString("\u0000") { it.orEmpty() }.toByteArray(Charsets.UTF_8)
                ).joinToString("") { "%02x".format(it) }
                result += CalendarSource("android:$id", name, features,
                    if (local) "HAM local · this device" else "Android · $account",
                    color, CalendarProviderKind.ANDROID, fingerprint)
            }
        }
        return result
    }

    /** Instances expands recurring rules and exceptions within the requested range. */
    fun events(sources: List<CalendarSource>, range: DateRange): List<AgendaEvent> {
        if (!canRead() || sources.isEmpty()) return emptyList()
        val ids = sources.associateBy { it.id }
        val zone = ZoneId.systemDefault()
        val start = range.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = range.endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start); ContentUris.appendId(it, end)
        }.build()
        val columns = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.RRULE,
            CalendarContract.Instances.RDATE, CalendarContract.Instances.ORIGINAL_ID)
        val calendarIds = sources.map { it.id.removePrefix("android:") }
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",") { "?" }})"
        val result = mutableListOf<AgendaEvent>()
        (context.contentResolver.query(uri, columns, selection, calendarIds.toTypedArray(), null)
            ?: error("Could not read Android calendar events")).use { cursor ->
            while (cursor.moveToNext()) {
                val source = ids["android:${cursor.getLong(1)}"] ?: continue
                val begin = cursor.getLong(3)
                val finish = cursor.getLong(4)
                if (finish <= begin) continue
                val allDay = cursor.getInt(5) == 1
                fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
                fun datetime(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toOffsetDateTime().toString()
                result += AgendaEvent(source.id, source.name, cursor.getString(2)?.ifBlank { null } ?: "Untitled event",
                    if (allDay) date(begin) else datetime(begin),
                    if (allDay) date(finish) else datetime(finish), allDay,
                    cursor.getString(6).orEmpty(), cursor.getString(7).orEmpty(),
                    "android-event:${cursor.getLong(0)}",
                    if (cursor.getString(8).isNullOrBlank() && cursor.getString(9).isNullOrBlank() &&
                        cursor.isNull(10)) null else begin.toString(), CalendarProviderKind.ANDROID,
                    cursor.getString(8)?.ifBlank { null }, true)
            }
        }
        return result
    }
}
