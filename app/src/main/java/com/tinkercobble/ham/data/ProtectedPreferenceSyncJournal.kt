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

/** Encrypted and atomically replaced queue. It is inert until a user enables a transport. */
class ProtectedPreferenceSyncJournal(context: Context) : PreferenceSyncJournal {
    private companion object { val lock = Any() }
    private val file = AtomicFile(File(context.filesDir, "preference-sync-v1.bin"))
    private val alias = "ham-preference-sync-v1"
    private val maxBytes = 2_000_000

    private data class State(val cursor: Long = 0,
        val pending: List<PreferenceChange> = emptyList(),
        val deferred: Map<String, PreferenceChange> = emptyMap(),
        val remote: Map<String, RemotePreference> = emptyMap(),
        val conflicts: List<PreferenceConflict> = emptyList())

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

    private fun valueJson(value: SyncSettingValue): JSONObject = when (value) {
        is SyncSettingValue.Text -> JSONObject().put("kind", "text").put("value", value.value)
        is SyncSettingValue.Flag -> JSONObject().put("kind", "flag").put("value", value.value)
        is SyncSettingValue.Number -> JSONObject().put("kind", "number").put("value", value.value)
        is SyncSettingValue.TextList -> JSONObject().put("kind", "text_list")
            .put("value", JSONArray(value.value))
        SyncSettingValue.Deleted -> JSONObject().put("kind", "deleted")
    }

    private fun parseValue(item: JSONObject): SyncSettingValue = when (item.getString("kind")) {
        "text" -> SyncSettingValue.Text(item.getString("value"))
        "flag" -> SyncSettingValue.Flag(item.getBoolean("value"))
        "number" -> SyncSettingValue.Number(item.getInt("value"))
        "text_list" -> item.getJSONArray("value").let { array ->
            require(array.length() <= 50)
            SyncSettingValue.TextList((0 until array.length()).map(array::getString))
        }
        "deleted" -> SyncSettingValue.Deleted
        else -> error("Unsupported preference value")
    }

    private fun changeJson(change: PreferenceChange) = JSONObject()
        .put("id", change.id).put("key", change.key)
        .put("base", change.baseRevision).put("value", valueJson(change.value))

    private fun parseChange(item: JSONObject): PreferenceChange = PreferenceChange(
        item.getString("id"), item.getString("key"), item.getLong("base"),
        parseValue(item.getJSONObject("value"))).also {
        require(it.baseRevision >= 0 && PreferenceSyncKeys.valid(it.key, it.value))
    }

    private fun remoteJson(value: RemotePreference) = JSONObject().put("key", value.key)
        .put("revision", value.revision).put("value", valueJson(value.value))

    private fun parseRemote(item: JSONObject): RemotePreference = RemotePreference(
        item.getString("key"), item.getLong("revision"),
        parseValue(item.getJSONObject("value"))).also {
        require(it.revision > 0 && PreferenceSyncKeys.valid(it.key, it.value))
    }

    private fun read(): State {
        if (!file.baseFile.exists()) return State()
        val encrypted = file.openRead().use { it.readBytes() }
        require(encrypted.size in 29..maxBytes) { "Preference sync journal is unreadable" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        val plain = cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
        val root = try { JSONObject(String(plain, Charsets.UTF_8)) }
            finally { plain.fill(0) }
        require(root.getInt("version") == 1)
        val pending = root.getJSONArray("pending")
        val deferred = root.optJSONArray("deferred") ?: JSONArray()
        val remote = root.getJSONArray("remote")
        val conflicts = root.getJSONArray("conflicts")
        require(pending.length() <= 100 && deferred.length() <= 100 &&
            remote.length() <= 1000 && conflicts.length() <= 100)
        val pendingItems = (0 until pending.length()).map { parseChange(pending.getJSONObject(it)) }
        val deferredItems = (0 until deferred.length()).map { parseChange(deferred.getJSONObject(it)) }
        val remoteItems = (0 until remote.length()).map { parseRemote(remote.getJSONObject(it)) }
        val conflictItems = (0 until conflicts.length()).map { index ->
            val item = conflicts.getJSONObject(index)
            PreferenceConflict(parseChange(item.getJSONObject("local")),
                if (item.isNull("remote")) null else parseRemote(item.getJSONObject("remote")))
        }
        require(pendingItems.map { it.id }.distinct().size == pendingItems.size)
        require(deferredItems.map { it.key }.distinct().size == deferredItems.size &&
            deferredItems.all { item -> pendingItems.any { it.key == item.key } } &&
            (pendingItems + deferredItems).map { it.id }.distinct().size ==
                pendingItems.size + deferredItems.size)
        require(remoteItems.map { it.key }.distinct().size == remoteItems.size)
        return State(root.getLong("cursor").also { require(it >= 0) }, pendingItems,
            deferredItems.associateBy { it.key }, remoteItems.associateBy { it.key }, conflictItems)
    }

    private fun write(state: State) {
        require(state.pending.size <= 100 && state.deferred.size <= 100 &&
            state.remote.size <= 1000 && state.conflicts.size <= 100)
        val root = JSONObject().put("version", 1).put("cursor", state.cursor)
            .put("pending", JSONArray().also { array -> state.pending.forEach { array.put(changeJson(it)) } })
            .put("deferred", JSONArray().also { array ->
                state.deferred.values.forEach { array.put(changeJson(it)) } })
            .put("remote", JSONArray().also { array -> state.remote.values.forEach { array.put(remoteJson(it)) } })
            .put("conflicts", JSONArray().also { array -> state.conflicts.forEach { conflict ->
                array.put(JSONObject().put("local", changeJson(conflict.local))
                    .put("remote", conflict.remote?.let(::remoteJson)))
            } })
        val plain = root.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = try { cipher.iv + cipher.doFinal(plain) }
            finally { plain.fill(0) }
        require(encrypted.size <= maxBytes) { "Preference sync journal is full" }
        check(file.baseFile.parentFile?.mkdirs() == true || file.baseFile.parentFile?.isDirectory == true) {
            "Preference sync storage is unavailable"
        }
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    override fun pending() = synchronized(lock) { read().pending }
    override fun remoteRevision() = synchronized(lock) { read().cursor }
    override fun current(key: String) = synchronized(lock) { read().remote[key] }
    override fun conflicts() = synchronized(lock) { read().conflicts }

    /** User-reviewed conflict choice; no provider calendar or event is changed. */
    fun resolveConflict(localId: String, keepLocal: Boolean) = synchronized(lock) {
        val state = read()
        val conflict = state.conflicts.singleOrNull { it.local.id == localId }
            ?: error("Preference conflict is no longer available")
        val remaining = state.conflicts.filterNot { it.local.id == localId }
        if (!keepLocal) {
            write(state.copy(conflicts = remaining))
            return@synchronized
        }
        val revision = conflict.remote?.revision
            ?: error("Refresh the other device's setting before retrying")
        require(state.pending.none { it.key == conflict.local.key } &&
            state.deferred[conflict.local.key] == null &&
            remaining.none { it.local.key == conflict.local.key }) {
            "Resolve other changes to this setting first"
        }
        val retry = conflict.local.copy(id = UUID.randomUUID().toString(), baseRevision = revision)
        write(state.copy(conflicts = remaining, pending = state.pending + retry))
    }

    override fun stage(change: PreferenceChange) = synchronized(lock) {
        require(PreferenceSyncKeys.valid(change.key, change.value) && change.baseRevision >= 0)
        val state = read()
        require(state.pending.none { it.id == change.id } &&
            state.deferred.values.none { it.id == change.id })
        // Keep the first operation's ID and payload for an uncertain server response.
        // Only the latest later choice is needed; it remains encrypted until that
        // first operation is acknowledged and can be rebased safely.
        if (state.pending.any { it.key == change.key })
            write(state.copy(deferred = state.deferred + (change.key to change)))
        else write(state.copy(pending = state.pending + change))
    }

    override fun apply(exchange: SyncExchange) = synchronized(lock) {
        val state = read()
        val pendingById = state.pending.associateBy { it.id }
        require(exchange.results.map { it.id }.distinct().size == exchange.results.size &&
            exchange.results.all { it.id in pendingById })
        require(exchange.headRevision >= state.cursor &&
            exchange.changes.zipWithNext().all { (a, b) -> a.revision < b.revision } &&
            exchange.changes.all { it.revision > state.cursor && it.revision <= exchange.headRevision &&
                PreferenceSyncKeys.valid(it.key, it.value) })
        require(!exchange.hasMore || exchange.changes.isNotEmpty())
        val updated = state.remote.toMutableMap()
        exchange.changes.forEach { updated[it.key] = it }
        val newConflicts = exchange.results.filter { it.status == SyncOperationResult.Status.CONFLICT }
            .map { result ->
                val active = pendingById.getValue(result.id)
                // A later local choice replaces the earlier proposal for review,
                // but never silently overrides a concurrent server change.
                PreferenceConflict(state.deferred[active.key] ?: active, updated[active.key])
            }
        val applied = exchange.results.map { it.id }.toSet()
        val nextPending = state.pending.filterNot { it.id in applied }.toMutableList()
        val nextDeferred = state.deferred.toMutableMap()
        exchange.results.forEach { result ->
            val active = pendingById.getValue(result.id)
            val later = nextDeferred.remove(active.key)
            if (later != null && result.status == SyncOperationResult.Status.APPLIED)
                nextPending += later.copy(baseRevision = result.revision)
        }
        write(state.copy(cursor = if (exchange.hasMore) exchange.changes.last().revision
                else exchange.headRevision,
            pending = nextPending, deferred = nextDeferred, remote = updated,
            conflicts = state.conflicts + newConflicts))
    }
}
