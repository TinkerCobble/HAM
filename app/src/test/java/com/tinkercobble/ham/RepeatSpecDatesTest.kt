package com.tinkercobble.ham

import com.tinkercobble.ham.data.RepeatFrequency
import com.tinkercobble.ham.data.RepeatSpec
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepeatSpecDatesTest {
    @Test fun monthlyCountSkipsMissingDays() {
        val dates = RepeatSpec(RepeatFrequency.MONTHLY, 5)
            .occurrenceDates(LocalDate.parse("2026-01-31"))
        assertEquals(listOf("2026-01-31", "2026-03-31", "2026-05-31",
            "2026-07-31", "2026-08-31"), dates.map(LocalDate::toString))
    }

    @Test fun dailyAndWeeklyDatesAreFiniteAndOrdered() {
        val start = LocalDate.parse("2026-10-30")
        assertEquals(listOf(start, start.plusDays(1), start.plusDays(2)),
            RepeatSpec(RepeatFrequency.DAILY, 3).occurrenceDates(start))
        assertEquals(listOf(start, start.plusWeeks(1), start.plusWeeks(2)),
            RepeatSpec(RepeatFrequency.WEEKLY, 3).occurrenceDates(start))
    }

    @Test fun syncAdapterWeekStartDoesNotChangeSimpleFiniteDates() {
        val expected = RepeatSpec(RepeatFrequency.DAILY, 4)
        assertEquals(expected, RepeatSpec.supported("FREQ=DAILY;COUNT=4;WKST=MO"))
        assertEquals(expected, RepeatSpec.supported("WKST=SU;COUNT=4;FREQ=DAILY"))
        assertEquals(expected.occurrenceDates(LocalDate.parse("2026-10-28")),
            RepeatSpec.supported("FREQ=DAILY;COUNT=4;WKST=MO")
                ?.occurrenceDates(LocalDate.parse("2026-10-28")))
        assertNull(RepeatSpec.supported("FREQ=DAILY;COUNT=4;BYDAY=MO"))
        assertNull(RepeatSpec.supported("FREQ=DAILY;COUNT=4;INTERVAL=2"))
        assertNull(RepeatSpec.supported("FREQ=DAILY;COUNT=4;WKST=MO;WKST=SU"))
        assertNull(RepeatSpec.supported("FREQ=DAILY;COUNT=4;WKST=XX"))
    }
}
