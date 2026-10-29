package com.tinkercobble.ham.data

import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DavFlushResult(val confirmed: Int, val conflicts: Int,
    val stoppedForRetry: Boolean, val networkFailure: Boolean = false,
    val conflictCause: DavConflictCause? = null)

/** Serialized transport for reviewed encrypted changes, in foreground or scheduled work. */
object DavMutationDispatcher {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    fun <T> exclusive(block: () -> T): T = synchronized(this, block)

    /** A verified detail-only edit cannot need schedule overlap checks. */
    internal fun requiresSchedulePreflight(base: ByteArray, payload: ByteArray,
        source: CalendarSource): Boolean {
        if (DavDetailsConflict.canReview(base, payload, source)) return false
        return DavEventPayload.timedSeriesScheduleChanged(base, payload) ||
            DavEventPayload.timedSingleScheduleChanged(base, payload) ||
            DavEventPayload.allDaySingleScheduleChanged(base, payload)
    }

    /** Recheck a moved date or time against other resources in the selected collection. */
    private fun preflightScheduleUpdate(account: DavAccount,
        collection: DavConnection.DiscoveredCalendar, change: PendingDavMutation,
        http: OkHttpClient) {
        val source = DavConnection.source(account, collection)
        val payload = checkNotNull(change.ics)
        val base = checkNotNull(change.baseIcs)
        val range = DavEventPayload.createSearchRange(payload)
        val proposed = IcsEventParser.parse(payload, source, range)
        val expected = proposed.events.firstOrNull()?.rrule?.let {
            RepeatSpec.supported(it)?.count ?: throw DavConflictException()
        } ?: 1
        val allDay = proposed.events.firstOrNull()?.allDay ?: throw DavConflictException()
        if (proposed.unsupportedRecurring != 0 || proposed.events.size != expected ||
            proposed.events.any { it.allDay != allDay || it.uid != proposed.events.first().uid })
            throw DavConflictException()
        val old = IcsEventParser.parse(base, source, DavEventPayload.createSearchRange(base))
        if (old.unsupportedRecurring != 0 || old.events.size != expected ||
            old.events.firstOrNull()?.uid != proposed.events.first().uid ||
            old.events.any { it.allDay != allDay || it.uid != proposed.events.first().uid })
            throw DavConflictException()
        val fresh = DavConnection.readEvents(account, collection, source, range, http)
        if (fresh.partial || fresh.resources.distinctBy { it.url }.size != fresh.resources.size)
            throw DavConflictException()
        val other = fresh.resources.filter { it.url != change.resourceUrl }
            .flatMap { resource ->
                val read = IcsEventParser.parse(resource.ics, source, range)
                if (read.unsupportedRecurring != 0) throw DavConflictException()
                read.events
            }
        if (other.any { it.uid == proposed.events.first().uid || proposed.events.any { candidate ->
                it.summary.trim().equals(candidate.summary.trim(), true) &&
                    it.allDay == candidate.allDay && (if (candidate.allDay)
                        it.start == candidate.start && it.end == candidate.end else
                        AgendaEvent.instant(it.start) == AgendaEvent.instant(candidate.start) &&
                            AgendaEvent.instant(it.end) == AgendaEvent.instant(candidate.end))
            } }) throw DavConflictException()
    }

    @Synchronized
    fun flush(account: DavAccount, queue: PendingDavMutations,
        http: OkHttpClient = client, maxItems: Int = Int.MAX_VALUE,
        accountStillCurrent: () -> Boolean = { true },
        shouldContinue: () -> Boolean = { true }): DavFlushResult {
        require(maxItems > 0)
        var confirmed = 0
        var conflicts = 0
        var conflictCause: DavConflictCause? = null
        for (change in queue.list().filter { it.accountId == account.id && it.state == DavPendingState.QUEUED }
            .take(maxItems)) {
            if (!accountStillCurrent() || !shouldContinue())
                return DavFlushResult(confirmed, conflicts, false)
            var currentCollection: DavConnection.DiscoveredCalendar? = null
            try {
                // Rediscover for every item: collection privileges can change between queued writes.
                val collection = DavConnection.listAccount(account, http).singleOrNull {
                    DavConnection.source(account, it).id == change.calendarId
                }
                val permitted = collection != null && when (change.operation) {
                    DavOperation.CREATE -> collection.canCreate
                    DavOperation.UPDATE -> collection.canUpdate
                    DavOperation.DELETE -> collection.canDelete
                }
                if (!permitted) {
                    queue.markConflict(change.id)
                    conflicts++
                    continue
                }
                val current = checkNotNull(collection)
                currentCollection = current
                when (change.operation) {
                    DavOperation.CREATE -> {
                        val payload = checkNotNull(change.ics)
                        val source = DavConnection.source(account, current)
                        val range = runCatching { DavEventPayload.createSearchRange(payload) }
                            .getOrElse { throw DavConflictException() }
                        val proposed = runCatching {
                            IcsEventParser.parse(payload, source, range).let { read ->
                                val expected = RepeatSpec.supported(read.events.firstOrNull()?.rrule)
                                    ?.count ?: 1
                                check(read.unsupportedRecurring == 0 && read.events.isNotEmpty() &&
                                    read.events.size == expected &&
                                    read.events.all { it.uid == "${change.id}@ham.tinkercobble.com" })
                                read.events
                            }
                        }.getOrElse { throw DavConflictException() }
                        val fresh = DavConnection.readEvents(account, current, source, range, http)
                        if (fresh.partial) throw DavConflictException()
                        val duplicate = fresh.events.any { event -> proposed.any { candidate ->
                            event.summary.trim().equals(candidate.summary.trim(), true) &&
                            event.allDay == candidate.allDay && (if (event.allDay)
                                event.start == candidate.start && event.end == candidate.end else
                                AgendaEvent.instant(event.start) == AgendaEvent.instant(candidate.start) &&
                                    AgendaEvent.instant(event.end) == AgendaEvent.instant(candidate.end))
                        }
                        }
                        if (duplicate) throw DavConflictException()
                        DavMutations.create(account, current, change.id, payload, http)
                    }
                    DavOperation.UPDATE -> {
                        val payload = checkNotNull(change.ics)
                        if (change.baseIcs == null && payload.toString(Charsets.UTF_8)
                                .contains(Regex("(?m)^RRULE[:;]"))) throw DavConflictException()
                        val source = DavConnection.source(account, current)
                        val scheduleChange = change.baseIcs?.let { base ->
                            runCatching {
                                requiresSchedulePreflight(base, payload, source)
                            }.getOrElse { throw DavConflictException() }
                        } ?: false
                        if (scheduleChange)
                            preflightScheduleUpdate(account, current, change, http)
                        DavMutations.update(account, current,
                            checkNotNull(change.resourceUrl), checkNotNull(change.expectedEtag),
                            payload, http)
                    }
                    DavOperation.DELETE -> DavMutations.delete(account, current,
                        checkNotNull(change.resourceUrl), checkNotNull(change.expectedEtag), http)
                }
                queue.removeConfirmed(change.id)
                confirmed++
            } catch (e: DavConflictException) {
                val acceptedEarlier = if (change.operation == DavOperation.CREATE ||
                    change.operation == DavOperation.UPDATE) {
                    try {
                        when (change.operation) {
                            DavOperation.CREATE -> DavMutations.confirmedCreate(account,
                                checkNotNull(currentCollection), change.id, checkNotNull(change.ics), http)
                            DavOperation.UPDATE -> DavMutations.confirmedUpdate(account,
                                checkNotNull(currentCollection), checkNotNull(change.resourceUrl),
                                checkNotNull(change.ics), http)
                            DavOperation.DELETE -> false
                        }
                    } catch (_: IOException) {
                        // A failed verification GET does not establish a conflicting server copy.
                        return DavFlushResult(confirmed, conflicts, true, networkFailure = true)
                    } catch (_: Exception) { false }
                } else false
                if (acceptedEarlier) {
                    queue.removeConfirmed(change.id)
                    confirmed++
                } else {
                    queue.markConflict(change.id)
                    conflicts++
                    if (conflictCause == null) conflictCause = e.reason
                }
            } catch (_: Exception) {
                // Unknown network outcomes retain the stable operation ID and do not make a second item.
                return DavFlushResult(confirmed, conflicts, true, networkFailure = true)
            }
        }
        val remaining = queue.list().any { it.accountId == account.id && it.state == DavPendingState.QUEUED }
        return DavFlushResult(confirmed, conflicts, remaining, conflictCause = conflictCause)
    }
}
