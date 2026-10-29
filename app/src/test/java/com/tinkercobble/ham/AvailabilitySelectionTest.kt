package com.tinkercobble.ham

import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.AvailabilitySelection
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.CalendarPreferences
import com.tinkercobble.ham.data.CalendarSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class AvailabilitySelectionTest {
    private fun source(id: String, provider: CalendarProviderKind, fingerprint: String? = null) =
        CalendarSource(id, "Synthetic", 0, provider = provider, identityFingerprint = fingerprint)
    private fun event(id: String, provider: CalendarProviderKind) = AgendaEvent(id,
        "Synthetic", "Fixture", "2026-11-02", "2026-11-03", true,
        "", "", null, null, provider)

    @Test fun hiddenHomeAssistantCalendarStillBlocksInitialAvailability() {
        val hiddenHa = source("calendar.fixture_hidden", CalendarProviderKind.HOME_ASSISTANT)
        val selectedAndroid = source("android:12", CalendarProviderKind.ANDROID, "a".repeat(64))
        val unselectedFeed = source("ics:00000000-0000-0000-0000-000000000001",
            CalendarProviderKind.ICS)
        val sources = listOf(hiddenHa, selectedAndroid, unselectedFeed)
        val initial = AvailabilitySelection.initial(sources, setOf(selectedAndroid.id))
        assertEquals(setOf(hiddenHa.id, selectedAndroid.id), initial)
        val loaded = sources.map { event(it.id, it.provider) }
        assertEquals(emptyList<AgendaEvent>(), CalendarSelection.visibleEvents(
            listOf(loaded.first()), CalendarPreferences(hidden = setOf(hiddenHa.id)),
            false, false, emptySet()))
        assertEquals(listOf(hiddenHa.id, selectedAndroid.id), AvailabilitySelection.events(
            loaded, sources, initial).map { it.calendarId })
    }

    @Test fun reusedAndroidIdCannotKeepBusyCalendarChoice() {
        val id = "android:12"
        val old = "a".repeat(64)
        val replacement = source(id, CalendarProviderKind.ANDROID, "b".repeat(64))
        val ha = source("calendar.fixture", CalendarProviderKind.HOME_ASSISTANT)
        assertEquals(setOf(ha.id), AvailabilitySelection.safeAndroidIds(
            setOf(id, ha.id), mapOf(id to old), listOf(replacement, ha)))
        assertEquals(emptyList<AgendaEvent>(), AvailabilitySelection.events(
            listOf(event(id, CalendarProviderKind.ANDROID)), listOf(ha), setOf(id)))
    }

    @Test fun confirmedDiscoveryPrunesOnlyMissingSources() {
        val ha = source("calendar.current", CalendarProviderKind.HOME_ASSISTANT)
        val ids = setOf(ha.id, "calendar.no_longer_visible", "ics:old-feed", "dav:temporarily-offline")
        assertEquals(setOf(ha.id, "dav:temporarily-offline"),
            AvailabilitySelection.pruneConfirmedMissing(ids, listOf(ha), true, true))
        assertEquals(ids, AvailabilitySelection.pruneConfirmedMissing(ids, emptyList(), false, false))
    }
}
