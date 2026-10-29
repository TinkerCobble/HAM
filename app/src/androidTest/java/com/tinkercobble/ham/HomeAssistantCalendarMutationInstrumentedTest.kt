package com.tinkercobble.ham

import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.HomeAssistantCalendarMutation
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeAssistantCalendarMutationInstrumentedTest {
    @Test fun detailEditUsesExactUidAndRequiresMatchingProjection() {
        val current = event.copy(description = "Old synthetic notes", location = "Old room")
        val command = HomeAssistantCalendarMutation.updateCommand(source, current,
            listOf(current), current.summary, description = "New synthetic notes",
            location = "New room")
        val payload = command.getJSONObject("event")
        assertEquals(current.uid, command.getString("uid"))
        assertEquals("New synthetic notes", payload.getString("description"))
        assertEquals("New room", payload.getString("location"))
        assertTrue(HomeAssistantCalendarMutation.updateVisible(source, current, command,
            listOf(current.copy(description = "New synthetic notes", location = "New room"))))
        assertFalse(HomeAssistantCalendarMutation.updateVisible(source, current, command,
            listOf(current.copy(description = "New synthetic notes"))))
        assertTrue(runCatching { HomeAssistantCalendarMutation.updateCommand(source, current,
            listOf(current.copy(location = "Changed remotely")), current.summary,
            description = "New synthetic notes", location = "New room") }.isFailure)
    }
    private val source = CalendarSource("calendar.synthetic", "Synthetic", 1 or 2 or 4)
    private val event = AgendaEvent(source.id, source.name, "Synthetic item", "2026-11-12",
        "2026-11-13", true, "", "", "synthetic-uid", null,
        recurrenceKnown = true)

    @Test fun acceptedUpdateNeedsUniqueCompleteProjectionBeforeConfirmation() {
        val command = HomeAssistantCalendarMutation.updateCommand(source, event, listOf(event),
            "Revised")
        val changed = event.copy(summary = "Revised")
        assertTrue(HomeAssistantCalendarMutation.updateVisible(source, event, command,
            listOf(changed)))
        assertFalse(HomeAssistantCalendarMutation.updateVisible(source, event, command,
            listOf(event)))
        assertFalse(HomeAssistantCalendarMutation.updateVisible(source, event, command,
            listOf(changed, changed)))
        assertFalse(HomeAssistantCalendarMutation.updateVisible(source, event, command,
            listOf(changed.copy(description = "Unexpected"))))
        assertFalse(HomeAssistantCalendarMutation.updateVisible(source, event, command,
            listOf(changed.copy(recurrenceKnown = false))))
    }

    @Test fun timedProjectionMayUseEquivalentUtcOffsetButMustMatchInstant() {
        val timed = event.copy(start = "2026-10-27T09:00:00-04:00",
            end = "2026-10-27T10:00:00-04:00", allDay = false)
        val command = HomeAssistantCalendarMutation.updateCommand(source, timed, listOf(timed),
            "Revised", "11:00", "12:00", ZoneId.of("America/New_York"))
        val visible = timed.copy(summary = "Revised", start = "2026-10-27T15:00:00Z",
            end = "2026-10-27T16:00:00Z")
        assertTrue(HomeAssistantCalendarMutation.updateVisible(source, timed, command,
            listOf(visible)))
        assertFalse(HomeAssistantCalendarMutation.updateVisible(source, timed, command,
            listOf(visible.copy(end = "2026-10-27T17:00:00Z"))))
    }

    @Test fun homeAssistantDeleteFeatureUsesOfficialBitTwo() {
        assertTrue(source.canDelete)
        assertFalse(source.copy(features = 1 or 4).canDelete)
        assertFalse(source.copy(features = 8).canDelete)
    }

    @Test fun exactNonrecurringEventBuildsOneUidCommand() {
        val command = HomeAssistantCalendarMutation.deleteCommand(source, event, listOf(event))
        assertEquals("calendar/event/delete", command.getString("type"))
        assertEquals(source.id, command.getString("entity_id"))
        assertEquals(event.uid, command.getString("uid"))
        assertFalse(command.has("recurrence_id"))
    }

    @Test fun recurringAmbiguousOrChangedEventIsRejected() {
        val recurring = event.copy(rrule = "FREQ=DAILY")
        val unknown = event.copy(recurrenceKnown = false)
        val changed = event.copy(summary = "Changed on server")
        listOf(recurring, unknown, event.copy(recurrenceId = "instance")).forEach { chosen ->
            assertTrue(runCatching { HomeAssistantCalendarMutation.deleteCommand(source,
                chosen, listOf(chosen)) }.isFailure)
        }
        assertTrue(runCatching { HomeAssistantCalendarMutation.deleteCommand(source,
            event, listOf(changed)) }.isFailure)
        assertTrue(runCatching { HomeAssistantCalendarMutation.deleteCommand(source,
            event, listOf(event, event.copy(start = "2026-11-13"))) }.isFailure)
        assertTrue(runCatching { HomeAssistantCalendarMutation.deleteCommand(source.copy(features = 4),
            event, listOf(event)) }.isFailure)
    }

    @Test fun homeAssistantApiRecurrenceMetadataControlsDeleteGuard() {
        fun parsed(rrule: Any?): AgendaEvent {
            val json = JSONObject().put("summary", "Synthetic item")
                .put("start", JSONObject().put("date", "2026-11-12"))
                .put("end", JSONObject().put("date", "2026-11-13"))
                .put("uid", "synthetic-uid").put("recurrence_id", JSONObject.NULL)
                .put("rrule", rrule)
            return AgendaEvent.fromJson(source, json)
        }
        assertTrue(parsed(JSONObject.NULL).recurrenceKnown)
        assertEquals(null, parsed(JSONObject.NULL).rrule)
        assertEquals("FREQ=DAILY", parsed("FREQ=DAILY").rrule)
    }

    @Test fun guardedTimedUpdateUsesSameDayOffsetAndRetainsOtherFields() {
        val zone = ZoneId.of("America/New_York")
        val timed = event.copy(start = "2026-10-27T09:00:00-04:00",
            end = "2026-10-27T10:00:00-04:00", allDay = false,
            description = "Synthetic description", location = "Synthetic location")
        val command = HomeAssistantCalendarMutation.updateCommand(source, timed, listOf(timed),
            "Revised", "11:00", "12:30", zone)
        assertEquals("calendar/event/update", command.getString("type"))
        assertEquals(timed.uid, command.getString("uid"))
        val edited = command.getJSONObject("event")
        assertEquals("Revised", edited.getString("summary"))
        assertEquals("2026-10-27T11:00-04:00", edited.getString("start"))
        assertEquals("2026-10-27T12:30-04:00", edited.getString("end"))
        assertEquals(timed.description, edited.getString("description"))
        assertEquals(timed.location, edited.getString("location"))
        assertTrue(runCatching { HomeAssistantCalendarMutation.updateCommand(source,
            timed.copy(rrule = "FREQ=DAILY"), listOf(timed), "Revised") }.isFailure)
        assertTrue(runCatching { HomeAssistantCalendarMutation.updateCommand(source,
            timed, listOf(timed.copy(summary = "Changed")), "Revised") }.isFailure)
        assertTrue(runCatching { HomeAssistantCalendarMutation.updateCommand(source,
            timed, listOf(timed.copy(description = "Changed on server")), "Revised") }.isFailure)
        val fallBack = timed.copy(start = "2026-11-01T00:30:00-04:00",
            end = "2026-11-01T03:00:00-05:00")
        assertTrue(runCatching { HomeAssistantCalendarMutation.updateCommand(source,
            fallBack, listOf(fallBack), "Revised", "01:30", "02:30",
            zone) }.isFailure)
    }
}
