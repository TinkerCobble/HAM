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
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class DavOperation { CREATE, UPDATE, DELETE }
enum class DavPendingState { QUEUED, CONFLICT }

data class PendingDavMutation(val id: UUID, val accountId: String, val calendarId: String,
    val operation: DavOperation, val resourceUrl: String?, val expectedEtag: String?,
    val ics: ByteArray?, val state: DavPendingState = DavPendingState.QUEUED,
    val uid: String? = null, val baseIcs: ByteArray? = null)

/** Encrypted, atomic local queue. Nothing is removed until the provider confirms a mutation. */
class PendingDavMutations(context: Context) {
    private companion object {
        val globalLock = Any()
        const val MAX_FILE_BYTES = 16_000_000
        const val MAX_ITEMS = 100
        const val MAX_ICS_BYTES = 512 * 1024
    }
    private val file = AtomicFile(File(context.filesDir, "pending-dav-v1.bin"))
    private val alias = "ham-pending-dav-v1"

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

    fun list(): List<PendingDavMutation> = synchronized(globalLock) {
        if (!file.baseFile.exists()) return@synchronized emptyList()
        val raw = file.openRead().use { it.readBytes() }
        require(raw.size in 29..MAX_FILE_BYTES) { "Pending calendar queue is unreadable" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        val data = JSONObject(String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8))
        require(data.getInt("version") == 1) { "Pending calendar queue version changed" }
        val items = data.getJSONArray("items")
        require(items.length() <= MAX_ITEMS) { "Too many pending calendar changes" }
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val payload = if (item.isNull("ics")) null else Base64.decode(item.getString("ics"), Base64.NO_WRAP)
            val base = if (!item.has("baseIcs") || item.isNull("baseIcs")) null else
                Base64.decode(item.getString("baseIcs"), Base64.NO_WRAP)
            require(payload == null || payload.size <= MAX_ICS_BYTES) { "Pending calendar item is too large" }
            require(base == null || base.size in 1..MAX_ICS_BYTES) { "Pending calendar base is too large" }
            PendingDavMutation(UUID.fromString(item.getString("id")), item.getString("account"),
                item.getString("calendar"), DavOperation.valueOf(item.getString("operation")),
                if (item.isNull("resource")) null else item.getString("resource"),
                if (item.isNull("etag")) null else item.getString("etag"), payload,
                DavPendingState.valueOf(item.getString("state")),
                if (!item.has("uid") || item.isNull("uid")) null else item.getString("uid"), base)
        }
    }

    private fun write(items: List<PendingDavMutation>) {
        require(items.size <= MAX_ITEMS) { "Too many pending calendar changes" }
        val array = JSONArray()
        items.forEach { item ->
            require(item.accountId.isNotBlank() && item.calendarId.isNotBlank())
            require(item.ics == null || item.ics.size <= MAX_ICS_BYTES)
            require(item.baseIcs == null || item.baseIcs.size in 1..MAX_ICS_BYTES)
            require(item.baseIcs == null || item.operation != DavOperation.CREATE)
            require(when (item.operation) {
                DavOperation.CREATE -> item.resourceUrl == null && item.expectedEtag == null && item.ics != null
                DavOperation.UPDATE -> item.resourceUrl != null && item.expectedEtag != null && item.ics != null
                DavOperation.DELETE -> item.resourceUrl != null && item.expectedEtag != null && item.ics == null
            }) { "Invalid pending calendar change" }
            array.put(JSONObject().put("id", item.id.toString()).put("account", item.accountId)
                .put("calendar", item.calendarId).put("operation", item.operation.name)
                .put("resource", item.resourceUrl).put("etag", item.expectedEtag)
                .put("ics", item.ics?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
                .put("state", item.state.name).put("uid", item.uid)
                .put("baseIcs", item.baseIcs?.let { Base64.encodeToString(it, Base64.NO_WRAP) }))
        }
        val plain = JSONObject().put("version", 1).put("items", array).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(plain)
        require(encrypted.size <= MAX_FILE_BYTES) { "Pending calendar queue is too large" }
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }

    fun enqueue(change: PendingDavMutation) = synchronized(globalLock) {
        val items = list()
        require(items.none { it.id == change.id }) { "Calendar change is already queued" }
        require(change.resourceUrl == null || items.none {
            it.accountId == change.accountId && it.calendarId == change.calendarId &&
                it.resourceUrl == change.resourceUrl
        }) { "Calendar item already has a pending change" }
        write(items + change)
    }

    fun markConflict(id: UUID) = synchronized(globalLock) {
        val items = list()
        require(items.any { it.id == id }) { "Calendar change is missing" }
        write(items.map { if (it.id == id) it.copy(state = DavPendingState.CONFLICT) else it })
    }

    fun requeueReviewedTitle(id: UUID, previousEtag: String, currentEtag: String,
        currentIcs: ByteArray, mergedIcs: ByteArray) = synchronized(globalLock) {
        val items = list()
        val old = items.singleOrNull { it.id == id } ?: error("Calendar conflict was removed")
        require(old.operation == DavOperation.UPDATE && old.state == DavPendingState.CONFLICT &&
            old.baseIcs != null && old.expectedEtag == previousEtag &&
            currentEtag.isNotBlank() && currentIcs.size in 1..MAX_ICS_BYTES &&
            mergedIcs.size in 1..MAX_ICS_BYTES) { "Calendar conflict changed; review it again" }
        write(items.map { if (it.id == id) old.copy(expectedEtag = currentEtag,
            ics = mergedIcs, state = DavPendingState.QUEUED, baseIcs = currentIcs) else it })
    }

    /** Metadata review uses the same encrypted conditional UPDATE replacement as title review. */
    fun requeueReviewedDetails(id: UUID, previousEtag: String, currentEtag: String,
        currentIcs: ByteArray, mergedIcs: ByteArray) =
        requeueReviewedTitle(id, previousEtag, currentEtag, currentIcs, mergedIcs)

    /** Recheck the saved representation; never rebase a removal onto changed event content. */
    fun requeueReviewedDelete(id: UUID, previousEtag: String, currentEtag: String,
        currentIcs: ByteArray) = synchronized(globalLock) {
        val items = list()
        val old = items.singleOrNull { it.id == id } ?: error("Calendar conflict was removed")
        require(old.operation == DavOperation.DELETE && old.state == DavPendingState.CONFLICT &&
            old.expectedEtag == previousEtag && old.baseIcs != null &&
            old.baseIcs.contentEquals(currentIcs) && currentEtag.isNotBlank() &&
            currentIcs.size in 1..MAX_ICS_BYTES) { "Calendar item changed; keep or discard this removal" }
        write(items.map { if (it.id == id) old.copy(expectedEtag = currentEtag,
            state = DavPendingState.QUEUED) else it })
    }

    fun removeConfirmed(id: UUID) = synchronized(globalLock) { write(list().filterNot { it.id == id }) }

    fun discard(id: UUID) = synchronized(globalLock) {
        val items = list()
        require(items.any { it.id == id }) { "Pending calendar change is missing" }
        write(items.filterNot { it.id == id })
    }

    fun discardAccount(id: String) = synchronized(globalLock) { write(list().filterNot { it.accountId == id }) }
}
