package com.tinkercobble.ham

import com.tinkercobble.ham.data.PreferenceSyncKeys
import com.tinkercobble.ham.data.SyncSettingValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PreferenceSyncGroupProtocolTest {
    @Test fun groupNamesAndMappedMembershipsAreBoundedAndProviderNeutral() {
        val id = UUID.randomUUID().toString()
        val logical = "android-provider:v1:${"d".repeat(64)}"
        assertTrue(PreferenceSyncKeys.valid("group:$id:name", SyncSettingValue.Text("Synthetic group")))
        assertTrue(PreferenceSyncKeys.valid("global:group_order", SyncSettingValue.TextList(listOf(id))))
        assertTrue(PreferenceSyncKeys.valid("global:active_group", SyncSettingValue.Text(id)))
        assertTrue(PreferenceSyncKeys.valid("calendar:$logical:groups", SyncSettingValue.TextList(listOf(id))))
        assertFalse(PreferenceSyncKeys.valid("group:$id:name", SyncSettingValue.Text("\nPrivate log")))
        assertFalse(PreferenceSyncKeys.valid("calendar:android:7:groups", SyncSettingValue.TextList(listOf(id))))
        assertFalse(PreferenceSyncKeys.valid("calendar:$logical:groups", SyncSettingValue.TextList(listOf(id, id))))
    }
}
