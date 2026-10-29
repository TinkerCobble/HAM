package com.tinkercobble.ham.data

import android.content.Context
import com.tinkercobble.ham.auth.HomeAssistantAuth
import com.tinkercobble.ham.auth.SecureSession
import com.tinkercobble.ham.auth.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

interface HaMutationTransport {
    fun sessionBinding(): String?
    suspend fun calendars(): List<CalendarSource>
    suspend fun events(source: CalendarSource, from: LocalDate, until: LocalDate): List<AgendaEvent>
    suspend fun send(command: JSONObject)
}

class HomeAssistantClient(context: Context) {
    data class EventRead(val events: List<AgendaEvent>, val succeeded: Set<String>, val failed: Map<String, String>)
    private val store = SecureSession(context)
    @Volatile private var session: Session? = store.load()
    private val refreshLock = Mutex()
    val connected get() = session != null
    val server get() = session?.server
    val clientId get() = session?.clientId
    private fun binding(value: Session): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf(value.server, value.clientId, value.refreshToken)
            .joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** A nonreversible key for one server/client authorization; no token enters the queue. */
    fun sessionBinding(): String? = store.load()?.let(::binding)

    fun mutationTransport(): HaMutationTransport = object : HaMutationTransport {
        override fun sessionBinding() = this@HomeAssistantClient.sessionBinding()
        override suspend fun calendars() = this@HomeAssistantClient.calendars()
        override suspend fun events(source: CalendarSource, from: LocalDate, until: LocalDate) =
            this@HomeAssistantClient.events(listOf(source), from, until)
        override suspend fun send(command: JSONObject) {
            val inMemory = session ?: error("Reconnect Home Assistant before syncing")
            check(sessionBinding() == binding(inMemory)) { "Home Assistant sign-in changed" }
            calendarCommand(command, "Pending calendar change rejected")
        }
    }
    fun setSession(value: Session) { store.save(value); session = value }
    suspend fun disconnect(requestRevocation: Boolean): Boolean {
        val accepted = if (requestRevocation) session?.let { HomeAssistantAuth.requestRevocation(it) } ?: false else false
        refreshLock.withLock {
            store.clear()
            session = null
        }
        return accepted
    }

    private suspend fun current(): Session {
        val existing = session ?: error("Connect to Home Assistant first")
        if (existing.expiresAt > System.currentTimeMillis() + 60000) return existing
        return refreshLock.withLock {
            val latest = session ?: error("Connect to Home Assistant first")
            if (latest.expiresAt > System.currentTimeMillis() + 60000) latest else {
                val renewed = HomeAssistantAuth.refresh(latest)
                setSession(renewed)
                renewed
            }
        }
    }

    private suspend fun refreshAfterUnauthorized(used: Session): Session = refreshLock.withLock {
        val latest = session ?: error("Connect to Home Assistant first")
        check(latest.server == used.server && latest.clientId == used.clientId) {
            "Home Assistant connection changed. Refresh the calendar."
        }
        if (latest.accessToken != used.accessToken) latest else {
            val renewed = HomeAssistantAuth.refresh(latest)
            setSession(renewed)
            renewed
        }
    }

    private suspend fun request(path: String, payload: JSONObject? = null): String = withContext(Dispatchers.IO) {
        var auth = current()
        repeat(2) { attempt ->
            val connection = URL(auth.server + path).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false // Never forward a bearer token to another host.
            connection.connectTimeout = 10000
            connection.readTimeout = 20000
            connection.setRequestProperty("Authorization", "Bearer ${auth.accessToken}")
            connection.setRequestProperty("Accept", "application/json")
            try {
                if (payload != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                if (code == 401 && payload == null && attempt == 0) {
                    auth = refreshAfterUnauthorized(auth)
                    return@repeat
                }
                if (code == 401) error("Home Assistant session was rejected. Reconnect Home Assistant.")
                if (code !in 200..299) error("Home Assistant request failed ($code)")
                return@withContext connection.inputStream.bufferedReader().use { it.readText() }
            } finally { connection.disconnect() }
        }
        error("Home Assistant request could not complete")
    }

    suspend fun calendars(): List<CalendarSource> {
        val array = JSONArray(request("/api/calendars"))
        val states = JSONArray(request("/api/states"))
        val features = mutableMapOf<String, Int>()
        val colors = mutableMapOf<String, String>()
        for (i in 0 until states.length()) {
            val item = states.getJSONObject(i)
            val id = item.optString("entity_id")
            if (id.startsWith("calendar.")) {
                val attrs = item.optJSONObject("attributes")
                features[id] = attrs?.optInt("supported_features") ?: 0
                attrs?.optString("color")?.takeIf { Regex("#[0-9A-Fa-f]{6}").matches(it) }?.let { colors[id] = it }
            }
        }
        return (0 until array.length()).map { array.getJSONObject(it) }
            .map { CalendarSource(it.getString("entity_id"), it.optString("name", it.getString("entity_id")), features[it.getString("entity_id")] ?: 0,
                it.optString("source").ifBlank { "Not provided by Home Assistant" },
                it.optString("color").takeIf { color -> Regex("#[0-9A-Fa-f]{6}").matches(color) } ?: colors[it.getString("entity_id")]) }
            .sortedBy { it.name.lowercase() }
    }

    suspend fun ping() { request("/api/") }

    suspend fun tasks(): List<TodoEntry> {
        val states = JSONArray(request("/api/states"))
        val lists = (0 until states.length()).map { states.getJSONObject(it) }
            .filter { it.optString("entity_id").startsWith("todo.") }
        if (lists.isEmpty()) return emptyList()
        val ids = lists.map { it.getString("entity_id") }
        val payload = JSONObject().put("entity_id", JSONArray(ids)).put("status", "needs_action")
        val response = JSONObject(request("/api/services/todo/get_items?return_response", payload))
        val service = response.optJSONObject("service_response") ?: response
        val names = lists.associate { it.getString("entity_id") to it.optJSONObject("attributes")?.optString("friendly_name", it.getString("entity_id")) }
        return ids.flatMap { id ->
            val items = service.optJSONObject(id)?.optJSONArray("items") ?: JSONArray()
            (0 until items.length()).map { i ->
                val item = items.getJSONObject(i)
                TodoEntry(names[id] ?: id, item.optString("summary"), item.optString("due").ifBlank { null },
                    item.optString("uid").ifBlank { null })
            }
        }
    }

    suspend fun events(sources: List<CalendarSource>, from: LocalDate, until: LocalDate): List<AgendaEvent> {
        return distinctEvents(sources.flatMap { eventsFor(it, from, until) })
    }

    suspend fun readEvents(sources: List<CalendarSource>, from: LocalDate, until: LocalDate): EventRead = supervisorScope {
        val results = mutableListOf<AgendaEvent>()
        val succeeded = mutableSetOf<String>()
        val failed = mutableMapOf<String, String>()
        val limit = Semaphore(4)
        val reads = sources.map { source -> async {
            limit.withPermit {
                try { source to Result.success(eventsFor(source, from, until)) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { source to Result.failure(e) }
            }
        } }.awaitAll()
        for ((source, outcome) in reads) {
            outcome.onSuccess { results += it; succeeded += source.id }
                .onFailure { failed[source.id] = it.message ?: "Request failed" }
        }
        EventRead(distinctEvents(results), succeeded, failed)
    }

    private suspend fun eventsFor(source: CalendarSource, from: LocalDate, until: LocalDate): List<AgendaEvent> {
        val zone = ZoneId.systemDefault()
        val start = java.net.URLEncoder.encode(from.atStartOfDay(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "UTF-8")
        val end = java.net.URLEncoder.encode(until.atStartOfDay(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "UTF-8")
        val array = JSONArray(request("/api/calendars/${source.id}?start=$start&end=$end"))
        return (0 until array.length()).map { AgendaEvent.fromJson(source, array.getJSONObject(it)) }
    }

    /** The attachment endpoint belongs to HAM's optional authenticated HA integration. */
    suspend fun uploadScreenshot(file: File): String = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() in 1..10_485_760) { "Choose a screenshot under 10 MB" }
        val auth = current()
        val connection = URL(auth.server + "/api/ham_attachments").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer ${auth.accessToken}")
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        try {
            file.inputStream().use { input -> connection.outputStream.use { output -> input.copyTo(output) } }
            val status = connection.responseCode
            if (status !in 200..299) error("Secure screenshot storage unavailable ($status). Event was not created.")
            val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val localHash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            require(result.optString("sha256").equals(localHash, true)) { "Stored screenshot checksum differs" }
            val id = result.getString("id")
            require(Regex("[0-9a-f-]{36}").matches(id)) { "Invalid attachment response" }
            id
        } finally { connection.disconnect() }
    }

    suspend fun screenshot(id: String): ByteArray = withContext(Dispatchers.IO) {
        require(Regex("[0-9a-f-]{36}").matches(id))
        val auth = current()
        val connection = URL(auth.server + "/api/ham_attachments/$id").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Authorization", "Bearer ${auth.accessToken}")
        try {
            if (connection.responseCode !in 200..299) error("Screenshot is unavailable")
            connection.inputStream.use { stream ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(result.size() + count <= 10_485_760) { "Screenshot exceeds size limit" }
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
        } finally { connection.disconnect() }
    }

    suspend fun deleteScreenshot(id: String) = withContext(Dispatchers.IO) {
        require(Regex("[0-9a-f-]{36}").matches(id))
        val auth = current()
        val connection = URL(auth.server + "/api/ham_attachments/$id").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.requestMethod = "DELETE"
        connection.setRequestProperty("Authorization", "Bearer ${auth.accessToken}")
        try { if (connection.responseCode !in 200..299 && connection.responseCode != 404)
            error("Could not remove unused screenshot from Home Assistant") }
        finally { connection.disconnect() }
    }

    suspend fun createScreenshotEvent(sourceId: String, date: LocalDate, title: String,
        start: LocalTime?, end: LocalTime?, location: String, description: String,
        attachmentId: String, allowDuplicate: Boolean = false): AgendaEvent {
        val source = CalendarSelection.resolveWritable(sourceId, calendars())
            ?: error("Calendar unavailable or read-only. Choose another calendar.")
        require(title.isNotBlank()) { "Enter an event title" }
        require(start == null && end == null || start != null && end != null && end.isAfter(start)) {
            "Enter a valid start and end time, or choose all-day"
        }
        require(Regex("[0-9a-f-]{36}").matches(attachmentId)) { "Invalid screenshot reference" }
        // A fresh read is mandatory; this also makes a retry after a lost service response safe.
        val existing = events(listOf(source), date, date.plusDays(2))
        val marker = "ham-attachment://$attachmentId"
        existing.firstOrNull { it.description.contains(marker) }?.let { return it }
        if (!allowDuplicate && existing.any { it.date == date && it.summary.equals(title.trim(), true) })
            error("A similar event already exists on this calendar and date. Review it before saving another.")
        val data = JSONObject().put("entity_id", source.id).put("summary", title.trim())
            .put("description", description.trim() + "\n\nHAM screenshot: $marker\nOpen in HAM on a connected device.")
            .put("location", location.trim())
        if (start == null || end == null) {
            data.put("start_date", date.toString()).put("end_date", date.plusDays(1).toString())
        } else {
            val zone = ZoneId.systemDefault()
            data.put("start_date_time", date.atTime(start).atZone(zone).toOffsetDateTime().toString())
                .put("end_date_time", date.atTime(end).atZone(zone).toOffsetDateTime().toString())
        }
        request("/api/services/calendar/create_event", data)
        return events(listOf(source), date, date.plusDays(2)).firstOrNull { it.description.contains(marker) }
            ?: error("Home Assistant accepted the request, but event read-back is pending. Do not retry until the calendar refreshes.")
    }

    suspend fun updateTitle(source: CalendarSource, event: AgendaEvent, title: String): Boolean =
        updateEvent(source, event, title)

    suspend fun updateTimed(source: CalendarSource, event: AgendaEvent, title: String,
        startText: String, endText: String): Boolean =
        updateEvent(source, event, title, startText, endText)

    private suspend fun updateEvent(source: CalendarSource, event: AgendaEvent, title: String,
        startText: String? = null, endText: String? = null): Boolean {
        val currentSource = calendars().singleOrNull {
            it.id == source.id && it.provider == CalendarProviderKind.HOME_ASSISTANT && it.canUpdate
        } ?: error("This calendar is unavailable or does not permit editing")
        val live = events(listOf(currentSource), event.date.minusDays(1), event.date.plusDays(2))
        val command = HomeAssistantCalendarMutation.updateCommand(currentSource, event, live,
            title, startText, endText)
        calendarCommand(command, "Update rejected")
        // The provider may project the accepted change later; a failed post-write read is
        // unconfirmed and must not be reported as a rejected command or automatically replayed.
        return try {
            HomeAssistantCalendarMutation.updateVisible(currentSource, event, command,
                events(listOf(currentSource), event.date.minusDays(1), event.date.plusDays(2)))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    /** Delete one exact nonrecurring event on a calendar that still advertises delete support. */
    suspend fun deleteEvent(source: CalendarSource, event: AgendaEvent): Boolean {
        val currentSource = calendars().singleOrNull {
            it.id == source.id && it.provider == CalendarProviderKind.HOME_ASSISTANT && it.canDelete
        } ?: error("This calendar is unavailable or does not permit removal")
        val from = event.date.minusDays(1)
        val until = event.date.plusDays(2)
        val live = events(listOf(currentSource), from, until)
        val command = HomeAssistantCalendarMutation.deleteCommand(currentSource, event, live)
        calendarCommand(command, "Removal rejected")
        // A delayed provider projection is an accepted but unconfirmed removal, not a reason to resend.
        return runCatching { events(listOf(currentSource), from, until)
            .none { it.calendarId == source.id && it.uid == event.uid } }.getOrDefault(false)
    }

    private suspend fun calendarCommand(command: JSONObject, rejection: String) {
        val auth = current()
        val result = CompletableFuture<JSONObject>()
        val wsUrl = auth.server.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/websocket"
        val socket = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .readTimeout(20, TimeUnit.SECONDS).build().newWebSocket(
            Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        when (json.optString("type")) {
                            "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", auth.accessToken).toString())
                            "auth_ok" -> webSocket.send(command.toString())
                            "result" -> if (json.optInt("id") == command.optInt("id")) result.complete(json)
                            "auth_invalid" -> result.completeExceptionally(IllegalStateException("Home Assistant session expired"))
                        }
                    } catch (e: Exception) { result.completeExceptionally(e) }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { result.completeExceptionally(t) }
            })
        try {
            val reply = withContext(Dispatchers.IO) { withTimeout(25000) { result.get(25, TimeUnit.SECONDS) } }
            if (!reply.optBoolean("success")) error(reply.optJSONObject("error")?.optString("message") ?: rejection)
        } finally { socket.close(1000, "Done") }
    }
}
