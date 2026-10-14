package com.tinkercobble.ham.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tinkercobble.ham.auth.HomeAssistantAuth
import com.tinkercobble.ham.auth.AuthIdentity
import com.tinkercobble.ham.auth.AuthRequest
import com.tinkercobble.ham.auth.ConnectionPolicy
import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.CalendarPreferences
import com.tinkercobble.ham.data.CalendarPreferenceStore
import com.tinkercobble.ham.data.CalendarSelection
import com.tinkercobble.ham.data.CalendarView
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.data.distinctEvents
import com.tinkercobble.ham.data.mergeCalendarRead
import com.tinkercobble.ham.data.RefreshGeneration
import com.tinkercobble.ham.data.HomeAssistantClient
import com.tinkercobble.ham.data.TodoEntry
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.Instant

class HamViewModel(application: Application) : AndroidViewModel(application) {
    private val client = HomeAssistantClient(application)
    private val calendarStore = CalendarPreferenceStore(application)
    private val statusPrefs = application.getSharedPreferences("ham-connection-status", android.content.Context.MODE_PRIVATE)
    var connected = androidx.compose.runtime.mutableStateOf(client.connected)
    var server = androidx.compose.runtime.mutableStateOf(client.server.orEmpty())
    var clientId = androidx.compose.runtime.mutableStateOf(client.clientId.orEmpty())
    var authRequest = androidx.compose.runtime.mutableStateOf<AuthRequest?>(null)
    var lastSync = androidx.compose.runtime.mutableStateOf(statusPrefs.getLong("last_sync", 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli))
    var lastConnection = androidx.compose.runtime.mutableStateOf(statusPrefs.getLong("last_connection", 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli))
    var selectedDate = androidx.compose.runtime.mutableStateOf(LocalDate.now())
    var actualToday = androidx.compose.runtime.mutableStateOf(LocalDate.now())
    var dayBrowsing = androidx.compose.runtime.mutableStateOf(false)
    var calendarAnchor = androidx.compose.runtime.mutableStateOf(LocalDate.now())
    var monthSelectedDate = androidx.compose.runtime.mutableStateOf(LocalDate.now())
    var calendarView = androidx.compose.runtime.mutableStateOf(calendarStore.load(client.server.orEmpty()).view)
    var activeAgenda = androidx.compose.runtime.mutableStateOf("Today")
    var navigationDirection = androidx.compose.runtime.mutableIntStateOf(0)
    var failedCalendars = androidx.compose.runtime.mutableStateOf<Map<String, String>>(emptyMap())
    var loadedRange = androidx.compose.runtime.mutableStateOf<DateRange?>(null)
    private var refreshJob: Job? = null
    private val refreshMutex = Mutex()
    private val refreshGeneration = RefreshGeneration()
    var calendarPreferences = androidx.compose.runtime.mutableStateOf(calendarStore.load(client.server.orEmpty()))
    val visibleEvents get() = events.value.filterNot { it.calendarId in calendarPreferences.value.hidden }
    fun setCalendarPreferences(value: CalendarPreferences) {
        calendarPreferences.value = value
        calendarView.value = value.view
        calendarStore.save(server.value, value)
    }
    fun setView(value: CalendarView) {
        navigationDirection.intValue = 0
        setCalendarPreferences(calendarPreferences.value.copy(view = value))
        calendarAnchor.value = actualToday.value
        monthSelectedDate.value = actualToday.value
        refresh()
    }
    fun updateActualToday() {
        val current = LocalDate.now()
        val previous = actualToday.value
        if (current != previous) {
            actualToday.value = current
            if (!dayBrowsing.value && selectedDate.value == previous) {
                selectedDate.value = current
                if (activeAgenda.value == "Today") refresh()
            }
        }
    }
    fun enterCalendar() {
        navigationDirection.intValue = 0
        activeAgenda.value = "Calendar"
        calendarAnchor.value = actualToday.value
        monthSelectedDate.value = actualToday.value
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
        refresh()
    }
    fun selectMonthDay(day: LocalDate) { monthSelectedDate.value = day }
    fun selectedWritable(id: String?) = CalendarSelection.resolveWritable(id, calendars.value)
    var calendars = androidx.compose.runtime.mutableStateOf<List<CalendarSource>>(emptyList())
    var events = androidx.compose.runtime.mutableStateOf<List<AgendaEvent>>(emptyList())
    var tasks = androidx.compose.runtime.mutableStateOf<List<TodoEntry>>(emptyList())
    var taskError = androidx.compose.runtime.mutableStateOf("")
    var loading = androidx.compose.runtime.mutableStateOf(false)
    var message = androidx.compose.runtime.mutableStateOf("")

    init { if (client.connected) refresh() }
    fun refresh() {
        if (!client.connected) return
        refreshJob?.cancel()
        val serial = refreshGeneration.next()
        val range = if (activeAgenda.value == "Calendar") calendarRange()
            else DateRange(selectedDate.value, selectedDate.value.plusDays(1))
        refreshJob = viewModelScope.launch { refreshMutex.withLock {
        if (!refreshGeneration.accepts(serial)) return@withLock
        loading.value = true
        try {
            val sources = client.calendars()
            val result = client.readEvents(sources, range.start, range.endExclusive)
            if (!refreshGeneration.accepts(serial)) return@launch
            calendars.value = sources
            events.value = mergeCalendarRead(events.value, result.events, result.failed.keys, range)
            failedCalendars.value = result.failed
            loadedRange.value = range
            try {
                tasks.value = client.tasks()
                taskError.value = ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                taskError.value = e.message ?: "Could not load tasks"
            }
            if (!refreshGeneration.accepts(serial)) return@launch
            message.value = if (result.failed.isEmpty()) "Updated from Home Assistant" else
                "${result.failed.size} calendar${if (result.failed.size == 1) "" else "s"} could not load"
            if (result.succeeded.isNotEmpty()) {
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
    fun move(days: Long) { navigationDirection.intValue = days.toInt(); selectedDate.value = selectedDate.value.plusDays(days); dayBrowsing.value = true; refresh() }
    fun today() { navigationDirection.intValue = 0; selectedDate.value = LocalDate.now(); dayBrowsing.value = false; refresh() }
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
            client.setSession(session)
            server.value = session.server
            calendarPreferences.value = calendarStore.load(session.server)
            calendarView.value = calendarPreferences.value.view
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
        val accepted = client.disconnect(requestRevocation)
        connected.value = false
        server.value = ""
        calendarPreferences.value = CalendarPreferences()
        calendarView.value = CalendarView.WEEK
        clientId.value = ""
        clearLoadedInformation()
        lastConnection.value = null
        statusPrefs.edit().remove("last_connection").apply()
        message.value = if (!requestRevocation) "Disconnected on this device"
            else if (accepted) "Disconnected. Home Assistant accepted the revocation request."
            else "Disconnected here. Home Assistant revocation could not be confirmed."
        loading.value = false
    }
    fun clearLoadedInformation() {
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
    fun create(calendarId: String, date: LocalDate, title: String, done: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            val source = selectedWritable(calendarId) ?: error("Calendar unavailable or read-only. Choose another calendar.")
            client.createAllDay(source, date, title, events.value)
            setCalendarPreferences(calendarPreferences.value.used(calendarId))
            message.value = "Event created"
            done()
            refresh()
        } catch (e: Exception) { message.value = e.message ?: "Event was not created" }
        finally { loading.value = false }
    }
    fun edit(source: CalendarSource, event: AgendaEvent, title: String, done: () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            client.updateTitle(source, event, title)
            message.value = "Event updated"
            done()
            refresh()
        } catch (e: Exception) { message.value = e.message ?: "Event was not updated" }
        finally { loading.value = false }
    }
}
