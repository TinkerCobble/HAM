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

data class IcsSubscription(val id: String, val name: String, val url: String,
    val username: String, val password: String) {
    override fun toString(): String = "IcsSubscription(id=$id, name=$name)"
}

data class IcsSubscriptionSummary(val id: String, val name: String)

/** Feed URLs can themselves contain bearer secrets; encrypt every field with a separate device key. */
class IcsSubscriptions(context: Context) {
    private val prefs = context.getSharedPreferences("ham-ics-subscriptions", Context.MODE_PRIVATE)
    private val alias = "ham-ics-subscriptions-v1"

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

    fun all(): List<IcsSubscription> {
        val encoded = prefs.getString("items", null) ?: return emptyList()
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        require(data.size > 12) { "Calendar subscriptions are unavailable" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val items = JSONArray(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        return (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            IcsSubscription(item.getString("id"), item.getString("name"), item.getString("url"),
                item.getString("username"), item.getString("password"))
        }
    }

    fun summaries(): List<IcsSubscriptionSummary> = all().map { IcsSubscriptionSummary(it.id, it.name) }

    private fun write(items: List<IcsSubscription>) {
        val json = JSONArray()
        items.forEach { item -> json.put(JSONObject().put("id", item.id).put("name", item.name)
            .put("url", item.url).put("username", item.username).put("password", item.password)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("items", Base64.encodeToString(data, Base64.NO_WRAP)).commit()) {
            "Could not save calendar subscription"
        }
    }

    fun save(name: String, url: String, username: String, password: String): String {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty() && normalizedName.length <= 100) { "Enter a calendar name" }
        val existing = all()
        val matched = existing.firstOrNull { it.url == url && it.username == username }
        val id = matched?.id ?: UUID.randomUUID().toString()
        write(existing.filterNot { it.id == id } +
            IcsSubscription(id, normalizedName, url, username, password))
        return id
    }

    fun remove(id: String) { write(all().filterNot { it.id == id }) }
}
