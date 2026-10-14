package com.tinkercobble.ham

import com.tinkercobble.ham.auth.ConnectionPolicy
import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.distinctEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HamFoundationTest {
    @Test fun remoteHttpCannotReceiveCredentials() {
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionPolicy.normalize("http://example.com")
        }
        assertEquals("http://192.168.1.5:8123", ConnectionPolicy.normalize(" http://192.168.1.5:8123/ "))
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionPolicy.normalize("https://user:secret@example.com")
        }
    }

    @Test fun allDayEndIsExclusiveAndDuplicateIdentityIsStable() {
        val event = AgendaEvent("calendar.a", "A", "Trip", "2026-10-13", "2026-10-15", true, "", "", "uid-1", null)
        assertTrue(event.occursOn(LocalDate.parse("2026-10-13")))
        assertTrue(event.occursOn(LocalDate.parse("2026-10-14")))
        assertFalse(event.occursOn(LocalDate.parse("2026-10-15")))
        assertEquals(1, distinctEvents(listOf(event, event.copy())).size)
    }

    @Test fun timedEventSpanningMidnightAppearsOnBothDays() {
        val event = AgendaEvent("calendar.a", "A", "Night", "2026-10-13T23:00:00-04:00", "2026-10-14T01:00:00-04:00", false, "", "", null, null)
        assertTrue(event.occursOn(LocalDate.parse("2026-10-13")))
        assertTrue(event.occursOn(LocalDate.parse("2026-10-14")))
        assertFalse(event.occursOn(LocalDate.parse("2026-10-15")))
    }
}
