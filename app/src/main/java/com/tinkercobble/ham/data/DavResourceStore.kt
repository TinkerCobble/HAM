package com.tinkercobble.ham.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class DavStoredResource(val accountId: String, val calendarId: String,
    val url: String, val etag: String, val uid: String?, val ics: ByteArray)

/** Latest successfully read resource metadata. A missing or ambiguous UID is never editable. */
class DavResourceStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "dav-resources-v1.bin"))
    private val lock = Any()
    private val alias = "ham-dav-resources-v1"
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

    fun list(): List<DavStoredResource> = synchronized(lock) {
        if (!file.baseFile.exists()) return@synchronized emptyList()
        val raw = file.openRead().use { it.readBytes() }
        require(raw.size in 29..16_000_000) { "Calendar resource index is unreadable" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        val data = JSONObject(String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8))
        require(data.getInt("version") == 1) { "Calendar resource index version changed" }
        val items = data.getJSONArray("items")
        require(items.length() <= 2000) { "Too many calendar resources" }
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val ics = Base64.decode(item.getString("ics"), Base64.NO_WRAP)
            require(ics.size in 1..512 * 1024) { "Calendar resource is too large" }
            DavStoredResource(item.getString("account"), item.getString("calendar"),
                item.getString("url"), item.getString("etag"),
                if (item.isNull("uid")) null else item.getString("uid"), ics)
        }
    }

    private fun write(items: List<DavStoredResource>) {
        require(items.size <= 2000) { "Too many calendar resources" }
        val array = JSONArray()
        items.forEach { item ->
            require(item.accountId.isNotBlank() && item.calendarId.isNotBlank() && item.url.isNotBlank())
            require(item.ics.size in 1..512 * 1024)
            array.put(JSONObject().put("account", item.accountId).put("calendar", item.calendarId)
                .put("url", item.url).put("etag", item.etag).put("uid", item.uid)
                .put("ics", Base64.encodeToString(item.ics, Base64.NO_WRAP)))
        }
        val plain = JSONObject().put("version", 1).put("items", array).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(plain)
        require(encrypted.size <= 16_000_000) { "Calendar resource index is too large" }
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }

    fun replaceCalendar(accountId: String, calendarId: String,
        resources: List<DavConnection.Resource>) = synchronized(lock) {
        require(accountId.isNotBlank() && calendarId.isNotBlank())
        val retained = list().filterNot { it.accountId == accountId && it.calendarId == calendarId }
        val new = resources.filter { it.ics.size in 1..512 * 1024 }.map {
            DavStoredResource(accountId, calendarId, it.url, it.etag, it.uid, it.ics)
        }
        write(retained + new)
    }

    fun uniqueForUid(accountId: String, calendarId: String, uid: String): DavStoredResource? {
        if (uid.isBlank()) return null
        return list().filter { it.accountId == accountId && it.calendarId == calendarId && it.uid == uid }
            .singleOrNull()
    }

    fun removeCalendar(accountId: String, calendarId: String) = synchronized(lock) {
        write(list().filterNot { it.accountId == accountId && it.calendarId == calendarId })
    }

    fun removeAccount(accountId: String) = synchronized(lock) {
        write(list().filterNot { it.accountId == accountId })
    }

    fun retainCalendars(accountId: String, selectedIds: Set<String>) = synchronized(lock) {
        write(list().filter { it.accountId != accountId || it.calendarId in selectedIds })
    }

    fun clear() = synchronized(lock) { file.delete() }
}
