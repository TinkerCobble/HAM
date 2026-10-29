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
        if (durable) check(edit.commit()) { "Could not save migrated calendar settings" }
        else edit.apply()
    }

    fun loadPosition(server: String): CalendarPosition {
        val today = LocalDate.now()
        if (server.isBlank()) return CalendarPosition(today, today, today, "Today")
        val key = prefix(server)
        fun date(suffix: String) = runCatching { LocalDate.parse(prefs.getString(key + suffix, null)) }.getOrDefault(today)
        return CalendarPosition(date("selected_date"), date("calendar_anchor"), date("month_day"),
            prefs.getString(key + "agenda", "Today").takeIf { it == "Calendar" } ?: "Today")
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
