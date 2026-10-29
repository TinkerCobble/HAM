package com.tinkercobble.ham.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ScreenshotDraft(
    val filename: String, val title: String, val date: String, val start: String, val end: String,
    val allDay: Boolean, val venue: String, val address: String, val description: String,
    val calendarId: String?, val attachmentId: String?, val writeAttempted: Boolean = false,
    val dateChoices: List<String> = emptyList(), val savedDates: List<String> = emptyList(),
    val suggestedEnd: Boolean = false,
    val originalText: String = "",
)

/** One private, encrypted unsent draft. Its image stays in the app's private files directory. */
class ScreenshotDraftStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("ham-screenshot-draft", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val alias = "ham-screenshot-draft-v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun image(draft: ScreenshotDraft): File? {
        if (!Regex("[0-9a-f-]{36}\\.img").matches(draft.filename)) return null
        return File(File(context.filesDir, "screenshot-drafts"), draft.filename).takeIf { it.isFile }
    }

    fun save(draft: ScreenshotDraft, durable: Boolean = false) {
        require(image(draft) != null)
        val value = JSONObject().put("file", draft.filename).put("title", draft.title).put("date", draft.date)
            .put("start", draft.start).put("end", draft.end).put("all_day", draft.allDay)
            .put("venue", draft.venue).put("address", draft.address).put("description", draft.description)
            .put("calendar", draft.calendarId).put("attachment", draft.attachmentId)
            .put("write_attempted", draft.writeAttempted).put("date_choices", JSONArray(draft.dateChoices))
            .put("saved_dates", JSONArray(draft.savedDates))
            .put("suggested_end", draft.suggestedEnd)
            .put("original_text", draft.originalText).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val edit = prefs.edit().putString("draft", encoded)
        if (durable) check(edit.commit()) { "Could not protect the screenshot draft before saving" }
        else edit.apply()
    }

    fun load(): ScreenshotDraft? = try {
        val raw = Base64.decode(prefs.getString("draft", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        val value = JSONObject(String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8))
        fun strings(key: String): List<String> = value.optJSONArray(key)?.let { array ->
            (0 until array.length()).map { array.getString(it) }
        } ?: emptyList()
        ScreenshotDraft(value.getString("file"), value.optString("title"), value.optString("date"),
            value.optString("start"), value.optString("end"), value.optBoolean("all_day"),
            value.optString("venue"), value.optString("address"), value.optString("description"),
            if (value.isNull("calendar")) null else value.optString("calendar").ifBlank { null },
            if (value.isNull("attachment")) null else value.optString("attachment").ifBlank { null },
            value.optBoolean("write_attempted"), strings("date_choices"), strings("saved_dates"),
            value.optBoolean("suggested_end"), value.optString("original_text"))
            .takeIf { image(it) != null }
    } catch (_: Exception) { null }

    fun clear(deleteImage: Boolean = true) {
        if (deleteImage) load()?.let { image(it)?.delete() }
        prefs.edit().remove("draft").apply()
    }
}
