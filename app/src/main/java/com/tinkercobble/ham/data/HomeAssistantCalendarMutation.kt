package com.tinkercobble.ham.data

import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** Validate one nonrecurring event against a fresh provider read before deletion. */
object HomeAssistantCalendarMutation {
    /** HA's create schema has no client UID; a preflight prevents known duplicates. */
    fun createCommand(source: CalendarSource, proposed: AgendaEvent,
        live: List<AgendaEvent>): JSONObject {
        require(source.provider == CalendarProviderKind.HOME_ASSISTANT && source.canCreate &&
            source.id.startsWith("calendar.") && proposed.provider == CalendarProviderKind.HOME_ASSISTANT &&
            proposed.calendarId == source.id && proposed.uid == null &&
            proposed.recurrenceId == null && proposed.recurrenceKnown && proposed.rrule == null &&
            proposed.summary.isNotBlank() && proposed.summary.length <= 200 &&
            proposed.description.length <= 65536 && proposed.location.length <= 8192) {
            "This event cannot be safely created here"
        }
        if (proposed.allDay) {
            require(LocalDate.parse(proposed.end).isAfter(LocalDate.parse(proposed.start))) {
                "All-day event end must follow its start"
            }
        } else {
            val start = java.time.OffsetDateTime.parse(proposed.start).toInstant()
            val end = java.time.OffsetDateTime.parse(proposed.end).toInstant()
            require(start.isBefore(end)) { "Event end must follow start" }
        }
        check(live.none { existing -> knownCreateDuplicate(proposed, existing) }) {
            "A matching event already appears on this calendar. Review it before creating another."
        }
        return JSONObject().put("id", 1).put("type", "calendar/event/create")
            .put("entity_id", source.id).put("event", JSONObject()
                .put("summary", proposed.summary).put("start", proposed.start)
                .put("end", proposed.end).put("description", proposed.description)
                .put("location", proposed.location))
    }

    fun knownCreateDuplicate(proposed: AgendaEvent, existing: AgendaEvent): Boolean =
        existing.provider == CalendarProviderKind.HOME_ASSISTANT &&
            existing.calendarId == proposed.calendarId &&
            existing.summary.equals(proposed.summary, true) &&
            (if (proposed.allDay) existing.occursOn(proposed.date) else
                !existing.allDay && AgendaEvent.instant(existing.start) == AgendaEvent.instant(proposed.start) &&
                    AgendaEvent.instant(existing.end) == AgendaEvent.instant(proposed.end))

    /** Only a unique exact projection after an accepted response can confirm creation. */
    fun createdVisible(proposed: AgendaEvent, live: List<AgendaEvent>): Boolean =
        live.count { current ->
            current.provider == CalendarProviderKind.HOME_ASSISTANT &&
                current.calendarId == proposed.calendarId && current.summary == proposed.summary &&
                current.allDay == proposed.allDay &&
                (if (proposed.allDay) current.start == proposed.start && current.end == proposed.end else
                    AgendaEvent.instant(current.start) == AgendaEvent.instant(proposed.start) &&
                        AgendaEvent.instant(current.end) == AgendaEvent.instant(proposed.end)) &&
                current.description == proposed.description && current.location == proposed.location
        } == 1

    /** Rebuild a persisted target only after comparing the exact original with a fresh read. */
    fun savedUpdateCommand(source: CalendarSource, chosen: AgendaEvent,
        live: List<AgendaEvent>, target: HaTarget): JSONObject {
        exactNonrecurring(source, chosen, live, source.canUpdate)
        require(target.summary.isNotBlank() && target.summary.length <= 200 &&
            target.description.length <= 65536 && target.location.length <= 8192) {
            "Pending Home Assistant edit has unsupported fields"
        }
        if (chosen.allDay) {
            val originalStart = LocalDate.parse(chosen.start)
            val originalEnd = LocalDate.parse(chosen.end)
            val targetStart = LocalDate.parse(target.start)
            val targetEnd = LocalDate.parse(target.end)
            require(originalEnd.isAfter(originalStart) && targetEnd.isAfter(targetStart) &&
                java.time.temporal.ChronoUnit.DAYS.between(originalStart, originalEnd) ==
                java.time.temporal.ChronoUnit.DAYS.between(targetStart, targetEnd)) {
                "Pending all-day edit changed its duration"
            }
        } else {
            val start = AgendaEvent.instant(target.start)
            val end = AgendaEvent.instant(target.end)
            require(start != null && end != null && start.isBefore(end) &&
                target.start.take(10) == chosen.start.take(10)) {
                "Pending timed edit has unsupported bounds"
            }
        }
        return JSONObject().put("id", 1).put("type", "calendar/event/update")
            .put("entity_id", source.id).put("uid", chosen.uid)
            .put("event", JSONObject().put("start", target.start).put("end", target.end)
                .put("summary", target.summary).put("description", target.description)
                .put("location", target.location))
    }
    /** Only an exact single-UID projection confirms an accepted update. */
    fun updateVisible(source: CalendarSource, chosen: AgendaEvent,
        command: JSONObject, live: List<AgendaEvent>): Boolean {
        val target = command.optJSONObject("event") ?: return false
        if (command.optString("type") != "calendar/event/update" ||
            command.optString("entity_id") != source.id ||
            command.optString("uid") != chosen.uid) return false
        val current = live.filter { it.calendarId == source.id && it.uid == chosen.uid }
            .singleOrNull() ?: return false
        val start = target.optString("start")
        val end = target.optString("end")
        val sameBounds = if (chosen.allDay) current.start == start && current.end == end else
            AgendaEvent.instant(current.start) == AgendaEvent.instant(start) &&
                AgendaEvent.instant(current.end) == AgendaEvent.instant(end) &&
                AgendaEvent.instant(start) != null && AgendaEvent.instant(end) != null
        return current.provider == CalendarProviderKind.HOME_ASSISTANT &&
            current.allDay == chosen.allDay && current.recurrenceId == null &&
            current.recurrenceKnown && current.rrule == null && sameBounds &&
            current.summary == target.optString("summary") &&
            current.description == target.optString("description") &&
            current.location == target.optString("location")
    }
    private fun exactNonrecurring(source: CalendarSource, chosen: AgendaEvent,
        live: List<AgendaEvent>, allowed: Boolean): AgendaEvent {
        require(source.provider == CalendarProviderKind.HOME_ASSISTANT && allowed &&
            source.id.startsWith("calendar.") && chosen.calendarId == source.id &&
            !chosen.uid.isNullOrBlank() && chosen.recurrenceId == null &&
            chosen.recurrenceKnown && chosen.rrule == null) {
            "This event cannot be safely changed here"
        }
        val current = live.filter { it.calendarId == source.id && it.uid == chosen.uid }.singleOrNull()
        check(current != null && current.recurrenceId == null &&
            current.recurrenceKnown && current.rrule == null &&
            current.summary == chosen.summary && current.start == chosen.start &&
            current.end == chosen.end && current.allDay == chosen.allDay &&
            current.description == chosen.description && current.location == chosen.location) {
            "This event changed in Home Assistant. Refresh before editing."
        }
        return current
    }

    fun deleteCommand(source: CalendarSource, chosen: AgendaEvent,
        live: List<AgendaEvent>): JSONObject {
        exactNonrecurring(source, chosen, live, source.canDelete)
        return JSONObject().put("id", 1).put("type", "calendar/event/delete")
            .put("entity_id", source.id).put("uid", chosen.uid)
    }

    fun updateCommand(source: CalendarSource, chosen: AgendaEvent, live: List<AgendaEvent>,
        title: String, startText: String? = null, endText: String? = null,
        zone: ZoneId = ZoneId.systemDefault(), description: String? = null,
        location: String? = null, newDate: LocalDate = chosen.date): JSONObject {
        exactNonrecurring(source, chosen, live, source.canUpdate)
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty() && cleanTitle.length <= 200) {
            "Enter a valid event title (up to 200 characters)"
        }
        val bounds = if (newDate != chosen.date) {
            require(chosen.allDay && startText == null && endText == null) {
                "Only all-day events can change date here"
            }
            val duration = java.time.temporal.ChronoUnit.DAYS.between(
                LocalDate.parse(chosen.start), LocalDate.parse(chosen.end))
            require(duration > 0) { "All-day event has invalid dates" }
            newDate.toString() to newDate.plusDays(duration).toString()
        } else if (startText != null && endText != null) {
            require(!chosen.allDay) { "Only timed events can change time here" }
            val times = TimedEventTime.parse(chosen.date, startText, endText, zone)
            times.start.toOffsetDateTime().toString() to times.end.toOffsetDateTime().toString()
        } else {
            require(startText == null && endText == null) { "Enter both event times" }
            chosen.start to chosen.end
        }
        val details = if (description != null || location != null)
            EventDraftDetails.of(description ?: chosen.description, location ?: chosen.location)
        else EventDraftDetails(chosen.description, chosen.location)
        val edited = JSONObject().put("start", bounds.first).put("end", bounds.second)
            .put("summary", cleanTitle).put("description", details.description)
            .put("location", details.location)
        return JSONObject().put("id", 1).put("type", "calendar/event/update")
            .put("entity_id", source.id).put("uid", chosen.uid).put("event", edited)
    }
}
