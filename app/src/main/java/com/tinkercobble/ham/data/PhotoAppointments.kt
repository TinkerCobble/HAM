package com.tinkercobble.ham.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.util.Locale

/** Suggestions only. A photo never creates a calendar record without an explicit review. */
data class PhotoAppointment(
    val date: LocalDate,
    val start: LocalTime,
    val durationMinutes: Int?,
    val originalCode: String,
    val printedLocation: String,
    val originalText: String,
    val warnings: List<String> = emptyList(),
    val privateTranscriptMatched: Boolean = false,
) {
    val end: LocalTime? get() = durationMinutes?.let { start.plusMinutes(it.toLong()) }
}

/** Optional device-private corrections for a printed sheet. Never bundled with the app. */
data class VerifiedPhotoRow(val date: LocalDate, val start: LocalTime, val durationMinutes: Int,
    val originalCode: String, val printedLocation: String)

object PhotoTranscriptReconciliation {
    private fun evidence(value: String): Set<String> = Regex("[A-Z0-9]{4,}").findAll(value.uppercase(Locale.US))
        .map { it.value }.filterNot { it in setOf("WHITE", "FLOOR", "BUFFALO", "APPOINTMENT", "CITY") }.toSet()
    private fun closeToken(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length != b.length || a.length < 5) return false
        return a.indices.count { a[it] != b[it] } <= 1
    }

    fun reconcile(item: PhotoAppointment, verifiedRows: List<VerifiedPhotoRow>): PhotoAppointment {
        val candidates = verifiedRows.filter { it.date == item.date && it.start == item.start &&
            it.durationMinutes == item.durationMinutes }
        if (candidates.size != 1) return item
        val expected = candidates.single()
        if (evidence(item.originalCode).none { found ->
                evidence(expected.originalCode).any { closeToken(found, it) } }) return item
        return item.copy(originalCode = expected.originalCode, printedLocation = expected.printedLocation,
            warnings = emptyList(), privateTranscriptMatched = true)
    }

    fun reconcileAll(items: List<PhotoAppointment>, verifiedRows: List<VerifiedPhotoRow>): List<PhotoAppointment> =
        items.map { item ->
            if (items.count { it.date == item.date && it.start == item.start &&
                    it.durationMinutes == item.durationMinutes } == 1) reconcile(item, verifiedRows)
            else item
        }
}

data class PhotoParseResult(val appointments: List<PhotoAppointment>, val warnings: List<String>)
data class PhotoCalendarScan(val events: List<AgendaEvent>, val unverifiedCalendars: Set<String>,
    val pendingEvents: List<AgendaEvent>)

/** Generic printed-schedule parser. Facility-specific meanings are supplied by private user rules. */
object PhotoAppointments {
    private val monthDate = Regex(
        "\\b(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\s+([0-3]?[0-9])[,]?[\\s]+(20[0-9]{2})\\b",
        RegexOption.IGNORE_CASE)
    private val numericDate = Regex("\\b(0?[1-9]|1[0-2])[/.-](0?[1-9]|[12][0-9]|3[01])[/.-](20[0-9]{2})\\b")
    private val clock = Regex("\\b(0?[1-9]|1[0-2]):([0-5][0-9])\\s*([AP])\\.?M\\.?\\b", RegexOption.IGNORE_CASE)
    private val duration = Regex("\\(?\\s*([0-9]{1,3})\\s*(?:MINUTES?|MINS?)\\s*\\)?", RegexOption.IGNORE_CASE)
    private val locationClue = Regex("\\b(?:FLOOR|FL|WING|DESK|ROOM|VIDEO|REMOTE|VIRTUAL|BUILDING)\\b", RegexOption.IGNORE_CASE)
    private val locationStart = Regex("\\b(?:[0-9]+[A-Z]?\\s+)?[0-9]+(?:ST|ND|RD|TH)?\\s+(?:FLOOR|FL)\\b|\\b(?:VIDEO|REMOTE|VIRTUAL|DESK|ROOM)\\b", RegexOption.IGNORE_CASE)
    private val likelyCode = Regex("\\b[A-Z][A-Z0-9]{1,}(?:[- ][A-Z0-9]{1,}){1,}\\b")

    fun parse(text: String): PhotoParseResult {
        val clean = text.replace('\u00a0', ' ').replace(Regex("[\\t ]+"), " ")
        val lines = clean.lines().map(String::trim).filter(String::isNotBlank)
        val rows = mutableListOf<PhotoAppointment>()
        val unparsed = mutableListOf<String>()
        for ((index, firstLine) in lines.withIndex()) {
            val named = monthDate.find(firstLine)
            val dateMatch = named ?: numericDate.find(firstLine)
            if (dateMatch == null) continue
            // Some OCR engines wrap the time onto the next visual line.
            val next = lines.getOrNull(index + 1).orEmpty()
            val timeWrapped = clock.find(firstLine, dateMatch.range.last + 1) == null &&
                monthDate.find(next) == null && numericDate.find(next) == null
            val line = if (timeWrapped) "$firstLine $next" else firstLine
            val timeMatch = clock.find(line, dateMatch.range.last + 1)
            if (timeMatch == null) {
                if (line.contains(Regex("\\b(?:MINUTES?|AM|PM)\\b", RegexOption.IGNORE_CASE)))
                    unparsed += "A row with a date could not be read; inspect the original image."
                continue
            }
            val date = runCatching {
                if (named == null) LocalDate.of(dateMatch.groupValues[3].toInt(),
                    dateMatch.groupValues[1].toInt(), dateMatch.groupValues[2].toInt())
                else {
                    val month = Month.values().first { it.name.startsWith(dateMatch.groupValues[1].take(3).uppercase(Locale.US)) }
                    LocalDate.of(dateMatch.groupValues[3].toInt(), month, dateMatch.groupValues[2].toInt())
                }
            }.getOrNull()
            if (date == null) { unparsed += "A date is invalid; inspect the original image."; continue }
            val hour = timeMatch.groupValues[1].toInt() % 12 + if (timeMatch.groupValues[3].equals("P", true)) 12 else 0
            val start = LocalTime.of(hour, timeMatch.groupValues[2].toInt())
            val tail = line.substring(timeMatch.range.last + 1).trim()
            val length = duration.find(tail)
            val minutes = length?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..720 }
            val afterLength = if (length != null) tail.substring(length.range.last + 1).trim() else tail
            val continuation = lines.getOrNull(index + if (timeWrapped) 2 else 1).orEmpty().takeIf {
                it.isNotBlank() && monthDate.find(it) == null && numericDate.find(it) == null &&
                    (locationClue.containsMatchIn(it) || likelyCode.containsMatchIn(it))
            }.orEmpty()
            val details = listOf(afterLength, continuation).filter(String::isNotBlank).joinToString(" ")
            val location = locationStart.find(details)?.let { details.substring(it.range.first).trim() }.orEmpty()
            val code = if (location.isNotBlank()) details.substringBefore(location).trim() else details
            val warnings = buildList {
                if (minutes == null) add("Confirm the appointment duration.")
                if (code.isBlank()) add("Confirm the department or appointment type.")
                if (location.isBlank()) add("Confirm the printed location or visit type.")
            }
            rows += PhotoAppointment(date, start, minutes, code, location,
                listOf(line, continuation).filter(String::isNotBlank).joinToString("\n"), warnings)
        }
        // Keep every printed row. Two appointments may share a time and department;
        // the review screen and provider read-back resolve duplicates before writing.
        return PhotoParseResult(rows, unparsed.distinct())
    }
}

enum class PhotoMatchKind { ALREADY_EXISTS, POSSIBLE_DUPLICATE, PENDING, NEW, UNVERIFIED }
data class PhotoMatch(val kind: PhotoMatchKind, val event: AgendaEvent? = null)

object PhotoAppointmentMatches {
    private val aliases = mapOf(
        "psych" to "behavioral", "psychology" to "behavioral", "bhip" to "behavioral",
        "eye" to "optometry", "eyes" to "optometry", "nutritionist" to "nutrition",
    )
    private fun words(value: String): Set<String> = Regex("[A-Za-z]{3,}").findAll(value.lowercase(Locale.US))
        .map { aliases[it.value] ?: it.value }.toSet()

    fun assess(item: PhotoAppointment, title: String, facility: String,
        events: List<AgendaEvent>, unverifiedCalendars: Set<String>,
        zone: ZoneId = ZoneId.systemDefault(), toleranceMinutes: Long = 15): PhotoMatch {
        val mine = words(title + " " + item.originalCode)
        val facilityWords = words(facility)
        val candidates = events.mapNotNull { event ->
            val start = AgendaEvent.instant(event.start, zone)?.atZone(zone) ?: return@mapNotNull null
            if (start.toLocalDate() != item.date) return@mapNotNull null
            val delta = kotlin.math.abs(java.time.Duration.between(item.start, start.toLocalTime()).toMinutes())
            if (delta > toleranceMinutes) return@mapNotNull null
            val theirs = words(event.summary + " " + event.description)
            val department = mine.intersect(theirs).any { it !in facilityWords && it.length >= 4 }
            if (!department) return@mapNotNull null
            val facilityAgrees = facilityWords.isEmpty() || facilityWords.intersect(words(event.summary + " " + event.location)).isNotEmpty()
            Triple(event, delta, facilityAgrees)
        }
        val exact = candidates.firstOrNull { (event, delta, facilityAgrees) ->
            val sameTitle = words(title) == words(event.summary)
            val printedCode = item.originalCode.takeIf(String::isNotBlank)?.let {
                event.description.contains(it, ignoreCase = true) } == true
            val samePlace = words(item.printedLocation).intersect(words(event.location + " " + event.description))
                .any { it.length >= 4 && it !in facilityWords }
            delta <= 2 && facilityAgrees && matchesDuration(item, event, zone) &&
                (sameTitle || printedCode || samePlace)
        }
        if (exact != null) return PhotoMatch(PhotoMatchKind.ALREADY_EXISTS, exact.first)
        if (candidates.isNotEmpty()) return PhotoMatch(PhotoMatchKind.POSSIBLE_DUPLICATE, candidates.first().first)
        return PhotoMatch(if (unverifiedCalendars.isEmpty()) PhotoMatchKind.NEW else PhotoMatchKind.UNVERIFIED)
    }

    private fun matchesDuration(item: PhotoAppointment, event: AgendaEvent, zone: ZoneId): Boolean {
        val start = AgendaEvent.instant(event.start, zone) ?: return false
        val end = AgendaEvent.instant(event.end, zone) ?: return false
        return item.durationMinutes == null || java.time.Duration.between(start, end).toMinutes() == item.durationMinutes.toLong()
    }
}
