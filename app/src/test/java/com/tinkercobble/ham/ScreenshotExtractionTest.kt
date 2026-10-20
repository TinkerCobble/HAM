package com.tinkercobble.ham

import com.tinkercobble.ham.data.ScreenshotExtraction
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ScreenshotExtractionTest {
    @Test fun clearEventAndAddress() {
        val result = ScreenshotExtraction.parse("Community Fair\nTuesday, November 10, 2026\n7–9 PM\n123 Fictional Street, Exampletown, NY 00000")
        assertEquals("Community Fair", result.title)
        assertEquals(listOf(LocalDate.of(2026, 11, 10)), result.dates)
        assertEquals(LocalTime.of(19, 0), result.start)
        assertEquals(LocalTime.of(21, 0), result.end)
        assertTrue(result.address.contains("123 Fictional Street"))
    }

    @Test fun venueWithoutStreetIsUnverified() {
        val result = ScreenshotExtraction.parse("Book Fair\nExample School\n11/10/26")
        assertEquals("Example School", result.venue)
        assertEquals("", result.address)
        assertTrue(result.warnings.any { it.contains("unverified") })
    }

    @Test fun addressAcrossTwoLinesKeepsCityStateAndZip() {
        val result = ScreenshotExtraction.parse("Community Fair\nNovember 10, 2026\n123 Fictional Street\nExampletown, NY 00000")
        assertEquals("123 Fictional Street, Exampletown, NY 00000", result.address)
    }

    @Test fun squareAddressAndGeocoderVenueDoNotDuplicateLocation() {
        val result = ScreenshotExtraction.parse("Open House\nOctober 24, 2026\nBuffalo City Hall\n65 Niagara Square\nBuffalo, NY 14202")
        assertEquals("65 Niagara Square, Buffalo, NY 14202", result.address)
        assertEquals("Buffalo City Hall, 65 Niagara Square, Buffalo, NY 14202",
            ScreenshotExtraction.eventLocation(result.venue, result.address))
        assertEquals("Buffalo City Hall, 65, Niagara Square, Buffalo, New York",
            ScreenshotExtraction.eventLocation("Buffalo City Hall", "Buffalo City Hall, 65, Niagara Square, Buffalo, New York"))
    }

    @Test fun severalAddressesAndDatesRequireReview() {
        val result = ScreenshotExtraction.parse("Open House\n11/10/26\n11/11/26\n123 Fictional Road\n456 Imaginary Avenue")
        assertEquals(2, result.dates.size)
        assertTrue(result.warnings.any { it.contains("Several addresses") })
    }

    @Test fun missingYearAndEndNeverInvented() {
        val result = ScreenshotExtraction.parse("Open House\nTuesday, November 10\n7 PM")
        assertTrue(result.dates.isEmpty())
        assertNull(result.end)
        assertTrue(result.warnings.any { it.contains("no year") })
    }
}
