package com.tinkercobble.ham

import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.LocalReminderPlanner
import com.tinkercobble.ham.data.LocalReminderRecord
import com.tinkercobble.ham.data.LocalReminderWhen
import com.tinkercobble.ham.data.PinnedReminderText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class PinnedReminderTextTest {
    private val zone = ZoneId.of("America/New_York")
    private val longTitle = "Synthetic orchestra practice with an unusually long title that must remain complete"
    private val longLocation = "123 Fictional Boulevard, Suite 408, Imaginary Town, NY 10000, North entrance"

    private fun record(event: AgendaEvent): LocalReminderRecord = LocalReminderRecord.from(
        LocalReminderPlanner.plan(event, LocalReminderWhen.MORNING_OF, zone = zone), event, true)

    @Test fun timedCardKeepsFullTitleRangeAndLocationOnSeparateLines() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val event = AgendaEvent("synthetic-source", "Synthetic", longTitle,
                "2027-02-04T09:30:00-05:00", "2027-02-04T10:45:00-05:00", false,
                "", longLocation, "synthetic-uid", "2027-02-04T09:30:00-05:00",
                CalendarProviderKind.ICS)
            val lines = PinnedReminderText.lines(record(event))
            assertEquals(3, lines.size)
            assertEquals(longTitle, lines[0])
            assertTrue(lines[1].contains("9:30 AM"))
            assertTrue(lines[1].contains("10:45 AM"))
            assertEquals(longLocation, lines[2])
        } finally { Locale.setDefault(old) }
    }

    @Test fun allDayCardDoesNotInventTimeOrLocation() {
        val event = AgendaEvent("synthetic-source", "Synthetic", "Fictional all-day event",
            "2027-05-01", "2027-05-02", true, "", "", "synthetic-uid", null,
            CalendarProviderKind.ICS)
        val lines = PinnedReminderText.lines(record(event))
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("All day"))
        assertFalse(lines[1].contains(":"))
    }

    @Test fun ordinaryReminderRetainsNoEventSnapshot() {
        val event = AgendaEvent("synthetic-source", "Synthetic", longTitle,
            "2027-02-04T09:30:00-05:00", "2027-02-04T10:45:00-05:00", false,
            "", longLocation, "synthetic-uid", null, CalendarProviderKind.ICS)
        val record = LocalReminderRecord.from(LocalReminderPlanner.plan(event,
            LocalReminderWhen.MORNING_OF, zone = zone))
        assertFalse(record.keepUntilDone)
        assertEquals(null, record.details)
    }
}
