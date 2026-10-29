package com.tinkercobble.ham.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A portable, password-encrypted copy of app-owned local content only. */
data class LocalBackupContent(val tasks: List<LocalTask>, val templates: List<EventTemplate>)

object PortableLocalBackup {
    private val magic = "HAMLOCAL1".toByteArray(Charsets.US_ASCII)
    private val aad = "com.tinkercobble.ham.local-backup.v1".toByteArray(Charsets.US_ASCII)
    private const val saltSize = 16
    private const val ivSize = 12
    private const val iterations = 600_000
    private const val maxFileSize = 2_000_000

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        require(password.size in 12..256) { "Use a backup passphrase of 12–256 characters" }
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }

    fun encrypt(content: LocalBackupContent, password: CharArray): ByteArray {
        val plain = encode(content)
        require(plain.size <= maxFileSize - 128) { "Local content is too large to back up" }
        val salt = ByteArray(saltSize).also(SecureRandom()::nextBytes)
        val iv = ByteArray(ivSize).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return try { magic + salt + iv + cipher.doFinal(plain) }
        finally { plain.fill(0) }
    }

    fun decrypt(file: ByteArray, password: CharArray): LocalBackupContent {
        require(file.size in (magic.size + saltSize + ivSize + 17)..maxFileSize &&
            file.copyOfRange(0, magic.size).contentEquals(magic)) { "This is not a supported HAM local backup" }
        val salt = file.copyOfRange(magic.size, magic.size + saltSize)
        val iv = file.copyOfRange(magic.size + saltSize, magic.size + saltSize + ivSize)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val plain = try { cipher.doFinal(file.copyOfRange(magic.size + saltSize + ivSize, file.size)) }
        catch (_: Exception) { throw IllegalArgumentException("Incorrect passphrase or damaged backup") }
        return try { decode(plain) } finally { plain.fill(0) }
    }

    private fun encode(content: LocalBackupContent): ByteArray {
        require(content.tasks.size <= 500 && content.templates.size <= 20)
        require(content.tasks.map { it.id }.distinct().size == content.tasks.size)
        require(content.templates.map { it.id }.distinct().size == content.templates.size)
        val tasks = JSONArray()
        content.tasks.forEach { task ->
            val item = task.validated()
            tasks.put(JSONObject().put("id", item.id).put("title", item.title)
                .put("notes", item.notes).put("completed", item.completed)
                .put("created_at", item.createdAt))
        }
        val templates = JSONArray()
        content.templates.forEach { template ->
            val item = template.validated()
            templates.put(JSONObject().put("id", item.id).put("name", item.name)
                .put("title", item.title).put("description", item.description)
                .put("location", item.location).put("all_day", item.allDay)
                .put("start", item.startTime).put("end", item.endTime)
                .put("frequency", item.repeat?.frequency?.name ?: "")
                .put("count", item.repeat?.count ?: 0))
        }
        return JSONObject().put("schema", 1).put("tasks", tasks).put("templates", templates)
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun decode(plain: ByteArray): LocalBackupContent {
        val root = JSONObject(String(plain, Charsets.UTF_8))
        require(root.getInt("schema") == 1) { "Unsupported backup version" }
        val taskArray = root.getJSONArray("tasks")
        val templateArray = root.getJSONArray("templates")
        require(taskArray.length() <= 500 && templateArray.length() <= 20) { "Backup content is too large" }
        val tasks = (0 until taskArray.length()).map { i ->
            val item = taskArray.getJSONObject(i)
            LocalTask(item.getString("id"), item.getString("title"), item.optString("notes"),
                item.getBoolean("completed"), item.getLong("created_at")).validated()
        }
        val templates = (0 until templateArray.length()).map { i ->
            val item = templateArray.getJSONObject(i)
            val frequency = item.optString("frequency").takeIf { it.isNotEmpty() }
            EventTemplate(item.getString("id"), item.getString("name"), item.getString("title"),
                item.optString("description"), item.optString("location"), item.getBoolean("all_day"),
                item.optString("start"), item.optString("end"), frequency?.let {
                    RepeatSpec(RepeatFrequency.valueOf(it), item.getInt("count"))
                }).validated()
        }
        require(tasks.map { it.id }.distinct().size == tasks.size &&
            templates.map { it.id }.distinct().size == templates.size) { "Backup has duplicate identities" }
        return LocalBackupContent(tasks, templates)
    }
}
