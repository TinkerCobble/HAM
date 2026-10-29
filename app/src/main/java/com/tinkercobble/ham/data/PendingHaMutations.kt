package com.tinkercobble.ham.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class HaOperation { CREATE, UPDATE, DELETE }
enum class HaPendingState { QUEUED, AWAITING_CONFIRMATION, ACCEPTED, CONFLICT }

data class HaTarget(val summary: String, val start: String, val end: String,
    val description: String, val location: String)

data class PendingHaMutation(val id: UUID, val sessionBinding: String,
    val sourceId: String, val original: AgendaEvent, val operation: HaOperation,
    val target: HaTarget?, val state: HaPendingState = HaPendingState.QUEUED)

/** Keystore-encrypted, atomic queue. Authorization tokens never enter this file. */
class PendingHaMutations(context: Context) {
    private companion object {
        val lock = Any()
        const val MAX_FILE = 16_000_000
        const val MAX_ITEMS = 100
    }
    private val file = AtomicFile(File(context.filesDir, "pending-ha-v1.bin"))
    private val alias = "ham-pending-ha-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private fun eventJson(event: AgendaEvent) = JSONObject()
        .put("id", event.calendarId).put("name", event.calendarName)
        .put("summary", event.summary).put("start", event.start).put("end", event.end)
        .put("allDay", event.allDay).put("description", event.description)
        .put("location", event.location).put("uid", event.uid ?: JSONObject.NULL)

    private fun event(json: JSONObject) = AgendaEvent(json.getString("id"), json.getString("name"),
        json.getString("summary"), json.getString("start"), json.getString("end"),
        json.getBoolean("allDay"), json.getString("description"), json.getString("location"),
        if (json.isNull("uid")) null else json.getString("uid"), null, recurrenceKnown = true)

    private fun targetJson(target: HaTarget) = JSONObject().put("summary", target.summary)
        .put("start", target.start).put("end", target.end)
        .put("description", target.description).put("location", target.location)

    private fun target(json: JSONObject) = HaTarget(json.getString("summary"),
        json.getString("start"), json.getString("end"), json.getString("description"),
        json.getString("location"))

    fun list(): List<PendingHaMutation> = synchronized(lock) {
        if (!file.baseFile.exists()) return@synchronized emptyList()
        val raw = file.openRead().use { it.readBytes() }
        require(raw.size in 29..MAX_FILE) { "Pending Home Assistant queue is unreadable" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        val data = JSONObject(String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8))
        require(data.getInt("version") == 1) { "Pending Home Assistant queue version changed" }
        val items = data.getJSONArray("items")
        require(items.length() <= MAX_ITEMS) { "Too many pending Home Assistant changes" }
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val operation = HaOperation.valueOf(item.getString("operation"))
            PendingHaMutation(UUID.fromString(item.getString("id")), item.getString("binding"),
                item.getString("source"), event(item.getJSONObject("original")), operation,
                if (item.isNull("target")) null else target(item.getJSONObject("target")),
                HaPendingState.valueOf(item.getString("state"))).also(::validate)
        }
    }

    private fun validate(item: PendingHaMutation) {
        val validCreateBounds = if (item.operation != HaOperation.CREATE) true else
            runCatching {
                if (item.original.allDay) LocalDate.parse(item.original.end)
                    .isAfter(LocalDate.parse(item.original.start))
                else OffsetDateTime.parse(item.original.start).toInstant().isBefore(
                    OffsetDateTime.parse(item.original.end).toInstant())
            }.getOrDefault(false)
        require(item.sessionBinding.matches(Regex("[0-9a-f]{64}")) &&
            item.sourceId.startsWith("calendar.") &&
            (item.operation != HaOperation.CREATE || item.sourceId.length <= 256) &&
            item.original.calendarId == item.sourceId &&
            item.original.provider == CalendarProviderKind.HOME_ASSISTANT &&
            (if (item.operation == HaOperation.CREATE) item.original.uid == null else
                !item.original.uid.isNullOrBlank()) && item.original.recurrenceId == null &&
            item.original.recurrenceKnown && item.original.rrule == null &&
            (if (item.operation == HaOperation.CREATE)
                item.original.summary.isNotBlank() && item.original.summary.length <= 200
            else item.original.summary.length <= 500) &&
            item.original.description.length <= 65536 &&
            item.original.location.length <= 8192 &&
            (item.operation != HaOperation.CREATE ||
                item.original.start.length <= 100 && item.original.end.length <= 100) &&
            validCreateBounds &&
            (item.operation == HaOperation.UPDATE) == (item.target != null)) {
            "Invalid pending Home Assistant change"
        }
        item.target?.let { target ->
            require(target.summary.isNotBlank() && target.summary.length <= 200 &&
                target.description.length <= 65536 && target.location.length <= 8192 &&
                target.start.length <= 100 && target.end.length <= 100) {
                "Invalid pending Home Assistant target"
            }
        }
    }

    private fun write(items: List<PendingHaMutation>) {
        require(items.size <= MAX_ITEMS) { "Too many pending Home Assistant changes" }
        val array = JSONArray()
        items.forEach { item ->
            validate(item)
            array.put(JSONObject().put("id", item.id.toString())
                .put("binding", item.sessionBinding).put("source", item.sourceId)
                .put("original", eventJson(item.original)).put("operation", item.operation.name)
                .put("target", item.target?.let(::targetJson)).put("state", item.state.name))
        }
        val plain = JSONObject().put("version", 1).put("items", array).toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(plain)
        require(encrypted.size <= MAX_FILE) { "Pending Home Assistant queue is too large" }
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }

    fun enqueue(change: PendingHaMutation) = synchronized(lock) {
        val items = list()
        require(items.none { it.id == change.id || it.sessionBinding == change.sessionBinding &&
            it.sourceId == change.sourceId &&
            (if (it.operation == HaOperation.CREATE && change.operation == HaOperation.CREATE)
                it.original.summary.equals(change.original.summary, true) &&
                    it.original.start == change.original.start &&
                    it.original.end == change.original.end
            else it.operation != HaOperation.CREATE && change.operation != HaOperation.CREATE &&
                it.original.uid == change.original.uid) }) {
            "This event already has a pending Home Assistant change"
        }
        write(items + change)
    }

    fun mark(id: UUID, state: HaPendingState) = synchronized(lock) {
        val items = list()
        require(items.any { it.id == id }) { "Pending Home Assistant change is missing" }
        write(items.map { if (it.id == id) it.copy(state = state) else it })
    }

    fun removeConfirmed(id: UUID) = synchronized(lock) { write(list().filterNot { it.id == id }) }
    fun discard(id: UUID) = synchronized(lock) {
        val items = list()
        require(items.any { it.id == id }) { "Pending Home Assistant change is missing" }
        write(items.filterNot { it.id == id })
    }
}
