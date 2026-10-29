package com.tinkercobble.ham.auth

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

data class Session(val server: String, val refreshToken: String, val accessToken: String, val expiresAt: Long,
    val clientId: String = AuthIdentity.LEGACY_CLIENT_ID)

/** Credentials remain encrypted in this app's private sandbox; Android backup is disabled. */
class SecureSession(context: Context) {
    private val prefs = context.getSharedPreferences("ham-secure-session", Context.MODE_PRIVATE)
    private val alias = "ham-session-v1"
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
    fun save(session: Session) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val json = JSONObject().put("server", session.server).put("refresh", session.refreshToken)
            .put("access", session.accessToken).put("expires", session.expiresAt)
            .put("client_id", session.clientId).toString()
        val data = cipher.iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("session", Base64.encodeToString(data, Base64.NO_WRAP)).commit()) {
            "Could not securely save Home Assistant sign-in"
        }
    }
    fun load(): Session? = try {
        val encoded = prefs.getString("session", null) ?: return null
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val value = JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        Session(value.getString("server"), value.getString("refresh"), value.getString("access"),
            value.getLong("expires"), value.optString("client_id", AuthIdentity.LEGACY_CLIENT_ID))
    } catch (_: Exception) { null }
    fun clear() {
        check(prefs.edit().remove("session").commit()) { "Could not clear Home Assistant sign-in" }
    }
}
