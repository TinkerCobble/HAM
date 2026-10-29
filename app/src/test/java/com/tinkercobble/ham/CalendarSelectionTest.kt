package com.tinkercobble.ham

import com.tinkercobble.ham.data.CalendarPreferences
import com.tinkercobble.ham.data.CalendarSelection
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarProviderKind
import org.junit.Assert.*
import org.junit.Test

class CalendarSelectionTest {
    private val a = CalendarSource("calendar.appointments", "Appointments", 1, "Provider A")
    private val b = CalendarSource("calendar.voice_appointments", "Appointments", 1, "Provider B")
    private val readOnly = CalendarSource("calendar.scouts", "Scouts", 0)

    @Test fun sameNamesRemainSeparateAndOnlyExactIdResolves() {
        val list = listOf(a, b, readOnly)
        assertEquals(listOf(a, b), CalendarSelection.writable(list))
        assertEquals(b.id, CalendarSelection.resolveWritable(b.id, list)?.id)
        assertNull(CalendarSelection.resolveWritable("calendar.missing", list))
        assertNull(CalendarSelection.resolveWritable(readOnly.id, list))
        assertEquals(listOf(a, b), CalendarSelection.search(list, "Appointments"))
        assertEquals(listOf(b), CalendarSelection.search(list, "voice_appointments"))
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

    @Test fun privateIcsFeedRequiresExplicitSelectionEvenWhenSearchingHiddenCalendars() {
        val id = "ics:00000000-0000-4000-8000-000000000001"
        val event = AgendaEvent(id, "Synthetic ICS", "Synthetic event", "2026-11-12",
            "2026-11-13", true, "", "", "synthetic-1", null, CalendarProviderKind.ICS)
        assertTrue(CalendarSelection.visibleEvents(listOf(event), CalendarPreferences(), false,
            false, emptySet(), includeHidden = true).isEmpty())
        assertEquals(listOf(event), CalendarSelection.visibleEvents(listOf(event),
            CalendarPreferences(), false, false, emptySet(), icsVisibleIds = setOf(id)))
        assertNull(CalendarSelection.resolveWritable(id,
            listOf(CalendarSource(id, "Synthetic ICS", 0, provider = CalendarProviderKind.ICS))))
    }

    @Test fun reusedAndroidRowIdCannotInheritSelectionOrWriteDefault() {
        val old = CalendarSource("android:1", "Old", 7, "Android · old account",
            provider = CalendarProviderKind.ANDROID, identityFingerprint = "a".repeat(64))
        val replacement = CalendarSource("android:1", "New", 7, "Android · new account",
            provider = CalendarProviderKind.ANDROID, identityFingerprint = "b".repeat(64))
        val prefs = CalendarPreferences(favorites = setOf(old.id, a.id),
            hidden = setOf(old.id), defaultId = old.id, recent = listOf(old.id, a.id),
            colors = mapOf(old.id to "#123456", a.id to "#654321"))
        val result = CalendarSelection.reconcileAndroidChoices(prefs, setOf(old.id),
            listOf(replacement), listOf(old), mapOf(old.id to old.identityFingerprint!!))
        assertTrue(result.visibleIds.isEmpty())
        assertTrue(result.choicesRemoved)
        assertNull(result.preferences.defaultId)
        assertEquals(setOf(a.id), result.preferences.favorites)
        assertEquals(listOf(a.id), result.preferences.recent)
        assertEquals(mapOf(a.id to "#654321"), result.preferences.colors)
        assertEquals(mapOf(replacement.id to replacement.identityFingerprint), result.fingerprints)
    }

    @Test fun legacyCacheCanBindMatchingAndroidCalendarButMissingCacheFailsClosed() {
        val current = CalendarSource("android:7", "Synthetic", 7, "Android · account",
            provider = CalendarProviderKind.ANDROID, identityFingerprint = "c".repeat(64))
        val legacy = current.copy(identityFingerprint = null)
        val prefs = CalendarPreferences(defaultId = current.id)
        val accepted = CalendarSelection.reconcileAndroidChoices(prefs, setOf(current.id),
            listOf(current), listOf(legacy), emptyMap())
        assertEquals(current.id, accepted.preferences.defaultId)
        assertEquals(setOf(current.id), accepted.visibleIds)
        assertFalse(accepted.choicesRemoved)
        val rejected = CalendarSelection.reconcileAndroidChoices(prefs, setOf(current.id),
            listOf(current), emptyList(), emptyMap())
        assertNull(rejected.preferences.defaultId)
        assertTrue(rejected.visibleIds.isEmpty())
        assertTrue(rejected.choicesRemoved)
        assertEquals(mapOf(current.id to current.identityFingerprint),
            CalendarSelection.parseAndroidFingerprints(CalendarSelection.encodeAndroidFingerprints(
                accepted.fingerprints)))
    }
}
