package com.tinkercobble.ham.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tinkercobble.ham.auth.HomeAssistantAuth
import com.tinkercobble.ham.auth.AuthIdentity
import com.tinkercobble.ham.auth.AuthRequest
import com.tinkercobble.ham.auth.ConnectionPolicy
import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.AndroidCalendarProvider
import com.tinkercobble.ham.data.IcsImportDraft
import com.tinkercobble.ham.data.DavAccounts
import com.tinkercobble.ham.data.DavAccountSummary
import com.tinkercobble.ham.data.DavConnection
import com.tinkercobble.ham.data.DavResourceStore
import com.tinkercobble.ham.data.DavKind
import com.tinkercobble.ham.data.DavEventPayload
import com.tinkercobble.ham.data.EventDraftDetails
import com.tinkercobble.ham.data.DavMutations
import com.tinkercobble.ham.data.DavConflictCause
import com.tinkercobble.ham.data.DavTitleConflict
import com.tinkercobble.ham.data.DavDetailsConflict
import com.tinkercobble.ham.data.DavSeriesValidation
import com.tinkercobble.ham.data.DavBackgroundSync
import com.tinkercobble.ham.data.DavForegroundRetry
import com.tinkercobble.ham.data.DavMutationDispatcher
import com.tinkercobble.ham.data.DavOperation
import com.tinkercobble.ham.data.DavPendingState
import com.tinkercobble.ham.data.IcsEventParser
import com.tinkercobble.ham.data.IcsConnection
import com.tinkercobble.ham.data.IcsSubscription
import com.tinkercobble.ham.data.IcsSubscriptionSummary
import com.tinkercobble.ham.data.IcsSubscriptions
import com.tinkercobble.ham.data.RepeatSpec
import com.tinkercobble.ham.data.PendingDavMutation
import com.tinkercobble.ham.data.PendingDavMutations
import com.tinkercobble.ham.data.PendingDavAgenda
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.CalendarPreferences
import com.tinkercobble.ham.data.CalendarPreferenceStore
import com.tinkercobble.ham.data.CalendarReadDiagnosticStore
import com.tinkercobble.ham.data.AvailabilitySelection
import com.tinkercobble.ham.data.AvailabilitySelectionStore
import com.tinkercobble.ham.data.SavedCalendarGroup
import com.tinkercobble.ham.data.SavedCalendarGroupStore
import com.tinkercobble.ham.data.SavedGroupMember
import com.tinkercobble.ham.data.SavedGroupSelection
import com.tinkercobble.ham.data.SavedGroupState
import com.tinkercobble.ham.data.CalendarPosition
import com.tinkercobble.ham.data.AgendaCache
import com.tinkercobble.ham.HamAgendaWidget
import com.tinkercobble.ham.data.CachedAgenda
import com.tinkercobble.ham.data.LegacyMigration
import com.tinkercobble.ham.data.CalendarSelection
import com.tinkercobble.ham.data.CalendarView
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.data.distinctEvents
import com.tinkercobble.ham.data.mergeCachedCalendarRead
import com.tinkercobble.ham.data.pruneRemovedCalendars
import com.tinkercobble.ham.data.retainUnverifiedPartialDav
import com.tinkercobble.ham.data.RefreshGeneration
import com.tinkercobble.ham.data.HomeAssistantClient
import com.tinkercobble.ham.data.HomeAssistantCalendarMutation
import com.tinkercobble.ham.data.HomeAssistantCreateDraft
import com.tinkercobble.ham.data.HaBackgroundSync
import com.tinkercobble.ham.data.HaMutationDispatcher
import com.tinkercobble.ham.data.HaOperation
import com.tinkercobble.ham.data.HaPendingState
import com.tinkercobble.ham.data.HaTarget
import com.tinkercobble.ham.data.PendingHaMutation
import com.tinkercobble.ham.data.PendingHaMutations
import com.tinkercobble.ham.data.TodoEntry
import com.tinkercobble.ham.data.TimedEventTime
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class DavPendingSummary(val id: UUID, val calendarId: String,
    val operation: DavOperation, val state: DavPendingState, val canReview: Boolean,
    val canReviewDetails: Boolean)

data class DavConflictReview(val id: UUID, val calendarName: String,
    val originalTitle: String, val serverTitle: String, val proposedTitle: String)

data class DavDeleteConflictReview(val id: UUID, val calendarName: String, val eventTitle: String)

data class DavDetailsConflictReview(val id: UUID, val calendarName: String,
    val eventTitle: String, val originalDescription: String, val originalLocation: String,
    val serverDescription: String, val serverLocation: String,
    val proposedDescription: String, val proposedLocation: String)

data class HaPendingSummary(val id: UUID, val calendarId: String, val title: String,
    val originalStart: String, val originalEnd: String,
    val originalDescription: String, val originalLocation: String, val target: HaTarget?,
    val operation: HaOperation, val state: HaPendingState)

class HamViewModel(application: Application) : AndroidViewModel(application) {
    private fun updateWidget() { runCatching { HamAgendaWidget.requestUpdate(getApplication()) } }
    private val client = HomeAssistantClient(application)
    private val calendarStore = CalendarPreferenceStore(application)
    private val agendaCache = AgendaCache(application)
    private val davStore = DavAccounts(application)
    private val icsStore = IcsSubscriptions(application)
    private val davResources = DavResourceStore(application)
    private val pendingDav = PendingDavMutations(application)
    private val pendingHa = PendingHaMutations(application)
    var haPending = androidx.compose.runtime.mutableStateOf<List<HaPendingSummary>>(emptyList())
    var haBusy = androidx.compose.runtime.mutableStateOf(false)
    var haMessage = androidx.compose.runtime.mutableStateOf("")
    private var haSyncJob: Job? = null
    private var lastHaAttemptElapsed = 0L
    var davAccounts = androidx.compose.runtime.mutableStateOf<List<DavAccountSummary>>(
        runCatching { davStore.summaries() }.getOrDefault(emptyList()))
    var icsSubscriptions = androidx.compose.runtime.mutableStateOf<List<IcsSubscriptionSummary>>(
        runCatching { icsStore.summaries() }.getOrDefault(emptyList()))
    var icsBusy = androidx.compose.runtime.mutableStateOf(false)
    var icsMessage = androidx.compose.runtime.mutableStateOf("")
    private val icsPrefs = application.getSharedPreferences("ham-ics-selection", android.content.Context.MODE_PRIVATE)
    var icsVisibleIds = androidx.compose.runtime.mutableStateOf(
        icsPrefs.getStringSet("visible", emptySet()).orEmpty().toSet())
    var davBusy = androidx.compose.runtime.mutableStateOf(false)
    var davMessage = androidx.compose.runtime.mutableStateOf("")
    var davPending = androidx.compose.runtime.mutableStateOf<List<DavPendingSummary>>(emptyList())
    var davConflictReview = androidx.compose.runtime.mutableStateOf<DavConflictReview?>(null)
    var davDetailsConflictReview = androidx.compose.runtime.mutableStateOf<DavDetailsConflictReview?>(null)
    var davDeleteConflictReview = androidx.compose.runtime.mutableStateOf<DavDeleteConflictReview?>(null)
    private var reviewedDavEtag: String? = null
    private var reviewedDavIcs: ByteArray? = null
    private var pendingDavDeletes = androidx.compose.runtime.mutableStateOf<Map<Pair<String, String>, DavPendingState>>(emptyMap())
    private var davSyncJob: Job? = null
    private var lastDavAttemptElapsed = 0L
    private var pendingDavDrafts = androidx.compose.runtime.mutableStateOf<List<PendingDavAgenda.Draft>>(emptyList())
    private suspend fun reloadPendingDav(range: DateRange = if (activeAgenda.value == "Calendar")
        calendarRange() else DateRange(selectedDate.value, selectedDate.value.plusDays(1))) {
        val sources = calendars.value
        val (summaries, drafts) = withContext(Dispatchers.IO) {
            val changes = pendingDav.list()
            changes.map { DavPendingSummary(it.id, it.calendarId, it.operation, it.state,
                it.state == DavPendingState.CONFLICT && it.baseIcs != null &&
                    sources.singleOrNull { source -> source.id == it.calendarId }?.let { source ->
                        when (it.operation) {
                            DavOperation.UPDATE -> it.ics?.let { ics ->
                                DavTitleConflict.canReview(it.baseIcs, ics, source) } == true
                            DavOperation.DELETE -> runCatching {
                                val parsed = IcsEventParser.parse(it.baseIcs, source)
                                parsed.unsupportedRecurring == 0 && parsed.events.size == 1 &&
                                    parsed.events.single().uid == it.uid
                            }.getOrDefault(false)
                            DavOperation.CREATE -> false
                        }
                    } == true,
                it.state == DavPendingState.CONFLICT && it.operation == DavOperation.UPDATE &&
                    it.baseIcs != null && it.ics != null &&
                    sources.singleOrNull { source -> source.id == it.calendarId }?.let { source ->
                        DavDetailsConflict.canReview(it.baseIcs, it.ics, source)
                    } == true) } to
                PendingDavAgenda.drafts(changes, sources, range)
        }
        davPending.value = summaries
        pendingDavDrafts.value = drafts
        pendingDavDeletes.value = withContext(Dispatchers.IO) {
            pendingDav.list().filter { it.operation == DavOperation.DELETE && !it.uid.isNullOrBlank() }
                .associate { (it.calendarId to checkNotNull(it.uid)) to it.state }
        }
    }
    fun connectDav(kind: DavKind, address: String, username: String, secret: String, done: () -> Unit) = viewModelScope.launch {
        davBusy.value = true
        davMessage.value = ""
        try {
            val verified = withContext(Dispatchers.IO) {
                DavConnection.verify(kind, address, username, secret)
            }
            withContext(Dispatchers.IO) {
                DavMutationDispatcher.exclusive {
                    davStore.save(kind, verified.home, username.trim(), secret)
                }
                runCatching { DavBackgroundSync.scheduleIfQueued(getApplication(), false) }
            }
            davAccounts.value = withContext(Dispatchers.IO) { davStore.summaries() }
            davMessage.value = "Calendar account verified; ${verified.calendars.size} calendar collections found"
            refresh()
            done()
        } catch (_: Exception) { davMessage.value = "Could not verify this calendar account. Check its address and app password." }
        finally { davBusy.value = false }
    }
    fun removeDav(id: String) = viewModelScope.launch {
        davBusy.value = true
        try {
            refreshJob?.cancel()
            refreshGeneration.next()
            withContext(Dispatchers.IO) {
                DavMutationDispatcher.exclusive {
                    davResources.removeAccount(id)
                    pendingDav.discardAccount(id)
                    davStore.remove(id)
                }
            }
            reloadPendingDav()
            davAccounts.value = withContext(Dispatchers.IO) { davStore.summaries() }
            val removed = "dav:$id:"
            val removedReminderSources = calendars.value.filter { it.id.startsWith(removed) }
            withContext(Dispatchers.IO) {
                runCatching { com.tinkercobble.ham.LocalReminderAlarms(getApplication())
                    .removeConfirmedSources(removedReminderSources) }
                removedReminderSources.forEach { source ->
                    runCatching { readDiagnostics.remove(source.id) }
                }
            }
            readDiagnosticsRevision.intValue++
            davVisibleIds.value = davVisibleIds.value.filterNot { it.startsWith(removed) }.toSet()
            davPrefs.edit().putStringSet("visible", davVisibleIds.value).apply()
            removeAvailability(availabilityIds.value.filter { it.startsWith(removed) }.toSet())
            calendars.value = calendars.value.filterNot { it.id.startsWith(removed) }
            events.value = events.value.filterNot { it.calendarId.startsWith(removed) }
            if (!savedGroupStorageError.value) saveGroupState(savedGroups.value.copy(groups =
                savedGroups.value.groups.map { group -> group.copy(members =
                    group.members.filterNot { it.sourceId.startsWith(removed) }) }))
            val snapshot = CachedAgenda(calendars.value, events.value)
            val cacheSaved = try {
                withContext(Dispatchers.IO) {
                    agendaCache.save(preferenceScope(), snapshot, selectedDate.value) }
                true
            } catch (_: Exception) {
                withContext(Dispatchers.IO) { agendaCache.clear() }
                false
            }
            davMessage.value = if (cacheSaved) "Calendar account removed from this device"
                else "Calendar account removed; offline calendar copy cleared"
            if (hasAgendaSource) refresh()
        } catch (_: Exception) { davMessage.value = "Could not remove calendar account" }
        finally { davBusy.value = false }
    }
    fun connectIcs(name: String, address: String, username: String,
        password: String, done: () -> Unit) = viewModelScope.launch {
        icsBusy.value = true
        icsMessage.value = ""
        try {
            require(name.trim().isNotEmpty() && name.trim().length <= 100) { "Enter a calendar name" }
            val verified = withContext(Dispatchers.IO) {
                val url = IcsConnection.normalize(address).toString()
                val item = IcsSubscription(UUID.randomUUID().toString(), name.trim(), url,
                    username.trim(), password)
                check(IcsConnection.fetch(item, IcsConnection.verificationRange()).unsupportedRecurring == 0) {
                    "Unsupported calendar recurrence"
                }
                url
            }
            withContext(Dispatchers.IO) { icsStore.save(name, verified, username.trim(), password) }
            icsSubscriptions.value = withContext(Dispatchers.IO) { icsStore.summaries() }
            icsMessage.value = "ICS feed verified. Choose it in Manage calendars to show its events."
            refresh()
            done()
        } catch (e: Exception) { icsMessage.value = if (e.message == "Unsupported calendar recurrence")
            "This ICS feed has recurrence HAM cannot safely show yet" else
            "Could not verify the HTTPS ICS feed. Check its address and access details." }
        finally { icsBusy.value = false }
    }
    fun removeIcs(id: String) = viewModelScope.launch {
        icsBusy.value = true
        try {
            refreshJob?.cancel()
            refreshGeneration.next()
            withContext(Dispatchers.IO) { icsStore.remove(id) }
            icsSubscriptions.value = withContext(Dispatchers.IO) { icsStore.summaries() }
            val sourceId = "ics:$id"
            val removedReminderSources = calendars.value.filter { it.id == sourceId }
            withContext(Dispatchers.IO) {
                runCatching { com.tinkercobble.ham.LocalReminderAlarms(getApplication())
                    .removeConfirmedSources(removedReminderSources) }
                runCatching { readDiagnostics.remove(sourceId) }
            }
            readDiagnosticsRevision.intValue++
            icsVisibleIds.value = icsVisibleIds.value - sourceId
            icsPrefs.edit().putStringSet("visible", icsVisibleIds.value).apply()
            removeAvailability(setOf(sourceId))
            calendars.value = calendars.value.filterNot { it.id == sourceId }
            events.value = events.value.filterNot { it.calendarId == sourceId }
            if (!savedGroupStorageError.value) saveGroupState(savedGroups.value.copy(groups =
                savedGroups.value.groups.map { group -> group.copy(members =
                    group.members.filterNot { it.sourceId == sourceId }) }))
            val saved = try {
                withContext(Dispatchers.IO) {
                    agendaCache.save(preferenceScope(), CachedAgenda(calendars.value, events.value), selectedDate.value)
                }
                true
            } catch (_: Exception) { withContext(Dispatchers.IO) { agendaCache.clear() }; false }
            icsMessage.value = if (saved) "ICS subscription removed from this device" else
                "ICS subscription removed; offline calendar copy cleared"
            if (hasAgendaSource) refresh()
        } catch (_: Exception) { icsMessage.value = "Could not remove ICS subscription" }
        finally { icsBusy.value = false }
    }
    fun syncDavPending() { startDavSync(manual = true) }
    private suspend fun reloadPendingHa() {
        haPending.value = withContext(Dispatchers.IO) {
            pendingHa.list().map { HaPendingSummary(it.id, it.sourceId, it.original.summary,
                it.original.start, it.original.end, it.original.description, it.original.location,
                it.target, it.operation, it.state) }
        }
    }
    fun syncHaPending() { startHaSync(manual = true) }
    fun syncHaPendingIfStaleOnResume() = viewModelScope.launch {
        try { reloadPendingHa() }
        catch (_: Exception) { haMessage.value = "Pending Home Assistant changes could not be opened" }
        startHaSync(manual = false)
    }
    private fun startHaSync(manual: Boolean) {
        if (haSyncJob?.isActive == true || haBusy.value) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!manual && !DavForegroundRetry.due(lastHaAttemptElapsed, now)) return
        if (haPending.value.none { it.state == HaPendingState.QUEUED ||
                it.state == HaPendingState.ACCEPTED || it.state == HaPendingState.AWAITING_CONFIRMATION }) return
        lastHaAttemptElapsed = now
        haBusy.value = true
        haSyncJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    HaMutationDispatcher.flush(pendingHa, client.mutationTransport())
                }
                reloadPendingHa()
                if (manual || result.confirmed > 0 || result.conflicts > 0) {
                    haMessage.value = when {
                        result.conflicts > 0 -> "Home Assistant changes need review; keep or discard them in Settings"
                        result.confirmed > 0 -> "${result.confirmed} Home Assistant changes confirmed"
                        result.networkFailure -> "Home Assistant changes remain on this device for retry"
                        else -> "Home Assistant changes are still pending confirmation"
                    }
                }
                if (result.confirmed > 0) refresh()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (manual) haMessage.value = "Could not check pending Home Assistant changes" }
            finally { haBusy.value = false }
        }
    }
    fun discardHaPending(id: UUID) = viewModelScope.launch {
        haBusy.value = true
        try {
            withContext(Dispatchers.IO) { HaMutationDispatcher.exclusive { pendingHa.discard(id) } }
            reloadPendingHa()
            haMessage.value = "Pending Home Assistant change discarded from this device"
        } catch (_: Exception) { haMessage.value = "Could not discard pending Home Assistant change" }
        finally { haBusy.value = false }
    }
    fun retryUncertainHaPending(id: UUID) = viewModelScope.launch {
        if (haBusy.value) return@launch
        haBusy.value = true
        try {
            val result = withContext(Dispatchers.IO) {
                HaMutationDispatcher.flush(pendingHa, client.mutationTransport(),
                    maxItems = 1, onlyId = id, reviewedRetryId = id)
            }
            reloadPendingHa()
            haMessage.value = when {
                result.confirmed > 0 -> "Home Assistant confirmed the reviewed change"
                result.conflicts > 0 -> "Calendar event or sign-in changed; the reviewed change was not sent"
                result.networkFailure -> "Could not reach Home Assistant; the reviewed change remains pending"
                haPending.value.any { it.id == id && it.state == HaPendingState.AWAITING_CONFIRMATION } ->
                    "The reviewed request is still uncertain; HAM will not automatically resend it"
                else -> "This Home Assistant change is no longer awaiting review"
            }
            if (result.confirmed > 0) refresh()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { haMessage.value = "Could not review the pending Home Assistant change" }
        finally { haBusy.value = false }
    }
    fun syncDavPendingIfStaleOnResume() = viewModelScope.launch {
        val previous = davPending.value.size
        try { reloadPendingDav() } catch (_: Exception) { davMessage.value = "Pending calendar changes could not be opened" }
        if (previous > davPending.value.size) refresh()
        refreshJob?.join()
        startDavSync(manual = false)
    }
    private fun startDavSync(manual: Boolean) {
        if (davSyncJob?.isActive == true || davBusy.value) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!manual && !DavForegroundRetry.due(lastDavAttemptElapsed, now)) return
        davBusy.value = true
        davSyncJob = viewModelScope.launch {
            try {
                val queuedAccounts = withContext(Dispatchers.IO) {
                    pendingDav.list().filter { it.state == DavPendingState.QUEUED }
                        .map { it.accountId }.distinct()
                }
                if (queuedAccounts.isNotEmpty()) lastDavAttemptElapsed = now
                val result = withContext(Dispatchers.IO) {
                    queuedAccounts.mapNotNull { accountId ->
                        davStore.account(accountId)?.let { account ->
                            DavMutationDispatcher.flush(account, pendingDav,
                                accountStillCurrent = { davStore.account(accountId) == account })
                        }
                    }
                }
                reloadPendingDav()
                val confirmed = result.sumOf { it.confirmed }
                val conflicts = davPending.value.count { it.state == DavPendingState.CONFLICT }
                if (manual || confirmed > 0 || conflicts > 0) davMessage.value = when {
                    conflicts > 0 -> "$conflicts calendar changes have a server conflict; keep or discard them in Settings"
                    davPending.value.isNotEmpty() -> "Calendar changes remain on this device for retry"
                    confirmed > 0 -> "$confirmed calendar changes synchronized"
                    else -> "No pending calendar changes"
                }
                if (confirmed > 0) refresh()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (manual) davMessage.value = "Could not synchronize pending calendar changes"
            } finally {
                davBusy.value = false
            }
        }
    }
    fun discardDavPending(id: UUID) = viewModelScope.launch {
        davBusy.value = true
        try {
            withContext(Dispatchers.IO) { pendingDav.discard(id) }
            reloadPendingDav()
            davMessage.value = "Pending calendar change discarded from this device"
        } catch (_: Exception) { davMessage.value = "Could not discard pending calendar change" }
        finally { davBusy.value = false }
    }
    fun dismissDavConflictReview() {
        davConflictReview.value = null
        reviewedDavEtag = null
        reviewedDavIcs = null
    }
    fun dismissDavDetailsConflictReview() {
        davDetailsConflictReview.value = null
        reviewedDavEtag = null
        reviewedDavIcs = null
    }
    fun dismissDavDeleteConflictReview() {
        davDeleteConflictReview.value = null
        reviewedDavEtag = null
        reviewedDavIcs = null
    }
    private fun currentDavConflict(id: UUID, operation: DavOperation = DavOperation.UPDATE): Triple<PendingDavMutation,
        Pair<com.tinkercobble.ham.data.DavAccount, DavConnection.DiscoveredCalendar>,
        DavMutations.CurrentItem> {
        val change = pendingDav.list().singleOrNull { it.id == id &&
            it.operation == operation && it.state == DavPendingState.CONFLICT &&
            it.baseIcs != null } ?: error("Calendar conflict is unavailable")
        val account = davStore.account(change.accountId) ?: error("Calendar account was removed")
        val collection = DavConnection.listAccount(account).singleOrNull {
            DavConnection.source(account, it).id == change.calendarId &&
                (if (operation == DavOperation.DELETE) it.canDelete else it.canUpdate)
        } ?: error("Calendar no longer permits editing")
        val current = DavMutations.readCurrent(account, collection, checkNotNull(change.resourceUrl))
        return Triple(change, account to collection, current)
    }
    fun reviewDavConflict(id: UUID) = viewModelScope.launch {
        davBusy.value = true
        dismissDavConflictReview()
        try {
            val (change, pair, current) = withContext(Dispatchers.IO) {
                DavMutationDispatcher.exclusive { currentDavConflict(id) }
            }
            val source = DavConnection.source(pair.first, pair.second)
            val review = withContext(Dispatchers.Default) {
                DavTitleConflict.review(checkNotNull(change.baseIcs), checkNotNull(change.ics),
                    current.ics, source)
            }
            reviewedDavEtag = current.etag
            reviewedDavIcs = current.ics
            davConflictReview.value = DavConflictReview(id, source.name, review.originalTitle,
                review.serverTitle, review.proposedTitle)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { davMessage.value = "This conflict cannot be safely reviewed. Refresh or discard the pending change." }
        finally { davBusy.value = false }
    }
    fun applyDavConflictReview(id: UUID) = viewModelScope.launch {
        val shown = davConflictReview.value?.takeIf { it.id == id } ?: return@launch
        val shownEtag = reviewedDavEtag ?: return@launch
        val shownIcs = reviewedDavIcs ?: return@launch
        davBusy.value = true
        dismissDavConflictReview()
        try {
            val result = withContext(Dispatchers.IO) {
                DavMutationDispatcher.exclusive {
                    val (change, pair, current) = currentDavConflict(id)
                    check(current.etag == shownEtag && current.ics.contentEquals(shownIcs)) {
                        "Calendar changed after review"
                    }
                    val source = DavConnection.source(pair.first, pair.second)
                    val review = DavTitleConflict.review(checkNotNull(change.baseIcs),
                        checkNotNull(change.ics), current.ics, source)
                    check(review.originalTitle == shown.originalTitle &&
                        review.serverTitle == shown.serverTitle &&
                        review.proposedTitle == shown.proposedTitle) { "Calendar changed after review" }
                    pendingDav.requeueReviewedTitle(id, checkNotNull(change.expectedEtag),
                        current.etag, current.ics, review.mergedIcs)
                    DavMutationDispatcher.flush(pair.first, pendingDav,
                        accountStillCurrent = { davStore.account(pair.first.id) == pair.first })
                }
            }
            reloadPendingDav()
            davMessage.value = when {
                result.confirmed > 0 -> "Reviewed title synchronized"
                result.conflicts > 0 -> "Server changed again; review the conflict before retrying"
                else -> "Reviewed title saved on this device for retry"
            }
            if (result.confirmed > 0) refresh()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { davMessage.value = "Calendar changed or could not be reached. Review the conflict again." }
        finally { davBusy.value = false }
    }
    fun reviewDavDetailsConflict(id: UUID) = viewModelScope.launch {
        davBusy.value = true
        dismissDavDetailsConflictReview()
        try {
            val (change, pair, current) = withContext(Dispatchers.IO) {
                DavMutationDispatcher.exclusive { currentDavConflict(id) }
            }
            val source = DavConnection.source(pair.first, pair.second)
            val review = withContext(Dispatchers.Default) {
                DavDetailsConflict.review(checkNotNull(change.baseIcs), checkNotNull(change.ics),
                    current.ics, source)
            }
            reviewedDavEtag = current.etag
            reviewedDavIcs = current.ics
            davDetailsConflictReview.value = DavDetailsConflictReview(id, source.name,
                review.eventTitle, review.originalDescription, review.originalLocation,
                review.serverDescription, review.serverLocation,
                review.proposedDescription, review.proposedLocation)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            davMessage.value = "This detail conflict cannot be safely reviewed. Refresh or discard the pending change."
        } finally { davBusy.value = false }
    }
    fun applyDavDetailsConflictReview(id: UUID) = viewModelScope.launch {
        val shown = davDetailsConflictReview.value?.takeIf { it.id == id } ?: return@launch
        val shownEtag = reviewedDavEtag ?: return@launch
        val shownIcs = reviewedDavIcs ?: return@launch
        davBusy.value = true
        dismissDavDetailsConflictReview()
        try {
            val result = withContext(Dispatchers.IO) {
                DavMutationDispatcher.exclusive {
                    val (change, pair, current) = currentDavConflict(id)
                    check(current.etag == shownEtag && current.ics.contentEquals(shownIcs)) {
                        "Calendar changed after review"
                    }
                    val source = DavConnection.source(pair.first, pair.second)
                    val review = DavDetailsConflict.review(checkNotNull(change.baseIcs),
                        checkNotNull(change.ics), current.ics, source)
                    check(source.name == shown.calendarName &&
                        review.eventTitle == shown.eventTitle &&
                        review.originalDescription == shown.originalDescription &&
                        review.originalLocation == shown.originalLocation &&
                        review.serverDescription == shown.serverDescription &&
                        review.serverLocation == shown.serverLocation &&
                        review.proposedDescription == shown.proposedDescription &&
                        review.proposedLocation == shown.proposedLocation) {
                        "Calendar changed after review"
                    }
                    pendingDav.requeueReviewedDetails(id, checkNotNull(change.expectedEtag),
                        current.etag, current.ics, review.mergedIcs)
                    DavMutationDispatcher.flush(pair.first, pendingDav,
                        accountStillCurrent = { davStore.account(pair.first.id) == pair.first })
                }
            }
            reloadPendingDav()
            davMessage.value = when {
                result.confirmed > 0 -> "Reviewed event details synchronized"
                result.conflicts > 0 -> when (result.conflictCause) {
                    DavConflictCause.INVALID_CALENDAR_DATA ->
                        "Calendar server rejected the event data; your draft remains for review"
                    DavConflictCause.UID_CONFLICT ->
                        "Calendar server reported an event identity conflict; your draft remains for review"
                    DavConflictCause.MISSING_ITEM ->
                        "Calendar event is missing on the server; your draft remains for review"
                    DavConflictCause.OTHER_PRECONDITION ->
                        "Calendar server rejected an update condition; your draft remains for review"
                    else -> "Server changed again; review the detail conflict before retrying"
                }
                else -> "Reviewed event details saved on this device for retry"
            }
            if (result.confirmed > 0) refresh()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            davMessage.value = "Calendar changed or could not be reached. Review the detail conflict again."
        } finally { davBusy.value = false }
    }
    fun reviewDavDeleteConflict(id: UUID) = viewModelScope.launch {
        davBusy.value = true
        dismissDavDeleteConflictReview()
        try {
            val (change, pair, current) = withContext(Dispatchers.IO) {
                DavMutationDispatcher.exclusive { currentDavConflict(id, DavOperation.DELETE) }
            }
            check(checkNotNull(change.baseIcs).contentEquals(current.ics)) {
                "Calendar item changed"
            }
            val source = DavConnection.source(pair.first, pair.second)
            val parsed = IcsEventParser.parse(current.ics, source)
            check(parsed.unsupportedRecurring == 0 && parsed.events.size == 1 &&
                parsed.events.single().uid == change.uid) { "Calendar item changed" }
            reviewedDavEtag = current.etag
            reviewedDavIcs = current.ics
            davDeleteConflictReview.value = DavDeleteConflictReview(id, source.name,
                parsed.events.single().summary)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { davMessage.value = "This removal cannot be safely retried; the calendar item may have changed." }
        finally { davBusy.value = false }
    }
    fun applyDavDeleteConflict(id: UUID) = viewModelScope.launch {
        val shown = davDeleteConflictReview.value?.takeIf { it.id == id } ?: return@launch
        val shownEtag = reviewedDavEtag ?: return@launch
        val shownIcs = reviewedDavIcs ?: return@launch
        davBusy.value = true
        dismissDavDeleteConflictReview()
        try {
            val result = withContext(Dispatchers.IO) {
                DavMutationDispatcher.exclusive {
                    val (change, pair, current) = currentDavConflict(id, DavOperation.DELETE)
                    check(current.etag == shownEtag && current.ics.contentEquals(shownIcs) &&
                        checkNotNull(change.baseIcs).contentEquals(current.ics)) {
                        "Calendar item changed after review"
                    }
                    val source = DavConnection.source(pair.first, pair.second)
                    val parsed = IcsEventParser.parse(current.ics, source)
                    check(parsed.unsupportedRecurring == 0 && parsed.events.size == 1 &&
                        parsed.events.single().uid == change.uid &&
                        parsed.events.single().summary == shown.eventTitle &&
                        source.name == shown.calendarName) { "Calendar item changed after review" }
                    pendingDav.requeueReviewedDelete(id, checkNotNull(change.expectedEtag),
                        current.etag, current.ics)
                    DavMutationDispatcher.flush(pair.first, pendingDav,
                        accountStillCurrent = { davStore.account(pair.first.id) == pair.first })
                }
            }
            reloadPendingDav()
            davMessage.value = when {
                result.confirmed > 0 -> "Reviewed removal synchronized"
                result.conflicts > 0 -> "Server changed again; review the removal before retrying"
                else -> "Reviewed removal saved on this device for retry"
            }
            if (result.confirmed > 0) refresh()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { davMessage.value = "Calendar item changed or could not be reached. Review the removal again." }
        finally { davBusy.value = false }
    }
    private val androidProvider = AndroidCalendarProvider(application)
    private val androidPrefs = application.getSharedPreferences("ham-android-calendars", android.content.Context.MODE_PRIVATE)
    var androidEnabled = androidx.compose.runtime.mutableStateOf(androidPrefs.getBoolean("enabled", false))
    var androidPermissionGranted = androidx.compose.runtime.mutableStateOf(androidProvider.canRead())
    var androidWritePermissionGranted = androidx.compose.runtime.mutableStateOf(androidProvider.canWrite())
    var hamLocalCalendarId = androidx.compose.runtime.mutableStateOf<String?>(null)
    var androidVisibleIds = androidx.compose.runtime.mutableStateOf(
        androidPrefs.getStringSet("visible", emptySet()).orEmpty().toSet())
    var androidIdentityReviewNeeded = androidx.compose.runtime.mutableStateOf(
        androidPrefs.getBoolean("identity_review_needed", false))
    fun acknowledgeAndroidIdentityReview() {
        if (androidPrefs.edit().putBoolean("identity_review_needed", false).commit()) {
            androidIdentityReviewNeeded.value = false
        }
    }
    private val davPrefs = application.getSharedPreferences("ham-dav-selection", android.content.Context.MODE_PRIVATE)
    var davVisibleIds = androidx.compose.runtime.mutableStateOf(
        davPrefs.getStringSet("visible", emptySet()).orEmpty().toSet())
    private fun preferenceScope() = client.server ?: "android-device"
    val hasAgendaSource get() = client.connected || (androidEnabled.value && androidPermissionGranted.value) ||
        davAccounts.value.isNotEmpty() || icsSubscriptions.value.isNotEmpty()
    private val initialPosition = calendarStore.loadPosition(preferenceScope())
    private val statusPrefs = application.getSharedPreferences("ham-connection-status", android.content.Context.MODE_PRIVATE)
    private val readDiagnostics = CalendarReadDiagnosticStore(application)
    private var availabilityStore = AvailabilitySelectionStore(application, preferenceScope())
    private var availabilityConfigured = availabilityStore.isConfigured()
    var availabilityIds = androidx.compose.runtime.mutableStateOf(availabilityStore.ids())
    var availabilityStorageError = androidx.compose.runtime.mutableStateOf(false)
    private val savedGroupStore = SavedCalendarGroupStore(application)
    var savedGroups = androidx.compose.runtime.mutableStateOf(SavedGroupState())
    var savedGroupStorageError = androidx.compose.runtime.mutableStateOf(false)
    private fun loadSavedGroupScope() {
        runCatching { savedGroupStore.load(preferenceScope()) }
            .onSuccess { savedGroups.value = it; savedGroupStorageError.value = false }
            .onFailure { savedGroups.value = SavedGroupState(); savedGroupStorageError.value = true }
    }
    val activeSavedGroup get() = savedGroups.value.groups.firstOrNull { it.id == savedGroups.value.activeId }
    private fun activeGroupIds(sources: List<CalendarSource>) =
        SavedGroupSelection.activeSourceIds(savedGroups.value, sources)
    private fun saveGroupState(next: SavedGroupState): Boolean {
        val checked = runCatching { next.validated() }.getOrNull() ?: return false
        return try {
        check(!savedGroupStorageError.value) { "Saved calendar groups could not be opened" }
        savedGroupStore.save(preferenceScope(), checked)
        savedGroups.value = checked
        true
        } catch (_: Exception) { savedGroupStorageError.value = true; false }
    }
    fun setActiveSavedGroup(id: String?) {
        if (id != null && savedGroups.value.groups.none { it.id == id }) return
        if (saveGroupState(savedGroups.value.copy(activeId = id))) refresh()
    }
    fun saveSavedGroup(name: String, ids: Set<String>, existingId: String? = null): Boolean {
        val sources = calendars.value.filter { it.id in ids }
        if (sources.size != ids.size || ids.isEmpty()) return false
        val candidate = runCatching { SavedCalendarGroup(existingId ?: UUID.randomUUID().toString(),
            name, sources.map(SavedGroupMember::from)).validated() }.getOrNull() ?: return false
        val old = savedGroups.value.groups
        if (existingId != null && old.none { it.id == existingId }) return false
        val next = savedGroups.value.copy(groups = if (existingId == null) old + candidate else
            old.map { if (it.id == existingId) candidate else it })
        if (!saveGroupState(next)) return false
        if (next.activeId == candidate.id) refresh()
        return true
    }
    fun removeSavedGroup(id: String): Boolean {
        if (savedGroups.value.groups.none { it.id == id }) return false
        val next = savedGroups.value.copy(groups = savedGroups.value.groups.filterNot { it.id == id },
            activeId = savedGroups.value.activeId.takeUnless { it == id })
        if (!saveGroupState(next)) return false
        refresh()
        return true
    }
    private fun loadAvailabilityScope() {
        availabilityStore = AvailabilitySelectionStore(getApplication(), preferenceScope())
        availabilityConfigured = availabilityStore.isConfigured()
        availabilityIds.value = availabilityStore.ids()
        availabilityStorageError.value = false
    }
    var readDiagnosticsRevision = androidx.compose.runtime.mutableIntStateOf(0)
    var readDiagnosticsUnavailable = androidx.compose.runtime.mutableStateOf(false)
    var connected = androidx.compose.runtime.mutableStateOf(client.connected)
    var demoPreview = androidx.compose.runtime.mutableStateOf(false)
    var server = androidx.compose.runtime.mutableStateOf(client.server.orEmpty())
    var clientId = androidx.compose.runtime.mutableStateOf(client.clientId.orEmpty())
    var authRequest = androidx.compose.runtime.mutableStateOf<AuthRequest?>(null)
    var lastSync = androidx.compose.runtime.mutableStateOf(statusPrefs.getLong("last_sync", 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli))
    var lastConnection = androidx.compose.runtime.mutableStateOf(statusPrefs.getLong("last_connection", 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli))
    var selectedDate = androidx.compose.runtime.mutableStateOf(initialPosition.selected)
    var actualToday = androidx.compose.runtime.mutableStateOf(LocalDate.now())
    var dayBrowsing = androidx.compose.runtime.mutableStateOf(initialPosition.selected != LocalDate.now())
    var calendarAnchor = androidx.compose.runtime.mutableStateOf(initialPosition.anchor)
    var monthSelectedDate = androidx.compose.runtime.mutableStateOf(initialPosition.monthDay)
    var calendarView = androidx.compose.runtime.mutableStateOf(calendarStore.load(preferenceScope()).view)
    var activeAgenda = androidx.compose.runtime.mutableStateOf(initialPosition.agenda)
    var navigationDirection = androidx.compose.runtime.mutableIntStateOf(0)
    var failedCalendars = androidx.compose.runtime.mutableStateOf<Map<String, String>>(emptyMap())
    var loadedRange = androidx.compose.runtime.mutableStateOf<DateRange?>(null)
    private var refreshJob: Job? = null
    private var initialCacheRestored = false
    private val refreshMutex = Mutex()
    private val refreshGeneration = RefreshGeneration()
    var calendarPreferences = androidx.compose.runtime.mutableStateOf(calendarStore.load(preferenceScope()))
    val visibleEvents get() = searchableEvents(includeHidden = false)
    val availabilityEvents get() = AvailabilitySelection.events(
        PendingDavAgenda.merge(events.value, pendingDavDrafts.value), calendars.value,
        availabilityIds.value)
    fun setAvailabilityIncluded(source: CalendarSource, included: Boolean) {
        if (calendars.value.none { it.id == source.id }) return
        val next = if (included) availabilityIds.value + source.id else availabilityIds.value - source.id
        val fingerprints = availabilityStore.androidFingerprints().toMutableMap()
        if (source.provider == CalendarProviderKind.ANDROID) {
            if (included) {
                val fingerprint = source.identityFingerprint ?: return
                fingerprints[source.id] = fingerprint
            } else fingerprints.remove(source.id)
        }
        try {
            availabilityStore.save(next, fingerprints.filterKeys { it in next })
            availabilityIds.value = next
            availabilityConfigured = true
            availabilityStorageError.value = false
            refresh()
        } catch (_: Exception) { availabilityStorageError.value = true }
    }
    private fun removeAvailability(ids: Set<String>) {
        val next = availabilityIds.value - ids
        if (next == availabilityIds.value) return
        try {
            availabilityStore.save(next,
                availabilityStore.androidFingerprints().filterKeys { it in next })
            availabilityIds.value = next
            availabilityStorageError.value = false
        } catch (_: Exception) { availabilityStorageError.value = true }
    }
    fun pendingStateFor(event: AgendaEvent) = PendingDavAgenda.stateFor(event, pendingDavDrafts.value)
    fun pendingDeleteStateFor(event: AgendaEvent) = event.uid?.let {
        pendingDavDeletes.value[event.calendarId to it]
    }
    fun hasPendingDavResource(event: AgendaEvent) =
        PendingDavAgenda.hasPendingResource(event, pendingDavDrafts.value) ||
            pendingDeleteStateFor(event) != null
    fun searchableEvents(includeHidden: Boolean): List<AgendaEvent> {
        val baseline = CalendarSelection.visibleEvents(
        PendingDavAgenda.merge(events.value, pendingDavDrafts.value),
        calendarPreferences.value, androidEnabled.value, androidPermissionGranted.value,
        androidVisibleIds.value, includeHidden, davVisibleIds.value, icsVisibleIds.value)
        if (activeSavedGroup == null) return baseline
        val allowed = activeGroupIds(calendars.value)
        return PendingDavAgenda.merge(events.value, pendingDavDrafts.value).filter { event ->
            event.calendarId in allowed && (event.provider != CalendarProviderKind.ANDROID ||
                androidEnabled.value && androidPermissionGranted.value)
        }
    }
    private fun savePosition() = calendarStore.savePosition(preferenceScope(),
        CalendarPosition(selectedDate.value, calendarAnchor.value, monthSelectedDate.value, activeAgenda.value))
    fun setCalendarPreferences(value: CalendarPreferences) {
        calendarPreferences.value = value
        calendarView.value = value.view
        calendarStore.save(preferenceScope(), value)
        updateWidget()
    }
    fun enableAndroidCalendars() {
        androidPermissionGranted.value = androidProvider.canRead()
        if (!androidPermissionGranted.value) return
        androidEnabled.value = true
        androidPrefs.edit().putBoolean("enabled", true).apply()
        message.value = "Android calendars found on this device. Choose which ones to show in Calendars."
        refresh()
    }
    fun createLocalCalendar() = viewModelScope.launch {
        loading.value = true
        try {
            check(androidEnabled.value && androidProvider.canRead() && androidProvider.canWrite()) {
                "Connect Android calendars and allow editing first"
            }
            val local = withContext(Dispatchers.IO) { androidProvider.createLocalCalendar() }
            hamLocalCalendarId.value = local.id
            message.value = "HAM local calendar is ready. Select it in Manage calendars to show events."
            refresh()
        } catch (e: Exception) { message.value = e.message ?: "Could not create the local calendar" }
        finally { loading.value = false }
    }
    fun removeEmptyLocalCalendar() = viewModelScope.launch {
        loading.value = true
        try {
            val id = hamLocalCalendarId.value ?: error("No HAM local calendar is available")
            val source = calendars.value.singleOrNull { it.id == id }
                ?: error("Refresh Android calendars before removing the local calendar")
            withContext(Dispatchers.IO) { androidProvider.removeEmptyLocalCalendar(source) }
            hamLocalCalendarId.value = null
            message.value = "Empty HAM local calendar removed from this device"
            refresh()
        } catch (e: Exception) { message.value = e.message ?: "Could not remove the HAM local calendar" }
        finally { loading.value = false }
    }
    fun disableAndroidCalendars() {
        androidEnabled.value = false
        hamLocalCalendarId.value = null
        androidPrefs.edit().putBoolean("enabled", false).apply()
        calendars.value = calendars.value.filter { it.provider != CalendarProviderKind.ANDROID }
        events.value = events.value.filter { it.provider != CalendarProviderKind.ANDROID }
        if (hasAgendaSource) refresh() else clearLoadedInformation()
        updateWidget()
    }
    fun isCalendarVisible(source: CalendarSource): Boolean = when (source.provider) {
        CalendarProviderKind.ANDROID -> source.id in androidVisibleIds.value
        CalendarProviderKind.CALDAV -> source.id in davVisibleIds.value
        CalendarProviderKind.ICS -> source.id in icsVisibleIds.value
        CalendarProviderKind.HOME_ASSISTANT -> source.id !in calendarPreferences.value.hidden
    }
    fun setCalendarVisible(source: CalendarSource, visible: Boolean) {
        if (source.provider == CalendarProviderKind.ANDROID) {
            val next = if (visible) androidVisibleIds.value + source.id else androidVisibleIds.value - source.id
            androidVisibleIds.value = next
            androidPrefs.edit().putStringSet("visible", next).apply()
            refresh()
        } else if (source.provider == CalendarProviderKind.CALDAV) {
            val next = if (visible) davVisibleIds.value + source.id else davVisibleIds.value - source.id
            davVisibleIds.value = next
            davPrefs.edit().putStringSet("visible", next).apply()
            if (!visible) viewModelScope.launch(Dispatchers.IO) {
                try { davResources.removeCalendar(source.id.substringAfter("dav:").substringBefore(":"), source.id) }
                catch (_: Exception) { davResources.clear() }
            }
            refresh()
        } else if (source.provider == CalendarProviderKind.ICS) {
            val next = if (visible) icsVisibleIds.value + source.id else icsVisibleIds.value - source.id
            icsVisibleIds.value = next
            icsPrefs.edit().putStringSet("visible", next).apply()
            refresh()
        } else setCalendarPreferences(calendarPreferences.value.copy(hidden = if (visible)
            calendarPreferences.value.hidden - source.id else calendarPreferences.value.hidden + source.id))
        updateWidget()
    }
    fun setAllCalendarsVisible(visible: Boolean) {
        val androidIds = calendars.value.filter { it.provider == CalendarProviderKind.ANDROID }.map { it.id }.toSet()
        val next = if (visible) androidVisibleIds.value + androidIds else androidVisibleIds.value - androidIds
        androidVisibleIds.value = next
        androidPrefs.edit().putStringSet("visible", next).apply()
        val davIds = calendars.value.filter { it.provider == CalendarProviderKind.CALDAV }.map { it.id }.toSet()
        val nextDav = if (visible) davVisibleIds.value + davIds else davVisibleIds.value - davIds
        davVisibleIds.value = nextDav
        davPrefs.edit().putStringSet("visible", nextDav).apply()
        val icsIds = calendars.value.filter { it.provider == CalendarProviderKind.ICS }.map { it.id }.toSet()
        val nextIcs = if (visible) icsVisibleIds.value + icsIds else icsVisibleIds.value - icsIds
        icsVisibleIds.value = nextIcs
        icsPrefs.edit().putStringSet("visible", nextIcs).apply()
        if (!visible) viewModelScope.launch(Dispatchers.IO) { runCatching { davResources.clear() } }
        val ha = calendars.value.filter { it.provider == CalendarProviderKind.HOME_ASSISTANT }
        setCalendarPreferences(if (visible) calendarPreferences.value.showAll(ha)
            else calendarPreferences.value.hideAll(ha))
        updateWidget()
        refresh()
    }
    /** Called only after an explicit tap in Settings; the old installation remains intact. */
    fun importLegacySettings(): Boolean {
        val result = LegacyMigration(getApplication()).importSettings()
        server.value = result.server
        calendarPreferences.value = result.preferences
        calendarView.value = result.preferences.view
        message.value = if (result.hasPendingDraft)
            "Settings copied. Finish the pending screenshot draft in the previous HAM app before removing it. Sign in to Home Assistant here."
        else "Calendar settings copied. Sign in to Home Assistant here; the previous app remains installed."
        return result.hasPendingDraft
    }
    fun setView(value: CalendarView) {
        navigationDirection.intValue = 0
        setCalendarPreferences(calendarPreferences.value.copy(view = value))
        savePosition()
        refresh()
    }
    fun updateActualToday() {
        val current = LocalDate.now()
        val previous = actualToday.value
        if (current != previous) {
            actualToday.value = current
            if (!dayBrowsing.value && selectedDate.value == previous) {
                selectedDate.value = current
                savePosition()
                if (activeAgenda.value == "Today") refresh()
            }
        }
    }
    fun enterCalendar() {
        navigationDirection.intValue = 0
        activeAgenda.value = "Calendar"
        savePosition()
        refresh()
    }
    fun enterToday() {
        activeAgenda.value = "Today"
        today()
    }
    fun calendarRange() = calendarView.value.range(calendarAnchor.value)
    fun moveCalendar(direction: Long) {
        navigationDirection.intValue = direction.toInt()
        calendarAnchor.value = calendarView.value.move(calendarAnchor.value, direction)
        if (calendarView.value == CalendarView.MONTH) monthSelectedDate.value = calendarAnchor.value.withDayOfMonth(1)
        savePosition()
        refresh()
    }
    fun selectMonthDay(day: LocalDate) { monthSelectedDate.value = day; savePosition() }
    fun selectedWritable(id: String?) = CalendarSelection.resolveWritable(id, calendars.value)
    fun onAndroidWritePermissionResult() {
        androidWritePermissionGranted.value = androidProvider.canWrite()
        if (androidEnabled.value && androidPermissionGranted.value) refresh()
    }
    var calendars = androidx.compose.runtime.mutableStateOf<List<CalendarSource>>(emptyList())
    var events = androidx.compose.runtime.mutableStateOf<List<AgendaEvent>>(emptyList())
    var tasks = androidx.compose.runtime.mutableStateOf<List<TodoEntry>>(emptyList())
    var taskError = androidx.compose.runtime.mutableStateOf("")
    var loading = androidx.compose.runtime.mutableStateOf(client.connected)
    var message = androidx.compose.runtime.mutableStateOf("")

    init { loadSavedGroupScope(); if (hasAgendaSource) viewModelScope.launch {
        val serial = refreshGeneration.next()
        val cached = withContext(Dispatchers.IO) { agendaCache.load(preferenceScope()) }
        initialCacheRestored = true
        if (!refreshGeneration.accepts(serial) || !hasAgendaSource) return@launch
        if (cached != null) {
            calendars.value = cached.calendars
            events.value = cached.events
        }
        try { reloadPendingDav() }
        catch (_: Exception) { davMessage.value = "Pending calendar changes could not be opened" }
        withContext(Dispatchers.IO) {
            runCatching { DavBackgroundSync.scheduleIfQueued(getApplication(), false) }
        }
        refresh()
    } else {
        initialCacheRestored = true
        viewModelScope.launch {
            try { reloadPendingDav() }
            catch (_: Exception) { davMessage.value = "Pending calendar changes could not be opened" }
            withContext(Dispatchers.IO) {
                runCatching { DavBackgroundSync.scheduleIfQueued(getApplication(), false) }
            }
        }
    } }
    fun refreshIfStaleOnResume() {
        if (!initialCacheRestored || !hasAgendaSource || refreshJob?.isActive == true) return
        val last = lastSync.value
        if (last == null || last.isBefore(Instant.now().minusSeconds(5 * 60))) refresh()
    }
    fun refresh() {
        androidPermissionGranted.value = androidProvider.canRead()
        if (androidEnabled.value && !androidPermissionGranted.value) {
            hamLocalCalendarId.value = null
            calendars.value = calendars.value.filter { it.provider != CalendarProviderKind.ANDROID }
            events.value = events.value.filter { it.provider != CalendarProviderKind.ANDROID }
        }
        if (!hasAgendaSource) return
        refreshJob?.cancel()
        val serial = refreshGeneration.next()
        val range = if (activeAgenda.value == "Calendar") calendarRange()
            else DateRange(selectedDate.value, selectedDate.value.plusDays(1))
        refreshJob = viewModelScope.launch { refreshMutex.withLock {
        if (!refreshGeneration.accepts(serial)) return@withLock
        loading.value = true
        try {
            val priorHa = calendars.value.filter { it.provider == CalendarProviderKind.HOME_ASSISTANT }
            val priorAndroid = calendars.value.filter { it.provider == CalendarProviderKind.ANDROID }
            val priorDav = calendars.value.filter { it.provider == CalendarProviderKind.CALDAV }
            val priorIcs = calendars.value.filter { it.provider == CalendarProviderKind.ICS }
            var haSources = priorHa
            var androidSources = priorAndroid
            val davSources = mutableListOf<CalendarSource>()
            val icsSources = mutableListOf<CalendarSource>()
            var haDiscoverySucceeded = false
            var androidDiscoverySucceeded = false
            var icsDiscoverySucceeded = false
            var hasIcsSubscriptions = false
            val failures = mutableMapOf<String, String>()
            val succeeded = mutableSetOf<String>()
            val received = mutableListOf<AgendaEvent>()
            val partialDavEvents = mutableListOf<AgendaEvent>()
            val partialDavIds = mutableSetOf<String>()
            val davResourceReads = mutableListOf<Triple<String, String, List<DavConnection.Resource>>>()
            val davDiscovered = mutableListOf<Pair<String, Set<String>>>()
            if (client.connected) {
                try {
                    haSources = client.calendars()
                    haDiscoverySucceeded = true
                    val read = client.readEvents(haSources, range.start, range.endExclusive)
                    succeeded += read.succeeded
                    received += read.events
                    failures += read.failed
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    priorHa.forEach { failures[it.id] = "Home Assistant unavailable" }
                }
            } else haSources = emptyList()
            if (androidEnabled.value && androidPermissionGranted.value) {
                try {
                    androidSources = withContext(Dispatchers.IO) { androidProvider.calendars() }
                    hamLocalCalendarId.value = withContext(Dispatchers.IO) {
                        androidProvider.localCalendar()?.id }
                    androidDiscoverySucceeded = true
                    val bound = CalendarSelection.parseAndroidFingerprints(
                        androidPrefs.getString("fingerprints", null))
                    val reconciled = CalendarSelection.reconcileAndroidChoices(
                        calendarPreferences.value, androidVisibleIds.value, androidSources,
                        priorAndroid, bound)
                    if (reconciled.choicesRemoved) {
                        withContext(Dispatchers.IO) {
                            check(androidPrefs.edit().putBoolean("identity_review_needed", true).commit()) {
                                "Could not save Android calendar review notice"
                            }
                        }
                        androidIdentityReviewNeeded.value = true
                    }
                    if (reconciled.preferences != calendarPreferences.value) {
                        withContext(Dispatchers.IO) { calendarStore.save(preferenceScope(),
                            reconciled.preferences, durable = true) }
                        calendarPreferences.value = reconciled.preferences
                    }
                    if (reconciled.visibleIds != androidVisibleIds.value ||
                        reconciled.fingerprints != bound) {
                        withContext(Dispatchers.IO) {
                            check(androidPrefs.edit()
                                .putStringSet("visible", reconciled.visibleIds)
                                .putString("fingerprints", CalendarSelection.encodeAndroidFingerprints(
                                    reconciled.fingerprints)).commit()) {
                                "Could not save Android calendar identity choices"
                            }
                        }
                        androidVisibleIds.value = reconciled.visibleIds
                    }
                    val groupIds = activeGroupIds(androidSources)
                    received += withContext(Dispatchers.IO) { androidProvider.events(
                        androidSources.filter { it.id in androidVisibleIds.value ||
                            it.id in availabilityIds.value || it.id in groupIds }, range) }
                    succeeded += androidSources.map { it.id }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    priorAndroid.forEach { failures[it.id] = "Android calendar unavailable" }
                }
            } else { androidSources = emptyList(); hamLocalCalendarId.value = null }
            val davAccountsNow = withContext(Dispatchers.IO) { davStore.accounts() }
            for (account in davAccountsNow) {
                val prefix = "dav:${account.id}:"
                try {
                    val collections = withContext(Dispatchers.IO) { DavConnection.listAccount(account) }
                    val discovered = collections.map { DavConnection.source(account, it) }
                    davSources += discovered
                    val present = discovered.map { it.id }.toSet()
                    val selected = davVisibleIds.value.filter { it.startsWith(prefix) }.toSet()
                    if (selected != selected.intersect(present)) {
                        davVisibleIds.value = davVisibleIds.value - (selected - present)
                        davPrefs.edit().putStringSet("visible", davVisibleIds.value).apply()
                    }
                    val groupIds = activeGroupIds(discovered)
                    davDiscovered += account.id to present.intersect(
                        davVisibleIds.value + availabilityIds.value + groupIds)
                    for ((collection, source) in collections.zip(discovered)) {
                        if (source.id !in davVisibleIds.value && source.id !in availabilityIds.value &&
                            source.id !in groupIds) {
                            succeeded += source.id
                            continue
                        }
                        try {
                            val read = withContext(Dispatchers.IO) {
                                DavConnection.readEvents(account, collection, source, range) }
                            if (read.partial) {
                                failures[source.id] = "Some CalDAV items could not be read"
                                partialDavIds += source.id
                                received += read.events
                                partialDavEvents += read.events
                            } else {
                                received += read.events
                                succeeded += source.id
                                davResourceReads += Triple(account.id, source.id, read.resources)
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { failures[source.id] = "CalDAV calendar unavailable" }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    val retained = priorDav.filter { it.id.startsWith(prefix) }
                    davSources += retained
                    retained.forEach { failures[it.id] = "CalDAV account unavailable" }
                }
            }
            try {
                val subscriptions = withContext(Dispatchers.IO) { icsStore.all() }
                hasIcsSubscriptions = subscriptions.isNotEmpty()
                icsDiscoverySucceeded = true
                subscriptions.forEach { item ->
                    val source = IcsConnection.source(item)
                    icsSources += source
                    if (source.id !in icsVisibleIds.value && source.id !in availabilityIds.value &&
                        source.id !in activeGroupIds(icsSources)) {
                        succeeded += source.id
                    } else try {
                        val read = withContext(Dispatchers.IO) { IcsConnection.fetch(item, range) }
                        if (read.unsupportedRecurring > 0) {
                            failures[source.id] = "ICS feed has unsupported recurrence"
                        } else {
                            received += read.events
                            succeeded += source.id
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failures[source.id] = "ICS feed unavailable" }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                icsSources += priorIcs
                priorIcs.forEach { failures[it.id] = "ICS subscriptions unavailable" }
            }
            if (!refreshGeneration.accepts(serial)) return@launch
            try {
                withContext(Dispatchers.IO) {
                    davDiscovered.forEach { (id, selectedIds) -> davResources.retainCalendars(id, selectedIds) }
                    davResourceReads.forEach { (id, calendarId, resources) ->
                        davResources.replaceCalendar(id, calendarId, resources) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { withContext(Dispatchers.IO) { davResources.clear() } }
            val sources = (haSources + androidSources + davSources + icsSources).distinctBy { it.id }
            if (!savedGroupStorageError.value) {
                val complete = buildSet {
                    if (haDiscoverySucceeded) add(CalendarProviderKind.HOME_ASSISTANT)
                    if (androidDiscoverySucceeded) add(CalendarProviderKind.ANDROID)
                    if (icsDiscoverySucceeded) add(CalendarProviderKind.ICS)
                }
                val reconciledGroups = SavedGroupSelection.reconcile(savedGroups.value, sources,
                    complete, davDiscovered.map { it.first }.toSet())
                if (reconciledGroups != savedGroups.value) saveGroupState(reconciledGroups)
            }
            val confirmedRemovedSources = buildList {
                if (haDiscoverySucceeded) addAll(priorHa.filter { old ->
                    haSources.none { it.id == old.id } })
                if (androidDiscoverySucceeded) addAll(priorAndroid.filter { old ->
                    androidSources.none { it.id == old.id } })
                if (icsDiscoverySucceeded) addAll(priorIcs.filter { old ->
                    icsSources.none { it.id == old.id } })
            }
            if (confirmedRemovedSources.isNotEmpty()) withContext(Dispatchers.IO) {
                runCatching { com.tinkercobble.ham.LocalReminderAlarms(getApplication())
                    .removeConfirmedSources(confirmedRemovedSources) }
                confirmedRemovedSources.forEach { source ->
                    runCatching { readDiagnostics.remove(source.id) }
                }
            }
            if (sources.isNotEmpty() || (availabilityConfigured &&
                (haDiscoverySucceeded || androidDiscoverySucceeded || icsDiscoverySucceeded))) {
                val candidateIds = if (!availabilityConfigured) {
                    val visible = androidVisibleIds.value + davVisibleIds.value + icsVisibleIds.value
                    AvailabilitySelection.initial(sources, visible)
                } else availabilityIds.value - confirmedRemovedSources.map { it.id }.toSet()
                val nextIds = AvailabilitySelection.pruneConfirmedMissing(candidateIds, sources,
                    haDiscoverySucceeded, icsDiscoverySucceeded)
                val fingerprints = if (!availabilityConfigured) sources.filter { it.id in nextIds &&
                    it.provider == CalendarProviderKind.ANDROID && it.identityFingerprint != null }
                    .associate { it.id to it.identityFingerprint!! }
                else availabilityStore.androidFingerprints()
                val safeIds = if (androidDiscoverySucceeded)
                    AvailabilitySelection.safeAndroidIds(nextIds, fingerprints, sources) else nextIds
                if (!availabilityConfigured || safeIds != availabilityIds.value) {
                    availabilityStorageError.value = withContext(Dispatchers.IO) {
                        runCatching { availabilityStore.save(safeIds,
                            fingerprints.filterKeys { it in safeIds }) }.isFailure
                    }
                    if (!availabilityStorageError.value) {
                        availabilityIds.value = safeIds
                        availabilityConfigured = true
                    }
                }
            }
            calendars.value = sources
            if (androidDiscoverySucceeded) {
                val (cleanPrefs, selected) = CalendarSelection.pruneMissingAndroidChoices(
                    calendarPreferences.value, androidVisibleIds.value, androidSources.map { it.id }.toSet())
                if (selected != androidVisibleIds.value) {
                    androidVisibleIds.value = selected
                    androidPrefs.edit().putStringSet("visible", selected).apply()
                }
                if (cleanPrefs != calendarPreferences.value) setCalendarPreferences(cleanPrefs)
            }
            var priorEvents = events.value
            if (haDiscoverySucceeded) priorEvents = pruneRemovedCalendars(priorEvents,
                CalendarProviderKind.HOME_ASSISTANT, haSources.map { it.id }.toSet())
            if (androidDiscoverySucceeded) priorEvents = pruneRemovedCalendars(priorEvents,
                CalendarProviderKind.ANDROID, androidSources.map { it.id }.toSet())
            priorEvents = pruneRemovedCalendars(priorEvents,
                CalendarProviderKind.CALDAV, davSources.map { it.id }.toSet())
            if (icsDiscoverySucceeded) priorEvents = pruneRemovedCalendars(priorEvents,
                CalendarProviderKind.ICS, icsSources.map { it.id }.toSet())
            priorEvents = retainUnverifiedPartialDav(priorEvents, partialDavEvents)
            events.value = mergeCachedCalendarRead(priorEvents, received, succeeded, range)
            val completeReminderSources = sources.filter { it.id in succeeded &&
                isCalendarVisible(it) }
            withContext(Dispatchers.IO) {
                runCatching { com.tinkercobble.ham.LocalReminderAlarms(getApplication())
                    .reconcileFresh(received.filter { event ->
                        completeReminderSources.any { it.id == event.calendarId }
                    }, completeReminderSources, range) }
            }
            try { reloadPendingDav(range) }
            catch (_: Exception) { davMessage.value = "Pending calendar changes could not be opened" }
            failedCalendars.value = failures
            loadedRange.value = range
            var cacheSaveFailed = false
            if (succeeded.isNotEmpty() || haDiscoverySucceeded || androidDiscoverySucceeded ||
                davAccountsNow.isNotEmpty() || hasIcsSubscriptions) {
                val snapshot = CachedAgenda(sources, events.value)
                val focusDate = if (activeAgenda.value == "Calendar") calendarAnchor.value else selectedDate.value
                try { withContext(Dispatchers.IO) { agendaCache.save(preferenceScope(), snapshot, focusDate) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { cacheSaveFailed = true }
                if (!cacheSaveFailed) updateWidget()
            }
            if (client.connected) {
                try {
                    tasks.value = client.tasks()
                    taskError.value = ""
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { taskError.value = "Could not load Home Assistant tasks" }
            } else {
                tasks.value = emptyList()
                taskError.value = ""
            }
            if (!refreshGeneration.accepts(serial)) return@launch
            val attemptedDiagnostics = sources.filter { source ->
                (isCalendarVisible(source) || source.id in availabilityIds.value) &&
                    (source.id in succeeded || source.id in failures)
            }.map { it.id }.toSet()
            readDiagnosticsUnavailable.value = withContext(Dispatchers.IO) {
                runCatching { readDiagnostics.record(attemptedDiagnostics, succeeded, failures,
                    Instant.now()) }.isFailure
            }
            readDiagnosticsRevision.intValue++
            message.value = when {
                cacheSaveFailed -> "Calendar updated, but offline copy could not be saved"
                failures.isEmpty() -> "Calendars updated"
                partialDavIds.isNotEmpty() && failures.size == partialDavIds.size ->
                    "Some CalDAV items could not be read; available events updated"
                else -> "${failures.size} calendar${if (failures.size == 1) "" else "s"} could not load"
            }
            if (succeeded.isNotEmpty()) {
                lastSync.value = Instant.now()
                lastConnection.value = lastSync.value
                statusPrefs.edit().putLong("last_sync", lastSync.value!!.toEpochMilli())
                    .putLong("last_connection", lastConnection.value!!.toEpochMilli()).apply()
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (refreshGeneration.accepts(serial)) message.value = e.message ?: "Could not load calendars" }
        finally { if (refreshGeneration.accepts(serial)) loading.value = false }
        } }
    }
    fun move(days: Long) { navigationDirection.intValue = days.toInt(); selectedDate.value = selectedDate.value.plusDays(days); dayBrowsing.value = true; savePosition(); refresh() }
    fun today() { navigationDirection.intValue = 0; selectedDate.value = LocalDate.now(); dayBrowsing.value = false; savePosition(); refresh() }
    fun beginAuth(address: String) = viewModelScope.launch {
        loading.value = true
        try {
            authRequest.value = HomeAssistantAuth.prepare(ConnectionPolicy.normalize(address))
            message.value = ""
        } catch (e: Exception) { message.value = e.message ?: "Could not start Home Assistant sign-in" }
        finally { loading.value = false }
    }
    fun cancelAuth() { authRequest.value = null }
    fun connect(request: AuthRequest, code: String, onConnected: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            val session = HomeAssistantAuth.exchange(request, code)
            // Reauthorization may switch accounts even when the server address stays the same.
            clearLoadedInformation()
            HaMutationDispatcher.exclusive { client.setSession(session) }
            server.value = session.server
            loadAvailabilityScope()
            loadSavedGroupScope()
            calendarPreferences.value = calendarStore.load(session.server)
            calendarView.value = calendarPreferences.value.view
            val position = calendarStore.loadPosition(session.server)
            selectedDate.value = position.selected
            calendarAnchor.value = position.anchor
            monthSelectedDate.value = position.monthDay
            activeAgenda.value = position.agenda
            clientId.value = session.clientId
            connected.value = true
            lastConnection.value = Instant.now()
            statusPrefs.edit().putLong("last_connection", lastConnection.value!!.toEpochMilli()).apply()
            message.value = "Connected"
            onConnected()
            refresh()
        } catch (e: Exception) { message.value = e.message ?: "Connection failed" }
        finally { loading.value = false }
    }
    fun disconnect(requestRevocation: Boolean) = viewModelScope.launch {
        loading.value = true
        refreshJob?.cancel()
        refreshGeneration.next()
        val accepted = HaMutationDispatcher.exclusive { client.disconnect(requestRevocation) }
        connected.value = false
        server.value = ""
        loadAvailabilityScope()
        loadSavedGroupScope()
        calendarPreferences.value = calendarStore.load(preferenceScope())
        calendarView.value = calendarPreferences.value.view
        clientId.value = ""
        clearLoadedInformation()
        lastConnection.value = null
        statusPrefs.edit().remove("last_connection").apply()
        message.value = if (!requestRevocation) "Disconnected on this device"
            else if (accepted) "Disconnected. Home Assistant accepted the revocation request."
            else "Disconnected here. Home Assistant revocation could not be confirmed."
        loading.value = false
        if (hasAgendaSource) refresh()
    }
    fun clearLoadedInformation() {
        refreshJob?.cancel()
        refreshGeneration.next()
        agendaCache.clear()
        updateWidget()
        davResources.clear()
        loading.value = false
        calendars.value = emptyList()
        events.value = emptyList()
        tasks.value = emptyList()
        taskError.value = ""
        failedCalendars.value = emptyMap()
        loadedRange.value = null
        lastSync.value = null
        statusPrefs.edit().remove("last_sync").apply()
    }
    fun refreshConnectionStatus() = viewModelScope.launch {
        if (!client.connected) return@launch
        loading.value = true
        try {
            client.ping()
            lastConnection.value = Instant.now()
            statusPrefs.edit().putLong("last_connection", lastConnection.value!!.toEpochMilli()).apply()
            message.value = "Home Assistant connection is working"
        } catch (e: Exception) { message.value = e.message ?: "Home Assistant is unreachable" }
        finally { loading.value = false }
    }
    val legacyIdentity get() = clientId.value == AuthIdentity.LEGACY_CLIENT_ID
    private suspend fun createDav(source: CalendarSource, date: LocalDate, title: String,
        allDay: Boolean, startText: String = "", endText: String = "",
        repeat: RepeatSpec? = null, description: String = "", location: String = "",
        endExclusive: LocalDate = date.plusDays(1)): Boolean {
        require(source.provider == CalendarProviderKind.CALDAV && source.canCreate)
        val accountId = source.id.split(':').takeIf { it.size == 3 && it[0] == "dav" }
            ?.get(1) ?: error("Calendar account is unavailable")
        val operationId = UUID.randomUUID()
        val payload = DavEventPayload.create(operationId, date, title, allDay, startText, endText,
            repeat = repeat, description = description, location = location,
            endExclusive = endExclusive)
        val searchRange = DavEventPayload.createSearchRange(payload)
        val candidates = IcsEventParser.parse(payload, source, searchRange).let { read ->
            check(read.unsupportedRecurring == 0 && read.events.size == (repeat?.count ?: 1)) {
                "This calendar series could not be reviewed safely"
            }
            read.events
        }
        fun same(a: AgendaEvent, b: AgendaEvent): Boolean =
            a.summary.trim().equals(b.summary.trim(), true) &&
            a.allDay == b.allDay && if (a.allDay) a.start == b.start && a.end == b.end else
                AgendaEvent.instant(a.start) == AgendaEvent.instant(b.start) &&
                    AgendaEvent.instant(a.end) == AgendaEvent.instant(b.end)
        check(events.value.none { loaded -> loaded.calendarId == source.id &&
            candidates.any { same(loaded, it) } }) {
            "An event with this title and time is already loaded on that calendar"
        }
        val state = withContext(Dispatchers.IO) {
            val account = davStore.account(accountId) ?: error("Calendar account was removed")
            check(pendingDav.list().none { pending ->
                pending.calendarId == source.id && pending.operation == DavOperation.CREATE &&
                    pending.ics?.let { bytes ->
                        IcsEventParser.parse(bytes, source, searchRange).events
                            .any { existing -> candidates.any { same(existing, it) } }
                    } == true
            }) { "An event with this title and time is already pending on that calendar" }
            pendingDav.enqueue(PendingDavMutation(operationId, accountId, source.id,
                DavOperation.CREATE, null, null, payload))
            runCatching { DavBackgroundSync.scheduleIfQueued(getApplication(), true) }
            runCatching { DavMutationDispatcher.flush(account, pendingDav,
                accountStillCurrent = { davStore.account(accountId) == account }) }
            pendingDav.list().firstOrNull { it.id == operationId }?.state
        }
        reloadPendingDav()
        message.value = when (state) {
            null -> "Event created on calendar"
            DavPendingState.QUEUED -> "Event saved on this device; HAM will retry, or sync in Settings"
            DavPendingState.CONFLICT -> "HAM could not safely confirm this calendar create; review it in Settings"
        }
        return state == null
    }
    private suspend fun createHa(source: CalendarSource, date: LocalDate, title: String,
        startText: String? = null, endText: String? = null,
        description: String = "", location: String = "",
        endExclusive: LocalDate = date.plusDays(1)): Boolean {
        require(source.provider == CalendarProviderKind.HOME_ASSISTANT && source.canCreate) {
            "This Home Assistant calendar is not writable"
        }
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty() && cleanTitle.length <= 200) {
            "Enter a valid event title (up to 200 characters)"
        }
        val allDay = startText == null && endText == null
        val bounds = if (allDay) {
            require(endExclusive.isAfter(date)) { "All-day end must follow the start date" }
            date.toString() to endExclusive.toString()
        }
        else {
            require(startText != null && endText != null) { "Enter both event times" }
            TimedEventTime.parse(date, startText, endText).let {
                it.start.toOffsetDateTime().toString() to it.end.toOffsetDateTime().toString()
            }
        }
        val proposed = HomeAssistantCreateDraft.of(source, cleanTitle, bounds.first,
            bounds.second, allDay, description, location)
        HomeAssistantCalendarMutation.createCommand(source, proposed, emptyList())
        check(events.value.none { HomeAssistantCalendarMutation.knownCreateDuplicate(proposed, it) }) {
            "A matching event is already displayed on this calendar. Refresh before creating another."
        }
        val id = UUID.randomUUID()
        val state = withContext(Dispatchers.IO) {
            val binding = client.sessionBinding() ?: error("Connect to Home Assistant first")
            pendingHa.enqueue(PendingHaMutation(id, binding, source.id, proposed,
                HaOperation.CREATE, null))
            runCatching { HaBackgroundSync.scheduleIfQueued(getApplication(), true) }
            try {
                HaMutationDispatcher.flush(pendingHa, client.mutationTransport(),
                    maxItems = 1, onlyId = id)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The encrypted queued create remains for later review. */ }
            pendingHa.list().firstOrNull { it.id == id }?.state
        }
        reloadPendingHa()
        message.value = when (state) {
            null -> "Home Assistant confirmed the new event"
            HaPendingState.QUEUED -> "Event saved on this device; HAM will sync it when connected"
            HaPendingState.ACCEPTED -> "Home Assistant accepted creation; calendar confirmation is pending"
            HaPendingState.AWAITING_CONFIRMATION ->
                "Creation result is uncertain; HAM will not resend without your review"
            HaPendingState.CONFLICT -> "A matching event or sign-in changed; review the pending creation in Settings"
        }
        return state == null
    }
    fun create(calendarId: String, date: LocalDate, title: String,
        description: String = "", location: String = "", done: () -> Unit,
        endExclusive: LocalDate = date.plusDays(1)) = viewModelScope.launch {
        loading.value = true
        try {
            require(endExclusive.isAfter(date)) { "All-day end must follow the start date" }
            val source = selectedWritable(calendarId) ?: error("Calendar unavailable or read-only. Choose another calendar.")
            val sent = when (source.provider) {
                CalendarProviderKind.ANDROID -> {
                    withContext(Dispatchers.IO) { androidProvider.createAllDay(source, date, title,
                        description, location, endExclusive) }; true
                }
                CalendarProviderKind.HOME_ASSISTANT -> createHa(source, date, title,
                    description = description, location = location, endExclusive = endExclusive)
                CalendarProviderKind.CALDAV -> createDav(source, date, title, true,
                    description = description, location = location, endExclusive = endExclusive)
                CalendarProviderKind.ICS -> error("ICS subscriptions are read-only")
            }
            setCalendarPreferences(calendarPreferences.value.used(calendarId))
            if (source.provider == CalendarProviderKind.ANDROID) {
                message.value = "Event created"
            }
            done()
            if (sent) refresh()
        } catch (e: Exception) { message.value = e.message ?: "Event was not created" }
        finally { loading.value = false }
    }
    fun createTimed(calendarId: String, date: LocalDate, startText: String, endText: String,
        title: String, description: String = "", location: String = "",
        done: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            val source = selectedWritable(calendarId)
                ?: error("Calendar unavailable or read-only. Choose another calendar.")
            val sent = when (source.provider) {
                CalendarProviderKind.ANDROID -> {
                    withContext(Dispatchers.IO) {
                        androidProvider.createTimed(source, date, startText, endText, title,
                            description, location) }; true
                }
                CalendarProviderKind.HOME_ASSISTANT -> createHa(source, date, title, startText, endText,
                    description, location)
                CalendarProviderKind.CALDAV -> createDav(source, date, title, false, startText, endText,
                    description = description, location = location)
                CalendarProviderKind.ICS -> error("ICS subscriptions are read-only")
            }
            setCalendarPreferences(calendarPreferences.value.used(calendarId))
            if (source.provider == CalendarProviderKind.ANDROID) {
                message.value = "Event created"
            }
            done()
            if (sent) refresh()
        } catch (e: Exception) { message.value = e.message ?: "Event was not created" }
        finally { loading.value = false }
    }
    fun moveAndroidEvent(event: AgendaEvent, destinationId: String, draft: IcsImportDraft,
        done: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            val source = calendars.value.singleOrNull { it.id == event.calendarId &&
                it.provider == CalendarProviderKind.ANDROID && it.canDelete }
                ?: error("Original Android calendar is unavailable or read-only. Refresh before moving.")
            val destination = selectedWritable(destinationId)?.takeIf {
                it.provider == CalendarProviderKind.ANDROID && it.id != source.id }
                ?: error("Choose a different writable Android calendar.")
            withContext(Dispatchers.IO) {
                androidProvider.moveSingleEvent(source, destination, event, draft)
            }
            setCalendarPreferences(calendarPreferences.value.used(destinationId))
            message.value = "Event moved after destination confirmation"
            done()
            refresh()
        } catch (e: Exception) { message.value = e.message ?: "Event was not moved" }
        finally { loading.value = false }
    }
    fun createRecurringAllDay(calendarId: String, date: LocalDate, title: String,
        repeat: RepeatSpec, description: String = "", location: String = "",
        done: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            val source = selectedWritable(calendarId)
                ?: error("Calendar unavailable or read-only. Choose another calendar.")
            val sent = when (source.provider) {
                CalendarProviderKind.ANDROID -> {
                    withContext(Dispatchers.IO) {
                        androidProvider.createRecurringAllDay(source, date, title, repeat,
                            description, location) }; true
                }
                CalendarProviderKind.CALDAV -> createDav(source, date, title, true, repeat = repeat,
                    description = description, location = location)
                CalendarProviderKind.HOME_ASSISTANT -> error("This Home Assistant calendar does not offer series creation in HAM")
                CalendarProviderKind.ICS -> error("ICS subscriptions are read-only")
            }
            setCalendarPreferences(calendarPreferences.value.used(calendarId))
            if (source.provider != CalendarProviderKind.CALDAV) {
                message.value = "Series created"
            }
            done()
            if (sent) refresh()
        } catch (e: Exception) { message.value = e.message ?: "Series was not created" }
        finally { loading.value = false }
    }
    fun createRecurringTimed(calendarId: String, date: LocalDate, startText: String,
        endText: String, title: String, repeat: RepeatSpec,
        description: String = "", location: String = "", done: () -> Unit,
        expectedZoneId: String? = null) = viewModelScope.launch {
        loading.value = true
        try {
            val source = selectedWritable(calendarId)
                ?: error("Calendar unavailable or read-only. Choose another calendar.")
            val sent = when (source.provider) {
                CalendarProviderKind.ANDROID -> withContext(Dispatchers.IO) {
                    check(expectedZoneId == null || ZoneId.systemDefault().id == expectedZoneId) {
                        "Device time zone changed since import review. Preview the file again."
                    }
                    androidProvider.createRecurringTimed(source, date, startText, endText, title, repeat,
                        description, location)
                    true
                }
                CalendarProviderKind.CALDAV -> {
                    check(expectedZoneId == null || ZoneId.systemDefault().id == expectedZoneId) {
                        "Device time zone changed since import review. Preview the file again."
                    }
                    createDav(source, date, title, false, startText, endText, repeat,
                        description, location)
                }
                CalendarProviderKind.HOME_ASSISTANT -> error("This Home Assistant calendar does not offer series creation in HAM")
                CalendarProviderKind.ICS -> error("ICS subscriptions are read-only")
            }
            setCalendarPreferences(calendarPreferences.value.used(calendarId))
            if (source.provider != CalendarProviderKind.CALDAV) message.value = "Timed series created"
            done()
            if (sent) refresh()
        } catch (e: Exception) { message.value = e.message ?: "Timed series was not created" }
        finally { loading.value = false }
    }
    private suspend fun queueHaChange(source: CalendarSource, event: AgendaEvent,
        operation: HaOperation, title: String? = null,
        startText: String? = null, endText: String? = null,
        description: String? = null, location: String? = null,
        newDate: LocalDate = event.date): Boolean {
        check(source.id !in calendarPreferences.value.hidden &&
            source.provider == CalendarProviderKind.HOME_ASSISTANT &&
            event.calendarId == source.id) { "Show the selected Home Assistant calendar first" }
        val id = UUID.randomUUID()
        val state = withContext(Dispatchers.IO) {
            val binding = client.sessionBinding() ?: error("Connect to Home Assistant first")
            val target = if (operation == HaOperation.UPDATE) {
                val command = HomeAssistantCalendarMutation.updateCommand(source, event,
                    listOf(event), checkNotNull(title), startText, endText,
                    description = description, location = location, newDate = newDate)
                val values = command.getJSONObject("event")
                HaTarget(values.getString("summary"), values.getString("start"),
                    values.getString("end"), values.getString("description"),
                    values.getString("location"))
            } else {
                HomeAssistantCalendarMutation.deleteCommand(source, event, listOf(event))
                null
            }
            pendingHa.enqueue(PendingHaMutation(id, binding, source.id, event, operation, target))
            runCatching { HaBackgroundSync.scheduleIfQueued(getApplication(), true) }
            try { HaMutationDispatcher.flush(pendingHa, client.mutationTransport(),
                maxItems = 1, onlyId = id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The encrypted queued change remains for a later check. */ }
            pendingHa.list().firstOrNull { it.id == id }?.state
        }
        reloadPendingHa()
        message.value = when (state) {
            null -> "Home Assistant change confirmed"
            HaPendingState.QUEUED -> "Change saved on this device; HAM will retry when connected"
            HaPendingState.ACCEPTED -> "Home Assistant accepted the change; calendar confirmation is pending"
            HaPendingState.AWAITING_CONFIRMATION ->
                "Home Assistant response is uncertain; HAM will not resend without your review"
            HaPendingState.CONFLICT -> "Calendar item or sign-in changed; review the pending change in Settings"
        }
        return state == null
    }
    fun edit(source: CalendarSource, event: AgendaEvent, title: String, done: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            val sent = when (source.provider) {
                CalendarProviderKind.ANDROID -> {
                    withContext(Dispatchers.IO) { androidProvider.updateTitle(source, event, title) }; true
                }
                CalendarProviderKind.HOME_ASSISTANT -> {
                    queueHaChange(source, event, HaOperation.UPDATE, title)
                }
                CalendarProviderKind.CALDAV -> updateDav(source, event, title)
                CalendarProviderKind.ICS -> error("ICS subscriptions are read-only")
            }
            if (source.provider == CalendarProviderKind.ANDROID) message.value = "Event updated"
            done()
            if (sent) refresh()
        } catch (e: Exception) { message.value = e.message ?: "Event was not updated" }
        finally { loading.value = false }
    }
    fun editEventDetails(source: CalendarSource, event: AgendaEvent,
        description: String, location: String, done: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            val details = EventDraftDetails.of(description, location)
            check(details.description != event.description || details.location != event.location) {
                "Change the location or description before saving"
            }
            val sent = when (source.provider) {
                CalendarProviderKind.ANDROID -> {
                    check(source.id == event.calendarId && source.id in androidVisibleIds.value) {
                        "Show the selected Android calendar first"
                    }
                    withContext(Dispatchers.IO) {
                        androidProvider.updateDetails(source, event,
                            details.description, details.location)
                    }
                    true
                }
                CalendarProviderKind.HOME_ASSISTANT -> queueHaChange(source, event,
                    HaOperation.UPDATE, event.summary, description = details.description,
                    location = details.location)
                CalendarProviderKind.CALDAV -> updateDav(source, event, event.summary,
                    description = details.description, location = details.location)
                CalendarProviderKind.ICS -> error("ICS subscriptions are read-only")
            }
            if (source.provider == CalendarProviderKind.ANDROID) message.value = "Event details updated"
            done()
            if (sent) refresh()
        } catch (e: Exception) { message.value = e.message ?: "Event details were not updated" }
        finally { loading.value = false }
    }
    private suspend fun updateDav(source: CalendarSource, event: AgendaEvent,
        title: String, startText: String? = null, endText: String? = null,
        wholeSeries: Boolean = false, description: String? = null,
        location: String? = null, newDate: LocalDate = event.date): Boolean {
        check(source.provider == CalendarProviderKind.CALDAV && source.canUpdate &&
            source.id in davVisibleIds.value && event.calendarId == source.id &&
            (if (wholeSeries) event.recurrenceId != null &&
                RepeatSpec.supported(event.rrule) != null else event.recurrenceId == null) &&
            !event.uid.isNullOrBlank()) {
            "Calendar event is unavailable or read-only"
        }
        val accountId = source.id.split(':').takeIf { it.size == 3 && it[0] == "dav" }
            ?.get(1) ?: error("Calendar account is unavailable")
        val state = withContext(Dispatchers.IO) {
            val account = davStore.account(accountId) ?: error("Calendar account was removed")
            val resource = davResources.uniqueForUid(accountId, source.id, checkNotNull(event.uid))
                ?: error("Refresh this calendar before editing")
            check(resource.etag.isNotBlank()) { "Refresh this calendar before editing" }
            val detailEdit = description != null || location != null
            check(!detailEdit || !wholeSeries && description != null && location != null &&
                startText == null && endText == null) { "Edit this event's details separately" }
            if (wholeSeries) DavSeriesValidation.requireUnchanged(resource.ics, source, event) else {
                val original = IcsEventParser.parse(resource.ics, source)
                check(original.unsupportedRecurring == 0 && original.events.singleOrNull()?.let {
                    it.uid == event.uid && it.summary == event.summary && it.start == event.start &&
                        it.end == event.end && it.recurrenceId == null &&
                        (!detailEdit || it.description == event.description &&
                            it.location == event.location)
                } == true) { "Calendar item changed; refresh before editing" }
            }
            val payload = if (detailEdit) DavEventPayload.updateDetails(resource.ics,
                checkNotNull(description), checkNotNull(location))
            else if (wholeSeries && startText != null && endText != null) {
                check(!event.allDay) { "Only timed series can change time here" }
                DavEventPayload.updateTimedSeries(resource.ics, title, startText, endText)
            } else if (wholeSeries) DavEventPayload.updateSeriesTitle(resource.ics, title)
            else if (startText != null && endText != null) {
                check(!event.allDay) { "Only timed events can change time here" }
                DavEventPayload.updateTimed(resource.ics, newDate, title, startText, endText)
            } else if (newDate != event.date) {
                check(event.allDay) { "Only all-day events can change date without a time" }
                DavEventPayload.updateAllDay(resource.ics, newDate, title)
            } else DavEventPayload.updateTitle(resource.ics, title)
            val operationId = UUID.randomUUID()
            pendingDav.enqueue(PendingDavMutation(operationId, accountId, source.id,
                DavOperation.UPDATE, resource.url, resource.etag, payload,
                baseIcs = resource.ics))
            runCatching { DavBackgroundSync.scheduleIfQueued(getApplication(), true) }
            runCatching { DavMutationDispatcher.flush(account, pendingDav,
                accountStillCurrent = { davStore.account(accountId) == account }) }
            pendingDav.list().firstOrNull { it.id == operationId }?.state
        }
        reloadPendingDav()
        message.value = when (state) {
            null -> "Event updated on calendar"
            DavPendingState.QUEUED -> "Change saved on this device; HAM will retry, or sync in Settings"
            DavPendingState.CONFLICT -> "Calendar server reported a conflict; review pending changes in Settings"
        }
        return state == null
    }
    fun editDavSeriesTitle(source: CalendarSource, event: AgendaEvent,
        title: String, done: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            val sent = updateDav(source, event, title, wholeSeries = true)
            done()
            if (sent) refresh()
        } catch (e: Exception) { message.value = e.message ?: "Series was not updated" }
        finally { loading.value = false }
    }
    fun editTimedDavSeries(source: CalendarSource, event: AgendaEvent, title: String,
        startText: String, endText: String, done: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            val sent = updateDav(source, event, title, startText, endText, wholeSeries = true)
            done()
            if (sent) refresh()
        } catch (e: Exception) { message.value = e.message ?: "Series was not updated" }
        finally { loading.value = false }
    }
    fun editTimedAndroid(source: CalendarSource, event: AgendaEvent, title: String,
        startText: String, endText: String, done: () -> Unit,
        newDate: LocalDate = event.date) = viewModelScope.launch {
        loading.value = true
        try {
            withContext(Dispatchers.IO) {
                androidProvider.updateTimed(source, event, title, startText, endText, newDate)
            }
            message.value = "Event updated"
            done()
            refresh()
        } catch (e: Exception) { message.value = e.message ?: "Event was not updated" }
        finally { loading.value = false }
    }
    fun editTimedAndroidSeries(source: CalendarSource, event: AgendaEvent, title: String,
        startText: String, endText: String, done: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            withContext(Dispatchers.IO) {
                androidProvider.updateSeriesTime(source, event, title, startText, endText)
            }
            message.value = "Entire series updated"
            done()
            refresh()
        } catch (e: Exception) { message.value = e.message ?: "Series was not updated" }
        finally { loading.value = false }
    }
    fun editAllDayDateAndroid(source: CalendarSource, event: AgendaEvent, title: String,
        newDate: java.time.LocalDate, done: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            withContext(Dispatchers.IO) { androidProvider.updateAllDayDate(source, event, title, newDate) }
            message.value = "Event date updated"
            done()
            refresh()
        } catch (e: Exception) { message.value = e.message ?: "Event date was not updated" }
        finally { loading.value = false }
    }
    fun editAllDayDateDav(source: CalendarSource, event: AgendaEvent, title: String,
        newDate: LocalDate, done: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            val sent = updateDav(source, event, title, newDate = newDate)
            done()
            if (sent) refresh()
        } catch (e: Exception) { message.value = e.message ?: "Event date was not updated" }
        finally { loading.value = false }
    }
    fun editAllDayDateHomeAssistant(source: CalendarSource, event: AgendaEvent,
        title: String, newDate: LocalDate, done: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            val confirmed = queueHaChange(source, event, HaOperation.UPDATE, title,
                newDate = newDate)
            done()
            if (confirmed) refresh()
        } catch (e: Exception) { message.value = e.message ?: "Event date was not updated" }
        finally { loading.value = false }
    }
    fun editTimedDav(source: CalendarSource, event: AgendaEvent, title: String,
        startText: String, endText: String, done: () -> Unit,
        newDate: LocalDate = event.date) = viewModelScope.launch {
        loading.value = true
        try {
            val sent = updateDav(source, event, title, startText, endText, newDate = newDate)
            done()
            if (sent) refresh()
        } catch (e: Exception) { message.value = e.message ?: "Event was not updated" }
        finally { loading.value = false }
    }
    fun editTimedHomeAssistant(source: CalendarSource, event: AgendaEvent, title: String,
        startText: String, endText: String, done: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            check(source.id !in calendarPreferences.value.hidden) {
                "Show this calendar before editing an event"
            }
            val confirmed = queueHaChange(source, event, HaOperation.UPDATE,
                title, startText, endText)
            done()
            if (confirmed) refresh()
        } catch (e: Exception) { message.value = e.message ?: "Event was not updated" }
        finally { loading.value = false }
    }
    fun deleteAndroidEvent(source: CalendarSource, event: AgendaEvent, done: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            withContext(Dispatchers.IO) { androidProvider.deleteEvent(source, event) }
            message.value = "Event removed"
            done()
            refresh()
        } catch (e: Exception) { message.value = e.message ?: "Event was not removed" }
        finally { loading.value = false }
    }
    fun editAndroidSeriesTitle(source: CalendarSource, event: AgendaEvent,
        title: String, done: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            withContext(Dispatchers.IO) { androidProvider.updateSeriesTitle(source, event, title) }
            message.value = "Series title updated"
            done()
            refresh()
        } catch (e: Exception) { message.value = e.message ?: "Series was not updated" }
        finally { loading.value = false }
    }
    fun deleteAndroidSeries(source: CalendarSource, event: AgendaEvent,
        done: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            withContext(Dispatchers.IO) { androidProvider.deleteSeries(source, event) }
            message.value = "Entire series removed"
            done()
            refresh()
        } catch (e: Exception) { message.value = e.message ?: "Series was not removed" }
        finally { loading.value = false }
    }
    fun deleteHomeAssistantEvent(source: CalendarSource, event: AgendaEvent,
        done: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            check(source.id !in calendarPreferences.value.hidden) {
                "Show this calendar before removing an event"
            }
            val confirmedAbsent = queueHaChange(source, event, HaOperation.DELETE)
            done()
            refresh()
        } catch (e: Exception) { message.value = e.message ?: "Event was not removed" }
        finally { loading.value = false }
    }
    fun deleteDavEvent(source: CalendarSource, event: AgendaEvent, done: () -> Unit,
        wholeSeries: Boolean = false) = viewModelScope.launch {
        loading.value = true
        try {
            check(source.provider == CalendarProviderKind.CALDAV && source.canDelete &&
                source.id in davVisibleIds.value && event.calendarId == source.id &&
                (if (wholeSeries) event.recurrenceId != null &&
                    RepeatSpec.supported(event.rrule) != null else event.recurrenceId == null) &&
                !event.uid.isNullOrBlank()) {
                "Calendar event is unavailable or read-only"
            }
            val accountId = source.id.split(':').takeIf { it.size == 3 && it[0] == "dav" }
                ?.get(1) ?: error("Calendar account is unavailable")
            val state = withContext(Dispatchers.IO) {
                val account = davStore.account(accountId) ?: error("Calendar account was removed")
                val resource = davResources.uniqueForUid(accountId, source.id, checkNotNull(event.uid))
                    ?: error("Refresh this calendar before removing the event")
                check(resource.etag.isNotBlank()) { "Refresh this calendar before removing the event" }
                if (wholeSeries) DavSeriesValidation.requireUnchanged(resource.ics, source, event) else {
                    val original = IcsEventParser.parse(resource.ics, source)
                    check(original.unsupportedRecurring == 0 && original.events.singleOrNull()?.let {
                        it.uid == event.uid && it.summary == event.summary && it.start == event.start &&
                            it.end == event.end && it.recurrenceId == null
                    } == true) { "Calendar item changed; refresh before removing it" }
                }
                val operationId = UUID.randomUUID()
                pendingDav.enqueue(PendingDavMutation(operationId, accountId, source.id,
                    DavOperation.DELETE, resource.url, resource.etag, null, uid = event.uid,
                    baseIcs = resource.ics))
                runCatching { DavBackgroundSync.scheduleIfQueued(getApplication(), true) }
                runCatching { DavMutationDispatcher.flush(account, pendingDav,
                    accountStillCurrent = { davStore.account(accountId) == account }) }
                pendingDav.list().firstOrNull { it.id == operationId }?.state
            }
            reloadPendingDav()
            message.value = when (state) {
                null -> "Event removed from calendar"
                DavPendingState.QUEUED -> "Removal saved on this device; HAM will retry, or sync in Settings"
                DavPendingState.CONFLICT -> "Calendar server reported a conflict; review pending changes in Settings"
            }
            done()
            if (state == null) refresh()
        } catch (e: Exception) { message.value = e.message ?: "Event was not removed" }
        finally { loading.value = false }
    }
    fun deleteDavSeries(source: CalendarSource, event: AgendaEvent,
        done: () -> Unit) = deleteDavEvent(source, event, done, wholeSeries = true)
}
