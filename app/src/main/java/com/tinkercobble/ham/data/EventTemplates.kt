package com.tinkercobble.ham.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.time.LocalTime
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A reusable form draft. Calendar identity and event date are deliberately excluded. */
data class EventTemplate(val id: String, val name: String, val title: String,
    val description: String, val location: String, val allDay: Boolean,
    val startTime: String, val endTime: String, val repeat: RepeatSpec?) {
    fun validated(): EventTemplate {
        require(runCatching { UUID.fromString(id) }.isSuccess) { "Invalid template identity" }
        val cleanName = name.trim()
        val cleanTitle = title.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 80 &&
            cleanTitle.isNotEmpty() && cleanTitle.length <= 200) { "Enter a template name and event title" }
        val details = EventDraftDetails.of(description, location)
        if (!allDay) {
            require(Regex("^[0-9]{2}:[0-9]{2}$").matches(startTime) &&
                Regex("^[0-9]{2}:[0-9]{2}$").matches(endTime)) { "Use HH:mm template times" }
            val start = LocalTime.parse(startTime)
            val end = LocalTime.parse(endTime)
            require(end.isAfter(start)) {
                "Template end time must be later on the same day"
            }
        }
        return copy(name = cleanName, title = cleanTitle,
            description = details.description, location = details.location)
    }
}

/** User-created event form templates, protected by a dedicated nonexportable Keystore key. */
class EventTemplateStore(context: Context) {
    private val prefs = context.getSharedPreferences("ham-event-templates", Context.MODE_PRIVATE)
    private val alias = "ham-event-templates-v1"

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

    fun list(): List<EventTemplate> {
        val encoded = prefs.getString("templates", null) ?: return emptyList()
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        require(data.size > 12) { "Template storage is damaged" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val array = JSONArray(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        require(array.length() <= 20) { "Template storage is damaged" }
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val frequency = item.optString("frequency").takeIf { it.isNotEmpty() }
            EventTemplate(item.getString("id"), item.getString("name"), item.getString("title"),
                item.optString("description"), item.optString("location"), item.getBoolean("all_day"),
                item.optString("start"), item.optString("end"), frequency?.let {
                    RepeatSpec(RepeatFrequency.valueOf(it), item.getInt("count"))
                }).validated()
        }.also { items -> require(items.map { it.id }.distinct().size == items.size) }
    }

    private fun write(items: List<EventTemplate>) {
        require(items.size <= 20 && items.map { it.id }.distinct().size == items.size)
        val array = JSONArray()
        items.forEach { template ->
            val item = template.validated()
            array.put(JSONObject().put("id", item.id).put("name", item.name).put("title", item.title)
                .put("description", item.description).put("location", item.location)
                .put("all_day", item.allDay).put("start", item.startTime).put("end", item.endTime)
                .put("frequency", item.repeat?.frequency?.name ?: "")
                .put("count", item.repeat?.count ?: 0))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("templates", Base64.encodeToString(data, Base64.NO_WRAP)).commit()) {
            "Could not save event templates"
        }
    }

    fun save(template: EventTemplate) {
        val item = template.validated()
        val current = list()
        val next = current.filterNot { it.id == item.id } + item
        require(next.size <= 20) { "Keep at most 20 event templates" }
        write(next)
    }

    /** Checks whether an import can be merged without overwriting existing records. */
    fun additions(incoming: List<EventTemplate>): Int {
        require(incoming.size <= 20 && incoming.map { it.id }.distinct().size == incoming.size)
        val proposed = incoming.map { it.validated() }
        val current = list()
        val byId = current.associateBy { it.id }
        require(proposed.none { byId[it.id] != null && byId[it.id] != it }) {
            "An event template has changed since this backup was made"
        }
        val additions = proposed.count { !byId.containsKey(it.id) }
        require(current.size + additions <= 20) { "There is not enough room for these event templates" }
        return additions
    }

    /** Preserves existing records; retrying the same import cannot duplicate them. */
    fun merge(incoming: List<EventTemplate>): Int {
        additions(incoming)
        val current = list()
        val byId = current.associateBy { it.id }
        val proposed = incoming.map { it.validated() }
        val additions = proposed.filterNot { byId.containsKey(it.id) }
        if (additions.isNotEmpty()) write(current + additions)
        return additions.size
    }

    fun remove(id: String) { write(list().filterNot { it.id == id }) }
}
