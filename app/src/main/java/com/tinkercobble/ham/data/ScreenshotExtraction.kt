package com.tinkercobble.ham.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** OCR suggestions are never authoritative. The review screen requires an explicit save. */
data class ScreenshotSuggestion(
    val title: String,
    val dates: List<LocalDate>,
    val start: LocalTime?,
    val end: LocalTime?,
    val venue: String,
    val address: String,
    val description: String,
    val warnings: List<String>,
)

object ScreenshotExtraction {
    private val namedDate = Regex("\\b(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday,?\\s+)?(January|February|March|April|May|June|July|August|September|October|November|December)\\s+(\\d{1,2})(?:,?\\s+(20\\d{2}))?\\b", RegexOption.IGNORE_CASE)
    private val numericDate = Regex("\\b(0?[1-9]|1[0-2])/(0?[1-9]|[12]\\d|3[01])/(20\\d{2}|\\d{2})\\b")
    private val timeRange = Regex("\\b(1[0-2]|0?[1-9]):?([0-5]\\d)?\\s*(AM|PM)?\\s*[-–—to]+\\s*(1[0-2]|0?[1-9]):?([0-5]\\d)?\\s*(AM|PM)\\b", RegexOption.IGNORE_CASE)
    private val street = Regex("\\b\\d{1,6}\\s+[\\w .'-]+\\s+(?:Street|St|Road|Rd|Avenue|Ave|Boulevard|Blvd|Drive|Dr|Lane|Ln|Court|Ct|Way|Square|Sq)\\.?\\b(?:[^\\n]{0,70})?", RegexOption.IGNORE_CASE)
    private val cityState = Regex("^[\\p{L} .'-]+,?\\s+[A-Z]{2}\\s+\\d{5}(?:-\\d{4})?$", RegexOption.IGNORE_CASE)

    fun parse(text: String): ScreenshotSuggestion {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val warnings = mutableListOf<String>()
        val dates = mutableListOf<LocalDate>()
        namedDate.findAll(text).forEach { match ->
            val year = match.groupValues[3].toIntOrNull()
            if (year == null) warnings += "A date has no year. Confirm it before saving."
            else runCatching {
                val month = java.time.Month.valueOf(match.groupValues[1].uppercase(Locale.US))
                LocalDate.of(year, month, match.groupValues[2].toInt())
            }.getOrNull()?.let { dates += it }
        }
        numericDate.findAll(text).forEach { match ->
            val year = match.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
            runCatching { LocalDate.of(year, match.groupValues[1].toInt(), match.groupValues[2].toInt()) }
                .getOrNull()?.let { dates += it }
        }
        val range = timeRange.find(text)
        fun clock(hour: String, minute: String, meridiem: String): LocalTime {
            val h = hour.toInt() % 12 + if (meridiem.equals("PM", true)) 12 else 0
            return LocalTime.of(h, minute.ifBlank { "0" }.toInt())
        }
        val suffix = range?.groupValues?.get(6).orEmpty()
        val firstSuffix = range?.groupValues?.get(3)?.ifBlank { suffix }.orEmpty()
        val start = if (range != null) clock(range.groupValues[1], range.groupValues[2], firstSuffix) else null
        val end = if (range != null) clock(range.groupValues[4], range.groupValues[5], suffix) else null
        if (range == null) warnings += "No complete time range found. Choose all-day or enter both times."
        if (dates.isEmpty()) warnings += "No unambiguous date with a year found. Enter the date."
        val addresses = lines.mapIndexedNotNull { index, line ->
            street.find(line)?.value?.let { found ->
                val next = lines.getOrNull(index + 1).orEmpty()
                if (cityState.matches(next) && !found.contains(Regex("\\b[A-Z]{2}\\s+\\d{5}\\b"))) "$found, $next" else found
            }
        }.distinct()
        if (addresses.size > 1) warnings += "Several addresses appear. Check which belongs to the event."
        val title = lines.firstOrNull { line -> line.length in 4..100 &&
            !namedDate.containsMatchIn(line) && !numericDate.containsMatchIn(line) &&
            !street.containsMatchIn(line) && !line.startsWith("http", true) }.orEmpty()
        val venue = lines.firstOrNull { it.contains(Regex("school|center|centre|library|club|hall|arena|church", RegexOption.IGNORE_CASE)) }.orEmpty()
        if (venue.isNotBlank() && addresses.isEmpty()) warnings += "Venue address is unverified."
        return ScreenshotSuggestion(title, dates.distinct(), start, end, venue, addresses.firstOrNull().orEmpty(),
            text.trim(), warnings.distinct())
    }

    fun eventLocation(venue: String, address: String): String {
        val cleanVenue = venue.trim()
        val cleanAddress = address.trim()
        if (cleanAddress.isBlank()) return cleanVenue
        if (cleanVenue.isBlank()) return cleanAddress
        val repeatsVenue = cleanAddress.startsWith(cleanVenue, ignoreCase = true) &&
            (cleanAddress.length == cleanVenue.length || cleanAddress[cleanVenue.length] in charArrayOf(',', ' '))
        return if (repeatsVenue) cleanAddress else "$cleanVenue, $cleanAddress"
    }
}
