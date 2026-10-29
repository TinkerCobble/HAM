package com.tinkercobble.ham.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class LocalReminderRecord(
    val id: String,
    val eventKey: String,
    val sourceKey: String,
    val duplicateKey: String,
    val eventDate: LocalDate,
    val eventStart: String,
    val allDay: Boolean,
    val active: Boolean,
    val whenToNotify: LocalReminderWhen,
    val minutesBefore: Int,
    val triggerAt: Instant,
) {
    val key: String get() = "$eventKey|${whenToNotify.name}|$minutesBefore"

    fun validated(): LocalReminderRecord {
        require(runCatching { UUID.fromString(id) }.isSuccess)
        require(eventKey.matches(Regex("[0-9a-f]{64}")) &&
            sourceKey.matches(Regex("[0-9a-f]{64}")) &&
            duplicateKey.matches(Regex("[0-9a-f]{64}")))
        require(minutesBefore in 0..AndroidReminders.MAX_MINUTES)
        require(whenToNotify == LocalReminderWhen.MINUTES_BEFORE || minutesBefore == 0)
        require(eventDate.year in 1900..2200)
        require(eventStart.length in 10..64)
        LocalReminderPlanner.trigger(eventStart, allDay, whenToNotify, minutesBefore)
        return this
    }

    companion object {
        fun from(plan: LocalReminderPlan) = LocalReminderRecord(UUID.randomUUID().toString(),
            plan.eventKey, plan.sourceKey, plan.duplicateKey, plan.eventDate,
            plan.eventStart, plan.allDay, true, plan.whenToNotify,
            plan.minutesBefore, plan.triggerAt)
    }
}

/** Encrypted device-local reminder preferences; no event titles, URLs or credentials are retained. */
class LocalReminderStore(context: Context) {
    private val prefs = context.getSharedPreferences("ham-local-reminders", Context.MODE_PRIVATE)
    private val alias = "ham-local-reminders-v1"
    companion object { private val lock = Any() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun list(): List<LocalReminderRecord> = synchronized(lock) { read() }

    private fun read(): List<LocalReminderRecord> {
        val encoded = prefs.getString("records", null) ?: return emptyList()
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        require(data.size > 12) { "Reminder storage is damaged" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val array = JSONArray(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        require(array.length() <= 100) { "Reminder storage is damaged" }
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            LocalReminderRecord(item.getString("id"), item.getString("event_key"),
                item.getString("source_key"),
                item.getString("duplicate_key"), LocalDate.parse(item.getString("event_date")),
                item.getString("event_start"), item.getBoolean("all_day"),
                item.getBoolean("active"), LocalReminderWhen.valueOf(item.getString("when")),
                item.getInt("minutes"), Instant.ofEpochMilli(item.getLong("trigger"))).validated()
        }.also { records ->
            require(records.map { it.id }.distinct().size == records.size &&
                records.map { it.key }.distinct().size == records.size) {
                "Reminder storage is damaged"
            }
        }
    }

    private fun write(records: List<LocalReminderRecord>) {
        require(records.size <= 100 && records.map { it.id }.distinct().size == records.size)
        val array = JSONArray()
        records.forEach { record ->
            val item = record.validated()
            array.put(JSONObject().put("id", item.id).put("event_key", item.eventKey)
                .put("source_key", item.sourceKey)
                .put("duplicate_key", item.duplicateKey).put("event_date", item.eventDate.toString())
                .put("event_start", item.eventStart).put("all_day", item.allDay)
                .put("active", item.active).put("when", item.whenToNotify.name)
                .put("minutes", item.minutesBefore).put("trigger", item.triggerAt.toEpochMilli()))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("records", Base64.encodeToString(data, Base64.NO_WRAP)).commit()) {
            "Could not save local reminders"
        }
    }

    /** Add only when no equivalent reminder already exists, including another connection to the same UID. */
    fun add(plan: LocalReminderPlan): LocalReminderRecord = synchronized(lock) {
        val records = read()
        check(records.none { it.key == plan.key }) { "This local reminder already exists" }
        check(records.none { it.duplicateKey == plan.duplicateKey &&
            it.whenToNotify == plan.whenToNotify && it.minutesBefore == plan.minutesBefore }) {
            "An equivalent local reminder exists on another calendar connection"
        }
        check(records.size < 100) { "Local reminder limit reached" }
        LocalReminderRecord.from(plan).also { write(records + it) }
    }

    fun remove(id: String): LocalReminderRecord = synchronized(lock) {
        val records = read()
        val existing = records.singleOrNull { it.id == id } ?: error("Reminder changed")
        write(records.filterNot { it.id == id })
        existing
    }

    fun removeSource(sourceKey: String): List<LocalReminderRecord> = synchronized(lock) {
        val records = read()
        val removed = records.filter { it.sourceKey == sourceKey }
        if (removed.isNotEmpty()) write(records.filterNot { it.sourceKey == sourceKey })
        removed
    }

    /** Refresh a stable UID association after a successful calendar read. */
    fun refreshEvent(event: AgendaEvent): List<Pair<LocalReminderRecord, LocalReminderRecord>> =
        synchronized(lock) {
            val eventKey = LocalReminderPlanner.plan(event, LocalReminderWhen.MORNING_OF).eventKey
            val records = read()
            val changed = records.filter { it.eventKey == eventKey }.mapNotNull { old ->
                val plan = LocalReminderPlanner.plan(event, old.whenToNotify, old.minutesBefore)
                val next = old.copy(duplicateKey = plan.duplicateKey, eventDate = plan.eventDate,
                    eventStart = plan.eventStart, allDay = plan.allDay,
                    active = true, triggerAt = plan.triggerAt)
                if (next == old) null else old to next
            }
            if (changed.isNotEmpty()) {
                val byId = changed.associate { it.first.id to it.second }
                write(records.map { byId[it.id] ?: it })
            }
            changed
        }

    /** Preserve date-based local wall-clock choices across time-zone changes. */
    fun retime(zone: ZoneId = ZoneId.systemDefault()):
        List<Pair<LocalReminderRecord, LocalReminderRecord>> = synchronized(lock) {
        val records = read()
        val changed = records.mapNotNull { old ->
            val (day, instant) = LocalReminderPlanner.trigger(old.eventStart, old.allDay,
                old.whenToNotify, old.minutesBefore, zone)
            val next = old.copy(eventDate = day, triggerAt = instant)
            if (next == old) null else old to next
        }
        if (changed.isNotEmpty()) {
            val byId = changed.associate { it.first.id to it.second }
            write(records.map { byId[it.id] ?: it })
        }
        changed
    }

    /** Suspend only absent events within a fully read, selected source and covered date range. */
    fun suspendMissing(completeSourceKeys: Set<String>, range: DateRange,
        presentEventKeys: Set<String>): List<LocalReminderRecord> = synchronized(lock) {
        if (completeSourceKeys.isEmpty()) return@synchronized emptyList()
        val records = read()
        val suspended = records.filter { it.active && it.sourceKey in completeSourceKeys &&
            range.contains(it.eventDate) && it.eventKey !in presentEventKeys }
        if (suspended.isNotEmpty()) {
            val ids = suspended.map { it.id }.toSet()
            write(records.map { if (it.id in ids) it.copy(active = false) else it })
        }
        suspended
    }
}
