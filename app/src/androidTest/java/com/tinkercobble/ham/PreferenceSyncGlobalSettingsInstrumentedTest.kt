package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import android.content.ContextWrapper
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.tinkercobble.ham.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PreferenceSyncGlobalSettingsInstrumentedTest {
    private class IsolatedContext(private val base: Context, val label: String,
        private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, label).also { it.mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) =
            base.getSharedPreferences("synthetic-$label-$name", mode)
    }
    private class SyntheticTransport : PreferenceSyncTransport {
        val remote = mutableMapOf<String, RemotePreference>()
        var head = 0L
        var failNext = false
        override fun exchange(sinceRevision: Long, operations: List<PreferenceChange>): SyncExchange {
            if (failNext) { failNext = false; error("Synthetic outage") }
            val results = operations.map { op ->
                val current = remote[op.key]?.revision ?: 0L
                if (current == op.baseRevision) {
                    head++
                    remote[op.key] = RemotePreference(op.key, head, op.value)
                    SyncOperationResult(op.id, SyncOperationResult.Status.APPLIED, head)
                } else SyncOperationResult(op.id, SyncOperationResult.Status.CONFLICT, current)
            }
            return SyncExchange(results, remote.values.filter { it.revision > sinceRevision }
                .sortedBy { it.revision }, head)
        }
    }

    @Test fun actualGlobalAdapterSeedsPullsAndRetriesAfterOutage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ProtectedPreferenceSyncConnection(context)
        val journal = File(context.filesDir, "preference-sync-v1.bin")
        assertNull("Synthetic test requires an unpaired installation", store.load())
        assertFalse("Synthetic test requires an empty journal", journal.exists())
        try {
            store.save(PreferenceSyncConnection(PreferenceSyncEndpoint(
                "https://sync.example.invalid/", "a".repeat(64)),
                UUID.randomUUID().toString(), UUID.randomUUID().toString()))
            val adapter = PreferenceSyncGlobalSettings(context)
            val server = SyntheticTransport()
            val initial = adapter.exchange(WeekStart.SUNDAY, CalendarView.WEEK, transportOverride = server)
            assertEquals(WeekStart.SUNDAY, initial.weekStart)
            assertEquals(CalendarView.WEEK, initial.view)
            assertEquals(2, server.remote.size)
            server.head++
            server.remote["global:week_start"] = RemotePreference("global:week_start",
                server.head, SyncSettingValue.Text("MONDAY"))
            assertEquals(WeekStart.MONDAY,
                adapter.exchange(WeekStart.SUNDAY, CalendarView.WEEK, transportOverride = server).weekStart)
            adapter.recordWeekStart(WeekStart.SUNDAY)
            val scheduled = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("ham-preference-sync-soon-v1")
                .get(5, TimeUnit.SECONDS)
            assertTrue(scheduled.any { it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.RUNNING })
            server.failNext = true
            try { adapter.exchange(WeekStart.SUNDAY, CalendarView.WEEK, transportOverride = server); fail() }
            catch (_: IllegalStateException) { }
            assertEquals(1, ProtectedPreferenceSyncJournal(context).pending().size)
            val recovered = PreferenceSyncGlobalSettings(context)
                .exchange(WeekStart.SUNDAY, CalendarView.WEEK, transportOverride = server)
            assertEquals(WeekStart.SUNDAY, recovered.weekStart)
            assertEquals(0, recovered.pending)
            assertEquals(0, recovered.conflicts)
        } finally {
            WorkManager.getInstance(context).cancelUniqueWork("ham-preference-sync-soon-v1")
            WorkManager.getInstance(context).cancelUniqueWork("ham-preference-sync-periodic-v1")
            store.clear()
            journal.delete()
            File(context.filesDir, "preference-sync-v1.bin.bak").delete()
        }
    }

    @Test fun reviewedCalendarChoicesUseLogicalIdentityAcrossDifferentRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ProtectedPreferenceSyncConnection(context)
        val journal = File(context.filesDir, "preference-sync-v1.bin")
        val approvalsFile = File(context.filesDir, "preference-sync-approvals-v1.bin")
        assertNull(store.load())
        assertFalse(journal.exists())
        assertFalse(approvalsFile.exists())
        val identity = "android-provider:v1:${"b".repeat(64)}"
        val first = CalendarSource("android:7", "Sample calendar", 1,
            provider = CalendarProviderKind.ANDROID, identityFingerprint = "a".repeat(64),
            logicalIdentity = identity)
        val otherRow = first.copy(id = "android:41", identityFingerprint = "c".repeat(64))
        val groupId = UUID.randomUUID().toString()
        val groupState = SavedGroupState(listOf(SavedCalendarGroup(groupId,
            "Synthetic group", listOf(SavedGroupMember.from(first)))), groupId)
        try {
            store.save(PreferenceSyncConnection(PreferenceSyncEndpoint(
                "https://sync.example.invalid/", "a".repeat(64)),
                UUID.randomUUID().toString(), UUID.randomUUID().toString()))
            val approvals = ProtectedPreferenceSyncApprovals(context)
            approvals.approve(first, listOf(first))
            val adapter = PreferenceSyncGlobalSettings(context)
            val server = SyntheticTransport()
            val local = PreferenceSyncGlobalSettings.CalendarLocal(identity, true, false, null,
                mirrorHidden = false, order = 4, groups = listOf(groupId))
            val firstResult = adapter.exchange(WeekStart.SUNDAY, CalendarView.WEEK,
                listOf(local), identity, server, localGroups = groupState)
            assertEquals(true, firstResult.calendars[identity]?.visible)
            assertEquals(false, firstResult.calendars[identity]?.favorite)
            assertEquals(false, firstResult.calendars[identity]?.mirrorHidden)
            assertEquals(4, firstResult.calendars[identity]?.order)
            assertEquals(listOf(groupId), firstResult.groupOrder)
            assertEquals("Synthetic group", firstResult.groupNames[groupId])
            assertEquals(groupId, firstResult.activeGroupId)
            assertEquals(listOf(groupId), firstResult.calendars[identity]?.groups)
            assertEquals(identity, firstResult.defaultIdentity)
            assertTrue(server.remote.keys.none { it.contains("android:7") || it.contains("Sample") })
            assertTrue(approvals.resolved(listOf(otherRow)).isEmpty())
            // The second device independently approves its own row for the same
            // collection. No row number is sent to or read from the service.
            approvals.approve(otherRow, listOf(otherRow))
            assertEquals(otherRow, approvals.resolved(listOf(otherRow))[identity])
            server.head++
            server.remote["calendar:$identity:visible"] = RemotePreference(
                "calendar:$identity:visible", server.head, SyncSettingValue.Flag(false))
            server.head++
            server.remote["calendar:$identity:favorite"] = RemotePreference(
                "calendar:$identity:favorite", server.head, SyncSettingValue.Flag(true))
            server.head++
            server.remote["calendar:$identity:mirror_hidden"] = RemotePreference(
                "calendar:$identity:mirror_hidden", server.head, SyncSettingValue.Flag(true))
            server.head++
            server.remote["calendar:$identity:order"] = RemotePreference(
                "calendar:$identity:order", server.head, SyncSettingValue.Number(1))
            val second = adapter.exchange(WeekStart.SUNDAY, CalendarView.WEEK,
                listOf(local), identity, server, localGroups = groupState)
            assertEquals(false, second.calendars[identity]?.visible)
            assertEquals(true, second.calendars[identity]?.favorite)
            assertEquals(true, second.calendars[identity]?.mirrorHidden)
            assertEquals(1, second.calendars[identity]?.order)
        } finally {
            WorkManager.getInstance(context).cancelUniqueWork("ham-preference-sync-soon-v1")
            WorkManager.getInstance(context).cancelUniqueWork("ham-preference-sync-periodic-v1")
            store.clear()
            journal.delete()
            File(context.filesDir, "preference-sync-v1.bin.bak").delete()
            approvalsFile.delete()
            File(context.filesDir, "preference-sync-approvals-v1.bin.bak").delete()
        }
    }

    @Test fun threeIsolatedDevicesReconcileOfflineAndConflictWithoutNetworkListener() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.filesDir, "synthetic-sync-${UUID.randomUUID()}").also { it.mkdirs() }
        val contexts = listOf("first", "second", "third").map { IsolatedContext(base, it, root) }
        val profile = UUID.randomUUID().toString()
        val server = SyntheticTransport()
        try {
            val adapters = contexts.map { context ->
                ProtectedPreferenceSyncConnection(context).save(PreferenceSyncConnection(
                    PreferenceSyncEndpoint("https://sync.example.invalid/", "a".repeat(64)),
                    profile, UUID.randomUUID().toString()))
                PreferenceSyncGlobalSettings(context)
            }
            assertEquals(WeekStart.SUNDAY, adapters[0]
                .exchange(WeekStart.SUNDAY, CalendarView.WEEK, transportOverride = server).weekStart)
            assertEquals(WeekStart.SUNDAY, adapters[1]
                .exchange(WeekStart.MONDAY, CalendarView.MONTH, transportOverride = server).weekStart)
            assertEquals(WeekStart.SUNDAY, adapters[2]
                .exchange(WeekStart.MONDAY, CalendarView.MONTH, transportOverride = server).weekStart)
            adapters[0].recordWeekStart(WeekStart.MONDAY)
            adapters[0].exchange(WeekStart.MONDAY, CalendarView.WEEK, transportOverride = server)
            assertEquals(WeekStart.MONDAY, adapters[1]
                .exchange(WeekStart.SUNDAY, CalendarView.WEEK, transportOverride = server).weekStart)
            OfflineMode.setEnabled(contexts[1], true)
            adapters[1].recordView(CalendarView.MONTH)
            try {
                adapters[1].exchange(WeekStart.MONDAY, CalendarView.MONTH,
                    transportOverride = server)
                fail("Offline mode must hold queued changes")
            } catch (_: IllegalStateException) { }
            assertEquals(1, ProtectedPreferenceSyncJournal(contexts[1]).pending().size)
            OfflineMode.setEnabled(contexts[1], false)
            assertEquals(CalendarView.MONTH, PreferenceSyncGlobalSettings(contexts[1])
                .exchange(WeekStart.MONDAY, CalendarView.MONTH, transportOverride = server).view)
            assertEquals(CalendarView.MONTH, adapters[2]
                .exchange(WeekStart.MONDAY, CalendarView.WEEK, transportOverride = server).view)
            adapters[0].recordWeekStart(WeekStart.SUNDAY)
            adapters[2].recordWeekStart(WeekStart.SUNDAY)
            adapters[0].exchange(WeekStart.SUNDAY, CalendarView.MONTH, transportOverride = server)
            adapters[2].exchange(WeekStart.SUNDAY, CalendarView.MONTH, transportOverride = server)
            assertEquals(1, adapters[2].conflicts().size)
            adapters[2].resolveConflict(adapters[2].conflicts().single().local.id, keepLocal = false)
            assertTrue(adapters[2].conflicts().isEmpty())
        } finally {
            WorkManager.getInstance(base).cancelUniqueWork("ham-preference-sync-soon-v1")
            WorkManager.getInstance(base).cancelUniqueWork("ham-preference-sync-periodic-v1")
            contexts.forEach { context ->
                ProtectedPreferenceSyncConnection(context).clear()
                context.filesDir.listFiles()?.forEach(File::delete)
                context.filesDir.delete()
                base.deleteSharedPreferences("synthetic-${(context as IsolatedContext).label}-ham-offline-mode")
            }
            root.delete()
        }
    }

    @Test fun backgroundReconcilerWritesActualSettingsAcrossDifferentAndroidRows() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.filesDir, "synthetic-store-sync-${UUID.randomUUID()}").also { it.mkdirs() }
        val contexts = listOf("sender", "receiver").map { IsolatedContext(base, it, root) }
        val identity = "android-provider:v1:${"e".repeat(64)}"
        val first = CalendarSource("android:7", "Fictional A", 7, "Synthetic provider",
            provider = CalendarProviderKind.ANDROID, identityFingerprint = "a".repeat(64),
            logicalIdentity = identity)
        val second = first.copy(id = "android:41", name = "Fictional B",
            identityFingerprint = "b".repeat(64))
        val groupId = UUID.randomUUID().toString()
        val server = SyntheticTransport()
        val profile = UUID.randomUUID().toString()
        try {
            contexts.forEachIndexed { index, context ->
                val source = if (index == 0) first else second
                ProtectedPreferenceSyncConnection(context).save(PreferenceSyncConnection(
                    PreferenceSyncEndpoint("https://sync.example.invalid/", "a".repeat(64)),
                    profile, UUID.randomUUID().toString()))
                ProtectedPreferenceSyncApprovals(context).approve(source, listOf(source))
                AgendaCache(context).save("android-device", CachedAgenda(listOf(source), emptyList()),
                    LocalDate.now())
                val prefs = if (index == 0) CalendarPreferences(favorites = setOf(source.id),
                    defaultId = source.id, colors = mapOf(source.id to "#123456"),
                    mirrorHidden = setOf(source.id), order = mapOf(source.id to 2))
                    else CalendarPreferences(view = CalendarView.MONTH)
                CalendarPreferenceStore(context).save("android-device", prefs, durable = true)
                WeekStartPreferenceStore(context).save(if (index == 0) WeekStart.SUNDAY else WeekStart.MONDAY)
                context.getSharedPreferences("ham-android-calendars", Context.MODE_PRIVATE)
                    .edit().putStringSet("visible", if (index == 0) setOf(source.id) else emptySet())
                    .commit()
                if (index == 0) SavedCalendarGroupStore(context).save("android-device",
                    SavedGroupState(listOf(SavedCalendarGroup(groupId, "Synthetic group",
                        listOf(SavedGroupMember.from(source)))), groupId))
            }
            assertTrue(PreferenceSyncReconciler.runOnce(contexts[0], server, foreground = { false }))
            assertTrue(PreferenceSyncReconciler.runOnce(contexts[1], server, foreground = { false }))
            val received = CalendarPreferenceStore(contexts[1]).load("android-device")
            assertTrue(second.id in received.favorites)
            assertEquals("#123456", received.colors[second.id])
            assertTrue(second.id in received.mirrorHidden)
            assertEquals(2, received.order[second.id])
            assertEquals(second.id, received.defaultId)
            assertEquals(CalendarView.WEEK, received.view)
            assertEquals(WeekStart.SUNDAY, WeekStartPreferenceStore(contexts[1]).load())
            assertTrue(second.id in contexts[1].getSharedPreferences("ham-android-calendars",
                Context.MODE_PRIVATE).getStringSet("visible", emptySet()).orEmpty())
            val group = SavedCalendarGroupStore(contexts[1]).load("android-device").groups.single()
            assertEquals("Synthetic group", group.name)
            assertEquals(second.id, group.members.single().sourceId)
            WeekStartPreferenceStore(contexts[1]).save(WeekStart.MONDAY)
            PreferenceSyncGlobalSettings(contexts[1]).recordWeekStart(WeekStart.MONDAY)
            assertTrue(PreferenceSyncReconciler.runOnce(contexts[1], server, foreground = { false }))
            assertTrue(PreferenceSyncReconciler.runOnce(contexts[0], server, foreground = { false }))
            assertEquals(WeekStart.MONDAY, WeekStartPreferenceStore(contexts[0]).load())
        } finally {
            WorkManager.getInstance(base).cancelUniqueWork("ham-preference-sync-soon-v1")
            WorkManager.getInstance(base).cancelUniqueWork("ham-preference-sync-periodic-v1")
            contexts.forEach { context ->
                ProtectedPreferenceSyncConnection(context).clear()
                context.filesDir.listFiles()?.forEach(File::delete)
                context.filesDir.delete()
                listOf("ham-calendar-preferences", "ham-ui-preferences", "ham-saved-calendar-groups",
                    "ham-android-calendars", "ham-offline-mode").forEach { name ->
                    base.deleteSharedPreferences("synthetic-${context.label}-$name")
                }
            }
            root.delete()
        }
    }
}
