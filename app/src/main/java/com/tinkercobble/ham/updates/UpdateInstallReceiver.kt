package com.tinkercobble.ham.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Handles only Android's result for a session HAM created to update itself. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(EXTRA_SESSION, -1)
        val code = intent.getIntExtra(EXTRA_VERSION, -1)
        if (sessionId < 0 || code < 1) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_SUCCESS) {
            AutoUpdateScheduler.clearManualFallback(context, code)
            return
        }
        // A device can demand approval despite USER_ACTION_NOT_REQUIRED. Stop this
        // background session and let HAM's normal foreground installer ask the owner.
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
        AutoUpdateScheduler.requireManualFallback(context, code)
    }

    companion object {
        const val EXTRA_SESSION = "ham_update_session"
        const val EXTRA_VERSION = "ham_update_version"
    }
}
