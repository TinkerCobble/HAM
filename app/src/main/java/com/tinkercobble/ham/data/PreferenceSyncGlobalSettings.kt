package com.tinkercobble.ham.data

import android.content.Context

/** Applies global choices and only explicitly approved logical-calendar choices. */
class PreferenceSyncGlobalSettings(context: Context,
    private val identity: KeystorePreferenceSyncIdentity = KeystorePreferenceSyncIdentity()) {
    private val app = context.applicationContext
    private val connection = ProtectedPreferenceSyncConnection(app)
    private val journal = ProtectedPreferenceSyncJournal(app)
    private val approvals = ProtectedPreferenceSyncApprovals(app)

    data class CalendarLocal(val identity: String, val visible: Boolean,
        val favorite: Boolean, val color: String?, val mirrorHidden: Boolean = false,
        val order: Int? = null, val groups: List<String> = emptyList())
    data class CalendarApplied(val visible: Boolean?, val favorite: Boolean?,
        val color: String?, val colorKnown: Boolean, val mirrorHidden: Boolean?,
        val order: Int?, val orderKnown: Boolean, val groups: List<String>?)

    data class Applied(val weekStart: WeekStart?, val view: CalendarView?,
        val pending: Int, val conflicts: Int,
        val calendars: Map<String, CalendarApplied> = emptyMap(),
        val defaultKnown: Boolean = false, val defaultIdentity: String? = null,
        val groupOrder: List<String>? = null, val groupNames: Map<String, String> = emptyMap(),
        val activeGroupKnown: Boolean = false, val activeGroupId: String? = null,
        val deletedGroupIds: Set<String> = emptySet())

    fun paired(): Boolean = connection.load() != null

    fun conflicts(): List<PreferenceConflict> = journal.conflicts()

    fun resolveConflict(localId: String, keepLocal: Boolean) =
        journal.resolveConflict(localId, keepLocal)

    fun recordWeekStart(value: WeekStart): Boolean = record("global:week_start",
        SyncSettingValue.Text(value.name))

    fun recordView(value: CalendarView): Boolean = record("global:view",
        SyncSettingValue.Text(value.name))

    fun approved(sources: List<CalendarSource>): Map<String, CalendarSource> =
        approvals.resolved(sources)

    fun recordCalendar(source: CalendarSource, sources: List<CalendarSource>,
        field: String, value: SyncSettingValue): Boolean {
        require(field in setOf("visible", "favorite", "color", "mirror_hidden", "order"))
        val identity = source.logicalIdentity ?: return false
        if (approvals.resolved(sources)[identity]?.id != source.id) return false
        return record("calendar:$identity:$field", value)
    }

    fun recordQuickAddDefault(source: CalendarSource?, sources: List<CalendarSource>): Boolean {
        if (source == null) return record("global:quick_add_default", SyncSettingValue.Deleted)
        val identity = source.logicalIdentity ?: return false
        if (!source.canCreate || approvals.resolved(sources)[identity]?.id != source.id) return false
        return record("global:quick_add_default", SyncSettingValue.Text(identity))
    }

    /** Group names and memberships are private profile data; source IDs stay device-local. */
    fun recordGroups(old: SavedGroupState, next: SavedGroupState,
        sources: List<CalendarSource>): Boolean {
        if (!paired()) return false
        var queued = false
        val before = old.groups.associateBy { it.id }
        val after = next.groups.associateBy { it.id }
        for (id in before.keys + after.keys) {
            val priorName = before[id]?.name
            val nextName = after[id]?.name
            if (priorName != nextName) queued = record("group:$id:name",
                nextName?.let(SyncSettingValue::Text) ?: SyncSettingValue.Deleted) || queued
        }
        if (old.groups.map { it.id } != next.groups.map { it.id })
            queued = record("global:group_order", SyncSettingValue.TextList(
                next.groups.map { it.id })) || queued
        if (old.activeId != next.activeId)
            queued = record("global:active_group", next.activeId?.let(SyncSettingValue::Text)
                ?: SyncSettingValue.Deleted) || queued
        for ((identity, source) in approvals.resolved(sources)) {
            fun memberships(state: SavedGroupState) = state.groups.filter { group ->
                group.members.any { it.sourceId == source.id && it.provider == source.provider }
            }.map { it.id }.sorted()
            val earlier = memberships(old)
            val later = memberships(next)
            if (earlier != later) queued = record("calendar:$identity:groups",
                SyncSettingValue.TextList(later)) || queued
        }
        return queued
    }

    private fun record(key: String, value: SyncSettingValue): Boolean {
        if (connection.load() == null) return false
        // Queue before a network attempt. The journal retains an in-flight ID
        // and stores a newer choice separately if this key was already queued.
        val engine = PreferenceSyncEngine(journal, object : PreferenceSyncTransport {
            override fun exchange(sinceRevision: Long, operations: List<PreferenceChange>): SyncExchange =
                error("No transport is used while recording a local choice")
        })
        engine.queue(key, value)
        runCatching { PreferenceBackgroundSync.schedule(app, immediate = true) }
        return true
    }

    /** Pull first on enrollment; only missing profile keys receive this device's choices. */
    fun exchange(localWeekStart: WeekStart, localView: CalendarView,
        localCalendars: List<CalendarLocal> = emptyList(), localDefault: String? = null,
        transportOverride: PreferenceSyncTransport? = null,
        localGroups: SavedGroupState? = null): Applied {
        check(!OfflineMode.enabled(app)) { "Offline is on" }
        val paired = connection.load() ?: error("Pair this device first")
        val engine = PreferenceSyncEngine(journal, transportOverride ?: HttpPreferenceSyncTransport(
            PreferenceSyncHttps(paired.endpoint, identity), paired.profileId, paired.deviceId))
        var complete = false
        require(localCalendars.map { it.identity }.distinct().size == localCalendars.size &&
            localCalendars.all { it.identity.matches(Regex("[a-z][a-z0-9_-]{0,31}:v1:[0-9a-f]{64}")) })
        for (attempt in 0 until 20) {
            val response = engine.sync()
            if (journal.pending().isEmpty() && !response.hasMore) {
                val blocked = journal.conflicts().map { it.local.key }.toSet()
                val seeds = mutableListOf<Pair<String, SyncSettingValue>>()
                fun seed(key: String, value: SyncSettingValue) {
                    if (journal.current(key) == null && key !in blocked) seeds += key to value
                }
                seed("global:week_start", SyncSettingValue.Text(localWeekStart.name))
                seed("global:view", SyncSettingValue.Text(localView.name))
                localCalendars.forEach { local ->
                    val prefix = "calendar:${local.identity}:"
                    seed(prefix + "visible", SyncSettingValue.Flag(local.visible))
                    seed(prefix + "favorite", SyncSettingValue.Flag(local.favorite))
                    local.color?.let { seed(prefix + "color", SyncSettingValue.Text(it)) }
                    seed(prefix + "mirror_hidden", SyncSettingValue.Flag(local.mirrorHidden))
                    local.order?.let { seed(prefix + "order", SyncSettingValue.Number(it)) }
                    seed(prefix + "groups", SyncSettingValue.TextList(local.groups))
                }
                localDefault?.let { seed("global:quick_add_default", SyncSettingValue.Text(it)) }
                localGroups?.let { state ->
                    seed("global:group_order", SyncSettingValue.TextList(state.groups.map { it.id }))
                    state.activeId?.let { seed("global:active_group", SyncSettingValue.Text(it)) }
                    state.groups.forEach { group ->
                        seed("group:${group.id}:name", SyncSettingValue.Text(group.name))
                    }
                }
                seeds.take(50).forEach { (key, value) -> record(key, value) }
                if (seeds.isNotEmpty()) continue
                complete = true
                break
            }
        }
        check(complete) { "Some display settings are still queued" }
        val blocked = journal.conflicts().map { it.local.key }.toSet() +
            journal.pending().map { it.key }.toSet()
        val week = journal.current("global:week_start")?.value
            ?.takeIf { "global:week_start" !in blocked }
            ?.let { it as? SyncSettingValue.Text }
            ?.let { name -> WeekStart.entries.firstOrNull { it.name == name.value } }
        val view = journal.current("global:view")?.value
            ?.takeIf { "global:view" !in blocked }
            ?.let { it as? SyncSettingValue.Text }
            ?.let { name -> CalendarView.entries.firstOrNull { it.name == name.value } }
        val calendars = localCalendars.associate { local ->
            val prefix = "calendar:${local.identity}:"
            fun remote(field: String): SyncSettingValue? =
                journal.current(prefix + field)?.value?.takeIf { prefix + field !in blocked }
            val visible = (remote("visible") as? SyncSettingValue.Flag)?.value
            val favorite = (remote("favorite") as? SyncSettingValue.Flag)?.value
            val colorValue = remote("color")
            val orderValue = remote("order")
            local.identity to CalendarApplied(visible, favorite,
                (colorValue as? SyncSettingValue.Text)?.value, colorValue != null,
                (remote("mirror_hidden") as? SyncSettingValue.Flag)?.value,
                (orderValue as? SyncSettingValue.Number)?.value, orderValue != null,
                (remote("groups") as? SyncSettingValue.TextList)?.value)
        }
        val default = journal.current("global:quick_add_default")?.value
            ?.takeIf { "global:quick_add_default" !in blocked }
        val order = journal.current("global:group_order")?.value
            ?.takeIf { "global:group_order" !in blocked } as? SyncSettingValue.TextList
        val groupNames = order?.value.orEmpty().mapNotNull { id ->
            val name = journal.current("group:$id:name")?.value
                ?.takeIf { "group:$id:name" !in blocked } as? SyncSettingValue.Text
            name?.value?.let { id to it }
        }.toMap()
        val active = journal.current("global:active_group")?.value
            ?.takeIf { "global:active_group" !in blocked }
        val deletedGroups = localGroups?.groups.orEmpty().mapNotNull { group ->
            val key = "group:${group.id}:name"
            group.id.takeIf { key !in blocked && journal.current(key)?.value == SyncSettingValue.Deleted }
        }.toSet()
        return Applied(week, view, journal.pending().size, journal.conflicts().size,
            calendars, default != null, (default as? SyncSettingValue.Text)?.value,
            order?.value, groupNames, active != null, (active as? SyncSettingValue.Text)?.value,
            deletedGroups)
    }
}
