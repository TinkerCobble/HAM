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

enum class DavKind { ICLOUD, CALDAV }

data class DavAccount(val id: String, val kind: DavKind, val url: String,
    val username: String, val secret: String)
data class DavAccountSummary(val id: String, val kind: DavKind, val username: String)

/** The DAV password and account metadata use a separate nonexportable Android Keystore key. */
class DavAccounts(context: Context) {
    private val prefs = context.getSharedPreferences("ham-dav-accounts", Context.MODE_PRIVATE)
    private val alias = "ham-dav-accounts-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    private fun read(): List<DavAccount> {
        val encoded = prefs.getString("accounts", null) ?: return emptyList()
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        require(data.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val items = JSONArray(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        return (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            DavAccount(item.getString("id"), DavKind.valueOf(item.getString("kind")),
                item.getString("url"), item.getString("username"), item.getString("secret"))
        }
    }
    fun summaries(): List<DavAccountSummary> = read().map { DavAccountSummary(it.id, it.kind, it.username) }
    fun accounts(): List<DavAccount> = read()
    fun account(id: String): DavAccount? = read().singleOrNull { it.id == id }
    private fun write(accounts: List<DavAccount>) {
        val items = JSONArray()
        accounts.forEach { account -> items.put(JSONObject().put("id", account.id)
            .put("kind", account.kind.name).put("url", account.url)
            .put("username", account.username).put("secret", account.secret)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(items.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("accounts", Base64.encodeToString(data, Base64.NO_WRAP)).commit()) {
            "Could not save calendar account"
        }
    }
    fun save(kind: DavKind, url: String, username: String, secret: String) {
        val accounts = read()
        val matching = accounts.firstOrNull { it.kind == kind && it.url == url && it.username == username }
        val existing = accounts.filterNot { it.kind == kind && it.url == url && it.username == username }
        write(existing + DavAccount(matching?.id ?: UUID.randomUUID().toString(), kind, url, username, secret))
    }
    fun remove(id: String) { write(read().filterNot { it.id == id }) }
}
