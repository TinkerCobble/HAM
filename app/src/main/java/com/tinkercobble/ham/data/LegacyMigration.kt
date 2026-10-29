package com.tinkercobble.ham.data

import android.content.Context
import android.net.Uri
import com.tinkercobble.ham.auth.ConnectionPolicy
import org.json.JSONObject

data class LegacyMigrationResult(val server: String, val preferences: CalendarPreferences,
    val hasPendingDraft: Boolean)

/** Reads only owner-approved, signature-protected settings from the old package. No tokens move. */
class LegacyMigration(private val context: Context) {
    private val uri = Uri.parse("content://com.tinkercobble.ham.dev.migration/settings")

    fun importSettings(): LegacyMigrationResult {
        val cursor = context.contentResolver.query(uri, arrayOf("payload"), null, null, null)
            ?: error("The previous HAM installation is not ready to transfer settings")
        val payload = cursor.use {
            require(it.moveToFirst()) { "The previous HAM installation has no settings to transfer" }
            it.getString(it.getColumnIndexOrThrow("payload"))
        }
        val value = JSONObject(payload)
        require(value.getInt("version") == 1) { "Unsupported settings transfer version" }
        val server = ConnectionPolicy.normalize(value.getString("server"))
        fun strings(name: String): Set<String> = value.getJSONArray(name).let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        }
        val prefs = CalendarPreferences.fromStored(strings("favorites"), strings("hidden"),
            if (value.isNull("default_id")) null else value.optString("default_id").ifBlank { null },
            value.optString("recent"),
            value.optString("colors"), value.optString("view"))
        CalendarPreferenceStore(context).save(server, prefs, durable = true)
        return LegacyMigrationResult(server, prefs, value.optBoolean("pending_draft"))
    }
}
