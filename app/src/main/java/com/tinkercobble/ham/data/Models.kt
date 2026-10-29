package com.tinkercobble.ham.data

import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId

enum class CalendarProviderKind { HOME_ASSISTANT, ANDROID, CALDAV, ICS }

data class CalendarSource(val id: String, val name: String, val features: Int,
    val source: String = "Not provided by Home Assistant", val haColor: String? = null,
    val provider: CalendarProviderKind = CalendarProviderKind.HOME_ASSISTANT,
    val identityFingerprint: String? = null) {
    val canCreate get() = features and 1 != 0
    val canUpdate get() = features and 4 != 0
    val canDelete get() = features and 2 != 0
}

data class TodoEntry(val listName: String, val summary: String, val due: String?, val uid: String?)

data class AgendaEvent(
    val calendarId: String,
    val calendarName: String,
    val summary: String,
    val start: String,
    val end: String,
    val allDay: Boolean,
    val description: String,
    val location: String,
    val uid: String?,
    val recurrenceId: String?,
    val provider: CalendarProviderKind = CalendarProviderKind.HOME_ASSISTANT,
    val rrule: String? = null,
    val recurrenceKnown: Boolean = false,
) {
    val date: LocalDate get() = localDate(start)
    fun occursOn(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val first = if (allDay) LocalDate.parse(start.take(10)) else
            (instant(start, zone) ?: return false).atZone(zone).toLocalDate()
        val last = if (allDay) LocalDate.parse(end.take(10)).minusDays(1)
            else (instant(end, zone) ?: return false).minusNanos(1).atZone(zone).toLocalDate()
        return !day.isBefore(first) && !day.isAfter(last)
    }
    fun occursIn(range: DateRange, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val first = if (allDay) LocalDate.parse(start.take(10)) else
            (instant(start, zone) ?: return false).atZone(zone).toLocalDate()
        val last = if (allDay) LocalDate.parse(end.take(10)).minusDays(1) else
            (instant(end, zone) ?: return false).minusNanos(1).atZone(zone).toLocalDate()
        return first.isBefore(range.endExclusive) && !last.isBefore(range.start)
    }
    val identity: String get() = listOf(calendarId, uid ?: "", recurrenceId ?: "", start, end, summary).joinToString("|")
    companion object {
        fun instant(value: String, zone: ZoneId = ZoneId.systemDefault()): Instant? = if (!value.contains('T')) null else
            runCatching { OffsetDateTime.parse(value).toInstant() }
                .recoverCatching { LocalDateTime.parse(value).atZone(zone).toInstant() }.getOrNull()
        fun fromJson(source: CalendarSource, obj: JSONObject): AgendaEvent {
            fun field(name: String): Pair<String, Boolean> {
                val raw = obj.get(name)
                return if (raw is JSONObject) {
                    if (raw.has("date")) raw.getString("date") to true else raw.getString("dateTime") to false
                } else raw.toString() to !raw.toString().contains('T')
            }
            val (start, allDay) = field("start")
            val (end, _) = field("end")
            return AgendaEvent(source.id, source.name, obj.optString("summary", "Untitled event"), start, end,
                allDay, obj.optString("description"), obj.optString("location"),
                if (obj.isNull("uid")) null else obj.optString("uid").ifBlank { null },
                if (obj.isNull("recurrence_id")) null else obj.optString("recurrence_id").ifBlank { null },
                rrule = if (obj.isNull("rrule")) null else obj.optString("rrule").ifBlank { null },
                recurrenceKnown = obj.has("rrule"))
        }
    }
}

private fun localDate(value: String): LocalDate = if (value.contains('T'))
    (AgendaEvent.instant(value)?.atZone(ZoneId.systemDefault())?.toLocalDate() ?: LocalDate.parse(value.take(10)))
else LocalDate.parse(value.take(10))

fun distinctEvents(events: List<AgendaEvent>): List<AgendaEvent> = events.distinctBy { it.identity }
    .sortedWith(compareBy({ it.start }, { it.calendarName }, { it.summary }))
