package com.tinkercobble.ham.data

import android.content.Context
import java.security.MessageDigest
import java.time.LocalDate

data class CalendarPosition(val selected: LocalDate, val anchor: LocalDate,
    val monthDay: LocalDate, val agenda: String)

/** Private, server-scoped UI choices. No Home Assistant entity is changed. */
class CalendarPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("ham-calendar-preferences", Context.MODE_PRIVATE)
    private fun prefix(server: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(server.trimEnd('/').toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + "."
    }
    fun load(server: String): CalendarPreferences {
        if (server.isBlank()) return CalendarPreferences()
        val key = prefix(server)
        return CalendarPreferences.fromStored(
            prefs.getStringSet(key + "favorites", emptySet()),
            prefs.getStringSet(key + "hidden", emptySet()),
            prefs.getString(key + "default", null),
            prefs.getString(key + "recent", ""),
            prefs.getString(key + "colors", ""),
            prefs.getString(key + "view", null),
            prefs.getStringSet(key + "mirror_hidden", emptySet()),
            prefs.getString(key + "order", ""),
            prefs.getString(key + "default_view", null),
        )
    }
    fun save(server: String, value: CalendarPreferences, durable: Boolean = false) {
        if (server.isBlank()) return
        val key = prefix(server)
        val edit = prefs.edit().putStringSet(key + "favorites", value.favorites.toSet())
            .putStringSet(key + "hidden", value.hidden.toSet())
            .putString(key + "default", value.defaultId)
            .putString(key + "recent", value.recentString())
            .putString(key + "colors", value.colorString())
            .putString(key + "view", value.view.name)
            .putString(key + "default_view", value.defaultView.name)
            .putStringSet(key + "mirror_hidden", value.mirrorHidden.toSet())
            .putString(key + "order", value.order.entries.sortedBy { it.key }
                .joinToString("\n") { "${it.key}\t${it.value}" })
        if (durable) check(edit.commit()) { "Could not save migrated calendar settings" }
        else edit.apply()
    }

    /** Null means this installation predates explicit HA discovery tracking. */
    fun loadKnownHomeAssistant(server: String): Set<String>? {
        if (server.isBlank()) return null
        return prefs.getStringSet(prefix(server) + "known_ha", null)?.toSet()
    }

    fun saveKnownHomeAssistant(server: String, ids: Set<String>) {
        if (server.isBlank()) return
        check(prefs.edit().putStringSet(prefix(server) + "known_ha", ids.toSet()).commit()) {
            "Could not save Home Assistant calendar identities"
        }
    }

    /** One-time upgrade marker: retain old visibility when an existing session predates this feature. */
    fun prepareHomeAssistantSelectionMigration(existingSession: Boolean): Boolean {
        if (!prefs.contains("ha_selection_migration_initialized")) {
            check(prefs.edit().putBoolean("ha_selection_migration_initialized", true)
                .putBoolean("ha_legacy_session_pending", existingSession).commit()) {
                "Could not prepare Home Assistant calendar migration"
            }
        }
        return prefs.getBoolean("ha_legacy_session_pending", false)
    }

    fun completeHomeAssistantSelectionMigration() {
        check(prefs.edit().putBoolean("ha_legacy_session_pending", false).commit()) {
            "Could not complete Home Assistant calendar migration"
        }
    }

    fun loadPosition(@Suppress("UNUSED_PARAMETER") server: String): CalendarPosition {
        val today = LocalDate.now()
        // A fresh activity always opens on today. Saved browsing position is retained
        // for compatibility but never used as a launch target.
        return CalendarPosition(today, today, today, "Today")
    }

    fun savePosition(server: String, position: CalendarPosition) {
        if (server.isBlank()) return
        val key = prefix(server)
        prefs.edit().putString(key + "selected_date", position.selected.toString())
            .putString(key + "calendar_anchor", position.anchor.toString())
            .putString(key + "month_day", position.monthDay.toString())
            .putString(key + "agenda", position.agenda).apply()
    }
}
