package com.tinkercobble.ham.data

import android.content.Context
import java.security.MessageDigest
import java.time.Instant

enum class CalendarReadError(val label: String) {
    AUTH("Authorization needs review"),
    PERMISSION("Calendar permission needs review"),
    UNSUPPORTED("Calendar format is not fully supported"),
    PARTIAL("Some calendar items could not be read"),
    UNAVAILABLE("Calendar could not be reached"),
    OTHER("Calendar read failed");

    companion object {
        /** Persist a fixed category, never a server response or private exception string. */
        fun from(message: String): CalendarReadError {
            val value = message.lowercase()
            return when {
                listOf("auth", "sign-in", "unauthor", "token", "401").any { it in value } -> AUTH
                "permission" in value -> PERMISSION
                "unsupported" in value || "recurrence" in value -> UNSUPPORTED
                "some" in value || "partial" in value -> PARTIAL
                listOf("unavailable", "network", "offline", "reach").any { it in value } -> UNAVAILABLE
                else -> OTHER
            }
        }
    }
}

data class CalendarReadDiagnostic(
    val lastAttempt: Instant?,
    val lastSuccess: Instant?,
    val lastErrorAt: Instant?,
    val lastError: CalendarReadError?,
    val latestAttemptSucceeded: Boolean?,
)

/** Private source-hash keyed read status. No source name, ID, URL or event is stored. */
class CalendarReadDiagnosticStore(context: Context) {
    private val prefs = context.getSharedPreferences("ham-calendar-read-diagnostics", Context.MODE_PRIVATE)
    companion object { private val lock = Any() }

    private fun prefix(sourceId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(sourceId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) } + "."

    fun read(sourceId: String): CalendarReadDiagnostic = synchronized(lock) {
        val key = prefix(sourceId)
        fun time(suffix: String) = prefs.getLong(key + suffix, 0).takeIf { it > 0 }
            ?.let(Instant::ofEpochMilli)
        val outcome = prefs.getString(key + "outcome", null)
        CalendarReadDiagnostic(time("attempt"), time("success"), time("error_at"),
            prefs.getString(key + "error", null)?.let {
                runCatching { CalendarReadError.valueOf(it) }.getOrNull()
            }, when (outcome) { "success" -> true; "failure" -> false; else -> null })
    }

    /** Update only calendars actually attempted in this refresh; hidden sources retain prior status. */
    fun record(attemptedIds: Set<String>, succeededIds: Set<String>,
        failures: Map<String, String>, at: Instant) = synchronized(lock) {
        if (attemptedIds.isEmpty()) return@synchronized
        val editor = prefs.edit()
        attemptedIds.forEach { id ->
            val key = prefix(id)
            editor.putLong(key + "attempt", at.toEpochMilli())
            if (id in succeededIds && id !in failures) {
                editor.putLong(key + "success", at.toEpochMilli())
                    .putString(key + "outcome", "success")
            } else {
                editor.putLong(key + "error_at", at.toEpochMilli())
                    .putString(key + "error",
                        CalendarReadError.from(failures[id].orEmpty()).name)
                    .putString(key + "outcome", "failure")
            }
        }
        check(editor.commit()) { "Could not save calendar read status" }
    }

    fun remove(sourceId: String) = synchronized(lock) {
        val key = prefix(sourceId)
        val editor = prefs.edit()
        listOf("attempt", "success", "error_at", "error", "outcome").forEach {
            editor.remove(key + it)
        }
        check(editor.commit()) { "Could not remove calendar read status" }
    }
}
