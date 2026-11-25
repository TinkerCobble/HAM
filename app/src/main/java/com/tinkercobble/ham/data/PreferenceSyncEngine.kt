package com.tinkercobble.ham.data

import java.util.UUID

/** Provider-neutral preference operations. Event data and provider credentials never enter this model. */
sealed interface SyncSettingValue {
    data class Text(val value: String) : SyncSettingValue
    data class Flag(val value: Boolean) : SyncSettingValue
    data class Number(val value: Int) : SyncSettingValue
    data class TextList(val value: List<String>) : SyncSettingValue
    data object Deleted : SyncSettingValue
}

data class PreferenceChange(val id: String, val key: String, val baseRevision: Long,
    val value: SyncSettingValue)
data class RemotePreference(val key: String, val revision: Long, val value: SyncSettingValue)
data class PreferenceConflict(val local: PreferenceChange, val remote: RemotePreference?)
data class SyncOperationResult(val id: String, val status: Status, val revision: Long) {
    enum class Status { APPLIED, CONFLICT }
}
data class SyncExchange(val results: List<SyncOperationResult>, val changes: List<RemotePreference>,
    val headRevision: Long, val hasMore: Boolean = false)

/** An implementation must commit each queue/state transition before reporting success. */
interface PreferenceSyncJournal {
    fun pending(): List<PreferenceChange>
    fun remoteRevision(): Long
    fun current(key: String): RemotePreference?
    fun stage(change: PreferenceChange)
    fun apply(exchange: SyncExchange)
    fun conflicts(): List<PreferenceConflict>
}

interface PreferenceSyncTransport {
    fun exchange(sinceRevision: Long, operations: List<PreferenceChange>): SyncExchange
}

object PreferenceSyncKeys {
    private val logical = Regex("[a-z][a-z0-9_-]{0,31}:v1:[0-9a-f]{64}")
    private val uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val key = Regex("(?:global:(?:week_start|view|quick_add_default|group_order|active_group)|" +
        "group:$uuid:name|" +
        "calendar:([a-z][a-z0-9_-]{0,31}:v1:[0-9a-f]{64}):" +
        "(?:visible|favorite|color|order|group|groups|mirror_hidden))")

    fun valid(keyName: String, value: SyncSettingValue): Boolean {
        if (!key.matches(keyName)) return false
        if (value == SyncSettingValue.Deleted) return true
        return when (keyName.substringAfterLast(':')) {
            "week_start" -> value is SyncSettingValue.Text &&
                value.value in setOf("SYSTEM_DEFAULT", "SUNDAY", "MONDAY")
            "view" -> value is SyncSettingValue.Text &&
                value.value in setOf("MONTH", "WEEK", "THREE_DAYS")
            "quick_add_default" -> value is SyncSettingValue.Text && logical.matches(value.value)
            "group_order" -> value is SyncSettingValue.TextList && value.value.size <= 50 &&
                value.value.size == value.value.distinct().size &&
                value.value.all { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
            "active_group" -> value is SyncSettingValue.Text &&
                runCatching { UUID.fromString(value.value).toString() == value.value }.getOrDefault(false)
            "name" -> keyName.startsWith("group:") && value is SyncSettingValue.Text &&
                value.value.trim() == value.value && value.value.isNotEmpty() &&
                value.value.length <= 60 && value.value.none { it.isISOControl() }
            "groups" -> value is SyncSettingValue.TextList && value.value.size <= 20 &&
                value.value.size == value.value.distinct().size &&
                value.value.all { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
            "visible", "favorite", "mirror_hidden" -> value is SyncSettingValue.Flag
            "color" -> value is SyncSettingValue.Text && value.value.matches(Regex("#[0-9A-Fa-f]{6}"))
            "order" -> value is SyncSettingValue.Number && value.value in 0..10_000
            "group" -> value is SyncSettingValue.Text &&
                runCatching { UUID.fromString(value.value).toString() == value.value }.getOrDefault(false)
            else -> false
        }
    }
}

/** Explicitly opt-in, so an unpaired installation never contacts a sync service. */
class PreferenceSyncEngine(private val journal: PreferenceSyncJournal,
    private val transport: PreferenceSyncTransport) {
    fun queue(key: String, value: SyncSettingValue): PreferenceChange {
        require(PreferenceSyncKeys.valid(key, value)) { "Unsupported preference setting" }
        val change = PreferenceChange(UUID.randomUUID().toString(), key,
            journal.current(key)?.revision ?: 0, value)
        journal.stage(change)
        return change
    }

    fun sync(): SyncExchange {
        val response = transport.exchange(journal.remoteRevision(), journal.pending())
        require(response.headRevision >= journal.remoteRevision()) { "Server revision moved backwards" }
        require(response.changes.zipWithNext().all { (a, b) -> a.revision < b.revision }) {
            "Server changes are out of order"
        }
        require(response.changes.all { it.revision > journal.remoteRevision() &&
            it.revision <= response.headRevision && PreferenceSyncKeys.valid(it.key, it.value) }) {
            "Server returned an invalid preference"
        }
        require(!response.hasMore || response.changes.isNotEmpty()) { "Server pagination did not advance" }
        journal.apply(response)
        return response
    }
}
