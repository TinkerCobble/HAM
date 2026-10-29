package com.tinkercobble.ham.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

data class HaFlushResult(val confirmed: Int, val conflicts: Int,
    val networkFailure: Boolean, val needsReview: Int)

/** Serializes foreground and worker delivery. Unknown sends are never replayed automatically. */
object HaMutationDispatcher {
    private val mutex = Mutex()
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    private fun sameOriginal(before: AgendaEvent, live: AgendaEvent?): Boolean =
        live != null && live.provider == CalendarProviderKind.HOME_ASSISTANT &&
            live.calendarId == before.calendarId && live.uid == before.uid &&
            live.summary == before.summary && live.start == before.start &&
            live.end == before.end && live.allDay == before.allDay &&
            live.description == before.description && live.location == before.location &&
            live.recurrenceId == null && live.recurrenceKnown && live.rrule == null

    private fun targetCommand(change: PendingHaMutation): JSONObject {
        val target = checkNotNull(change.target)
        return JSONObject().put("id", 1).put("type", "calendar/event/update")
            .put("entity_id", change.sourceId).put("uid", change.original.uid)
            .put("event", JSONObject().put("summary", target.summary)
                .put("start", target.start).put("end", target.end)
                .put("description", target.description).put("location", target.location))
    }

    /** A moved event can leave the old read window; check both dates before and after sending. */
    private suspend fun readForChange(transport: HaMutationTransport, source: CalendarSource,
        change: PendingHaMutation): List<AgendaEvent> {
        val originalDate = change.original.date
        val original = transport.events(source, originalDate.minusDays(1),
            originalDate.plusDays(2))
        val targetDate = if (change.operation == HaOperation.UPDATE && change.original.allDay)
            change.target?.start?.let { runCatching { LocalDate.parse(it) }.getOrNull() } else null
        return if (targetDate != null && targetDate != originalDate)
            distinctEvents(original + transport.events(source, targetDate.minusDays(1),
                targetDate.plusDays(2))) else original
    }

    private fun destinationDuplicate(change: PendingHaMutation,
        live: List<AgendaEvent>): Boolean {
        val target = change.target ?: return false
        if (change.operation != HaOperation.UPDATE || !change.original.allDay ||
            target.start == change.original.start) return false
        val proposed = change.original.copy(summary = target.summary, start = target.start,
            end = target.end)
        return runCatching { live.any { it.uid != change.original.uid &&
            HomeAssistantCalendarMutation.knownCreateDuplicate(proposed, it) } }.getOrDefault(false)
    }

    suspend fun flush(queue: PendingHaMutations, transport: HaMutationTransport,
        maxItems: Int = 2, onlyId: UUID? = null,
        reviewedRetryId: UUID? = null,
        shouldContinue: () -> Boolean = { true }): HaFlushResult = mutex.withLock {
        require(maxItems > 0)
        require(reviewedRetryId == null || reviewedRetryId == onlyId) {
            "A reviewed retry must target exactly one pending change"
        }
        var confirmed = 0
        var conflicts = 0
        var needsReview = 0
        for (change in queue.list().filter { it.state != HaPendingState.CONFLICT &&
            (onlyId == null || it.id == onlyId) &&
            (reviewedRetryId == null || it.id == reviewedRetryId &&
                it.state == HaPendingState.AWAITING_CONFIRMATION) }.take(maxItems)) {
            if (!shouldContinue()) return@withLock HaFlushResult(confirmed, conflicts, true, needsReview)
            if (transport.sessionBinding() != change.sessionBinding) {
                queue.mark(change.id, HaPendingState.CONFLICT)
                conflicts++
                continue
            }
            val source: CalendarSource
            val live: List<AgendaEvent>
            try {
                source = transport.calendars().singleOrNull {
                    it.id == change.sourceId && it.provider == CalendarProviderKind.HOME_ASSISTANT &&
                        (when (change.operation) {
                            HaOperation.CREATE -> it.canCreate
                            HaOperation.UPDATE -> it.canUpdate
                            HaOperation.DELETE -> it.canDelete
                        })
                } ?: run { queue.mark(change.id, HaPendingState.CONFLICT); conflicts++; continue }
                live = readForChange(transport, source, change)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock HaFlushResult(confirmed, conflicts, true, needsReview)
            }
            if (change.operation == HaOperation.CREATE) {
                val matches = live.filter {
                    HomeAssistantCalendarMutation.knownCreateDuplicate(change.original, it)
                }
                if (change.state == HaPendingState.ACCEPTED &&
                    HomeAssistantCalendarMutation.createdVisible(change.original, live)) {
                    queue.removeConfirmed(change.id)
                    confirmed++
                    continue
                }
                if (matches.isNotEmpty()) {
                    if (change.state == HaPendingState.QUEUED) {
                        queue.mark(change.id, HaPendingState.CONFLICT)
                        conflicts++
                    } else needsReview++
                    continue
                }
                if (change.state == HaPendingState.ACCEPTED) continue
                if (change.state == HaPendingState.AWAITING_CONFIRMATION &&
                    reviewedRetryId != change.id) {
                    needsReview++
                    continue
                }
                val command = try {
                    HomeAssistantCalendarMutation.createCommand(source, change.original, live)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    queue.mark(change.id, HaPendingState.CONFLICT)
                    conflicts++
                    continue
                }
                // HA create has no caller-supplied UID. Persist uncertainty before transport.
                queue.mark(change.id, HaPendingState.AWAITING_CONFIRMATION)
                try {
                    check(transport.sessionBinding() == change.sessionBinding) {
                        "Home Assistant sign-in changed"
                    }
                    transport.send(command)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    needsReview++
                    continue
                }
                queue.mark(change.id, HaPendingState.ACCEPTED)
                val after = try {
                    transport.events(source, change.original.date.minusDays(1),
                        change.original.date.plusDays(2))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { continue }
                if (HomeAssistantCalendarMutation.createdVisible(change.original, after)) {
                    queue.removeConfirmed(change.id)
                    confirmed++
                }
                continue
            }
            val sameUid = live.filter { it.calendarId == change.sourceId &&
                it.uid == change.original.uid }
            if (sameUid.size > 1) {
                queue.mark(change.id, HaPendingState.CONFLICT)
                conflicts++
                continue
            }
            if (destinationDuplicate(change, live)) {
                queue.mark(change.id, HaPendingState.CONFLICT)
                conflicts++
                continue
            }
            val current = sameUid.singleOrNull()
            val targetVisible = change.operation == HaOperation.UPDATE &&
                HomeAssistantCalendarMutation.updateVisible(source, change.original,
                    targetCommand(change), live)
            if (targetVisible || change.operation == HaOperation.DELETE && current == null &&
                change.state == HaPendingState.ACCEPTED) {
                queue.removeConfirmed(change.id)
                confirmed++
                continue
            }
            if (change.operation == HaOperation.DELETE && current == null) {
                if (change.state == HaPendingState.QUEUED) {
                    queue.mark(change.id, HaPendingState.CONFLICT)
                    conflicts++
                } else needsReview++
                continue
            }
            if (change.operation == HaOperation.UPDATE && current == null &&
                change.state != HaPendingState.QUEUED) {
                // The old date may disappear before the moved event appears at the new date.
                needsReview++
                continue
            }
            if (!sameOriginal(change.original, current)) {
                queue.mark(change.id, HaPendingState.CONFLICT)
                conflicts++
                continue
            }
            if (change.state == HaPendingState.AWAITING_CONFIRMATION &&
                reviewedRetryId != change.id) {
                needsReview++
                continue
            }
            if (change.state == HaPendingState.ACCEPTED) continue
            val command = try {
                when (change.operation) {
                    HaOperation.CREATE -> error("Create is handled before UID mutation")
                    HaOperation.UPDATE -> HomeAssistantCalendarMutation.savedUpdateCommand(source,
                        change.original, live, checkNotNull(change.target))
                    HaOperation.DELETE -> HomeAssistantCalendarMutation.deleteCommand(source,
                        change.original, live)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                queue.mark(change.id, HaPendingState.CONFLICT)
                conflicts++
                continue
            }
            // Persist uncertainty before opening the WebSocket. A crash cannot cause replay.
            queue.mark(change.id, HaPendingState.AWAITING_CONFIRMATION)
            try {
                check(transport.sessionBinding() == change.sessionBinding) {
                    "Home Assistant sign-in changed"
                }
                transport.send(command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                needsReview++
                continue
            }
            queue.mark(change.id, HaPendingState.ACCEPTED)
            val after = try { readForChange(transport, source, change) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { continue }
            val verified = when (change.operation) {
                HaOperation.CREATE -> false
                HaOperation.UPDATE -> HomeAssistantCalendarMutation.updateVisible(source,
                    change.original, command, after)
                HaOperation.DELETE -> after.none { it.calendarId == change.sourceId &&
                    it.uid == change.original.uid }
            }
            if (verified) { queue.removeConfirmed(change.id); confirmed++ }
            else if (after.any { it.calendarId == change.sourceId &&
                it.uid == change.original.uid && !sameOriginal(change.original, it) }) {
                queue.mark(change.id, HaPendingState.CONFLICT)
                conflicts++
            }
        }
        HaFlushResult(confirmed, conflicts, false, needsReview)
    }
}
