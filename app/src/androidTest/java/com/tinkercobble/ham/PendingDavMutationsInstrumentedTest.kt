package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.tinkercobble.ham.data.DavBackgroundSync
import com.tinkercobble.ham.data.DavOperation
import com.tinkercobble.ham.data.DavPendingState
import com.tinkercobble.ham.data.DavEventPayload
import com.tinkercobble.ham.data.DavTitleConflict
import com.tinkercobble.ham.data.PendingDavAgenda
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.PendingDavMutation
import com.tinkercobble.ham.data.PendingDavMutations
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.data.RepeatFrequency
import com.tinkercobble.ham.data.RepeatSpec
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingDavMutationsInstrumentedTest {
    @Test fun detailEditQueueReopensEncryptedAndCannotUseTitleConflictMerge() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queue = PendingDavMutations(context)
        val account = "synthetic-details-${UUID.randomUUID()}"
        val calendar = "dav:$account:synthetic"
        val source = CalendarSource(calendar, "Synthetic", 7,
            provider = CalendarProviderKind.CALDAV)
        val id = UUID.randomUUID()
        val base = DavEventPayload.create(UUID.randomUUID(), LocalDate.parse("2026-11-02"),
            "Original", true, description = "Old synthetic notes")
        val revised = DavEventPayload.updateDetails(base, "New synthetic notes", "New room")
        try {
            queue.enqueue(PendingDavMutation(id, account, calendar, DavOperation.UPDATE,
                "https://dav.example.test/calendar/event.ics", "\"v1\"", revised,
                baseIcs = base))
            val reopened = PendingDavMutations(context).list().single { it.id == id }
            assertTrue(checkNotNull(reopened.ics).contentEquals(revised))
            assertTrue(checkNotNull(reopened.baseIcs).contentEquals(base))
            val raw = context.filesDir.resolve("pending-dav-v1.bin").readBytes()
            assertFalse(raw.toString(Charsets.UTF_8).contains("New synthetic notes"))
            assertFalse(DavTitleConflict.canReview(base, revised, source))
        } finally { queue.discardAccount(account) }
    }
    @Test fun unchangedDeleteConflictCanRequeueButChangedContentCannot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queue = PendingDavMutations(context)
        val account = "synthetic-delete-review-${UUID.randomUUID()}"
        val calendar = "dav:$account:synthetic"
        val eventId = UUID.randomUUID()
        val changeId = UUID.randomUUID()
        val base = DavEventPayload.create(eventId, LocalDate.parse("2026-11-02"),
            "Original", true, now = Instant.parse("2026-10-27T12:00:00Z"))
        try {
            queue.enqueue(PendingDavMutation(changeId, account, calendar, DavOperation.DELETE,
                "https://dav.example.test/calendar/event.ics", "\"v1\"", null,
                uid = "$eventId@ham.tinkercobble.com", baseIcs = base))
            queue.markConflict(changeId)
            val reopened = PendingDavMutations(context).list().single { it.id == changeId }
            assertTrue(checkNotNull(reopened.baseIcs).contentEquals(base))
            val changed = DavEventPayload.updateTitle(base, "Changed")
            assertTrue(runCatching { queue.requeueReviewedDelete(changeId, "\"v1\"",
                "\"v2\"", changed) }.isFailure)
            assertEquals(DavPendingState.CONFLICT,
                PendingDavMutations(context).list().single { it.id == changeId }.state)
            queue.requeueReviewedDelete(changeId, "\"v1\"", "\"v2\"", base)
            val after = PendingDavMutations(context).list().single { it.id == changeId }
            assertEquals(DavPendingState.QUEUED, after.state)
            assertEquals("\"v2\"", after.expectedEtag)
            assertTrue(checkNotNull(after.baseIcs).contentEquals(base))
        } finally { queue.discardAccount(account) }
    }
    @Test fun reviewedTitleRequeuesEncryptedUpdateWithoutTouchingOtherItems() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queue = PendingDavMutations(context)
        val account = "synthetic-review-${UUID.randomUUID()}"
        val calendar = "dav:$account:synthetic"
        val eventId = UUID.randomUUID()
        val changeId = UUID.randomUUID()
        val otherId = UUID.randomUUID()
        val base = DavEventPayload.create(eventId, LocalDate.parse("2026-11-02"),
            "Original", true, now = Instant.parse("2026-10-27T12:00:00Z"))
        val local = DavEventPayload.updateTitle(base, "Mine")
        val current = DavEventPayload.updateTitle(base, "Theirs")
        val merged = DavEventPayload.updateTitle(current, "Mine")
        try {
            queue.enqueue(PendingDavMutation(changeId, account, calendar, DavOperation.UPDATE,
                "https://dav.example.test/calendar/event.ics", "\"v1\"", local, baseIcs = base))
            queue.enqueue(PendingDavMutation(otherId, account, calendar, DavOperation.CREATE,
                null, null, base))
            queue.markConflict(changeId)
            val reopened = PendingDavMutations(context).list().single { it.id == changeId }
            assertTrue(checkNotNull(reopened.baseIcs).contentEquals(base))
            queue.requeueReviewedTitle(changeId, "\"v1\"", "\"v2\"", current, merged)
            val after = PendingDavMutations(context).list()
            assertEquals(DavPendingState.QUEUED, after.single { it.id == changeId }.state)
            assertEquals("\"v2\"", after.single { it.id == changeId }.expectedEtag)
            assertTrue(checkNotNull(after.single { it.id == changeId }.ics).contentEquals(merged))
            assertTrue(checkNotNull(after.single { it.id == changeId }.baseIcs).contentEquals(current))
            assertEquals(DavOperation.CREATE, after.single { it.id == otherId }.operation)
        } finally { queue.discardAccount(account) }
    }
    private val restartAccount = "synthetic-work-restart"
    private val restartId = UUID.fromString("00000000-0000-0000-0000-000000000123")
    @Test fun queuedSeriesEditAndRemovalSurviveEncryptedReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queue = PendingDavMutations(context)
        val account = "synthetic-series-mutation-${UUID.randomUUID()}"
        val calendar = "dav:$account:synthetic"
        val resource = "https://dav.example.test/calendar/series.ics"
        val id = UUID.randomUUID()
        val date = LocalDate.parse("2026-11-02")
        val original = DavEventPayload.create(id, date, "Synthetic series", true,
            now = Instant.parse("2026-10-27T12:00:00Z"),
            repeat = RepeatSpec(RepeatFrequency.WEEKLY, 4))
        val changed = DavEventPayload.updateSeriesTitle(original, "Synthetic renamed series")
        val source = CalendarSource(calendar, "Synthetic", 7, provider = CalendarProviderKind.CALDAV)
        val updateId = UUID.randomUUID()
        val deleteId = UUID.randomUUID()
        try {
            queue.enqueue(PendingDavMutation(updateId, account, calendar, DavOperation.UPDATE,
                resource, "\"v1\"", changed, uid = "$id@ham.tinkercobble.com"))
            val reopened = PendingDavMutations(context).list().single { it.id == updateId }
            assertTrue(checkNotNull(reopened.ics).contentEquals(changed))
            val projected = PendingDavAgenda.drafts(listOf(reopened), listOf(source),
                DateRange(date, date.plusDays(30)))
            assertEquals(4, projected.size)
            assertTrue(projected.all { it.event.summary == "Synthetic renamed series" &&
                it.state == DavPendingState.QUEUED })
            queue.discard(updateId)
            queue.enqueue(PendingDavMutation(deleteId, account, calendar, DavOperation.DELETE,
                resource, "\"v2\"", null, uid = "$id@ham.tinkercobble.com"))
            val reopenedDelete = PendingDavMutations(context).list().single { it.id == deleteId }
            assertEquals(DavOperation.DELETE, reopenedDelete.operation)
            assertEquals("$id@ham.tinkercobble.com", reopenedDelete.uid)
            assertTrue(PendingDavAgenda.drafts(listOf(reopenedDelete), listOf(source),
                DateRange(date, date.plusDays(30))).isEmpty())
        } finally { queue.discardAccount(account) }
    }
    @Test fun queuedFiniteSeriesProjectsAfterEncryptedStoreReopens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queue = PendingDavMutations(context)
        val account = "synthetic-series-${UUID.randomUUID()}"
        val calendar = "dav:$account:synthetic"
        val id = UUID.randomUUID()
        val date = LocalDate.parse("2026-11-02")
        val payload = DavEventPayload.create(id, date, "Synthetic offline series", true,
            now = Instant.parse("2026-10-27T12:00:00Z"),
            repeat = RepeatSpec(RepeatFrequency.WEEKLY, 4))
        val source = CalendarSource(calendar, "Synthetic", 1, provider = CalendarProviderKind.CALDAV)
        try {
            queue.enqueue(PendingDavMutation(id, account, calendar, DavOperation.CREATE,
                null, null, payload))
            val reopened = PendingDavMutations(context).list().filter { it.accountId == account }
            val projected = PendingDavAgenda.drafts(reopened, listOf(source),
                DateRange(date, date.plusDays(30)))
            assertEquals(listOf(date, date.plusWeeks(1), date.plusWeeks(2), date.plusWeeks(3)),
                projected.map { it.event.date })
            assertTrue(projected.all { it.state == DavPendingState.QUEUED })
        } finally { queue.discardAccount(account) }
    }
    @Test fun queuedEventProjectsAfterEncryptedStoreReopens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queue = PendingDavMutations(context)
        val account = "synthetic-${UUID.randomUUID()}"
        val calendar = "dav:$account:synthetic"
        val id = UUID.randomUUID()
        val payload = DavEventPayload.create(id, LocalDate.parse("2026-11-12"),
            "Synthetic offline event", true, now = Instant.parse("2026-10-27T12:00:00Z"))
        val source = CalendarSource(calendar, "Synthetic", 1, provider = CalendarProviderKind.CALDAV)
        try {
            queue.enqueue(PendingDavMutation(id, account, calendar, DavOperation.CREATE,
                null, null, payload))
            val reopened = PendingDavMutations(context).list().filter { it.accountId == account }
            val projected = PendingDavAgenda.drafts(reopened, listOf(source))
            assertEquals(1, projected.size)
            assertEquals("Synthetic offline event", projected.single().event.summary)
            assertEquals(DavPendingState.QUEUED, projected.single().state)
            assertEquals(1, PendingDavAgenda.merge(emptyList(), projected).size)
        } finally { queue.discardAccount(account) }
    }

    @Test fun queuedTitleEditProjectsAfterEncryptedStoreReopens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queue = PendingDavMutations(context)
        val account = "synthetic-${UUID.randomUUID()}"
        val calendar = "dav:$account:synthetic"
        val id = UUID.randomUUID()
        val original = DavEventPayload.create(id, LocalDate.parse("2026-11-12"),
            "Before", true, now = Instant.parse("2026-10-27T12:00:00Z"))
        val payload = DavEventPayload.updateTitle(original, "After locally")
        val source = CalendarSource(calendar, "Synthetic", 4, provider = CalendarProviderKind.CALDAV)
        try {
            queue.enqueue(PendingDavMutation(id, account, calendar, DavOperation.UPDATE,
                "https://dav.example.test/calendar/event.ics", "\"v1\"", payload))
            val reopened = PendingDavMutations(context).list().filter { it.accountId == account }
            val projected = PendingDavAgenda.drafts(reopened, listOf(source))
            assertEquals("After locally", projected.single().event.summary)
            assertEquals(DavPendingState.QUEUED, projected.single().state)
        } finally { queue.discardAccount(account) }
    }

    @Test fun encryptedQueuePersistsConflictAndConfirmedRemoval() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val disk = File(context.filesDir, "pending-dav-v1.bin")
        val account = "synthetic-account-${UUID.randomUUID()}"
        val id = UUID.randomUUID()
        val payload = "BEGIN:VCALENDAR\r\nSUMMARY:synthetic-private-marker\r\nEND:VCALENDAR\r\n"
            .toByteArray()
        val queued = PendingDavMutation(id, account, "synthetic-calendar", DavOperation.CREATE,
            null, null, payload)
        val queue = PendingDavMutations(context)
        try {
            queue.enqueue(queued)
            assertFalse(String(disk.readBytes(), Charsets.ISO_8859_1).contains("synthetic-private-marker"))
            val reopened = PendingDavMutations(context)
            assertEquals(1, reopened.list().size)
            assertTrue(reopened.list().single().ics!!.contentEquals(payload))
            reopened.markConflict(id)
            assertEquals(DavPendingState.CONFLICT, PendingDavMutations(context).list().single().state)
            reopened.removeConfirmed(id)
            assertTrue(PendingDavMutations(context).list().isEmpty())
        } finally {
            queue.discardAccount(account)
        }
    }

    @Test fun discardAccountKeepsOtherPendingChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queue = PendingDavMutations(context)
        val first = "synthetic-a-${UUID.randomUUID()}"
        val second = "synthetic-b-${UUID.randomUUID()}"
        val a = PendingDavMutation(UUID.randomUUID(), first, "calendar-a", DavOperation.CREATE,
            null, null, byteArrayOf(65))
        val b = PendingDavMutation(UUID.randomUUID(), second, "calendar-b", DavOperation.CREATE,
            null, null, byteArrayOf(66))
        try {
            queue.enqueue(a)
            queue.enqueue(b)
            queue.discardAccount(first)
            assertEquals(listOf(b.id), PendingDavMutations(context).list().map { it.id })
        } finally {
            queue.discardAccount(first)
            queue.discardAccount(second)
        }
    }

    @Test fun queuedDeleteRetainsUidAfterEncryptedReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queue = PendingDavMutations(context)
        val account = "synthetic-delete-${UUID.randomUUID()}"
        val id = UUID.randomUUID()
        val uid = "synthetic-event@example.test"
        try {
            queue.enqueue(PendingDavMutation(id, account, "dav:$account:calendar", DavOperation.DELETE,
                "https://dav.example.test/calendar/event.ics", "\"v1\"", null, uid = uid))
            val reopened = PendingDavMutations(context).list().single { it.id == id }
            assertEquals(uid, reopened.uid)
            assertEquals(DavOperation.DELETE, reopened.operation)
            assertEquals(null, reopened.ics)
            queue.markConflict(id)
            assertEquals(DavPendingState.CONFLICT,
                PendingDavMutations(context).list().single { it.id == id }.state)
        } finally { queue.discardAccount(account) }
    }

    @Test fun pendingChangeSchedulesPersistentNetworkWorkWithoutEventInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queue = PendingDavMutations(context)
        val account = "synthetic-schedule-${UUID.randomUUID()}"
        val id = UUID.randomUUID()
        val manager = WorkManager.getInstance(context)
        try {
            queue.enqueue(PendingDavMutation(id, account, "dav:$account:calendar", DavOperation.CREATE,
                null, null, byteArrayOf(65)))
            DavBackgroundSync.scheduleIfQueued(context, true)
            val work = manager.getWorkInfosForUniqueWork("ham-dav-pending-v1")
                .get(10, TimeUnit.SECONDS)
            assertTrue(work.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })
        } finally {
            queue.discardAccount(account)
            manager.cancelUniqueWork("ham-dav-pending-v1").result.get(10, TimeUnit.SECONDS)
        }
    }

    @Test fun stageSyntheticWorkForProcessRestart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ham_restart_phase") == "stage")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queue = PendingDavMutations(context)
        queue.discardAccount(restartAccount)
        WorkManager.getInstance(context).cancelUniqueWork("ham-dav-pending-v1")
            .result.get(10, TimeUnit.SECONDS)
        queue.enqueue(PendingDavMutation(restartId, restartAccount, "dav:$restartAccount:calendar",
            DavOperation.CREATE, null, null, byteArrayOf(65)))
        DavBackgroundSync.scheduleIfQueued(context, true)?.result?.get(10, TimeUnit.SECONDS)
        assertTrue(PendingDavMutations(context).list().any { it.id == restartId })
    }

    @Test fun confirmSyntheticWorkAfterProcessRestartAndCleanUp() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ham_restart_phase") == "confirm")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queue = PendingDavMutations(context)
        val manager = WorkManager.getInstance(context)
        try {
            assertTrue(queue.list().any { it.id == restartId })
            val work = manager.getWorkInfosForUniqueWork("ham-dav-pending-v1")
                .get(10, TimeUnit.SECONDS)
            assertTrue(work.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })
        } finally {
            queue.discardAccount(restartAccount)
            manager.cancelUniqueWork("ham-dav-pending-v1").result.get(10, TimeUnit.SECONDS)
        }
    }
}
