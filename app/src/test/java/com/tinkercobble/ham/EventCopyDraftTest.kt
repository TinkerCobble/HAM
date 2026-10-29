package com.tinkercobble.ham

import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.EventCopyDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class EventCopyDraftTest {
    @Test fun multiDayAllDayCopyPreservesDatesWithoutSourceMetadata() {
        val event = AgendaEvent("android:source", "Synthetic source", "Synthetic trip",
            "2026-11-05", "2026-11-08", true,
            "Public note\nham-attachment://123e4567-e89b-12d3-a456-426614174000",
            "Synthetic place", "provider-uid", null, CalendarProviderKind.ANDROID)
        val draft = EventCopyDraft.from(event, ZoneId.of("America/New_York"))
        assertEquals(LocalDate.of(2026, 11, 5), draft.date)
        assertEquals(LocalDate.of(2026, 11, 8), draft.endExclusive)
        assertEquals("Synthetic trip", draft.title)
        assertEquals("Synthetic place", draft.location)
        assertEquals("Public note", draft.description)
        assertFalse(draft.description.contains("provider-uid"))
        assertNull(draft.repeat)
    }

    @Test fun timedCopyPreservesLocalClockAndCopiesOneOccurrenceOnly() {
        val event = AgendaEvent("caldav:source", "Synthetic source", "Synthetic appointment",
            "2026-11-05T14:00:00Z", "2026-11-05T15:00:00Z", false,
            "", "", "provider-uid", "2026-11-05", CalendarProviderKind.CALDAV,
            "FREQ=WEEKLY;COUNT=4", true)
        val draft = EventCopyDraft.from(event, ZoneId.of("America/New_York"))
        assertEquals(LocalDate.of(2026, 11, 5), draft.date)
        assertEquals("09:00", draft.startText)
        assertEquals("10:00", draft.endText)
        assertNull(draft.repeat)
    }
}
