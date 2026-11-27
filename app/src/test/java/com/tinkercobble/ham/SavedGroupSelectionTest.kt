package com.tinkercobble.ham

import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.SavedCalendarGroup
import com.tinkercobble.ham.data.SavedGroupMember
import com.tinkercobble.ham.data.SavedGroupSelection
import com.tinkercobble.ham.data.SavedGroupSyncProjection
import com.tinkercobble.ham.data.SavedGroupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun remoteGroupUsesApprovedLocalRowAndKeepsUnmatchedMember() {
        val id = UUID.randomUUID().toString()
        val oldMember = SavedGroupMember("android:7", CalendarProviderKind.ANDROID, "a".repeat(64))
        val unrelated = SavedGroupMember("calendar.unrelated", CalendarProviderKind.HOME_ASSISTANT)
        val local = SavedGroupState(listOf(SavedCalendarGroup(id, "Old group",
            listOf(oldMember, unrelated))), id)
        val approved = CalendarSource("android:41", "Fictional source", 7,
            provider = CalendarProviderKind.ANDROID, identityFingerprint = "b".repeat(64))
        val result = SavedGroupSyncProjection.apply(local, listOf(id), mapOf(id to "Shared group"),
            true, id, mapOf(approved to listOf(id)))!!
        assertEquals("Shared group", result.groups.single().name)
        assertEquals(setOf(oldMember, unrelated, SavedGroupMember.from(approved)),
            result.groups.single().members.toSet())
        assertEquals(id, result.activeId)
    }

    @Test fun missingNameOrUnknownMembershipFailsClosed() {
        val id = UUID.randomUUID().toString()
        val source = CalendarSource("calendar.fixture", "Fixture", 0)
        assertNull(SavedGroupSyncProjection.apply(SavedGroupState(), listOf(id), emptyMap(),
            false, null, mapOf(source to listOf(id))))
        assertNull(SavedGroupSyncProjection.apply(SavedGroupState(), emptyList(), emptyMap(),
            false, null, mapOf(source to listOf(id))))
    }

    @Test fun initialPairingPreservesLocalGroupUntilExplicitRemoteDeletion() {
        val id = UUID.randomUUID().toString()
        val local = SavedGroupState(listOf(SavedCalendarGroup(id, "Local fixture", emptyList())), id)
        assertEquals(local, SavedGroupSyncProjection.apply(local, emptyList(), emptyMap(),
            false, null, emptyMap()))
        assertEquals(SavedGroupState(), SavedGroupSyncProjection.apply(local, emptyList(), emptyMap(),
            true, null, emptyMap(), setOf(id)))
    }
}
