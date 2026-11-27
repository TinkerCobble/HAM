package com.tinkercobble.ham.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Explicit device-local approval of a provider collection's stable candidate identity. */
class ProtectedPreferenceSyncApprovals(context: Context) {
    private companion object { val lock = Any() }
    private val file = AtomicFile(File(context.filesDir, "preference-sync-approvals-v1.bin"))
    private val alias = "ham-preference-sync-approvals-v1"
    private val connection = ProtectedPreferenceSyncConnection(context)
    private val logical = Regex("[a-z][a-z0-9_-]{0,31}:v1:[0-9a-f]{64}")

    data class Approval(val profileId: String, val sourceId: String, val identity: String,
        val provider: CalendarProviderKind, val fingerprint: String?)

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

    private fun read(): List<Approval> {
        if (!file.baseFile.exists()) return emptyList()
        val encrypted = file.openRead().use { it.readBytes() }
        require(encrypted.size in 29..100_000) { "Calendar sync approvals are unreadable" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        val plain = cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
        val root = try { JSONObject(String(plain, Charsets.UTF_8)) }
            finally { plain.fill(0) }
        require(root.getInt("version") == 1)
        val entries = root.getJSONArray("approvals")
        require(entries.length() <= 200)
        val approvals = (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            Approval(item.getString("profile"), item.getString("source"), item.getString("identity"),
                CalendarProviderKind.valueOf(item.getString("provider")),
                item.optString("fingerprint").ifBlank { null })
        }
        require(approvals.map { it.profileId to it.sourceId }.distinct().size == approvals.size &&
            approvals.all { UUID.fromString(it.profileId).toString() == it.profileId &&
                it.sourceId.isNotBlank() && it.identity.matches(logical) &&
                (it.provider != CalendarProviderKind.ANDROID ||
                    it.fingerprint?.matches(Regex("[0-9a-f]{64}")) == true) })
        return approvals
    }

    private fun write(approvals: List<Approval>) {
        require(approvals.size <= 200)
        val root = JSONObject().put("version", 1).put("approvals", JSONArray().also { array ->
            approvals.forEach { item -> array.put(JSONObject().put("profile", item.profileId)
                .put("source", item.sourceId)
                .put("identity", item.identity).put("provider", item.provider.name)
                .put("fingerprint", item.fingerprint)) }
        })
        val plain = root.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = try { cipher.iv + cipher.doFinal(plain) }
            finally { plain.fill(0) }
        require(encrypted.size <= 100_000)
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    fun approve(source: CalendarSource, current: List<CalendarSource>) = synchronized(lock) {
        val profile = connection.load()?.profileId ?: error("Pair this device before approving calendars")
        val identity = source.logicalIdentity ?: error("This calendar has no stable sync identity")
        require(identity.matches(logical) && current.count { it.id == source.id } == 1 &&
            current.count { it.logicalIdentity == identity } == 1 &&
            (source.provider != CalendarProviderKind.ANDROID ||
                source.identityFingerprint?.matches(Regex("[0-9a-f]{64}")) == true)) {
            "Review this calendar's identity before syncing"
        }
        val old = read().filterNot { it.profileId == profile && it.sourceId == source.id }
        write(old + Approval(profile, source.id, identity, source.provider, source.identityFingerprint))
    }

    fun remove(sourceId: String) = synchronized(lock) {
        val profile = connection.load()?.profileId ?: return@synchronized
        val old = read()
        if (old.any { it.profileId == profile && it.sourceId == sourceId })
            write(old.filterNot { it.profileId == profile && it.sourceId == sourceId })
    }

    /** A reused row ID, changed collection or duplicate candidate never inherits approval. */
    fun resolved(current: List<CalendarSource>): Map<String, CalendarSource> = synchronized(lock) {
        val profile = connection.load()?.profileId ?: return@synchronized emptyMap()
        val unique = current.filter { it.logicalIdentity != null }
            .groupBy { it.logicalIdentity }.filterValues { it.size == 1 }
        read().filter { it.profileId == profile }.mapNotNull { approval ->
            val source = unique[approval.identity]?.singleOrNull()
                ?.takeIf { it.id == approval.sourceId && it.provider == approval.provider &&
                    it.identityFingerprint == approval.fingerprint }
            source?.let { approval.identity to it }
        }.toMap()
    }
}
