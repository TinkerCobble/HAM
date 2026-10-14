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
import com.tinkercobble.ham.data.HomeAssistantClient
import com.tinkercobble.ham.data.TodoEntry
import kotlinx.coroutines.launch
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
    var calendarPreferences = androidx.compose.runtime.mutableStateOf(calendarStore.load(client.server.orEmpty()))
    val visibleEvents get() = events.value.filterNot { it.calendarId in calendarPreferences.value.hidden }
    fun setCalendarPreferences(value: CalendarPreferences) {
        calendarPreferences.value = value
        calendarStore.save(server.value, value)
    }
    fun selectedWritable(id: String?) = CalendarSelection.resolveWritable(id, calendars.value)
    var calendars = androidx.compose.runtime.mutableStateOf<List<CalendarSource>>(emptyList())
    var events = androidx.compose.runtime.mutableStateOf<List<AgendaEvent>>(emptyList())
    var tasks = androidx.compose.runtime.mutableStateOf<List<TodoEntry>>(emptyList())
    var taskError = androidx.compose.runtime.mutableStateOf("")
    var loading = androidx.compose.runtime.mutableStateOf(false)
    var message = androidx.compose.runtime.mutableStateOf("")

    init { if (client.connected) refresh() }
    fun refresh() = viewModelScope.launch {
        if (!client.connected) return@launch
        loading.value = true
        try {
            val sources = client.calendars()
            val day = selectedDate.value
            calendars.value = sources
            events.value = client.events(sources, day.minusDays(35), day.plusDays(65))
            try {
                tasks.value = client.tasks()
                taskError.value = ""
            } catch (e: Exception) {
                tasks.value = emptyList()
                taskError.value = e.message ?: "Could not load tasks"
            }
            message.value = "Updated from Home Assistant"
            lastSync.value = Instant.now()
            lastConnection.value = lastSync.value
            statusPrefs.edit().putLong("last_sync", lastSync.value!!.toEpochMilli())
                .putLong("last_connection", lastConnection.value!!.toEpochMilli()).apply()
        } catch (e: Exception) { message.value = e.message ?: "Could not load calendars" }
        finally { loading.value = false }
    }
    fun move(days: Long) { selectedDate.value = selectedDate.value.plusDays(days); refresh() }
    fun today() { selectedDate.value = LocalDate.now(); refresh() }
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
