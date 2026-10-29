package com.tinkercobble.ham.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedGroupMember(val sourceId: String, val provider: CalendarProviderKind,
    val androidFingerprint: String? = null) {
    fun validated(): SavedGroupMember {
        require(sourceId.isNotBlank() && sourceId.length <= 256 && sourceId.none { it.isISOControl() })
        if (provider == CalendarProviderKind.ANDROID) {
            require(sourceId.matches(Regex("android:[0-9]+")))
            require(androidFingerprint?.matches(Regex("[0-9a-f]{64}")) == true)
        } else {
            require(androidFingerprint == null)
            require(when (provider) {
                CalendarProviderKind.CALDAV -> sourceId.startsWith("dav:") &&
                    sourceId.removePrefix("dav:").substringBefore(':').isNotBlank()
                CalendarProviderKind.ICS -> sourceId.startsWith("ics:")
                CalendarProviderKind.HOME_ASSISTANT -> !sourceId.startsWith("android:") &&
                    !sourceId.startsWith("dav:") && !sourceId.startsWith("ics:")
                CalendarProviderKind.ANDROID -> false
            })
        }
        return this
    }
    companion object {
        fun from(source: CalendarSource): SavedGroupMember = SavedGroupMember(source.id,
            source.provider, if (source.provider == CalendarProviderKind.ANDROID)
                requireNotNull(source.identityFingerprint) else null).validated()
    }
}

data class SavedCalendarGroup(val id: String, val name: String,
    val members: List<SavedGroupMember>) {
    fun validated(): SavedCalendarGroup {
        require(runCatching { UUID.fromString(id) }.isSuccess)
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 60 && clean.none { it.isISOControl() })
        require(members.size <= 200 && members.map { it.sourceId }.distinct().size == members.size)
        members.forEach { it.validated() }
        return copy(name = clean)
    }
}

data class SavedGroupState(val groups: List<SavedCalendarGroup> = emptyList(),
    val activeId: String? = null) {
    fun validated(): SavedGroupState {
        require(groups.size <= 20)
        val checked = groups.map { it.validated() }
        require(checked.map { it.id }.distinct().size == checked.size)
        require(checked.map { it.name.lowercase() }.distinct().size == checked.size)
        require(activeId == null || checked.any { it.id == activeId })
        return copy(groups = checked)
    }
}

object SavedGroupSelection {
    fun activeSourceIds(state: SavedGroupState, sources: List<CalendarSource>): Set<String> {
        val group = state.groups.singleOrNull { it.id == state.activeId } ?: return emptySet()
        val current = sources.associateBy { it.id }
        return group.members.filterTo(mutableListOf()) { member ->
            val source = current[member.sourceId]
            source != null && source.provider == member.provider &&
                (member.provider != CalendarProviderKind.ANDROID ||
                    source.identityFingerprint == member.androidFingerprint)
        }.mapTo(mutableSetOf()) { it.sourceId }
    }

    /** Remove members only after an authoritative discovery or explicit account removal. */
    fun reconcile(state: SavedGroupState, sources: List<CalendarSource>,
        completeProviders: Set<CalendarProviderKind>, completeDavAccounts: Set<String> = emptySet()):
        SavedGroupState {
        val current = sources.associateBy { it.id }
        val groups = state.groups.map { group -> group.copy(members = group.members.filter { member ->
            val source = current[member.sourceId]
            if (source != null && source.provider == member.provider &&
                (member.provider != CalendarProviderKind.ANDROID ||
                    source.identityFingerprint == member.androidFingerprint)) true
            else if (member.provider == CalendarProviderKind.CALDAV) {
                val account = member.sourceId.removePrefix("dav:").substringBefore(':')
                account !in completeDavAccounts
            } else member.provider !in completeProviders
        }) }
        // An empty group remains editable; it cannot display or read a replacement source.
        return state.copy(groups = groups)
    }
}

/** Encrypted, server-scoped local group names and exact source identities. */
class SavedCalendarGroupStore(context: Context) {
    private val prefs = context.getSharedPreferences("ham-saved-calendar-groups", Context.MODE_PRIVATE)
    private val alias = "ham-saved-calendar-groups-v1"
    companion object { private val lock = Any() }

    private fun preferenceKey(scope: String) = MessageDigest.getInstance("SHA-256")
        .digest(scope.trimEnd('/').toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

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

    fun load(scope: String): SavedGroupState = synchronized(lock) {
        val encoded = prefs.getString(preferenceKey(scope), null) ?: return@synchronized SavedGroupState()
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        require(data.size in 29..100_000) { "Saved calendar groups are damaged" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val root = JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        require(root.getInt("schema") == 1)
        val array = root.getJSONArray("groups")
        require(array.length() <= 20)
        val groups = (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val members = item.getJSONArray("members")
            require(members.length() <= 200)
            SavedCalendarGroup(item.getString("id"), item.getString("name"),
                (0 until members.length()).map { j ->
                    val member = members.getJSONObject(j)
                    SavedGroupMember(member.getString("source"),
                        CalendarProviderKind.valueOf(member.getString("provider")),
                        member.optString("fingerprint").takeIf { it.isNotEmpty() })
                }).validated()
        }
        SavedGroupState(groups, root.optString("active").takeIf { it.isNotEmpty() }).validated()
    }

    fun save(scope: String, state: SavedGroupState) = synchronized(lock) {
        val checked = state.validated()
        val array = JSONArray()
        checked.groups.forEach { group ->
            val members = JSONArray()
            group.members.forEach { member ->
                members.put(JSONObject().put("source", member.sourceId)
                    .put("provider", member.provider.name)
                    .put("fingerprint", member.androidFingerprint.orEmpty()))
            }
            array.put(JSONObject().put("id", group.id).put("name", group.name)
                .put("members", members))
        }
        val plain = JSONObject().put("schema", 1).put("active", checked.activeId.orEmpty())
            .put("groups", array).toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= 90_000)
        val data = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.iv + cipher.doFinal(plain)
        } finally { plain.fill(0) }
        check(prefs.edit().putString(preferenceKey(scope),
            Base64.encodeToString(data, Base64.NO_WRAP)).commit()) {
            "Could not save calendar groups"
        }
    }
    fun clear(scope: String) = synchronized(lock) {
        check(prefs.edit().remove(preferenceKey(scope)).commit()) { "Could not clear calendar groups" }
    }
}
