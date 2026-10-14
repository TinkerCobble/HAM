package com.tinkercobble.ham

import com.tinkercobble.ham.data.CalendarPreferences
import com.tinkercobble.ham.data.CalendarSelection
import com.tinkercobble.ham.data.CalendarSource
import org.junit.Assert.*
import org.junit.Test

class CalendarSelectionTest {
    private val a = CalendarSource("calendar.appointments", "Appointments", 1, "Provider A")
    private val b = CalendarSource("calendar.other_appointments", "Appointments", 1, "Provider B")
    private val readOnly = CalendarSource("calendar.read_only", "Read only", 0)

    @Test fun sameNamesRemainSeparateAndOnlyExactIdResolves() {
        val list = listOf(a, b, readOnly)
        assertEquals(listOf(a, b), CalendarSelection.writable(list))
        assertEquals(b.id, CalendarSelection.resolveWritable(b.id, list)?.id)
        assertNull(CalendarSelection.resolveWritable("calendar.missing", list))
        assertNull(CalendarSelection.resolveWritable(readOnly.id, list))
        assertEquals(listOf(a, b), CalendarSelection.search(list, "Appointments"))
        assertEquals(listOf(b), CalendarSelection.search(list, "other_appointments"))
        assertEquals(listOf(b), CalendarSelection.search(list, "Provider B"))
    }

    @Test fun permissionAndRefreshChangesNeverSwitchToNamesake() {
        assertEquals(b, CalendarSelection.resolveWritable(b.id, listOf(a, b)))
        assertNull(CalendarSelection.resolveWritable(b.id, listOf(a, b.copy(features = 0))))
        assertNull(CalendarSelection.resolveWritable(b.id, listOf(a)))
        assertEquals(b, CalendarSelection.resolveWritable(b.id, listOf(b, a)))
        assertNull(CalendarSelection.resolveWritable(null, listOf(a, b)))
    }

    @Test fun preferencesGroupByIdAndKeepAllAvailableInitially() {
        assertEquals(3, CalendarSelection.groups(listOf(a, b, readOnly), CalendarPreferences()).single().second.size)
        val saved = CalendarPreferences(favorites = setOf(b.id), hidden = setOf(readOnly.id),
            defaultId = b.id, recent = listOf(a.id))
        assertEquals(listOf("Favorites", "Recently Used", "All Calendars"),
            CalendarSelection.groups(listOf(a, b, readOnly), saved).map { it.first })
        assertEquals(b.id, CalendarSelection.groups(listOf(a, b), saved)[0].second.single().id)
        assertEquals(b.id, saved.defaultId)
        assertEquals(listOf(b.id, a.id), saved.used(b.id).recent)
        assertEquals(listOf(a.id, b.id), saved.used(b.id).used(a.id).recent)
        val restored = CalendarPreferences.fromStored(saved.favorites, saved.hidden, saved.defaultId, saved.recentString())
        assertEquals(saved, restored)
    }

    @Test fun duplicateIdCannotResolveAmbiguously() {
        assertNull(CalendarSelection.resolveWritable(a.id, listOf(a, a.copy(name = "Renamed"))))
        assertEquals(1, CalendarSelection.writable(listOf(a, a)).size)
    }
}
