package com.tinkercobble.ham.data

import java.security.MessageDigest

/** Conservative candidate key for the same synced Android collection on two devices.
 * Never fall back to a display name or a device-local Calendar Provider row ID.
 * A candidate still needs user review before applying another device's choices.
 */
object CalendarLogicalIdentity {
    private fun digest(kind: String, parts: List<String?>): String? {
        if (parts.any { it.isNullOrBlank() }) return null
        val bytes = (listOf(kind) + parts.map { it!!.trim() }).joinToString("\u0000")
            .toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return "$kind:v1:$hash"
    }

    fun android(accountName: String?, accountType: String?, collectionName: String?,
        syncId: String?): String? {
        val parts = listOf(accountName, accountType, collectionName, syncId)
        if (parts.any { it.isNullOrBlank() } || accountType == "LOCAL") return null
        // Retain the original v1 Android digest for existing candidate identity stability.
        val bytes = parts.joinToString("\u0000") { it!!.trim() }.toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return "android-provider:v1:$hash"
    }

    /** Candidate only: a device must approve the exact local source before applying settings. */
    fun homeAssistant(server: String?, entityId: String?): String? =
        digest("home-assistant", listOf(server?.trimEnd('/'), entityId))

    fun calDav(collectionUrl: String?): String? =
        digest("caldav", listOf(collectionUrl?.trimEnd('/')))

    fun ics(feedUrl: String?): String? = digest("ics", listOf(feedUrl))
}
