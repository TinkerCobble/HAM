package com.tinkercobble.ham

import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.HomeAssistantCreateDraft
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeAssistantCreateDraftTest {
    @Test fun descriptionAndLocationRemainInTheirOwnFields() {
        val source = CalendarSource("calendar.synthetic", "Synthetic", 1)
        val draft = HomeAssistantCreateDraft.of(source, "Appointment", "2026-11-05",
            "2026-11-06", true, "Private notes", "Meeting room")
        assertEquals("Private notes", draft.description)
        assertEquals("Meeting room", draft.location)
    }
}
