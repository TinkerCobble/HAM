package com.tinkercobble.ham.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PreferenceSyncConnection(val endpoint: PreferenceSyncEndpoint,
    val profileId: String, val deviceId: String) {
    init {
        require(UUID.fromString(profileId).toString() == profileId)
        require(UUID.fromString(deviceId).toString() == deviceId)
    }
}

/** Stores only an explicitly paired endpoint and random IDs, never a provider credential. */
class ProtectedPreferenceSyncConnection(context: Context) {
    private companion object { val lock = Any() }
    private val file = AtomicFile(File(context.filesDir, "preference-sync-connection-v1.bin"))
    private val alias = "ham-preference-sync-connection-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun load(): PreferenceSyncConnection? = synchronized(lock) {
        if (!file.baseFile.exists()) return@synchronized null
        val encrypted = file.openRead().use { it.readBytes() }
        require(encrypted.size in 29..8192) { "Sync connection is unreadable" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        val plain = cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
        val root = try { JSONObject(String(plain, Charsets.UTF_8)) }
            finally { plain.fill(0) }
        require(root.getInt("version") == 1)
        PreferenceSyncConnection(PreferenceSyncEndpoint(root.getString("url"), root.getString("fingerprint")),
            root.getString("profile"), root.getString("device"))
    }

    fun save(connection: PreferenceSyncConnection) = synchronized(lock) {
        val plain = JSONObject().put("version", 1).put("url", connection.endpoint.baseUrl)
            .put("fingerprint", connection.endpoint.certificateSha256)
            .put("profile", connection.profileId).put("device", connection.deviceId)
            .toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = try { cipher.iv + cipher.doFinal(plain) }
            finally { plain.fill(0) }
        require(encrypted.size <= 8192)
        check(file.baseFile.parentFile?.mkdirs() == true || file.baseFile.parentFile?.isDirectory == true) {
            "Sync connection storage is unavailable"
        }
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    /** Deliberate unpair action; it does not revoke this device on the server. */
    fun clear() = synchronized(lock) { file.delete() }
}
