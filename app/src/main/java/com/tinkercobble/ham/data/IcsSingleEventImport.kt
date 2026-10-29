package com.tinkercobble.ham.data

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.Temporal

data class IcsImportDraft(val date: LocalDate, val title: String, val allDay: Boolean,
    val startText: String, val endText: String, val description: String, val location: String,
    val repeat: RepeatSpec? = null, val deviceZoneId: String? = null,
    val endExclusive: LocalDate = date.plusDays(1))

/** Review-only parser. No calendar write or source identifier is carried from the file. */
object IcsSingleEventImport {
    private val time = DateTimeFormatter.ofPattern("HH:mm")
    private val source = CalendarSource("ics-import-preview", "ICS preview", 0,
        provider = CalendarProviderKind.ICS)

    fun readLimited(stream: InputStream): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(result.size() + count <= IcsEventParser.MAX_BYTES) { "ICS file is too large" }
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    fun preview(bytes: ByteArray, zone: ZoneId = ZoneId.systemDefault()): IcsImportDraft {
        val drafts = previewAll(bytes, zone)
        require(drafts.size == 1) { "Choose a file containing one event" }
        return drafts.single()
    }

    fun previewAll(bytes: ByteArray, zone: ZoneId = ZoneId.systemDefault()): List<IcsImportDraft> {
        require(bytes.size <= IcsEventParser.MAX_BYTES) { "ICS file is too large" }
        val text = bytes.toString(Charsets.UTF_8)
        val count = Regex("(?im)^BEGIN:VEVENT\\s*$").findAll(text).count()
        require(count in 1..50) {
            "Choose a file containing 1 to 50 events"
        }
        val components = CalendarBuilder().build(bytes.inputStream())
            .getComponents<net.fortuna.ical4j.model.component.CalendarComponent>()
            .filterIsInstance<VEvent>()
        require(components.size == count) { "Every event must be readable" }
        if (components.any { event -> event.getProperties<Property>().any { property ->
                property.name.uppercase() in setOf("RRULE", "RDATE", "EXDATE", "EXRULE", "RECURRENCE-ID")
            } }) return listOf(previewFiniteSeries(bytes, zone, components.singleOrNull()
            ?: error("Import a repeating series from its own file")))
        val read = IcsEventParser.parse(bytes, source)
        require(read.unsupportedRecurring == 0 && read.events.size == count) {
            "Every event must be nonrepeating and readable"
        }
        val drafts = read.events.map { event -> toDraft(event, zone) }
        require(drafts.distinctBy { listOf(it.title.lowercase(), it.date.toString(),
            it.allDay.toString(), it.startText, it.endText,
            it.endExclusive.toString()) }.size == drafts.size) {
            "The file contains duplicate events"
        }
        return drafts
    }

    private fun previewFiniteSeries(bytes: ByteArray, zone: ZoneId, event: VEvent): IcsImportDraft {
        fun property(name: String): String? = event.getProperty<Property>(name).orElse(null)?.value
        require(listOf("RDATE", "EXDATE", "EXRULE", "RECURRENCE-ID").none { property(it) != null }) {
            "Recurrence exceptions need a different import flow"
        }
        require(event.getProperties<Property>().count { it.name.equals("RRULE", true) } == 1) {
            "Choose one finite recurrence rule"
        }
        val parts = checkNotNull(property("RRULE")).uppercase().split(';')
        val fields = parts.map { it.split('=', limit = 2) }
        require(fields.size == 2 && fields.all { it.size == 2 } &&
            fields.map { it[0] }.toSet() == setOf("FREQ", "COUNT")) {
            "Only finite daily, weekly or monthly series can be imported"
        }
        val values = fields.associate { it[0] to it[1] }
        val repeat = RepeatSpec(RepeatFrequency.valueOf(values.getValue("FREQ")),
            values.getValue("COUNT").toInt())
        val first = event.getStartDate<Temporal>().orElse(null)?.date
            ?: error("This series has no start")
        if (first is LocalDate) {
            val read = IcsEventParser.parse(bytes, source, DateRange(first, first.plusDays(1)))
            require(read.unsupportedRecurring == 0) { "This series could not be read safely" }
            val occurrence = read.events.singleOrNull { it.start == first.toString() }
                ?: error("This series has no unique first event")
            val draft = toDraft(occurrence, zone)
            require(draft.allDay && draft.endExclusive == draft.date.plusDays(1)) {
                "Only single-day all-day series can be imported"
            }
            return draft.copy(repeat = repeat)
        }
        val firstInstant = when (first) {
            is ZonedDateTime -> first.toInstant()
            is OffsetDateTime -> first.toInstant()
            is Instant -> first
            else -> error("Timed repeats need an explicit time zone")
        }
        val end = event.getEndDate<Temporal>().orElse(null)?.date
            ?: error("Timed repeats need an explicit end")
        require(end is ZonedDateTime || end is OffsetDateTime || end is Instant) {
            "Timed repeats need an explicit time zone"
        }
        val firstDay = firstInstant.atZone(zone).toLocalDate()
        val lastDay = when (repeat.frequency) {
            RepeatFrequency.DAILY -> firstDay.plusDays(repeat.count.toLong() + 3)
            RepeatFrequency.WEEKLY -> firstDay.plusWeeks(repeat.count.toLong() + 3)
            RepeatFrequency.MONTHLY -> firstDay.plusYears(12)
        }
        val read = IcsEventParser.parse(bytes, source, DateRange(firstDay.minusDays(2), lastDay))
        require(read.unsupportedRecurring == 0 && read.events.size == repeat.count) {
            "This timed series could not be expanded safely"
        }
        val occurrences = read.events.sortedBy { AgendaEvent.instant(it.start, zone) }
        val firstOccurrence = occurrences.first()
        require(AgendaEvent.instant(firstOccurrence.start, zone) == firstInstant) {
            "This timed series has a different first occurrence"
        }
        val draft = toDraft(firstOccurrence, zone)
        require(!draft.allDay) { "This series has mixed event times" }
        occurrences.forEach { occurrence ->
            require(!occurrence.allDay) { "This series has mixed event times" }
            val start = AgendaEvent.instant(occurrence.start, zone)
                ?: error("This series has an invalid start")
            val finish = AgendaEvent.instant(occurrence.end, zone)
                ?: error("This series has an invalid end")
            val expected = TimedEventTime.parse(start.atZone(zone).toLocalDate(),
                draft.startText, draft.endText, zone)
            require(expected.start.toInstant() == start && expected.end.toInstant() == finish) {
                "This timed series changes local time or duration"
            }
        }
        return draft.copy(repeat = repeat, deviceZoneId = zone.id)
    }

    private fun toDraft(event: AgendaEvent, zone: ZoneId): IcsImportDraft {
        val title = event.summary.trim()
        require(title.isNotEmpty() && title != "Untitled event" && title.length <= 200) {
            "Event title is missing or too long"
        }
        val details = EventDraftDetails.of(
            event.description.lines().filterNot { it.contains("ham-attachment://") }.joinToString("\n"),
            event.location)
        if (event.allDay) {
            val start = LocalDate.parse(event.start)
            val endExclusive = LocalDate.parse(event.end)
            require(endExclusive.isAfter(start)) { "All-day event end must follow its start" }
            return IcsImportDraft(start, title, true, "", "", details.description,
                details.location, endExclusive = endExclusive)
        }
        val startInstant = AgendaEvent.instant(event.start, zone) ?: error("Invalid event start")
        val endInstant = AgendaEvent.instant(event.end, zone) ?: error("Invalid event end")
        val start = startInstant.atZone(zone)
        val end = endInstant.atZone(zone)
        require(start.toLocalDate() == end.toLocalDate() && end.isAfter(start)) {
            "Only same-day timed events can be imported"
        }
        val startText = start.format(time)
        val endText = end.format(time)
        val exact = TimedEventTime.parse(start.toLocalDate(), startText, endText, zone)
        require(exact.start.toInstant() == startInstant && exact.end.toInstant() == endInstant) {
            "This event needs a time or precision HAM cannot preserve"
        }
        return IcsImportDraft(start.toLocalDate(), title, false, startText, endText,
            details.description, details.location)
    }
}
