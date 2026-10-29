package com.tinkercobble.ham.data

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A reminder HAM will eventually deliver locally, independent of the calendar provider. */
enum class LocalReminderWhen { PREVIOUS_EVENING, MORNING_OF, MINUTES_BEFORE }

data class LocalReminderPlan(
    val eventKey: String,
    val sourceKey: String,
    val duplicateKey: String,
    val eventDate: LocalDate,
    val eventStart: String,
    val allDay: Boolean,
    val whenToNotify: LocalReminderWhen,
    val minutesBefore: Int,
    val triggerAt: Instant,
) {
    val key: String get() = "$eventKey|${whenToNotify.name}|$minutesBefore"
}

/** Pure scheduling rules. A date-only event never receives an invented start time. */
object LocalReminderPlanner {
    private val evening = LocalTime.of(18, 0)
    private val morning = LocalTime.of(9, 0)

    fun trigger(start: String, allDay: Boolean, whenToNotify: LocalReminderWhen,
        minutesBefore: Int = 0, zone: ZoneId = ZoneId.systemDefault()): Pair<LocalDate, Instant> {
        require(whenToNotify == LocalReminderWhen.MINUTES_BEFORE ||
            minutesBefore == 0) { "Minute offset applies only to a timed reminder" }
        require(minutesBefore in 0..AndroidReminders.MAX_MINUTES) {
            "Choose a reminder within seven days"
        }
        val knownInstant = if (allDay) null else
            (AgendaEvent.instant(start, zone) ?: error("This event has no known start time"))
        val day = if (allDay) LocalDate.parse(start.take(10)) else
            knownInstant!!.atZone(zone).toLocalDate()
        val trigger = when (whenToNotify) {
            LocalReminderWhen.PREVIOUS_EVENING ->
                day.minusDays(1).atTime(evening).atZone(zone).toInstant()
            LocalReminderWhen.MORNING_OF ->
                day.atTime(morning).atZone(zone).toInstant()
            LocalReminderWhen.MINUTES_BEFORE -> {
                require(!allDay) { "Use a date reminder for an all-day event" }
                knownInstant!!.minusSeconds(minutesBefore * 60L)
            }
        }
        return day to Instant.ofEpochMilli(trigger.toEpochMilli())
    }

    fun plan(event: AgendaEvent, whenToNotify: LocalReminderWhen, minutesBefore: Int = 0,
        zone: ZoneId = ZoneId.systemDefault()): LocalReminderPlan {
        val (day, trigger) = trigger(event.start, event.allDay, whenToNotify, minutesBefore, zone)
        val stable = event.uid?.takeIf { it.isNotBlank() }
        val eventKey = sha256(listOf(event.provider.name, event.calendarId,
            stable ?: event.identity, event.recurrenceId.orEmpty()).joinToString("\u0000"))
        // Cross-provider UID matching is only a duplicate warning. It never merges event records.
        val duplicateKey = sha256(if (stable == null) eventKey else
            listOf(stable, event.recurrenceId.orEmpty(), event.start,
                event.summary.trim().lowercase()).joinToString("\u0000"))
        return LocalReminderPlan(eventKey, sourceKey(event.provider, event.calendarId),
            duplicateKey, day, event.start, event.allDay, whenToNotify, minutesBefore, trigger)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun sourceKey(provider: CalendarProviderKind, calendarId: String): String =
        sha256(listOf(provider.name, calendarId).joinToString("\u0000"))
}
