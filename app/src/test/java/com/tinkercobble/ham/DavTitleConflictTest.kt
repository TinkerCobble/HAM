package com.tinkercobble.ham

import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.DavEventPayload
import com.tinkercobble.ham.data.DavTitleConflict
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DavTitleConflictTest {
    private val id = UUID.fromString("4e921482-6b5c-4c18-a5b8-871027100001")
    private val source = CalendarSource("synthetic", "Synthetic", 7)
    private val base = DavEventPayload.create(id, LocalDate.parse("2026-11-02"), "Original", true,
        now = Instant.parse("2026-10-27T12:00:00Z"))

    @Test fun preservesCurrentServerFieldsWhileApplyingOnlyReviewedTitle() {
        val local = DavEventPayload.updateTitle(base, "Mine")
        val server = DavEventPayload.updateTitle(base, "Theirs")
            .toString(Charsets.UTF_8).replace("END:VEVENT", "DESCRIPTION:Server detail\r\nEND:VEVENT")
            .toByteArray(Charsets.UTF_8)
        val result = DavTitleConflict.review(base, local, server, source)
        assertEquals("Original", result.originalTitle)
        assertEquals("Theirs", result.serverTitle)
        assertEquals("Mine", result.proposedTitle)
        assertTrue(result.mergedIcs.toString(Charsets.UTF_8).contains("DESCRIPTION:Server detail"))
        assertTrue(result.mergedIcs.toString(Charsets.UTF_8).contains("SUMMARY:Mine"))
    }

    @Test fun rejectsOtherLocalEditsAndServerTimeChanges() {
        val local = DavEventPayload.updateTitle(base, "Mine")
        val changedTime = base.toString(Charsets.UTF_8)
            .replace("DTSTART;VALUE=DATE:20261102", "DTSTART;VALUE=DATE:20261103")
            .toByteArray(Charsets.UTF_8)
        assertTrue(runCatching { DavTitleConflict.review(base, local, changedTime, source) }.isFailure)
        val localWithDescription = local.toString(Charsets.UTF_8)
            .replace("END:VEVENT", "DESCRIPTION:Local detail\r\nEND:VEVENT")
            .toByteArray(Charsets.UTF_8)
        assertTrue(runCatching { DavTitleConflict.review(base, localWithDescription, base, source) }.isFailure)
    }
}
