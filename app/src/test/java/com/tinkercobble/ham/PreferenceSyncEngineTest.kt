package com.tinkercobble.ham

import com.tinkercobble.ham.data.*
import org.junit.Assert.*
import org.junit.Test

class PreferenceSyncEngineTest {
    private class Journal : PreferenceSyncJournal {
        val queued = mutableListOf<PreferenceChange>()
        val values = mutableMapOf<String, RemotePreference>()
        val conflicts = mutableListOf<PreferenceConflict>()
        var cursor = 0L
        override fun pending() = queued.toList()
        override fun remoteRevision() = cursor
        override fun current(key: String) = values[key]
        override fun stage(change: PreferenceChange) { queued += change }
        override fun conflicts() = conflicts.toList()
        override fun apply(exchange: SyncExchange) {
            for (change in exchange.changes) values[change.key] = change
            for (result in exchange.results) {
                val local = queued.find { it.id == result.id } ?: continue
                if (result.status == SyncOperationResult.Status.CONFLICT)
                    conflicts += PreferenceConflict(local, values[local.key])
                queued.remove(local)
            }
            cursor = if (exchange.hasMore) exchange.changes.last().revision else exchange.headRevision
        }
    }

    @Test fun queuesOnlyKnownPreferenceTypesWithoutContactingTransport() {
        val journal = Journal()
        var calls = 0
        val engine = PreferenceSyncEngine(journal, object : PreferenceSyncTransport {
            override fun exchange(sinceRevision: Long, operations: List<PreferenceChange>): SyncExchange {
                calls++
                return SyncExchange(emptyList(), emptyList(), 0)
            }
        })
        val change = engine.queue("global:week_start", SyncSettingValue.Text("SUNDAY"))
        assertEquals(0, calls)
        assertEquals(0, change.baseRevision)
        assertEquals(1, journal.pending().size)
        engine.queue("global:week_start", SyncSettingValue.Text("MONDAY"))
        try { engine.queue("event:title", SyncSettingValue.Text("private title")); fail() }
        catch (_: IllegalArgumentException) { }
        try { engine.queue("global:quick_add_default", SyncSettingValue.Text("android:42")); fail() }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun keepsConflictForReviewAndAdvancesOnlyThroughReceivedChanges() {
        val journal = Journal()
        val key = "global:week_start"
        val engine = PreferenceSyncEngine(journal, object : PreferenceSyncTransport {
            override fun exchange(sinceRevision: Long, operations: List<PreferenceChange>) =
                SyncExchange(listOf(SyncOperationResult(operations.single().id,
                    SyncOperationResult.Status.CONFLICT, 8)),
                    listOf(RemotePreference(key, 8, SyncSettingValue.Text("MONDAY"))), 8)
        })
        engine.queue(key, SyncSettingValue.Text("SUNDAY"))
        engine.sync()
        assertTrue(journal.pending().isEmpty())
        assertEquals(1, journal.conflicts().size)
        assertEquals(SyncSettingValue.Text("SUNDAY"), journal.conflicts().single().local.value)
        assertEquals(SyncSettingValue.Text("MONDAY"), journal.current(key)?.value)
        assertEquals(8, journal.remoteRevision())
    }

    @Test fun transportFailureKeepsPendingOperationForSafeRetry() {
        val journal = Journal()
        val engine = PreferenceSyncEngine(journal, object : PreferenceSyncTransport {
            override fun exchange(sinceRevision: Long, operations: List<PreferenceChange>): SyncExchange {
                error("server offline")
            }
        })
        val change = engine.queue("global:week_start", SyncSettingValue.Text("MONDAY"))
        try { engine.sync(); fail() } catch (_: IllegalStateException) { }
        assertEquals(change, journal.pending().single())
    }
}
