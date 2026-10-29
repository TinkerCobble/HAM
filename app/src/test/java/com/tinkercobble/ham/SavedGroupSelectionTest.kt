package com.tinkercobble.ham

import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.SavedCalendarGroup
import com.tinkercobble.ham.data.SavedGroupMember
import com.tinkercobble.ham.data.SavedGroupSelection
import com.tinkercobble.ham.data.SavedGroupState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class SavedGroupSelectionTest {
    @Test fun reusedAndroidIdCannotBecomeVisibleThroughSavedGroup() {
        val old = SavedGroupMember("android:4", CalendarProviderKind.ANDROID, "a".repeat(64))
        val ha = SavedGroupMember("calendar.synthetic", CalendarProviderKind.HOME_ASSISTANT)
        val group = SavedCalendarGroup(UUID.randomUUID().toString(), "Fixture group", listOf(old, ha))
        val state = SavedGroupState(listOf(group), group.id)
        val current = listOf(CalendarSource("android:4", "Replacement", 7,
            provider = CalendarProviderKind.ANDROID, identityFingerprint = "b".repeat(64)),
            CalendarSource("calendar.synthetic", "Fixture", 0))
        assertEquals(setOf(ha.sourceId), SavedGroupSelection.activeSourceIds(state, current))
        val reconciled = SavedGroupSelection.reconcile(state, current,
            setOf(CalendarProviderKind.ANDROID, CalendarProviderKind.HOME_ASSISTANT))
        assertEquals(listOf(ha), reconciled.groups.single().members)
    }

    @Test fun transientDiscoveryFailureKeepsGroupForRetry() {
        val member = SavedGroupMember("ics:00000000-0000-0000-0000-000000000001",
            CalendarProviderKind.ICS)
        val group = SavedCalendarGroup(UUID.randomUUID().toString(), "Fixture", listOf(member))
        val state = SavedGroupState(listOf(group), group.id)
        assertEquals(state, SavedGroupSelection.reconcile(state, emptyList(), emptySet()))
        assertEquals(emptyList<SavedGroupMember>(), SavedGroupSelection.reconcile(state,
            emptyList(), setOf(CalendarProviderKind.ICS)).groups.single().members)
    }
}
