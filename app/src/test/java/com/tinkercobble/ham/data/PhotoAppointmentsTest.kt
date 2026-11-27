package com.tinkercobble.ham.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class PhotoAppointmentsTest {
    @Test fun privateTranscriptionCorrectsOnlyUniqueMatchingRowsWithCodeEvidence() {
        val date = LocalDate.of(2028, 11, 4)
        val original = PhotoAppointment(date, LocalTime.of(10, 0), 60,
            "CITY-COUN5EL IND", "", "synthetic photo", listOf("Confirm location"))
        val verified = VerifiedPhotoRow(date, LocalTime.of(10, 0), 60,
            "CITY-COUNSEL IND", "10TH FLOOR")
        val corrected = PhotoTranscriptReconciliation.reconcile(original, listOf(verified))
        assertTrue(corrected.privateTranscriptMatched)
        assertEquals("CITY-COUNSEL IND", corrected.originalCode)
        assertEquals("10TH FLOOR", corrected.printedLocation)
        assertEquals("synthetic photo", corrected.originalText)
        assertTrue(corrected.warnings.isEmpty())
        assertEquals(original, PhotoTranscriptReconciliation.reconcile(original,
            listOf(verified, verified)))
        assertEquals(original, PhotoTranscriptReconciliation.reconcile(original,
            listOf(verified.copy(originalCode = "CITY-EYE"))))
        assertEquals(original, PhotoTranscriptReconciliation.reconcile(original,
            listOf(verified.copy(start = LocalTime.of(10, 15)))))
        assertEquals(listOf(original, original), PhotoTranscriptReconciliation.reconcileAll(
            listOf(original, original), listOf(verified)))
    }

    @Test fun printedSheetHasElevenSeparateSuggestionsWithDurationsAndYearBoundary() {
        val sheet = listOf(
            "Nov 04, 2028 10:00 AM (60 MINUTES) CITY-COUNSEL IND 10TH FLOOR",
            "Nov 24, 2028 9:00 AM (60 MINUTES) CITY-COUNSEL IND 10TH FLOOR",
            "Dec 16, 2028 10:00 AM (60 MINUTES) CITY-COUNSEL IND 10TH FLOOR",
            "Jan 05, 2029 11:00 AM (30 MINUTES) CITY-NUTR VIDEO VIDEO TO PATIENT",
            "Jan 07, 2029 10:30 AM (30 MINUTES) CITY-EYE OPTOMETRY 6TH FL D-WIN",
            "Jan 13, 2029 10:00 AM (60 MINUTES) CITY-COUNSEL IND 10TH FLOOR",
            "Feb 02, 2029 10:00 AM (60 MINUTES) CITY-COUNSEL IND 10TH FLOOR",
            "Feb 16, 2029 10:30 AM (30 MINUTES) CITY-PRIMARY TEAM 3B 3RD FLOOR B-WING",
            "Feb 23, 2029 10:00 AM (60 MINUTES) CITY-COUNSEL IND 10TH FLOOR",
            "Mar 16, 2029 10:00 AM (60 MINUTES) CITY-COUNSEL IND 10TH FLOOR",
            "Mar 25, 2029 10:30 AM (30 MINUTES) CITY-SLEEP CLINIC DESK 4 1ST FLOOR",
        ).joinToString("\n")
        val result = PhotoAppointments.parse(sheet)
        assertEquals(11, result.appointments.size)
        assertEquals(7, result.appointments.count { it.originalCode == "CITY-COUNSEL IND" })
        assertEquals(LocalDate.of(2029, 1, 5), result.appointments[3].date)
        assertEquals(LocalTime.of(11, 30), result.appointments[3].end)
        assertTrue(result.appointments[3].printedLocation.startsWith("VIDEO"))
        assertTrue(result.appointments[7].printedLocation.contains("3RD FLOOR"))
        assertTrue(result.appointments[10].printedLocation.contains("DESK"))
    }

    @Test fun numericDateAndUncertainDurationNeedReview() {
        val result = PhotoAppointments.parse("07/17/2028 2:15 PM CITY-CHECK ROOM 12")
        assertEquals(1, result.appointments.size)
        assertEquals(LocalDate.of(2028, 7, 17), result.appointments.single().date)
        assertTrue(result.appointments.single().warnings.any { it.contains("duration") })
    }

    @Test fun twoPrintedRowsWithSameTimeStaySeparateForReview() {
        val row = "Jul 17, 2028 2:15 PM (30 MINUTES) CITY-CHECK ROOM 12"
        val result = PhotoAppointments.parse("$row\n$row")
        assertEquals(2, result.appointments.size)
    }

    @Test fun aliasMatchDoesNotConflateDifferentTimesOrDates() {
        val item = PhotoAppointment(LocalDate.of(2028, 11, 4), LocalTime.of(10, 0), 60,
            "BHIP", "10TH FLOOR", "synthetic")
        val event = AgendaEvent("one", "Test", "Psychology - VA", "2028-11-04T10:00:00-04:00",
            "2028-11-04T11:00:00-04:00", false, "", "Fictional clinic", "id", null)
        val zone = ZoneId.of("America/New_York")
        assertEquals(PhotoMatchKind.POSSIBLE_DUPLICATE,
            PhotoAppointmentMatches.assess(item, "Behavioral Health", "", listOf(event), emptySet(), zone).kind)
        assertEquals(PhotoMatchKind.NEW,
            PhotoAppointmentMatches.assess(item.copy(start = LocalTime.of(12, 0)),
                "Behavioral Health", "", listOf(event), emptySet(), zone).kind)
        assertEquals(PhotoMatchKind.UNVERIFIED,
            PhotoAppointmentMatches.assess(item.copy(date = LocalDate.of(2028, 11, 5)),
                "Behavioral Health", "", listOf(event), setOf("Unavailable calendar"), zone).kind)
    }
}
