package com.tinkercobble.ham.data

import android.content.Context

/** User-controlled pause for HAM's own network traffic. Android account sync is outside HAM. */
object OfflineMode {
    private const val PREFS = "ham-offline-mode"
    private const val ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, enabled).commit()) { "Could not save Offline setting" }
    }
}
