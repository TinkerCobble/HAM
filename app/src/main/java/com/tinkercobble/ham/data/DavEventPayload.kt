package com.tinkercobble.ham.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.LocalDateTime
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import net.fortuna.ical4j.model.TimeZoneRegistryFactory

/** A stable single-event iCalendar resource for a reviewed CalDAV create operation. */
object DavEventPayload {
    private val dayFormat = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)
    private val utcFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
        .withZone(java.time.ZoneOffset.UTC)
    private val localFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT)

    /** Day of the first occurrence in the device's calendar view, for fresh duplicate review. */
    fun firstCreateDay(payload: ByteArray, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
        val starts = eventLines(unfoldedLines(payload)).filter { line ->
            line.startsWith("DTSTART:", true) || line.startsWith("DTSTART;", true)
        }
        require(starts.size == 1) { "Create item has no unique start" }
        val start = starts.single()
        return when {
            Regex("^DTSTART;VALUE=DATE:[0-9]{8}$", RegexOption.IGNORE_CASE).matches(start) ->
                LocalDate.parse(start.substringAfter(':'), dayFormat)
            Regex("^DTSTART:[0-9]{8}T[0-9]{6}Z$", RegexOption.IGNORE_CASE).matches(start) ->
                LocalDateTime.parse(start.substringAfter(':'), utcFormat)
                    .atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate()
            Regex("^DTSTART;TZID=[A-Za-z0-9_./+\\-]+:[0-9]{8}T[0-9]{6}$",
                RegexOption.IGNORE_CASE).matches(start) ->
                LocalDate.parse(start.substringAfter(':').take(8), dayFormat)
            else -> error("Create item has unsupported start time")
        }
    }

    /** Stable UTC-bounded search window covering every finite create occurrence. */
    fun createSearchRange(payload: ByteArray): DateRange {
        val lines = eventLines(unfoldedLines(payload))
        val rules = lines.filter { it.startsWith("RRULE:", true) || it.startsWith("RRULE;", true) }
        require(rules.size <= 1) { "Create item has multiple recurrence rules" }
        val repeat = rules.singleOrNull()?.let {
            require(it.startsWith("RRULE:", true)) { "Create item has an unsupported recurrence rule" }
            RepeatSpec.supported(it.substringAfter(':'))
                ?: error("Create item has an unsupported recurrence rule")
        }
        val first = firstCreateDay(payload, ZoneOffset.UTC)
        val lastExclusive = when (repeat?.frequency) {
            RepeatFrequency.DAILY -> first.plusDays(repeat.count.toLong() + 2)
            RepeatFrequency.WEEKLY -> first.plusWeeks(repeat.count.toLong() + 1)
            RepeatFrequency.MONTHLY -> first.plusYears(12)
            null -> first.plusDays(1)
        }
        // Two local-day margins cover UTC and named-zone timed values after a device zone change.
        val timed = lines.any { Regex("^DTSTART(?::[0-9]{8}T[0-9]{6}Z|;TZID=[A-Za-z0-9_./+\\-]+:[0-9]{8}T[0-9]{6})$",
            RegexOption.IGNORE_CASE).matches(it) }
        return DateRange(if (timed) first.minusDays(2) else first,
            if (timed) lastExclusive.plusDays(2) else lastExclusive)
    }

    private fun escapeText(value: String): String = value.replace("\\", "\\\\")
        .replace(";", "\\;").replace(",", "\\,")
        .replace("\n", "\\n")

    private fun summary(value: String): String {
        val title = value.trim().replace("\r\n", "\n").replace('\r', '\n')
        require(title.isNotEmpty() && title.length <= 200 && title.none {
            it.code < 32 && it != '\n' && it != '\t'
        }) { "Enter a valid event title (up to 200 characters)" }
        return escapeText(title.replace('\t', ' '))
    }

    private fun fold(line: String) = IcsContentLines.fold(line)

    private fun unfoldedLines(original: ByteArray): MutableList<String> {
        require(original.size in 1..512 * 1024) { "Calendar item is too large" }
        val decoded = String(original, Charsets.UTF_8)
        require(decoded.toByteArray(Charsets.UTF_8).contentEquals(original)) { "Calendar item is not UTF-8" }
        // XML parsers normalize line endings in CalDAV calendar-data to LF. Accept
        // that form as well as a direct CRLF resource, but reject mixed or bare CR.
        val normalized = when {
            decoded.endsWith("\r\n") && !decoded.contains(Regex("(?<!\\r)\\n|\\r(?!\\n)")) -> decoded
            decoded.endsWith('\n') && !decoded.contains('\r') -> decoded.replace("\n", "\r\n")
            else -> error("Calendar item has unsupported line endings")
        }
        return normalized.removeSuffix("\r\n").replace(Regex("\r\n[ \t]"), "")
            .split("\r\n").toMutableList()
    }

    private fun eventLines(lines: List<String>): List<String> {
        val starts = lines.indices.filter { lines[it].equals("BEGIN:VEVENT", true) }
        val ends = lines.indices.filter { lines[it].equals("END:VEVENT", true) }
        require(starts.size == 1 && ends.size == 1 && starts[0] < ends[0]) {
            "Only one calendar event is supported"
        }
        return lines.subList(starts[0] + 1, ends[0])
    }

    /** Refuse overrides and complex rules before editing or deleting an entire ICS resource. */
    fun simpleSeriesRule(original: ByteArray, expectedAllDay: Boolean? = null): String {
        val lines = unfoldedLines(original)
        require(lines.count { it.equals("BEGIN:VCALENDAR", true) } == 1 &&
            lines.count { it.equals("END:VCALENDAR", true) } == 1 &&
            lines.filter { it.startsWith("BEGIN:", true) }.all {
                it.equals("BEGIN:VCALENDAR", true) || it.equals("BEGIN:VEVENT", true) ||
                    it.equals("BEGIN:VALARM", true) || it.equals("BEGIN:VTIMEZONE", true) ||
                    it.equals("BEGIN:STANDARD", true) || it.equals("BEGIN:DAYLIGHT", true)
            }) { "Calendar resource contains another component" }
        val starts = lines.indices.filter { lines[it].equals("BEGIN:VEVENT", true) }
        val ends = lines.indices.filter { lines[it].equals("END:VEVENT", true) }
        require(starts.size == 1 && ends.size == 1 && starts[0] < ends[0]) {
            "Only a single series can be changed"
        }
        val body = (starts[0] + 1 until ends[0]).map(lines::get)
        require(body.none { line -> listOf("RDATE", "EXDATE", "EXRULE", "RECURRENCE-ID").any {
            line.startsWith("$it:", true) || line.startsWith("$it;", true)
        } }) { "This series has changed occurrences" }
        val rules = body.filter { it.startsWith("RRULE:", true) || it.startsWith("RRULE;", true) }
        require(rules.size == 1 && rules[0].startsWith("RRULE:", true)) {
            "This recurrence rule needs a different editor"
        }
        val rule = rules[0].substringAfter(':')
        require(RepeatSpec.supported(rule) != null) { "This recurrence rule needs a different editor" }
        val startsOn = body.filter { it.startsWith("DTSTART", true) }
        val endsOn = body.filter { it.startsWith("DTEND", true) }
        val patternStart = Regex("^DTSTART;VALUE=DATE:[0-9]{8}$", RegexOption.IGNORE_CASE)
        val patternEnd = Regex("^DTEND;VALUE=DATE:[0-9]{8}$", RegexOption.IGNORE_CASE)
        require(startsOn.size == 1 && endsOn.size == 1) {
            "Series has no unique start and end"
        }
        val allDay = patternStart.matches(startsOn.single()) && patternEnd.matches(endsOn.single())
        val utcStart = Regex("^DTSTART:[0-9]{8}T[0-9]{6}Z$", RegexOption.IGNORE_CASE)
        val utcEnd = Regex("^DTEND:[0-9]{8}T[0-9]{6}Z$", RegexOption.IGNORE_CASE)
        val zoneCount = lines.count { it.equals("BEGIN:VTIMEZONE", true) }
        val timedUtc = zoneCount == 0 && utcStart.matches(startsOn.single()) &&
            utcEnd.matches(endsOn.single())
        val namedStart = Regex("^DTSTART;TZID=([A-Za-z0-9_./+\\-]+):([0-9]{8}T[0-9]{6})$",
            RegexOption.IGNORE_CASE).matchEntire(startsOn.single())
        val namedEnd = Regex("^DTEND;TZID=([A-Za-z0-9_./+\\-]+):([0-9]{8}T[0-9]{6})$",
            RegexOption.IGNORE_CASE).matchEntire(endsOn.single())
        val zoneStart = lines.indexOfFirst { it.equals("BEGIN:VTIMEZONE", true) }
        val zoneEnd = lines.indexOfFirst { it.equals("END:VTIMEZONE", true) }
        val zoneBody = if (zoneCount == 1 && zoneStart >= 0 && zoneEnd > zoneStart)
            lines.subList(zoneStart + 1, zoneEnd) else emptyList()
        val timedNamed = zoneCount == 1 && lines.count { it.equals("END:VTIMEZONE", true) } == 1 &&
            namedStart != null && namedEnd != null && namedStart.groupValues[1] == namedEnd.groupValues[1] &&
            zoneBody.count { it.equals("TZID:${namedStart.groupValues[1]}", true) } == 1 &&
            zoneBody.count { it.startsWith("TZID:", true) } == 1 &&
            zoneBody.any { it.equals("BEGIN:STANDARD", true) || it.equals("BEGIN:DAYLIGHT", true) }
        require((allDay && zoneCount == 0 || timedUtc || timedNamed) &&
            (expectedAllDay == null || expectedAllDay == allDay)) {
            "Only simple all-day or defined timed series can be changed here"
        }
        val validDates = if (allDay) {
            val startDate = LocalDate.parse(startsOn.single().substringAfterLast(':'), dayFormat)
            val endDate = LocalDate.parse(endsOn.single().substringAfterLast(':'), dayFormat)
            endDate == startDate.plusDays(1)
        } else if (timedUtc) {
            val start = LocalDateTime.parse(startsOn.single().substringAfterLast(':'), utcFormat)
            val end = LocalDateTime.parse(endsOn.single().substringAfterLast(':'), utcFormat)
            val seconds = Duration.between(start, end).seconds
            seconds in 1..86_399
        } else {
            val start = LocalDateTime.parse(checkNotNull(namedStart).groupValues[2], localFormat)
            val end = LocalDateTime.parse(checkNotNull(namedEnd).groupValues[2], localFormat)
            start.toLocalDate() == end.toLocalDate() && Duration.between(start, end).seconds in 1..86_399
        }
        require(validDates && body.count { it.startsWith("UID:", true) } == 1) {
            "This series has unsupported dates or identity"
        }
        return rule
    }

    fun simpleAllDaySeriesRule(original: ByteArray): String = simpleSeriesRule(original, true)

    /** Change only the summary of one ordinary VEVENT, retaining its UID and provider fields. */
    fun updateTitle(original: ByteArray, title: String): ByteArray = replaceTitle(original, title, false)

    /** Edit plain text fields of one nonrecurring event, retaining its other ICS properties. */
    fun updateDetails(original: ByteArray, description: String, location: String): ByteArray {
        val details = EventDraftDetails.of(description, location)
        val lines = unfoldedLines(original)
        val starts = lines.indices.filter { lines[it].equals("BEGIN:VEVENT", true) }
        val ends = lines.indices.filter { lines[it].equals("END:VEVENT", true) }
        require(starts.size == 1 && ends.size == 1 && starts[0] < ends[0]) {
            "Only a single event can be edited"
        }
        val body = starts[0] + 1 until ends[0]
        require(body.none { index ->
            listOf("RRULE", "RDATE", "EXDATE", "EXRULE", "RECURRENCE-ID", "BEGIN", "END").any { name ->
                lines[index].startsWith("$name:", true) || lines[index].startsWith("$name;", true)
            }
        }) { "Recurring or nested calendar items need a different editor" }
        fun property(name: String): Int? {
            val matches = body.filter { lines[it].startsWith("$name:", true) ||
                lines[it].startsWith("$name;", true) }
            require(matches.size <= 1 && matches.all { lines[it].startsWith("$name:", true) }) {
                "$name uses an unsupported format"
            }
            return matches.singleOrNull()
        }
        val oldDescription = property("DESCRIPTION")
        val oldLocation = property("LOCATION")
        listOfNotNull(oldDescription, oldLocation).sortedDescending().forEach(lines::removeAt)
        val insertAt = lines.indexOfFirst { it.equals("END:VEVENT", true) }
        val additions = buildList {
            if (details.description.isNotEmpty()) add("DESCRIPTION:${escapeText(details.description)}")
            if (details.location.isNotEmpty()) add("LOCATION:${escapeText(details.location)}")
        }
        lines.addAll(insertAt, additions)
        return (lines.joinToString("\r\n") { fold(it) } + "\r\n")
            .toByteArray(Charsets.UTF_8).also {
                require(it.size <= 512 * 1024) { "Calendar item is too large" }
            }
    }

    fun updateSeriesTitle(original: ByteArray, title: String): ByteArray {
        simpleSeriesRule(original)
        return replaceTitle(original, title, true)
    }

    /** Move a finite series clock, retaining its UTC or matching named-zone definition. */
    fun updateTimedSeries(original: ByteArray, title: String, startText: String,
        endText: String, zone: ZoneId = ZoneId.systemDefault()): ByteArray {
        val rule = simpleSeriesRule(original, false)
        val originalEvent = eventLines(unfoldedLines(original))
        val utc = originalEvent.any {
            Regex("^DTSTART:[0-9]{8}T[0-9]{6}Z$", RegexOption.IGNORE_CASE).matches(it)
        }
        val named = originalEvent.singleOrNull { it.startsWith("DTSTART;TZID=", true) }
            ?.let { Regex("^DTSTART;TZID=([A-Za-z0-9_./+\\-]+):[0-9]{8}T[0-9]{6}$",
                RegexOption.IGNORE_CASE).matchEntire(it) }
        require(utc || named?.groupValues?.get(1) == zone.id) {
            "Edit this series in its original calendar time zone"
        }
        val localFirstDay = firstCreateDay(original, zone)
        val times = TimedEventTime.parse(localFirstDay, startText, endText, zone)
        val lines = unfoldedLines(replaceTitle(original, title, true))
        val eventStart = lines.indexOfFirst { it.equals("BEGIN:VEVENT", true) }
        val eventEnd = lines.indexOfFirst { it.equals("END:VEVENT", true) }
        val start = (eventStart + 1 until eventEnd).single { lines[it].startsWith("DTSTART", true) }
        val end = (eventStart + 1 until eventEnd).single { lines[it].startsWith("DTEND", true) }
        check(start >= 0 && end >= 0) { "Series has no unique time" }
        if (utc) {
            lines[start] = "DTSTART:${utcFormat.format(times.start.toInstant())}"
            lines[end] = "DTEND:${utcFormat.format(times.end.toInstant())}"
        } else {
            val tzid = checkNotNull(named).groupValues[1]
            lines[start] = "DTSTART;TZID=$tzid:${localFormat.format(times.start)}"
            lines[end] = "DTEND;TZID=$tzid:${localFormat.format(times.end)}"
        }
        val result = (lines.joinToString("\r\n") { fold(it) } + "\r\n")
            .toByteArray(Charsets.UTF_8)
        require(result.size <= 512 * 1024) { "Calendar item is too large" }
        val preview = runCatching { IcsSingleEventImport.preview(result, zone) }
            .getOrElse { throw IllegalArgumentException(
                "This series would change local time or duration across daylight saving") }
        require(preview.repeat?.rrule == rule && preview.date == localFirstDay &&
            !preview.allDay && preview.startText == times.start.toLocalTime().toString().take(5) &&
            preview.endText == times.end.toLocalTime().toString().take(5)) {
            "The changed series differs from the reviewed time"
        }
        return result
    }

    /** Detect an actual clock change in one supported series before a queued provider update. */
    fun timedSeriesScheduleChanged(original: ByteArray, proposed: ByteArray): Boolean {
        val originalLines = unfoldedLines(original)
        if (originalLines.none { it.startsWith("RRULE:", true) ||
                it.startsWith("RRULE;", true) }) return false
        val allDay = originalLines.any {
            Regex("^DTSTART;VALUE=DATE:[0-9]{8}$", RegexOption.IGNORE_CASE).matches(it)
        }
        val rule = simpleSeriesRule(original, allDay)
        check(simpleSeriesRule(proposed, allDay) == rule) { "Calendar series rule changed" }
        fun value(lines: List<String>, name: String): String = eventLines(lines).single {
            it.startsWith("$name:", true) || it.startsWith("$name;", true)
        }
        val proposedLines = unfoldedLines(proposed)
        check(value(originalLines, "UID") == value(proposedLines, "UID")) {
            "Calendar series identity changed"
        }
        return !allDay && (value(originalLines, "DTSTART") != value(proposedLines, "DTSTART") ||
            value(originalLines, "DTEND") != value(proposedLines, "DTEND"))
    }

    /** Detect a moved or retimed ordinary UTC event before sending its queued update. */
    fun timedSingleScheduleChanged(original: ByteArray, proposed: ByteArray): Boolean {
        val old = eventLines(unfoldedLines(original))
        if (old.any { it.startsWith("RRULE:", true) || it.startsWith("RRULE;", true) }) return false
        val next = eventLines(unfoldedLines(proposed))
        fun unique(lines: List<String>, name: String) = lines.single {
            it.startsWith("$name:", true) || it.startsWith("$name;", true)
        }
        val oldStart = unique(old, "DTSTART")
        val oldEnd = unique(old, "DTEND")
        val nextStart = unique(next, "DTSTART")
        val nextEnd = unique(next, "DTEND")
        if (oldStart.startsWith("DTSTART;VALUE=DATE:", true)) return false
        if (oldStart == nextStart && oldEnd == nextEnd) return false
        val utcStart = Regex("^DTSTART:[0-9]{8}T[0-9]{6}Z$", RegexOption.IGNORE_CASE)
        val utcEnd = Regex("^DTEND:[0-9]{8}T[0-9]{6}Z$", RegexOption.IGNORE_CASE)
        check(utcStart.matches(oldStart) && utcEnd.matches(oldEnd) &&
            utcStart.matches(nextStart) && utcEnd.matches(nextEnd) &&
            unique(old, "UID") == unique(next, "UID") &&
            listOf(old, next).all { lines -> lines.none { line ->
                listOf("RRULE", "RDATE", "EXDATE", "EXRULE", "RECURRENCE-ID").any { name ->
                    line.startsWith("$name:", true) || line.startsWith("$name;", true)
                }
            } }) { "Calendar event identity or recurrence changed" }
        return true
    }

    /** Detect a moved ordinary all-day event without accepting recurrence or identity changes. */
    fun allDaySingleScheduleChanged(original: ByteArray, proposed: ByteArray): Boolean {
        val old = eventLines(unfoldedLines(original))
        if (old.any { it.startsWith("RRULE:", true) || it.startsWith("RRULE;", true) }) return false
        val next = eventLines(unfoldedLines(proposed))
        fun unique(lines: List<String>, name: String) = lines.single {
            it.startsWith("$name:", true) || it.startsWith("$name;", true)
        }
        val oldStart = unique(old, "DTSTART")
        val oldEnd = unique(old, "DTEND")
        val nextStart = unique(next, "DTSTART")
        val nextEnd = unique(next, "DTEND")
        if (oldStart.startsWith("DTSTART:", true) ||
            oldStart.startsWith("DTSTART;TZID=", true)) return false
        if (oldStart == nextStart && oldEnd == nextEnd) return false
        val startPattern = Regex("^DTSTART;VALUE=DATE:([0-9]{8})$", RegexOption.IGNORE_CASE)
        val endPattern = Regex("^DTEND;VALUE=DATE:([0-9]{8})$", RegexOption.IGNORE_CASE)
        fun durationDays(start: String, end: String): Long? {
            val first = startPattern.matchEntire(start)?.groupValues?.get(1)?.let {
                LocalDate.parse(it, dayFormat)
            } ?: return null
            val last = endPattern.matchEntire(end)?.groupValues?.get(1)?.let {
                LocalDate.parse(it, dayFormat)
            } ?: return null
            return java.time.temporal.ChronoUnit.DAYS.between(first, last)
        }
        val days = durationDays(oldStart, oldEnd)
        check(days != null && days > 0 && days == durationDays(nextStart, nextEnd) &&
            unique(old, "UID") == unique(next, "UID") &&
            listOf(old, next).all { lines -> lines.none { line ->
                listOf("RRULE", "RDATE", "EXDATE", "EXRULE", "RECURRENCE-ID").any { name ->
                    line.startsWith("$name:", true) || line.startsWith("$name;", true)
                }
            } }) { "Calendar all-day event identity or recurrence changed" }
        return true
    }

    private fun replaceTitle(original: ByteArray, title: String, allowSimpleSeries: Boolean): ByteArray {
        val lines = unfoldedLines(original)
        val starts = lines.indices.filter { lines[it].equals("BEGIN:VEVENT", true) }
        val ends = lines.indices.filter { lines[it].equals("END:VEVENT", true) }
        require(starts.size == 1 && ends.size == 1 && starts[0] < ends[0]) {
            "Only a single event can be edited"
        }
        val body = (starts[0] + 1 until ends[0])
        require(allowSimpleSeries || body.none { index ->
            listOf("RRULE", "RDATE", "EXDATE", "EXRULE", "RECURRENCE-ID").any { name ->
                lines[index].startsWith("$name:", true) || lines[index].startsWith("$name;", true)
            }
        }) { "Recurring events need a recurrence-aware editor" }
        val summaries = body.filter { lines[it].startsWith("SUMMARY:", true) ||
            lines[it].startsWith("SUMMARY;", true) }
        require(summaries.size == 1) {
            "Calendar item has no unique title"
        }
        val index = summaries.single()
        var quoted = false
        val separator = lines[index].indexOfFirst { character ->
            if (character == '"') quoted = !quoted
            character == ':' && !quoted
        }
        require(separator > 0 && !quoted) { "Calendar title property is invalid" }
        lines[index] = lines[index].substring(0, separator + 1) + summary(title)
        val result = (lines.joinToString("\r\n") { fold(it) } + "\r\n").toByteArray(Charsets.UTF_8)
        require(result.size <= 512 * 1024) { "Calendar item is too large" }
        return result
    }

    /** Move one nonrecurring all-day item while retaining duration, UID and other properties. */
    fun updateAllDay(original: ByteArray, date: LocalDate, title: String): ByteArray {
        val lines = unfoldedLines(updateTitle(original, title))
        val start = lines.indexOfFirst { it.equals("BEGIN:VEVENT", true) }
        val end = lines.indexOfFirst { it.equals("END:VEVENT", true) }
        require(start >= 0 && end > start) { "Only a single event can be edited" }
        val starts = (start + 1 until end).filter { lines[it].startsWith("DTSTART", true) }
        val ends = (start + 1 until end).filter { lines[it].startsWith("DTEND", true) }
        require(starts.size == 1 && ends.size == 1 &&
            Regex("^DTSTART;VALUE=DATE:[0-9]{8}$", RegexOption.IGNORE_CASE)
                .matches(lines[starts.single()]) &&
            Regex("^DTEND;VALUE=DATE:[0-9]{8}$", RegexOption.IGNORE_CASE)
                .matches(lines[ends.single()])) { "This calendar item's dates need a different editor" }
        val oldDate = LocalDate.parse(lines[starts.single()].substringAfter(':'), dayFormat)
        val days = java.time.temporal.ChronoUnit.DAYS.between(oldDate,
            LocalDate.parse(lines[ends.single()].substringAfter(':'), dayFormat))
        require(days > 0) { "All-day event must have a positive duration" }
        lines[starts.single()] = "DTSTART;VALUE=DATE:${date.format(dayFormat)}"
        lines[ends.single()] = "DTEND;VALUE=DATE:${date.plusDays(days).format(dayFormat)}"
        return (lines.joinToString("\r\n") { fold(it) } + "\r\n")
            .toByteArray(Charsets.UTF_8).also {
                require(it.size <= 512 * 1024) { "Calendar item is too large" }
            }
    }

    /** Change one nonrecurring UTC timed event while preserving UID and unrelated properties. */
    fun updateTimed(original: ByteArray, date: LocalDate, title: String,
        startText: String, endText: String, zone: ZoneId = ZoneId.systemDefault()): ByteArray {
        val titled = updateTitle(original, title)
        val decoded = String(titled, Charsets.UTF_8)
        val lines = decoded.removeSuffix("\r\n").replace(Regex("\r\n[ \t]"), "")
            .split("\r\n").toMutableList()
        val start = lines.indexOfFirst { it.equals("BEGIN:VEVENT", true) }
        val end = lines.indexOfFirst { it.equals("END:VEVENT", true) }
        require(start >= 0 && end > start) { "Only a single event can be edited" }
        val starts = (start + 1 until end).filter { lines[it].startsWith("DTSTART", true) }
        val ends = (start + 1 until end).filter { lines[it].startsWith("DTEND", true) }
        val utcStart = Regex("^DTSTART:[0-9]{8}T[0-9]{6}Z$", RegexOption.IGNORE_CASE)
        val utcEnd = Regex("^DTEND:[0-9]{8}T[0-9]{6}Z$", RegexOption.IGNORE_CASE)
        require(starts.size == 1 && ends.size == 1 && utcStart.matches(lines[starts.single()]) &&
            utcEnd.matches(lines[ends.single()])) {
            "This calendar item's time zone needs a different editor"
        }
        val times = TimedEventTime.parse(date, startText, endText, zone)
        lines[starts.single()] = "DTSTART:${utcFormat.format(times.start.toInstant())}"
        lines[ends.single()] = "DTEND:${utcFormat.format(times.end.toInstant())}"
        val result = (lines.joinToString("\r\n") { fold(it) } + "\r\n").toByteArray(Charsets.UTF_8)
        require(result.size <= 512 * 1024) { "Calendar item is too large" }
        return result
    }

    fun create(operationId: UUID, date: LocalDate, title: String, allDay: Boolean,
        startText: String = "", endText: String = "", now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(), repeat: RepeatSpec? = null,
        description: String = "", location: String = "",
        endExclusive: LocalDate = date.plusDays(1)): ByteArray {
        require(!allDay || endExclusive.isAfter(date)) { "All-day end must follow the start date" }
        require(repeat == null || !allDay || endExclusive == date.plusDays(1)) {
            "Repeating all-day creation uses one-day occurrences"
        }
        val details = EventDraftDetails.of(description, location)
        val timed = if (allDay) null else TimedEventTime.parse(date, startText, endText, zone)
        val dates = if (allDay) listOf(
            "DTSTART;VALUE=DATE:${date.format(dayFormat)}",
            "DTEND;VALUE=DATE:${endExclusive.format(dayFormat)}")
        else listOf("DTSTART:${utcFormat.format(checkNotNull(timed).start.toInstant())}",
            "DTEND:${utcFormat.format(timed.end.toInstant())}")
        fun render(timezone: List<String>, eventDates: List<String>): ByteArray {
            val lines = listOf("BEGIN:VCALENDAR", "VERSION:2.0",
                "PRODID:-//TinkerCobble//Home Agenda Manager//EN", "CALSCALE:GREGORIAN") +
                timezone + listOf("BEGIN:VEVENT", "UID:$operationId@ham.tinkercobble.com",
                "DTSTAMP:${utcFormat.format(now)}") + eventDates +
                listOf("SUMMARY:${summary(title)}") +
                (if (details.description.isNotEmpty())
                    listOf("DESCRIPTION:${escapeText(details.description)}") else emptyList()) +
                (if (details.location.isNotEmpty())
                    listOf("LOCATION:${escapeText(details.location)}") else emptyList()) +
                (repeat?.let { listOf("RRULE:${it.rrule}") } ?: emptyList()) +
                listOf("END:VEVENT", "END:VCALENDAR")
            return (lines.joinToString("\r\n") { fold(it) } + "\r\n")
                .toByteArray(Charsets.UTF_8).also {
                    require(it.size <= 512 * 1024) { "Calendar item is too large" }
                }
        }
        val utc = render(emptyList(), dates)
        if (repeat == null || allDay) return utc
        val startClock = checkNotNull(timed).start.format(DateTimeFormatter.ofPattern("HH:mm"))
        val endClock = timed.end.format(DateTimeFormatter.ofPattern("HH:mm"))
        fun exactLocalSeries(bytes: ByteArray): Boolean = runCatching {
            val preview = IcsSingleEventImport.preview(bytes, zone)
            preview.repeat == repeat && preview.date == date && !preview.allDay &&
                preview.startText == startClock && preview.endText == endClock
        }.getOrDefault(false)
        if (exactLocalSeries(utc)) return utc
        val definition = TimeZoneRegistryFactory.getInstance().createRegistry()
            .getTimeZone(zone.id)?.vTimeZone
            ?: error("This calendar time zone has no portable definition")
        val tzid = definition.timeZoneId?.value
            ?: error("This calendar time zone has no identifier")
        require(Regex("^[A-Za-z0-9_./+\\-]+$").matches(tzid)) {
            "This calendar time zone has an unsupported identifier"
        }
        val zoneLines = definition.toString().trimEnd('\r', '\n').split("\r\n")
            .filterNot { it.startsWith("TZURL:", true) }
        require(zoneLines.firstOrNull() == "BEGIN:VTIMEZONE" &&
            zoneLines.lastOrNull() == "END:VTIMEZONE") {
            "This calendar time zone has an invalid definition"
        }
        val localDates = listOf("DTSTART;TZID=$tzid:${localFormat.format(timed.start)}",
            "DTEND;TZID=$tzid:${localFormat.format(timed.end)}")
        val named = render(zoneLines, localDates)
        require(exactLocalSeries(named)) {
            "Timed CalDAV series cannot preserve every local time; choose a shorter series or another calendar"
        }
        return named
    }
}
