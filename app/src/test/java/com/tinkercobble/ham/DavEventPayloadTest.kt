package com.tinkercobble.ham

import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.DavEventPayload
import com.tinkercobble.ham.data.DavSeriesValidation
import com.tinkercobble.ham.data.DavDetailsConflict
import com.tinkercobble.ham.data.DavMutationDispatcher
import com.tinkercobble.ham.data.DavAccount
import com.tinkercobble.ham.data.DavConnection
import com.tinkercobble.ham.data.DavKind
import com.tinkercobble.ham.data.IcsEventParser
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.data.RepeatFrequency
import com.tinkercobble.ham.data.RepeatSpec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DavEventPayloadTest {
    @Test fun detailOnlyUpdateSkipsSchedulePreflightButMovedEventDoesNot() {
        val date = LocalDate.parse("2026-11-12")
        val base = DavEventPayload.create(operation, date, "Synthetic", false,
            "10:00", "11:00", now = stamp, zone = ZoneId.of("America/New_York"))
        val details = DavEventPayload.updateDetails(base, "Fictional note", "Fictional room")
        assertFalse(DavMutationDispatcher.requiresSchedulePreflight(base, details, source))
        val moved = base.toString(Charsets.UTF_8).replace("T150000Z", "T170000Z")
            .replace("T160000Z", "T180000Z").toByteArray(Charsets.UTF_8)
        assertTrue(DavMutationDispatcher.requiresSchedulePreflight(base, moved, source))
    }
    @Test fun createPreflightDayUsesLocalDateForUtcTimedEvent() {
        val date = LocalDate.parse("2026-11-12")
        val allDay = DavEventPayload.create(UUID.randomUUID(), date, "Synthetic day", true,
            now = Instant.parse("2026-10-27T12:00:00Z"))
        assertEquals(date, DavEventPayload.firstCreateDay(allDay, ZoneId.of("America/New_York")))
        val timed = DavEventPayload.create(UUID.randomUUID(), date, "Synthetic evening", false,
            "23:00", "23:30", now = Instant.parse("2026-10-27T12:00:00Z"),
            zone = ZoneId.of("America/New_York"))
        assertEquals(date, DavEventPayload.firstCreateDay(timed, ZoneId.of("America/New_York")))
        assertTrue(runCatching { DavEventPayload.firstCreateDay(byteArrayOf(65)) }.isFailure)
    }

    @Test fun metadataConflictReviewPreservesServerTitleAndRejectsChangedIdentity() {
        val base = DavEventPayload.create(operation, LocalDate.parse("2026-11-12"),
            "Original", true, now = stamp, description = "Old notes", location = "Old room")
        val pending = DavEventPayload.updateDetails(base, "My notes", "My room")
        val server = DavEventPayload.updateDetails(DavEventPayload.updateTitle(base,
            "Server title"), "Server notes", "Server room")
        assertTrue(DavDetailsConflict.canReview(base, pending, source))
        val review = DavDetailsConflict.review(base, pending, server, source)
        val merged = IcsEventParser.parse(review.mergedIcs, source).events.single()
        assertEquals("Server title", merged.summary)
        assertEquals("My notes", merged.description)
        assertEquals("My room", merged.location)
        assertEquals(IcsEventParser.parse(base, source).events.single().uid, merged.uid)
        assertFalse(DavDetailsConflict.canReview(base,
            DavEventPayload.updateTitle(base, "Title only"), source))
        val moved = String(server, Charsets.UTF_8).replace("DTSTART;VALUE=DATE:20261112",
            "DTSTART;VALUE=DATE:20261113").toByteArray()
        assertTrue(runCatching { DavDetailsConflict.review(base, pending, moved, source) }.isFailure)
        assertTrue(runCatching { DavDetailsConflict.review(base, pending,
            DavEventPayload.updateDetails(base, "My notes", "My room"), source) }.isFailure)
    }
    @Test fun detailEditPreservesIdentityAndRejectsComplexResources() {
        val original = DavEventPayload.create(operation, LocalDate.parse("2026-11-12"),
            "Synthetic", true, now = stamp, description = "Before", location = "Old room")
        val revised = DavEventPayload.updateDetails(original,
            "Line one\nLine two, west", "New; room")
        val before = IcsEventParser.parse(original, source).events.single()
        val after = IcsEventParser.parse(revised, source).events.single()
        assertEquals(before.uid, after.uid)
        assertEquals(before.start, after.start)
        assertEquals(before.summary, after.summary)
        assertEquals("Line one\nLine two, west", after.description)
        assertEquals("New; room", after.location)
        val cleared = IcsEventParser.parse(DavEventPayload.updateDetails(revised, "", ""), source)
            .events.single()
        assertEquals("", cleared.description)
        assertEquals("", cleared.location)
        val recurring = original.toString(Charsets.UTF_8).replace("END:VEVENT\r\n",
            "RRULE:FREQ=DAILY;COUNT=3\r\nEND:VEVENT\r\n").toByteArray()
        assertTrue(runCatching { DavEventPayload.updateDetails(recurring, "New", "Here") }.isFailure)
        val parameterized = original.toString(Charsets.UTF_8).replace("LOCATION:Old room",
            "LOCATION;LANGUAGE=en:Old room").toByteArray()
        assertTrue(runCatching { DavEventPayload.updateDetails(parameterized, "New", "Here") }.isFailure)
    }
    @Test fun optionalDetailsRoundTripWithoutInjectingIcsProperties() {
        val date = LocalDate.parse("2026-11-12")
        val bytes = DavEventPayload.create(UUID.randomUUID(), date, "Synthetic", true,
            description = "Line one\nATTENDEE:untrusted, value; \\path",
            location = "Room, two; west")
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("DESCRIPTION:Line one\\nATTENDEE:untrusted\\, value\\; \\\\path"))
        assertTrue(text.contains("LOCATION:Room\\, two\\; west"))
        val event = IcsEventParser.parse(bytes, source).events.single()
        assertEquals("Line one\nATTENDEE:untrusted, value; \\path", event.description)
        assertEquals("Room, two; west", event.location)
        assertFalse(runCatching { DavEventPayload.create(UUID.randomUUID(), date,
            "Synthetic", true, location = "Room\u0000bad") }.isSuccess)
    }
    @Test fun onlyBoundedSimpleRulesSupportWholeSeriesEditing() {
        assertEquals(RepeatSpec(RepeatFrequency.WEEKLY, 4),
            RepeatSpec.supported("FREQ=WEEKLY;COUNT=4"))
        assertEquals(null, RepeatSpec.supported("FREQ=WEEKLY;COUNT=53"))
        assertEquals(null, RepeatSpec.supported("FREQ=WEEKLY;COUNT=4;BYDAY=MO"))
        assertEquals(null, RepeatSpec.supported("FREQ=YEARLY;COUNT=4"))
    }
    private val operation = UUID.fromString("2db79847-3454-4a50-8912-7ee1f6486f9b")
    private val stamp = Instant.parse("2026-10-27T12:00:00Z")
    private val source = CalendarSource("synthetic", "Synthetic", 0)

    @Test fun caldavXmlNormalizedLfCanBeEditedWithoutAcceptingMixedEndings() {
        val original = DavEventPayload.create(operation, LocalDate.parse("2026-11-12"),
            "Synthetic", true, now = stamp)
        val normalizedByXml = original.toString(Charsets.UTF_8)
            .replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        val edited = DavEventPayload.updateTitle(normalizedByXml, "Synthetic revised")
        val text = edited.toString(Charsets.UTF_8)
        assertTrue(text.contains("SUMMARY:Synthetic revised\r\n"))
        assertFalse(text.contains(Regex("(?<!\r)\n")))
        val mixed = original.toString(Charsets.UTF_8)
            .replaceFirst("\r\n", "\n").toByteArray(Charsets.UTF_8)
        assertTrue(runCatching { DavEventPayload.updateTitle(mixed, "Unsafe") }.isFailure)
    }

    @Test fun collectionPrivilegesExposeGuardedTitleEdit() {
        val account = DavAccount("synthetic-account", DavKind.CALDAV,
            "https://dav.example.test/", "synthetic@example.test", "synthetic-secret")
        val collection = DavConnection.DiscoveredCalendar("https://dav.example.test/calendar/",
            "Synthetic", canCreate = true, canUpdate = true, canDelete = true)
        val discovered = DavConnection.source(account, collection)
        assertTrue(discovered.canCreate)
        assertTrue(discovered.canUpdate)
        assertTrue(discovered.canDelete)
    }

    @Test fun allDayDraftRoundTripsWithExclusiveEndAndEscapedText() {
        val bytes = DavEventPayload.create(operation, LocalDate.parse("2026-11-12"),
            "A, B; C\\D\nNext", true, now = stamp)
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("UID:$operation@ham.tinkercobble.com\r\n"))
        assertTrue(text.contains("DTSTART;VALUE=DATE:20261112\r\nDTEND;VALUE=DATE:20261113"))
        assertTrue(text.contains("SUMMARY:A\\, B\\; C\\\\D\\nNext"))
        assertEquals("A, B; C\\D\nNext", IcsEventParser.parse(bytes, source).events.single().summary)
    }

    @Test fun timedDraftUsesUtcAndRejectsAmbiguousLocalTime() {
        val zone = ZoneId.of("America/New_York")
        val bytes = DavEventPayload.create(operation, LocalDate.parse("2026-10-27"),
            "Timed", false, "09:00", "10:00", stamp, zone)
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("DTSTART:20261027T130000Z\r\nDTEND:20261027T140000Z"))
        assertEquals(1, IcsEventParser.parse(bytes, source).events.size)
        assertTrue(runCatching { DavEventPayload.create(operation, LocalDate.parse("2026-11-01"),
            "Ambiguous", false, "01:30", "02:30", stamp, zone) }.isFailure)
    }

    @Test fun finiteAllDaySeriesRoundTripsWithFourOccurrences() {
        val date = LocalDate.parse("2026-11-02")
        val repeat = RepeatSpec(RepeatFrequency.WEEKLY, 4)
        val bytes = DavEventPayload.create(operation, date, "Synthetic weekly", true,
            now = stamp, repeat = repeat)
        assertTrue(bytes.toString(Charsets.UTF_8).contains("RRULE:FREQ=WEEKLY;COUNT=4\r\n"))
        val read = IcsEventParser.parse(bytes, source, DateRange(date, date.plusDays(30)))
        assertEquals(0, read.unsupportedRecurring)
        assertEquals(listOf(date, date.plusWeeks(1), date.plusWeeks(2), date.plusWeeks(3)),
            read.events.map { it.date })
        assertTrue(runCatching { RepeatSpec(RepeatFrequency.DAILY, 1) }.isFailure)
        val timed = DavEventPayload.create(operation, date, "Timed", false,
            "09:00", "10:00", stamp, ZoneId.of("America/New_York"), repeat)
        val timedRead = IcsEventParser.parse(timed, source, DateRange(date, date.plusDays(30)))
        assertEquals(0, timedRead.unsupportedRecurring)
        assertEquals(4, timedRead.events.size)
        assertEquals(date, DavEventPayload.firstCreateDay(timed,
            ZoneId.of("America/New_York")))
    }

    @Test fun timedDavSeriesUsesNamedZoneAcrossDaylightSaving() {
        val zone = ZoneId.of("America/New_York")
        val start = LocalDate.parse("2026-10-30")
        val crossing = DavEventPayload.create(operation, start,
            "Synthetic shift", false, "09:00", "10:00", stamp, zone,
            RepeatSpec(RepeatFrequency.DAILY, 5))
        assertTrue(crossing.toString(Charsets.UTF_8).contains("BEGIN:VTIMEZONE\r\n"))
        assertFalse(crossing.toString(Charsets.UTF_8).contains("TZURL:"))
        assertTrue(crossing.toString(Charsets.UTF_8).split("\r\n").all {
            it.toByteArray(Charsets.UTF_8).size <= 75
        })
        assertEquals("FREQ=DAILY;COUNT=5", DavEventPayload.simpleSeriesRule(crossing, false))
        val mismatchedZone = crossing.toString(Charsets.UTF_8).replaceFirst(
            "DTSTART;TZID=", "DTSTART;TZID=Other/")
        assertTrue(runCatching { DavEventPayload.simpleSeriesRule(
            mismatchedZone.toByteArray(), false) }.isFailure)
        assertEquals(start, DavEventPayload.firstCreateDay(crossing, zone))
        val crossingRange = DavEventPayload.createSearchRange(crossing)
        assertTrue(crossingRange.contains(start.plusDays(4)))
        val read = IcsEventParser.parse(crossing, source, crossingRange)
        assertEquals(5, read.events.size)
        assertTrue(read.events.all { AgendaEvent.instant(it.start)!!.atZone(zone)
            .toLocalTime().toString() == "09:00" })
        val davSource = source.copy(provider = CalendarProviderKind.CALDAV)
        val selected = IcsEventParser.parse(crossing, davSource, crossingRange).events[2]
        DavSeriesValidation.requireUnchanged(crossing, davSource, selected)
        val titled = DavEventPayload.updateSeriesTitle(crossing, "Synthetic renamed")
        assertFalse(DavEventPayload.timedSeriesScheduleChanged(crossing, titled))
        assertTrue(IcsEventParser.parse(titled, source, crossingRange).events.all {
            it.summary == "Synthetic renamed" })
        val stable = DavEventPayload.create(operation, LocalDate.parse("2026-11-05"),
            "Synthetic stable", false, "09:00", "10:00", stamp, zone,
            RepeatSpec(RepeatFrequency.DAILY, 5))
        assertTrue(stable.toString(Charsets.UTF_8).contains("RRULE:FREQ=DAILY;COUNT=5"))
        val range = DavEventPayload.createSearchRange(stable)
        assertEquals(LocalDate.parse("2026-11-03"), range.start)
        assertTrue(range.contains(LocalDate.parse("2026-11-09")))
        val month = DavEventPayload.create(operation, LocalDate.parse("2026-01-31"),
            "Synthetic month", true, now = stamp,
            repeat = RepeatSpec(RepeatFrequency.MONTHLY, 52))
        assertEquals(LocalDate.parse("2038-01-31"),
            DavEventPayload.createSearchRange(month).endExclusive)
    }

    @Test fun longUnicodeSummaryFoldsByUtf8OctetsAndRejectsControlCharacters() {
        val bytes = DavEventPayload.create(operation, LocalDate.parse("2026-11-12"),
            "🙂".repeat(40), true, now = stamp)
        val lines = bytes.toString(Charsets.UTF_8).split("\r\n").filter { it.isNotEmpty() }
        assertTrue(lines.all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertEquals("🙂".repeat(40), IcsEventParser.parse(bytes, source).events.single().summary)
        assertFalse(runCatching { DavEventPayload.create(operation, LocalDate.parse("2026-11-12"),
            "Bad\u0000title", true, now = stamp) }.isSuccess)
    }

    @Test fun titleUpdateRetainsIdentityTimeAndUnrelatedProperties() {
        val created = DavEventPayload.create(operation, LocalDate.parse("2026-11-12"),
            "Original", true, now = stamp)
        val original = created.toString(Charsets.UTF_8).replace("END:VEVENT\r\n",
            "LOCATION:Synthetic room\r\nEND:VEVENT\r\n").toByteArray()
        val changed = DavEventPayload.updateTitle(original, "Revised, with a detail")
        val before = IcsEventParser.parse(original, source).events.single()
        val after = IcsEventParser.parse(changed, source).events.single()
        assertEquals("Revised, with a detail", after.summary)
        assertEquals(before.uid, after.uid)
        assertEquals(before.start, after.start)
        assertEquals(before.end, after.end)
        assertEquals(before.location, after.location)
        assertTrue(changed.toString(Charsets.UTF_8).contains("LOCATION:Synthetic room"))
    }

    @Test fun titleUpdateRejectsRecurringOrAmbiguousResources() {
        val original = DavEventPayload.create(operation, LocalDate.parse("2026-11-12"),
            "Original", true, now = stamp).toString(Charsets.UTF_8)
        val recurring = original.replace("END:VEVENT\r\n", "RRULE:FREQ=DAILY\r\nEND:VEVENT\r\n")
        val duplicate = original.replace("END:VCALENDAR\r\n", "BEGIN:VEVENT\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
        assertTrue(runCatching { DavEventPayload.updateTitle(recurring.toByteArray(), "New") }.isFailure)
        assertTrue(runCatching { DavEventPayload.updateTitle(duplicate.toByteArray(), "New") }.isFailure)
    }

    @Test fun simpleAllDaySeriesTitleChangePreservesRuleUidAndDates() {
        val date = LocalDate.parse("2026-11-12")
        val original = DavEventPayload.create(operation, date, "Original", true,
            now = stamp, repeat = RepeatSpec(RepeatFrequency.WEEKLY, 4))
        assertEquals("FREQ=WEEKLY;COUNT=4", DavEventPayload.simpleAllDaySeriesRule(original))
        val changed = DavEventPayload.updateSeriesTitle(original, "Revised series")
        val read = IcsEventParser.parse(changed, source, DateRange(date, date.plusDays(30)))
        assertEquals(0, read.unsupportedRecurring)
        assertEquals(4, read.events.size)
        assertTrue(read.events.all { it.summary == "Revised series" &&
            it.uid == "$operation@ham.tinkercobble.com" && it.rrule == "FREQ=WEEKLY;COUNT=4" })
        assertTrue(changed.toString(Charsets.UTF_8).contains("RRULE:FREQ=WEEKLY;COUNT=4"))
    }

    @Test fun simpleUtcTimedSeriesCanChangeTitleAndRejectsStaleOrExceptionalResource() {
        val date = LocalDate.parse("2026-11-12")
        val davSource = source.copy(provider = CalendarProviderKind.CALDAV)
        val repeat = RepeatSpec(RepeatFrequency.WEEKLY, 4)
        val original = DavEventPayload.create(operation, date, "Original timed", false,
            "09:00", "10:00", stamp, ZoneId.of("America/New_York"), repeat,
            description = "Synthetic notes", location = "Synthetic room")
        val range = DateRange(date, date.plusDays(30))
        val chosen = IcsEventParser.parse(original, davSource, range).events
            .single { it.date == date.plusWeeks(1) }
        assertEquals(repeat.rrule, DavEventPayload.simpleSeriesRule(original, false))
        DavSeriesValidation.requireUnchanged(original, davSource, chosen)
        val changed = DavEventPayload.updateSeriesTitle(original, "Updated timed")
        val after = IcsEventParser.parse(changed, davSource, range).events
        assertEquals(4, after.size)
        assertTrue(after.all { it.summary == "Updated timed" && it.uid == chosen.uid &&
            it.rrule == repeat.rrule && it.description == "Synthetic notes" &&
            it.location == "Synthetic room" })
        assertEquals(IcsEventParser.parse(original, davSource, range).events.map { it.start to it.end },
            after.map { it.start to it.end })
        assertTrue(runCatching { DavEventPayload.simpleAllDaySeriesRule(original) }.isFailure)
        assertTrue(runCatching { DavSeriesValidation.requireUnchanged(original, davSource,
            chosen.copy(summary = "Stale timed")) }.isFailure)
        val exception = original.toString(Charsets.UTF_8).replace("END:VEVENT\r\n",
            "EXDATE:20261119T140000Z\r\nEND:VEVENT\r\n").toByteArray()
        assertTrue(runCatching { DavSeriesValidation.requireUnchanged(exception, davSource,
            chosen) }.isFailure)
        val excludedRule = original.toString(Charsets.UTF_8).replace("END:VEVENT\r\n",
            "EXRULE:FREQ=DAILY;COUNT=2\r\nEND:VEVENT\r\n").toByteArray()
        assertTrue(runCatching { DavSeriesValidation.requireUnchanged(excludedRule, davSource,
            chosen) }.isFailure)
        val changedRule = original.toString(Charsets.UTF_8).replace("COUNT=4", "COUNT=5")
            .toByteArray()
        assertTrue(runCatching { DavSeriesValidation.requireUnchanged(changedRule, davSource,
            chosen) }.isFailure)
    }

    @Test fun timedSeriesClockEditPreservesIdentityAndRefusesDaylightSavingShift() {
        val zone = ZoneId.of("America/New_York")
        val date = LocalDate.parse("2026-11-12")
        val repeat = RepeatSpec(RepeatFrequency.WEEKLY, 4)
        val original = DavEventPayload.create(operation, date, "Original timed", false,
            "09:00", "10:00", stamp, zone, repeat,
            description = "Synthetic notes", location = "Synthetic room")
        val changed = DavEventPayload.updateTimedSeries(original, "Revised timed",
            "11:00", "12:30", zone)
        assertTrue(DavEventPayload.timedSeriesScheduleChanged(original, changed))
        assertFalse(DavEventPayload.timedSeriesScheduleChanged(original,
            DavEventPayload.updateSeriesTitle(original, "Title only")))
        val range = DateRange(date, date.plusDays(30))
        val before = IcsEventParser.parse(original, source, range).events
        val after = IcsEventParser.parse(changed, source, range).events
        assertEquals(4, after.size)
        assertEquals(before.map { it.uid to it.date }, after.map { it.uid to it.date })
        assertTrue(after.all { it.summary == "Revised timed" &&
            it.description == "Synthetic notes" && it.location == "Synthetic room" &&
            it.rrule == repeat.rrule })
        assertEquals(listOf("11:00", "11:00", "11:00", "11:00"), after.map {
            AgendaEvent.instant(it.start)!!.atZone(zone).toLocalTime().toString()
        })
        assertTrue(changed.toString(Charsets.UTF_8).contains("RRULE:${repeat.rrule}\r\n"))
        val wrongIdentity = changed.toString(Charsets.UTF_8).replace(
            "UID:$operation@ham.tinkercobble.com", "UID:other@example.invalid").toByteArray()
        assertTrue(runCatching { DavEventPayload.timedSeriesScheduleChanged(original,
            wrongIdentity) }.isFailure)
        val crossing = DavEventPayload.create(operation, LocalDate.parse("2026-10-30"),
            "Crossing", false, "09:00", "10:00", stamp, zone)
            .toString(Charsets.UTF_8).replace("END:VEVENT\r\n",
                "RRULE:FREQ=DAILY;COUNT=4\r\nEND:VEVENT\r\n").toByteArray()
        assertTrue(runCatching { DavEventPayload.updateTimedSeries(crossing, "Crossing",
            "11:00", "12:00", zone) }.isFailure)
    }

    @Test fun namedZoneSeriesClockEditPreservesEveryLocalOccurrence() {
        val zone = ZoneId.of("America/New_York")
        val date = LocalDate.parse("2026-10-30")
        val repeat = RepeatSpec(RepeatFrequency.DAILY, 5)
        val original = DavEventPayload.create(operation, date, "Synthetic recurring", false,
            "09:00", "10:00", stamp, zone, repeat)
        val changed = DavEventPayload.updateTimedSeries(original, "Changed recurring",
            "11:00", "12:30", zone)
        assertTrue(DavEventPayload.timedSeriesScheduleChanged(original, changed))
        val raw = changed.toString(Charsets.UTF_8)
        assertTrue(raw.contains("BEGIN:VTIMEZONE\r\n"))
        assertTrue(raw.contains("DTSTART;TZID=America/New_York:20261030T110000\r\n"))
        assertTrue(raw.contains("DTEND;TZID=America/New_York:20261030T123000\r\n"))
        assertTrue(raw.contains("RRULE:${repeat.rrule}\r\n"))
        val after = IcsEventParser.parse(changed, source, DateRange(date, date.plusDays(6))).events
        assertEquals(5, after.size)
        assertEquals(List(5) { "11:00" }, after.map {
            AgendaEvent.instant(it.start)!!.atZone(zone).toLocalTime().toString()
        })
        assertEquals(List(5) { "12:30" }, after.map {
            AgendaEvent.instant(it.end)!!.atZone(zone).toLocalTime().toString()
        })
        assertTrue(runCatching { DavEventPayload.updateTimedSeries(original, "Changed recurring",
            "11:00", "12:30", ZoneId.of("America/Chicago")) }.isFailure)
    }

    @Test fun seriesMutationRejectsExceptionsOtherRulesAndTimedEvents() {
        val date = LocalDate.parse("2026-11-12")
        val original = DavEventPayload.create(operation, date, "Original", true,
            now = stamp, repeat = RepeatSpec(RepeatFrequency.DAILY, 3)).toString(Charsets.UTF_8)
        val variants = listOf(
            original.replace("END:VEVENT\r\n", "EXDATE;VALUE=DATE:20261113\r\nEND:VEVENT\r\n"),
            original.replace("END:VEVENT\r\n", "RECURRENCE-ID;VALUE=DATE:20261113\r\nEND:VEVENT\r\n"),
            original.replace("RRULE:FREQ=DAILY;COUNT=3", "RRULE:FREQ=YEARLY;COUNT=3"),
            original.replace("END:VCALENDAR\r\n", "BEGIN:VTODO\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"),
            original.replace("END:VCALENDAR\r\n", "BEGIN:VEVENT\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
        )
        variants.forEach { assertTrue(runCatching {
            DavEventPayload.updateSeriesTitle(it.toByteArray(), "Changed")
        }.isFailure) }
        val timed = DavEventPayload.create(operation, date, "Timed", false, "09:00", "10:00",
            stamp, repeat = null).toString(Charsets.UTF_8)
            .replace("END:VEVENT\r\n", "RRULE:FREQ=DAILY;COUNT=3\r\nEND:VEVENT\r\n")
        assertTrue(runCatching { DavEventPayload.simpleAllDaySeriesRule(timed.toByteArray()) }.isFailure)
    }

    @Test fun seriesValidationRejectsStaleOccurrenceAndChangedRule() {
        val date = LocalDate.parse("2026-11-02")
        val davSource = source.copy(provider = CalendarProviderKind.CALDAV)
        val original = DavEventPayload.create(operation, date, "Original", true,
            now = stamp, repeat = RepeatSpec(RepeatFrequency.WEEKLY, 4))
        val chosen = IcsEventParser.parse(original, davSource,
            DateRange(date, date.plusDays(30))).events.single { it.date == date.plusWeeks(1) }
        DavSeriesValidation.requireUnchanged(original, davSource, chosen)
        assertTrue(runCatching { DavSeriesValidation.requireUnchanged(original, davSource,
            chosen.copy(summary = "Stale")) }.isFailure)
        val changedRule = original.toString(Charsets.UTF_8).replace("COUNT=4", "COUNT=5")
            .toByteArray(Charsets.UTF_8)
        assertTrue(runCatching { DavSeriesValidation.requireUnchanged(changedRule, davSource, chosen) }.isFailure)
        val exception = original.toString(Charsets.UTF_8).replace("END:VEVENT\r\n",
            "EXDATE;VALUE=DATE:20261116\r\nEND:VEVENT\r\n").toByteArray(Charsets.UTF_8)
        assertTrue(runCatching { DavSeriesValidation.requireUnchanged(exception, davSource, chosen) }.isFailure)
    }

    @Test fun titleUpdateKeepsQuotedPropertyParameters() {
        val original = DavEventPayload.create(operation, LocalDate.parse("2026-11-12"),
            "Original", true, now = stamp).toString(Charsets.UTF_8)
            .replace("SUMMARY:Original", "SUMMARY;ALTREP=\"cid:part1@example.test\":Original")
        val changed = DavEventPayload.updateTitle(original.toByteArray(), "New")
        assertTrue(changed.toString(Charsets.UTF_8).contains(
            "SUMMARY;ALTREP=\"cid:part1@example.test\":New"))
    }

    @Test fun timedUpdateChangesOnlyUtcBoundsAndTitle() {
        val zone = ZoneId.of("America/New_York")
        val original = DavEventPayload.create(operation, LocalDate.parse("2026-10-27"),
            "Original", false, "09:00", "10:00", stamp, zone).toString(Charsets.UTF_8)
            .replace("END:VEVENT\r\n", "LOCATION:Synthetic room\r\nEND:VEVENT\r\n")
        val changed = DavEventPayload.updateTimed(original.toByteArray(), LocalDate.parse("2026-10-27"),
            "Revised", "11:00", "12:30", zone).toString(Charsets.UTF_8)
        assertTrue(changed.contains("DTSTART:20261027T150000Z\r\nDTEND:20261027T163000Z"))
        assertTrue(changed.contains("UID:$operation@ham.tinkercobble.com"))
        assertTrue(changed.contains("LOCATION:Synthetic room"))
        assertEquals("Revised", IcsEventParser.parse(changed.toByteArray(), source).events.single().summary)
    }

    @Test fun timedSingleMoveRequiresUnchangedIdentityAndNoRecurrence() {
        val zone = ZoneId.of("America/New_York")
        val original = DavEventPayload.create(operation, LocalDate.parse("2026-10-27"),
            "Synthetic move", false, "09:00", "10:00", stamp, zone)
        val moved = DavEventPayload.updateTimed(original, LocalDate.parse("2026-10-28"),
            "Synthetic move", "09:00", "10:00", zone)
        assertTrue(DavEventPayload.timedSingleScheduleChanged(original, moved))
        assertFalse(DavEventPayload.timedSingleScheduleChanged(original,
            DavEventPayload.updateTitle(original, "New title")))
        assertTrue(runCatching { DavEventPayload.timedSingleScheduleChanged(original,
            moved.toString(Charsets.UTF_8).replace("UID:$operation", "UID:changed")
                .toByteArray()) }.isFailure)
        assertTrue(runCatching { DavEventPayload.timedSingleScheduleChanged(original,
            moved.toString(Charsets.UTF_8).replace("END:VEVENT\r\n",
                "RRULE:FREQ=DAILY;COUNT=2\r\nEND:VEVENT\r\n").toByteArray()) }.isFailure)
    }

    @Test fun allDaySingleMoveKeepsIdentityAndRejectsRecurrence() {
        val original = DavEventPayload.create(operation, LocalDate.parse("2026-11-12"),
            "Synthetic day", true, now = stamp).toString(Charsets.UTF_8)
            .replace("END:VEVENT\r\n", "LOCATION:Synthetic room\r\nEND:VEVENT\r\n")
            .toByteArray()
        val moved = DavEventPayload.updateAllDay(original, LocalDate.parse("2026-11-13"),
            "Synthetic moved day")
        val text = moved.toString(Charsets.UTF_8)
        assertTrue(text.contains("DTSTART;VALUE=DATE:20261113\r\nDTEND;VALUE=DATE:20261114"))
        assertTrue(text.contains("UID:$operation@ham.tinkercobble.com"))
        assertTrue(text.contains("LOCATION:Synthetic room"))
        assertEquals(LocalDate.parse("2026-11-13"),
            IcsEventParser.parse(moved, source).events.single().date)
        assertTrue(DavEventPayload.allDaySingleScheduleChanged(original, moved))
        assertFalse(DavEventPayload.timedSingleScheduleChanged(original, moved))
        assertFalse(DavEventPayload.allDaySingleScheduleChanged(original,
            DavEventPayload.updateTitle(original, "Title only")))
        assertTrue(runCatching { DavEventPayload.allDaySingleScheduleChanged(original,
            text.replace("UID:$operation", "UID:changed").toByteArray()) }.isFailure)
        assertTrue(runCatching { DavEventPayload.updateAllDay(
            original.toString(Charsets.UTF_8).replace("END:VEVENT\r\n",
                "RRULE:FREQ=DAILY;COUNT=2\r\nEND:VEVENT\r\n").toByteArray(),
            LocalDate.parse("2026-11-13"), "Synthetic moved day") }.isFailure)
    }

    @Test fun multiDayMoveKeepsExclusiveEndAndRejectsDurationChange() {
        val original = DavEventPayload.create(operation, LocalDate.parse("2026-11-12"),
            "Synthetic trip", true, now = stamp).toString(Charsets.UTF_8)
            .replace("DTEND;VALUE=DATE:20261113", "DTEND;VALUE=DATE:20261115")
            .toByteArray()
        val moved = DavEventPayload.updateAllDay(original, LocalDate.parse("2026-11-20"),
            "Synthetic trip")
        val text = moved.toString(Charsets.UTF_8)
        assertTrue(text.contains("DTSTART;VALUE=DATE:20261120\r\nDTEND;VALUE=DATE:20261123"))
        assertTrue(DavEventPayload.allDaySingleScheduleChanged(original, moved))
        val parsed = IcsEventParser.parse(moved, source).events.single()
        assertEquals(LocalDate.parse("2026-11-20"), parsed.date)
        assertEquals("2026-11-23", parsed.end)
        assertTrue(runCatching { DavEventPayload.allDaySingleScheduleChanged(original,
            text.replace("DTEND;VALUE=DATE:20261123", "DTEND;VALUE=DATE:20261124")
                .toByteArray()) }.isFailure)
        assertTrue(runCatching { DavEventPayload.updateAllDay(
            original.toString(Charsets.UTF_8).replace("DTEND;VALUE=DATE:20261115",
                "DTEND;VALUE=DATE:20261112").toByteArray(),
            LocalDate.parse("2026-11-20"), "Synthetic trip") }.isFailure)
    }

    @Test fun multiDayCreateUsesInclusiveReviewedLastDay() {
        val first = LocalDate.parse("2026-11-12")
        val payload = DavEventPayload.create(operation, first, "Synthetic trip", true,
            now = stamp, endExclusive = first.plusDays(4))
        val text = payload.toString(Charsets.UTF_8)
        assertTrue(text.contains("DTSTART;VALUE=DATE:20261112\r\nDTEND;VALUE=DATE:20261116"))
        val event = IcsEventParser.parse(payload, source).events.single()
        assertEquals("2026-11-16", event.end)
        assertTrue(runCatching { DavEventPayload.create(operation, first, "Bad trip", true,
            now = stamp, endExclusive = first) }.isFailure)
        assertTrue(runCatching { DavEventPayload.create(operation, first, "Bad series", true,
            now = stamp, repeat = RepeatSpec(RepeatFrequency.DAILY, 2),
            endExclusive = first.plusDays(4)) }.isFailure)
    }

    @Test fun timedUpdateRejectsDateTzidAndRecurrence() {
        val zone = ZoneId.of("America/New_York")
        val original = DavEventPayload.create(operation, LocalDate.parse("2026-10-27"),
            "Original", false, "09:00", "10:00", stamp, zone).toString(Charsets.UTF_8)
        val day = DavEventPayload.create(operation, LocalDate.parse("2026-10-27"),
            "Original", true, now = stamp)
        val tzid = original.replace("DTSTART:20261027T130000Z",
            "DTSTART;TZID=America/New_York:20261027T090000")
        val recurring = original.replace("END:VEVENT\r\n", "RRULE:FREQ=DAILY\r\nEND:VEVENT\r\n")
        listOf(day, tzid.toByteArray(), recurring.toByteArray()).forEach { bytes ->
            assertTrue(runCatching { DavEventPayload.updateTimed(bytes,
                LocalDate.parse("2026-10-27"), "Revised", "11:00", "12:00", zone) }.isFailure)
        }
        assertTrue(runCatching { DavEventPayload.updateTimed(original.toByteArray(),
            LocalDate.parse("2026-11-01"), "Revised", "01:30", "02:30", zone) }.isFailure)
    }
}
