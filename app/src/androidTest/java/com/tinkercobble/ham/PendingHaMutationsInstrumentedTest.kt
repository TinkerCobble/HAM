package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.data.HaMutationDispatcher
import com.tinkercobble.ham.data.HaBackgroundSync
import com.tinkercobble.ham.data.HomeAssistantCalendarMutation
import com.tinkercobble.ham.data.HaMutationTransport
import com.tinkercobble.ham.data.HaOperation
import com.tinkercobble.ham.data.HaPendingState
import com.tinkercobble.ham.data.HaTarget
import com.tinkercobble.ham.data.PendingHaMutation
import com.tinkercobble.ham.data.PendingHaMutations
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PendingHaMutationsInstrumentedTest {
    @Test fun queuedDetailEditStaysEncryptedAndConfirmsExactProjection() = runBlocking {
        val queue = queue()
        val originalEvent = original.copy(uid = UUID.randomUUID().toString())
        val edited = HaTarget(originalEvent.summary, originalEvent.start, originalEvent.end,
            "Updated synthetic notes", "Updated synthetic room")
        val change = PendingHaMutation(UUID.randomUUID(), binding, source.id, originalEvent,
            HaOperation.UPDATE, edited)
        val transport = FakeTransport(binding, source, listOf(originalEvent))
        transport.onSend = { command ->
            assertEquals(edited.description,
                command.getJSONObject("event").getString("description"))
            assertEquals(edited.location,
                command.getJSONObject("event").getString("location"))
            transport.live = listOf(originalEvent.copy(description = edited.description,
                location = edited.location))
        }
        try {
            queue.enqueue(change)
            assertEquals(edited, queue().list().single { it.id == change.id }.target)
            val raw = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
                .resolve("pending-ha-v1.bin").readBytes()
            assertFalse(raw.toString(Charsets.UTF_8).contains(edited.description))
            assertEquals(1, HaMutationDispatcher.flush(queue, transport, onlyId = change.id).confirmed)
            assertTrue(queue.list().none { it.id == change.id })
        } finally { if (queue.list().any { it.id == change.id }) queue.discard(change.id) }
    }
    private val binding = "a".repeat(64)
    private val source = CalendarSource("calendar.synthetic", "Synthetic", 7)
    private val original = AgendaEvent(source.id, source.name, "Original", "2026-11-12",
        "2026-11-13", true, "Synthetic detail", "Synthetic place", "synthetic-uid", null,
        recurrenceKnown = true)
    private val target = HaTarget("Changed", original.start, original.end,
        original.description, original.location)

    private fun queue() = PendingHaMutations(InstrumentationRegistry.getInstrumentation().targetContext)
    private fun item(operation: HaOperation = HaOperation.UPDATE,
        session: String = binding) = PendingHaMutation(UUID.randomUUID(), session, source.id,
        original.copy(uid = UUID.randomUUID().toString()), operation,
        if (operation == HaOperation.UPDATE) target else null)

    private fun createItem() = PendingHaMutation(UUID.randomUUID(), binding, source.id,
        original.copy(summary = "Create synthetic event", uid = null), HaOperation.CREATE, null)

    private class FakeTransport(var binding: String, val source: CalendarSource,
        var live: List<AgendaEvent>) : HaMutationTransport {
        var sends = 0
        var offline = false
        var rangeAware = false
        val reads = mutableListOf<Pair<LocalDate, LocalDate>>()
        var onSend: ((JSONObject) -> Unit)? = null
        override fun sessionBinding() = binding
        override suspend fun calendars(): List<CalendarSource> {
            if (offline) throw IOException("Synthetic offline")
            return listOf(source)
        }
        override suspend fun events(source: CalendarSource, from: LocalDate,
            until: LocalDate): List<AgendaEvent> {
            reads += from to until
            return if (rangeAware) live.filter { it.occursIn(DateRange(from, until)) } else live
        }
        override suspend fun send(command: JSONObject) {
            sends++
            onSend?.invoke(command)
        }
    }

    @Test fun encryptedQueueSurvivesReopenAndRejectsDuplicateUid() {
        val queue = queue()
        val change = item()
        try {
            queue.enqueue(change)
            val reopened = queue().list().single { it.id == change.id }
            assertEquals(change.original.uid, reopened.original.uid)
            assertEquals(target.summary, reopened.target?.summary)
            assertTrue(runCatching { queue.enqueue(change.copy(id = UUID.randomUUID())) }.isFailure)
            val raw = InstrumentationRegistry.getInstrumentation().targetContext
                .filesDir.resolve("pending-ha-v1.bin").readBytes()
            assertFalse(raw.toString(Charsets.UTF_8).contains("Synthetic detail"))
        } finally { queue.discard(change.id) }
    }

    @Test fun offlinePreflightRetainsQueuedChangeWithoutSend() = runBlocking {
        val queue = queue()
        val change = item()
        val transport = FakeTransport(binding, source, listOf(change.original)).apply { offline = true }
        try {
            queue.enqueue(change)
            val result = HaMutationDispatcher.flush(queue, transport, onlyId = change.id)
            assertTrue(result.networkFailure)
            assertEquals(0, transport.sends)
            assertEquals(HaPendingState.QUEUED, queue.list().single { it.id == change.id }.state)
        } finally { queue.discard(change.id) }
    }

    @Test fun updateConfirmsOnlyAfterTargetProjection() = runBlocking {
        val queue = queue()
        val change = item()
        val transport = FakeTransport(binding, source, listOf(change.original))
        transport.onSend = { command ->
            assertEquals("calendar/event/update", command.getString("type"))
            assertEquals(change.original.uid, command.getString("uid"))
            transport.live = listOf(change.original.copy(summary = target.summary))
        }
        try {
            queue.enqueue(change)
            val result = HaMutationDispatcher.flush(queue, transport, onlyId = change.id)
            assertEquals(1, result.confirmed)
            assertEquals(1, transport.sends)
            assertTrue(queue.list().none { it.id == change.id })
        } finally { if (queue.list().any { it.id == change.id }) queue.discard(change.id) }
    }

    @Test fun lostResponseAfterUpdateIsReconciledWithoutReplay() = runBlocking {
        val queue = queue()
        val change = item()
        val transport = FakeTransport(binding, source, listOf(change.original))
        transport.onSend = {
            transport.live = listOf(change.original.copy(summary = target.summary))
            throw IOException("Synthetic lost reply")
        }
        try {
            queue.enqueue(change)
            HaMutationDispatcher.flush(queue, transport, onlyId = change.id)
            assertEquals(HaPendingState.AWAITING_CONFIRMATION,
                queue.list().single { it.id == change.id }.state)
            val result = HaMutationDispatcher.flush(queue, transport, onlyId = change.id)
            assertEquals(1, result.confirmed)
            assertEquals(1, transport.sends)
            assertTrue(queue.list().none { it.id == change.id })
        } finally { if (queue.list().any { it.id == change.id }) queue.discard(change.id) }
    }

    @Test fun uncertainSendWithOriginalStillVisibleNeverReplays() = runBlocking {
        val queue = queue()
        val change = item()
        val transport = FakeTransport(binding, source, listOf(change.original))
        transport.onSend = { throw IOException("Synthetic uncertain send") }
        try {
            queue.enqueue(change)
            HaMutationDispatcher.flush(queue, transport, onlyId = change.id)
            val result = HaMutationDispatcher.flush(queue, transport, onlyId = change.id)
            assertEquals(1, result.needsReview)
            assertEquals(1, transport.sends)
            assertEquals(HaPendingState.AWAITING_CONFIRMATION,
                queue.list().single { it.id == change.id }.state)
        } finally { queue.discard(change.id) }
    }

    @Test fun changedEventOrAuthorizationConflictsBeforeSend() = runBlocking {
        val queue = queue()
        val changed = item()
        val wrongAccount = item(session = "b".repeat(64))
        try {
            queue.enqueue(changed)
            queue.enqueue(wrongAccount)
            val transport = FakeTransport(binding, source,
                listOf(changed.original.copy(summary = "Changed elsewhere")))
            assertEquals(1, HaMutationDispatcher.flush(queue, transport,
                onlyId = changed.id).conflicts)
            assertEquals(1, HaMutationDispatcher.flush(queue, transport,
                onlyId = wrongAccount.id).conflicts)
            assertEquals(0, transport.sends)
        } finally { queue.discard(changed.id); queue.discard(wrongAccount.id) }
    }

    @Test fun acceptedDeleteNeedsAbsentProjection() = runBlocking {
        val queue = queue()
        val change = item(HaOperation.DELETE)
        val transport = FakeTransport(binding, source, listOf(change.original))
        transport.onSend = { command ->
            assertEquals("calendar/event/delete", command.getString("type"))
            transport.live = emptyList()
        }
        try {
            queue.enqueue(change)
            val result = HaMutationDispatcher.flush(queue, transport, onlyId = change.id)
            assertEquals(1, result.confirmed)
            assertEquals(1, transport.sends)
            assertTrue(queue.list().none { it.id == change.id })
        } finally { if (queue.list().any { it.id == change.id }) queue.discard(change.id) }
    }

    @Test fun reviewedRetrySendsOnlyAfterFreshUnchangedUpdateCheck() = runBlocking {
        val queue = queue()
        val change = item()
        val transport = FakeTransport(binding, source, listOf(change.original))
        transport.onSend = { throw IOException("Synthetic lost response") }
        try {
            queue.enqueue(change)
            HaMutationDispatcher.flush(queue, transport, onlyId = change.id)
            assertEquals(1, transport.sends)
            transport.onSend = { transport.live = listOf(change.original.copy(summary = target.summary)) }
            val result = HaMutationDispatcher.flush(queue, transport, onlyId = change.id,
                reviewedRetryId = change.id)
            assertEquals(1, result.confirmed)
            assertEquals(2, transport.sends)
            assertTrue(queue.list().none { it.id == change.id })
        } finally { if (queue.list().any { it.id == change.id }) queue.discard(change.id) }
    }

    @Test fun reviewedRetryRefusesChangedOriginalOrAuthorization() = runBlocking {
        val queue = queue()
        val changed = item()
        val signedOut = item()
        try {
            queue.enqueue(changed)
            queue.enqueue(signedOut)
            queue.mark(changed.id, HaPendingState.AWAITING_CONFIRMATION)
            queue.mark(signedOut.id, HaPendingState.AWAITING_CONFIRMATION)
            val changedTransport = FakeTransport(binding, source,
                listOf(changed.original.copy(summary = "Changed elsewhere")))
            val accountTransport = FakeTransport("b".repeat(64), source, listOf(signedOut.original))
            assertEquals(1, HaMutationDispatcher.flush(queue, changedTransport,
                onlyId = changed.id, reviewedRetryId = changed.id).conflicts)
            assertEquals(1, HaMutationDispatcher.flush(queue, accountTransport,
                onlyId = signedOut.id, reviewedRetryId = signedOut.id).conflicts)
            assertEquals(0, changedTransport.sends)
            assertEquals(0, accountTransport.sends)
        } finally { queue.discard(changed.id); queue.discard(signedOut.id) }
    }

    @Test fun reviewedDeleteNeverResendsWhenAlreadyAbsent() = runBlocking {
        val queue = queue()
        val change = item(HaOperation.DELETE)
        val transport = FakeTransport(binding, source, emptyList())
        try {
            queue.enqueue(change)
            queue.mark(change.id, HaPendingState.AWAITING_CONFIRMATION)
            val result = HaMutationDispatcher.flush(queue, transport, onlyId = change.id,
                reviewedRetryId = change.id)
            assertEquals(1, result.needsReview)
            assertEquals(0, transport.sends)
            assertEquals(HaPendingState.AWAITING_CONFIRMATION,
                queue.list().single { it.id == change.id }.state)
        } finally { queue.discard(change.id) }
    }

    @Test fun reviewedRetryReconcilesVisibleTargetWithoutSecondSend() = runBlocking {
        val queue = queue()
        val change = item()
        val transport = FakeTransport(binding, source,
            listOf(change.original.copy(summary = target.summary)))
        try {
            queue.enqueue(change)
            queue.mark(change.id, HaPendingState.AWAITING_CONFIRMATION)
            val result = HaMutationDispatcher.flush(queue, transport, onlyId = change.id,
                reviewedRetryId = change.id)
            assertEquals(1, result.confirmed)
            assertEquals(0, transport.sends)
            assertTrue(queue.list().none { it.id == change.id })
        } finally { if (queue.list().any { it.id == change.id }) queue.discard(change.id) }
    }

    @Test fun offlineCreateRemainsEncryptedAndQueued() = runBlocking {
        val queue = queue()
        val change = createItem()
        val transport = FakeTransport(binding, source, emptyList()).apply { offline = true }
        try {
            queue.enqueue(change)
            assertEquals(null, queue().list().single { it.id == change.id }.original.uid)
            assertTrue(HaMutationDispatcher.flush(queue, transport, onlyId = change.id).networkFailure)
            assertEquals(HaPendingState.QUEUED, queue.list().single { it.id == change.id }.state)
            assertEquals(0, transport.sends)
            val raw = InstrumentationRegistry.getInstrumentation().targetContext
                .filesDir.resolve("pending-ha-v1.bin").readBytes()
            assertFalse(raw.toString(Charsets.UTF_8).contains("Create synthetic event"))
        } finally { queue.discard(change.id) }
    }

    @Test fun createConfirmsOnlyAfterUniqueProjection() = runBlocking {
        val queue = queue()
        val change = createItem()
        val transport = FakeTransport(binding, source, emptyList())
        transport.onSend = { command ->
            assertEquals("calendar/event/create", command.getString("type"))
            assertFalse(command.has("uid"))
            assertEquals(change.original.description,
                command.getJSONObject("event").getString("description"))
            assertEquals(change.original.location,
                command.getJSONObject("event").getString("location"))
            transport.live = listOf(change.original.copy(uid = "provider-created-uid"))
        }
        try {
            queue.enqueue(change)
            assertEquals(1, HaMutationDispatcher.flush(queue, transport, onlyId = change.id).confirmed)
            assertEquals(1, transport.sends)
            assertTrue(queue.list().none { it.id == change.id })
        } finally { if (queue.list().any { it.id == change.id }) queue.discard(change.id) }
    }

    @Test fun multiDayCreateKeepsExclusiveEndAndConfirmsExactProjection() = runBlocking {
        val queue = queue()
        val original = createItem()
        val change = original.copy(original = original.original.copy(end = "2026-11-16"))
        val transport = FakeTransport(binding, source, emptyList())
        transport.onSend = { command ->
            assertEquals("calendar/event/create", command.getString("type"))
            val event = command.getJSONObject("event")
            assertEquals("2026-11-12", event.getString("start"))
            assertEquals("2026-11-16", event.getString("end"))
            transport.live = listOf(change.original.copy(uid = "provider-multi-day-uid"))
        }
        try {
            queue.enqueue(change)
            assertEquals(1, HaMutationDispatcher.flush(queue, transport, onlyId = change.id).confirmed)
            assertEquals(1, transport.sends)
            assertTrue(queue.list().none { it.id == change.id })
            assertTrue(runCatching { HomeAssistantCalendarMutation.createCommand(source,
                change.original.copy(end = "2026-11-11"), emptyList()) }.isFailure)
        } finally { if (queue.list().any { it.id == change.id }) queue.discard(change.id) }
    }

    @Test fun multiDayCreateDoesNotConfirmWrongEndOrResend() = runBlocking {
        val queue = queue()
        val original = createItem()
        val change = original.copy(original = original.original.copy(end = "2026-11-16"))
        val transport = FakeTransport(binding, source, emptyList())
        transport.onSend = {
            transport.live = listOf(change.original.copy(uid = "provider-wrong-end",
                end = "2026-11-15"))
        }
        try {
            queue.enqueue(change)
            assertEquals(0, HaMutationDispatcher.flush(queue, transport, onlyId = change.id).confirmed)
            assertEquals(HaPendingState.ACCEPTED, queue.list().single { it.id == change.id }.state)
            assertEquals(1, HaMutationDispatcher.flush(queue, transport, onlyId = change.id).needsReview)
            assertEquals(1, transport.sends)
            transport.live = listOf(change.original.copy(uid = "provider-multi-day-uid"))
            assertEquals(1, HaMutationDispatcher.flush(queue, transport, onlyId = change.id).confirmed)
            assertEquals(1, transport.sends)
        } finally { if (queue.list().any { it.id == change.id }) queue.discard(change.id) }
    }

    @Test fun uncertainCreateIsNeverAutomaticallyReplayed() = runBlocking {
        val queue = queue()
        val change = createItem()
        val transport = FakeTransport(binding, source, emptyList())
        transport.onSend = { throw IOException("Synthetic lost response") }
        try {
            queue.enqueue(change)
            HaMutationDispatcher.flush(queue, transport, onlyId = change.id)
            assertEquals(HaPendingState.AWAITING_CONFIRMATION,
                queue.list().single { it.id == change.id }.state)
            assertEquals(1, HaMutationDispatcher.flush(queue, transport, onlyId = change.id).needsReview)
            assertEquals(1, transport.sends)
        } finally { queue.discard(change.id) }
    }

    @Test fun queuedCreateRefusesExistingMatchingEvent() = runBlocking {
        val queue = queue()
        val change = createItem()
        val transport = FakeTransport(binding, source,
            listOf(change.original.copy(uid = "existing-provider-uid")))
        try {
            queue.enqueue(change)
            assertEquals(1, HaMutationDispatcher.flush(queue, transport, onlyId = change.id).conflicts)
            assertEquals(0, transport.sends)
            assertEquals(HaPendingState.CONFLICT, queue.list().single { it.id == change.id }.state)
        } finally { queue.discard(change.id) }
    }

    @Test fun acceptedCreateWaitsForProjectionWithoutResend() = runBlocking {
        val queue = queue()
        val change = createItem()
        val transport = FakeTransport(binding, source, emptyList())
        try {
            queue.enqueue(change)
            val first = HaMutationDispatcher.flush(queue, transport, onlyId = change.id)
            assertEquals(0, first.confirmed)
            assertEquals(HaPendingState.ACCEPTED, queue.list().single { it.id == change.id }.state)
            transport.live = listOf(change.original.copy(uid = "provider-created-uid"))
            assertEquals(1, HaMutationDispatcher.flush(queue, transport, onlyId = change.id).confirmed)
            assertEquals(1, transport.sends)
        } finally { if (queue.list().any { it.id == change.id }) queue.discard(change.id) }
    }

    @Test fun backgroundContinuationDoesNotLoopOnAcceptedOnly() {
        assertTrue(HaBackgroundSync.shouldSchedule(listOf(HaPendingState.ACCEPTED), false))
        assertFalse(HaBackgroundSync.shouldSchedule(listOf(HaPendingState.ACCEPTED), true))
        assertTrue(HaBackgroundSync.shouldSchedule(listOf(HaPendingState.QUEUED), true))
        assertFalse(HaBackgroundSync.shouldSchedule(listOf(HaPendingState.AWAITING_CONFIRMATION), false))
    }

    @Test fun createCommandRejectsKnownDuplicateAndInvalidBounds() {
        val proposed = createItem().original
        assertTrue(runCatching { HomeAssistantCalendarMutation.createCommand(source, proposed,
            listOf(proposed.copy(uid = "existing"))) }.isFailure)
        assertTrue(runCatching { HomeAssistantCalendarMutation.createCommand(source,
            proposed.copy(end = proposed.start), emptyList()) }.isFailure)
        assertEquals("calendar/event/create", HomeAssistantCalendarMutation.createCommand(source,
            proposed, emptyList()).getString("type"))
    }

    @Test fun timedCreateMatchesEquivalentOffsetsOnly() {
        val proposed = createItem().original.copy(allDay = false,
            start = "2026-11-12T09:00:00-05:00", end = "2026-11-12T10:00:00-05:00")
        val sameInstant = proposed.copy(uid = "existing", start = "2026-11-12T14:00:00Z",
            end = "2026-11-12T15:00:00Z")
        assertTrue(HomeAssistantCalendarMutation.knownCreateDuplicate(proposed, sameInstant))
        assertTrue(runCatching { HomeAssistantCalendarMutation.createCommand(source, proposed,
            listOf(sameInstant)) }.isFailure)
        assertEquals("calendar/event/create", HomeAssistantCalendarMutation.createCommand(source,
            proposed, emptyList()).getString("type"))
    }

    @Test fun reviewedCreateRefusesMatchOrChangedAuthorization() = runBlocking {
        val queue = queue()
        val matched = createItem()
        val wrongAccount = createItem().copy(original = createItem().original.copy(
            summary = "Other synthetic creation"))
        try {
            queue.enqueue(matched)
            queue.enqueue(wrongAccount)
            queue.mark(matched.id, HaPendingState.AWAITING_CONFIRMATION)
            queue.mark(wrongAccount.id, HaPendingState.AWAITING_CONFIRMATION)
            val matchedTransport = FakeTransport(binding, source,
                listOf(matched.original.copy(uid = "provider-created-uid")))
            val accountTransport = FakeTransport("b".repeat(64), source, emptyList())
            assertEquals(1, HaMutationDispatcher.flush(queue, matchedTransport,
                onlyId = matched.id, reviewedRetryId = matched.id).needsReview)
            assertEquals(1, HaMutationDispatcher.flush(queue, accountTransport,
                onlyId = wrongAccount.id, reviewedRetryId = wrongAccount.id).conflicts)
            assertEquals(0, matchedTransport.sends)
            assertEquals(0, accountTransport.sends)
        } finally { queue.discard(matched.id); queue.discard(wrongAccount.id) }
    }

    @Test fun olderPendingEditWithLongOriginalTitleRemainsReadable() {
        val queue = queue()
        val change = item().copy(original = item().original.copy(summary = "S".repeat(300)))
        try {
            queue.enqueue(change)
            assertEquals(300, queue().list().single { it.id == change.id }.original.summary.length)
        } finally { queue.discard(change.id) }
    }

    @Test fun multiDayAllDayMoveChecksBothWindowsAndKeepsDuration() = runBlocking {
        val queue = queue()
        val original = this@PendingHaMutationsInstrumentedTest.original.copy(
            uid = UUID.randomUUID().toString(), end = "2026-11-15")
        val command = HomeAssistantCalendarMutation.updateCommand(source, original,
            listOf(original), "Moved synthetic event", newDate = LocalDate.parse("2026-12-02"))
        val event = command.getJSONObject("event")
        assertEquals("2026-12-02", event.getString("start"))
        assertEquals("2026-12-05", event.getString("end"))
        val target = HaTarget(event.getString("summary"), event.getString("start"),
            event.getString("end"), event.getString("description"), event.getString("location"))
        val change = PendingHaMutation(UUID.randomUUID(), binding, source.id, original,
            HaOperation.UPDATE, target)
        val transport = FakeTransport(binding, source, listOf(original)).apply { rangeAware = true }
        transport.onSend = { sent ->
            assertEquals(target.start, sent.getJSONObject("event").getString("start"))
            transport.live = listOf(original.copy(summary = target.summary,
                start = target.start, end = target.end))
        }
        try {
            queue.enqueue(change)
            assertEquals(1, HaMutationDispatcher.flush(queue, transport, onlyId = change.id).confirmed)
            assertEquals(1, transport.sends)
            assertTrue(transport.reads.any { it.first == LocalDate.parse("2026-11-11") })
            assertTrue(transport.reads.any { it.first == LocalDate.parse("2026-12-01") })
            assertTrue(queue.list().none { it.id == change.id })
        } finally { if (queue.list().any { it.id == change.id }) queue.discard(change.id) }
    }

    @Test fun allDayMoveRefusesDestinationDuplicateWithoutSending() = runBlocking {
        val queue = queue()
        val original = this@PendingHaMutationsInstrumentedTest.original.copy(
            uid = UUID.randomUUID().toString())
        val target = HaTarget(original.summary, "2026-12-02", "2026-12-03",
            original.description, original.location)
        val change = PendingHaMutation(UUID.randomUUID(), binding, source.id, original,
            HaOperation.UPDATE, target)
        val other = original.copy(uid = "other-synthetic-uid", start = target.start,
            end = target.end)
        val transport = FakeTransport(binding, source, listOf(original, other)).apply {
            rangeAware = true
        }
        try {
            queue.enqueue(change)
            assertEquals(1, HaMutationDispatcher.flush(queue, transport, onlyId = change.id).conflicts)
            assertEquals(0, transport.sends)
            assertEquals(HaPendingState.CONFLICT, queue.list().single { it.id == change.id }.state)
        } finally { queue.discard(change.id) }
    }

    @Test fun allDayMoveRefusesChangedDurationInSavedTarget() = runBlocking {
        val queue = queue()
        val original = this@PendingHaMutationsInstrumentedTest.original.copy(
            uid = UUID.randomUUID().toString(), end = "2026-11-15")
        val target = HaTarget(original.summary, "2026-12-02", "2026-12-04",
            original.description, original.location)
        val change = PendingHaMutation(UUID.randomUUID(), binding, source.id, original,
            HaOperation.UPDATE, target)
        val transport = FakeTransport(binding, source, listOf(original)).apply { rangeAware = true }
        try {
            queue.enqueue(change)
            assertEquals(1, HaMutationDispatcher.flush(queue, transport, onlyId = change.id).conflicts)
            assertEquals(0, transport.sends)
        } finally { queue.discard(change.id) }
    }

    @Test fun malformedSavedAllDayDestinationBecomesConflictWithoutSending() = runBlocking {
        val queue = queue()
        val original = this@PendingHaMutationsInstrumentedTest.original.copy(
            uid = UUID.randomUUID().toString())
        val change = PendingHaMutation(UUID.randomUUID(), binding, source.id, original,
            HaOperation.UPDATE, HaTarget(original.summary, "invalid-date", "2026-12-03",
                original.description, original.location))
        val transport = FakeTransport(binding, source, listOf(original))
        try {
            queue.enqueue(change)
            assertEquals(1, HaMutationDispatcher.flush(queue, transport, onlyId = change.id).conflicts)
            assertEquals(0, transport.sends)
        } finally { queue.discard(change.id) }
    }

    @Test fun acceptedAllDayMoveWaitsWhenOldEventVanishesBeforeNewProjection() = runBlocking {
        val queue = queue()
        val original = this@PendingHaMutationsInstrumentedTest.original.copy(
            uid = UUID.randomUUID().toString())
        val target = HaTarget(original.summary, "2026-12-02", "2026-12-03",
            original.description, original.location)
        val change = PendingHaMutation(UUID.randomUUID(), binding, source.id, original,
            HaOperation.UPDATE, target)
        val transport = FakeTransport(binding, source, listOf(original)).apply { rangeAware = true }
        transport.onSend = { transport.live = emptyList() }
        try {
            queue.enqueue(change)
            assertEquals(0, HaMutationDispatcher.flush(queue, transport, onlyId = change.id).confirmed)
            assertEquals(HaPendingState.ACCEPTED, queue.list().single { it.id == change.id }.state)
            assertEquals(1, HaMutationDispatcher.flush(queue, transport, onlyId = change.id).needsReview)
            assertEquals(1, transport.sends)
            transport.live = listOf(original.copy(start = target.start, end = target.end))
            assertEquals(1, HaMutationDispatcher.flush(queue, transport, onlyId = change.id).confirmed)
            assertTrue(queue.list().none { it.id == change.id })
        } finally { if (queue.list().any { it.id == change.id }) queue.discard(change.id) }
    }
}
