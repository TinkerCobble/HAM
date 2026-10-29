package com.tinkercobble.ham.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** An unscheduled task that belongs only to this device, never to a calendar provider. */
data class LocalTask(val id: String, val title: String, val notes: String,
    val completed: Boolean, val createdAt: Long) {
    fun validated(): LocalTask {
        require(runCatching { UUID.fromString(id) }.isSuccess) { "Invalid task identity" }
        val cleanTitle = title.trim()
        val cleanNotes = notes.trim()
        require(cleanTitle.isNotEmpty() && cleanTitle.length <= 200) { "Enter a task title of at most 200 characters" }
        require(cleanNotes.length <= 2000) { "Keep task notes under 2000 characters" }
        require(createdAt > 0) { "Invalid task date" }
        return copy(title = cleanTitle, notes = cleanNotes)
    }

    companion object {
        fun create(title: String, notes: String = "") =
            LocalTask(UUID.randomUUID().toString(), title, notes, false, System.currentTimeMillis()).validated()
    }
}

/** Reverse only the last known task state; leave every unrelated task untouched. */
object LocalTaskUndo {
    fun restore(current: List<LocalTask>, before: LocalTask?, after: LocalTask?): List<LocalTask>? {
        val id = before?.id ?: after?.id ?: return null
        if (before != null && after != null && before.id != after.id) return null
        if (current.firstOrNull { it.id == id } != after) return null
        return current.filterNot { it.id == id } + listOfNotNull(before)
    }
}

/** Protected app-private task list. Android app backup is disabled for this package. */
class LocalTaskStore(context: Context) {
    private companion object { val mutationLock = Any() }
    private val prefs = context.getSharedPreferences("ham-local-tasks", Context.MODE_PRIVATE)
    private val alias = "ham-local-tasks-v1"

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

    fun list(): List<LocalTask> {
        val encoded = prefs.getString("tasks", null) ?: return emptyList()
        require(encoded.length <= 2_000_000) { "Task storage is damaged" }
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        require(data.size > 12) { "Task storage is damaged" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val array = JSONArray(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        require(array.length() <= 500) { "Task storage is damaged" }
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            LocalTask(item.getString("id"), item.getString("title"), item.optString("notes"),
                item.getBoolean("completed"), item.getLong("created_at")).validated()
        }.also { items -> require(items.map { it.id }.distinct().size == items.size) }
    }

    private fun write(items: List<LocalTask>) {
        require(items.size <= 500 && items.map { it.id }.distinct().size == items.size) {
            "Keep at most 500 local tasks"
        }
        val array = JSONArray()
        items.forEach { task ->
            val item = task.validated()
            array.put(JSONObject().put("id", item.id).put("title", item.title)
                .put("notes", item.notes).put("completed", item.completed)
                .put("created_at", item.createdAt))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("tasks", Base64.encodeToString(data, Base64.NO_WRAP)).commit()) {
            "Could not save local tasks"
        }
    }

    fun save(task: LocalTask) = synchronized(mutationLock) {
        val item = task.validated()
        val old = list()
        write(old.filterNot { it.id == item.id } + item)
    }

    /** Checks whether an import can be merged without overwriting existing records. */
    fun additions(incoming: List<LocalTask>): Int {
        require(incoming.size <= 500 && incoming.map { it.id }.distinct().size == incoming.size)
        val proposed = incoming.map { it.validated() }
        val current = list()
        val byId = current.associateBy { it.id }
        require(proposed.none { byId[it.id] != null && byId[it.id] != it }) {
            "A local task has changed since this backup was made"
        }
        val additions = proposed.count { !byId.containsKey(it.id) }
        require(current.size + additions <= 500) { "There is not enough room for these local tasks" }
        return additions
    }

    /** Preserves existing records; retrying the same import cannot duplicate them. */
    fun merge(incoming: List<LocalTask>): Int = synchronized(mutationLock) {
        additions(incoming)
        val current = list()
        val byId = current.associateBy { it.id }
        val proposed = incoming.map { it.validated() }
        val additions = proposed.filterNot { byId.containsKey(it.id) }
        if (additions.isNotEmpty()) write(current + additions)
        additions.size
    }

    fun remove(id: String) = synchronized(mutationLock) {
        write(list().filterNot { it.id == id })
    }

    /** Returns false if this task changed again since the recorded action. */
    fun undo(before: LocalTask?, after: LocalTask?): Boolean = synchronized(mutationLock) {
        val restored = LocalTaskUndo.restore(list(), before, after) ?: return@synchronized false
        write(restored)
        true
    }
}
