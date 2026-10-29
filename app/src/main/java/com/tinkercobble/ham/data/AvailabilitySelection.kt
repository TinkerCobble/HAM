package com.tinkercobble.ham.data

import android.content.Context
import java.security.MessageDigest

/** Find Time's busy-calendar choices are independent of agenda visibility. */
object AvailabilitySelection {
    fun initial(sources: List<CalendarSource>, visibleIds: Set<String>): Set<String> =
        sources.filter { it.provider == CalendarProviderKind.HOME_ASSISTANT || it.id in visibleIds }
            .mapTo(mutableSetOf()) { it.id }

    fun events(events: List<AgendaEvent>, sources: List<CalendarSource>, includedIds: Set<String>):
        List<AgendaEvent> {
        val available = sources.mapTo(mutableSetOf()) { it.id }
        return events.filter { it.calendarId in includedIds && it.calendarId in available }
    }

    /** Numeric Android Provider IDs can be reused for another calendar. */
    fun safeAndroidIds(ids: Set<String>, boundFingerprints: Map<String, String>,
        current: List<CalendarSource>): Set<String> {
        val android = current.filter { it.provider == CalendarProviderKind.ANDROID }
            .associateBy { it.id }
        return ids.filterTo(mutableSetOf()) { id ->
            if (!id.startsWith("android:")) true else {
                val source = android[id]
                source?.identityFingerprint != null &&
                    source.identityFingerprint == boundFingerprints[id]
            }
        }
    }

    fun pruneConfirmedMissing(ids: Set<String>, sources: List<CalendarSource>,
        homeAssistantDiscoverySucceeded: Boolean, icsDiscoverySucceeded: Boolean): Set<String> {
        val known = sources.mapTo(mutableSetOf()) { it.id }
        return ids.filterTo(mutableSetOf()) { id ->
            when {
                id.startsWith("android:") || id.startsWith("dav:") -> true
                id.startsWith("ics:") -> !icsDiscoverySucceeded || id in known
                else -> !homeAssistantDiscoverySucceeded || id in known
            }
        }
    }
}

/** Device-private choices; no event data, server response or credential is stored. */
class AvailabilitySelectionStore(context: Context, scope: String) {
    private val prefs = context.getSharedPreferences("ham-findtime-calendars", Context.MODE_PRIVATE)
    private val prefix = MessageDigest.getInstance("SHA-256")
        .digest(scope.trimEnd('/').toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) } + "."

    fun isConfigured(): Boolean = prefs.getBoolean(prefix + "configured", false)
    fun ids(): Set<String> = prefs.getStringSet(prefix + "included", emptySet()).orEmpty().toSet()
    fun androidFingerprints(): Map<String, String> = CalendarSelection.parseAndroidFingerprints(
        prefs.getString(prefix + "android_fingerprints", null))

    fun save(ids: Set<String>, fingerprints: Map<String, String>) {
        require(ids.size <= 250)
        require(fingerprints.keys.all { it in ids && it.startsWith("android:") })
        check(prefs.edit().putStringSet(prefix + "included", ids.toSet())
            .putString(prefix + "android_fingerprints", CalendarSelection.encodeAndroidFingerprints(fingerprints))
            .putBoolean(prefix + "configured", true).commit()) { "Could not save Find Time calendars" }
    }

    fun clear() {
        check(prefs.edit().remove(prefix + "configured").remove(prefix + "included")
            .remove(prefix + "android_fingerprints").commit()) { "Could not clear Find Time calendars" }
    }
}
