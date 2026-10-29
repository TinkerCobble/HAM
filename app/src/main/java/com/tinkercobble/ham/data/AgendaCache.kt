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
import java.time.temporal.ChronoUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CachedAgenda(val calendars: List<CalendarSource>, val events: List<AgendaEvent>)

/** The last loaded event ranges are private, Keystore-encrypted, and excluded from Android backup. */
class AgendaCache(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "agenda-cache-v1.bin"))
    private val lock = Any()

    private fun key(): SecretKey {
        val alias = "ham-agenda-cache-v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun load(server: String): CachedAgenda? {
        return synchronized(lock) { try {
        if (server.isBlank()) return null
        val raw = file.openRead().use { it.readBytes() }
        if (raw.size < 29 || raw.size > 16_000_000) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        val data = JSONObject(String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8))
        if (data.optInt("version") != 1 || data.optString("server") != server) return null
        val calendars = data.getJSONArray("calendars").let { array -> (0 until array.length()).map { i ->
            array.getJSONObject(i).let { CalendarSource(it.getString("id"), it.getString("name"),
                it.getInt("features"), it.getString("source"),
                if (it.isNull("color")) null else it.optString("color").ifBlank { null },
                runCatching { CalendarProviderKind.valueOf(it.optString("provider", "HOME_ASSISTANT")) }
                    .getOrDefault(CalendarProviderKind.HOME_ASSISTANT),
                if (it.isNull("fingerprint")) null else it.optString("fingerprint").ifBlank { null }) }
        } }
        val events = data.getJSONArray("events").let { array -> (0 until array.length()).map { i ->
            array.getJSONObject(i).let { AgendaEvent(it.getString("calendar_id"), it.getString("calendar_name"),
                it.getString("summary"), it.getString("start"), it.getString("end"), it.getBoolean("all_day"),
                it.getString("description"), it.getString("location"),
                if (it.isNull("uid")) null else it.optString("uid").ifBlank { null },
                if (it.isNull("recurrence_id")) null else it.optString("recurrence_id").ifBlank { null },
                runCatching { CalendarProviderKind.valueOf(it.optString("provider", "HOME_ASSISTANT")) }
                    .getOrDefault(CalendarProviderKind.HOME_ASSISTANT),
                if (it.isNull("rrule")) null else it.optString("rrule").ifBlank { null },
                it.optBoolean("recurrence_known", false)) }
        } }
        CachedAgenda(calendars, events)
        } catch (_: Exception) { null } }
    }

    fun save(server: String, agenda: CachedAgenda, focusDate: LocalDate) {
        synchronized(lock) {
        if (server.isBlank()) return
        val calendars = JSONArray()
        agenda.calendars.forEach { source -> calendars.put(JSONObject().put("id", source.id).put("name", source.name)
            .put("features", source.features).put("source", source.source).put("color", source.haColor)
            .put("provider", source.provider.name).put("fingerprint", source.identityFingerprint)) }
        val events = JSONArray()
        agenda.events.sortedBy { event -> kotlin.math.abs(ChronoUnit.DAYS.between(focusDate, event.date)) }
            .take(2000).forEach { event -> events.put(JSONObject()
            .put("calendar_id", event.calendarId).put("calendar_name", event.calendarName)
            .put("summary", event.summary).put("start", event.start).put("end", event.end)
            .put("all_day", event.allDay).put("description", event.description).put("location", event.location)
            .put("uid", event.uid).put("recurrence_id", event.recurrenceId)
            .put("rrule", event.rrule).put("recurrence_known", event.recurrenceKnown)
            .put("provider", event.provider.name)) }
        val value = JSONObject().put("version", 1).put("server", server)
            .put("calendars", calendars).put("events", events).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        }
    }

    fun clear() = synchronized(lock) { file.delete() }
}
