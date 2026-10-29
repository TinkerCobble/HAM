package com.tinkercobble.ham.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class QuickAddDraft(val title: String, val description: String, val location: String,
    val date: String, val allDay: Boolean, val start: String, val end: String,
    val lastDay: String, val calendarId: String?, val repeatFrequency: String?,
    val repeatCount: String)

/** Device-private encrypted form state; this never creates a provider event. */
class QuickAddDraftStore(context: Context) {
    private val prefs = context.getSharedPreferences("ham-quick-add-draft", Context.MODE_PRIVATE)
    private val alias = "ham-quick-add-draft-v1"

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

    fun save(draft: QuickAddDraft) {
        val value = JSONObject().put("title", draft.title).put("description", draft.description)
            .put("location", draft.location).put("date", draft.date).put("all_day", draft.allDay)
            .put("start", draft.start).put("end", draft.end).put("last_day", draft.lastDay)
            .put("calendar", draft.calendarId).put("repeat", draft.repeatFrequency)
            .put("count", draft.repeatCount).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString("draft", encoded).apply()
    }

    fun load(): QuickAddDraft? = try {
        val raw = Base64.decode(prefs.getString("draft", null) ?: return null, Base64.NO_WRAP)
        require(raw.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        val value = JSONObject(String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8))
        QuickAddDraft(value.optString("title"), value.optString("description"), value.optString("location"),
            value.optString("date"), value.optBoolean("all_day"), value.optString("start"),
            value.optString("end"), value.optString("last_day"),
            if (value.isNull("calendar")) null else value.optString("calendar").ifBlank { null },
            if (value.isNull("repeat")) null else value.optString("repeat").ifBlank { null },
            value.optString("count"))
    } catch (_: Exception) { null }

    fun clear() { prefs.edit().remove("draft").apply() }
}
