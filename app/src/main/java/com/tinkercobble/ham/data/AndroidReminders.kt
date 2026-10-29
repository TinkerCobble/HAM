package com.tinkercobble.ham.data

/** Calendar Provider reminder offsets; notifications are delivered by the provider's calendar app. */
object AndroidReminders {
    const val MAX_MINUTES = 7 * 24 * 60
    fun minutes(text: String): Int = text.trim().toIntOrNull()?.takeIf { it in 0..MAX_MINUTES }
        ?: error("Enter 0–10080 minutes before the event")
}

data class AndroidReminder(val id: Long, val minutes: Int, val method: Int)
