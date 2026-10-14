package com.tinkercobble.ham.data

import android.content.Context
import java.security.MessageDigest

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
        )
    }
    fun save(server: String, value: CalendarPreferences) {
        if (server.isBlank()) return
        val key = prefix(server)
        prefs.edit().putStringSet(key + "favorites", value.favorites.toSet())
            .putStringSet(key + "hidden", value.hidden.toSet())
            .putString(key + "default", value.defaultId)
            .putString(key + "recent", value.recentString()).apply()
    }
}
