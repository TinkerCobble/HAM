package com.tinkercobble.ham.updates

import android.content.Context
import android.content.Intent
import java.io.File

/** Private DEV has no public updater or APK-install capability. */
@Suppress("UNUSED_PARAMETER")
object UpdateService {
    fun shouldAutoCheck(context: Context) = false
    suspend fun check(context: Context, manual: Boolean) = UpdateCheckResult("HAM DEV is updated through internal testing.")
    fun later(context: Context, update: AvailableUpdate) = Unit
    suspend fun downloadAndVerify(context: Context, update: AvailableUpdate, onProgress: (Int) -> Unit): File = error("DEV cannot install Stable updates")
    fun canInstall(context: Context) = false
    fun permissionIntent(context: Context): Intent = error("DEV cannot request install permission")
    fun installerIntent(context: Context, file: File): Intent = error("DEV cannot install Stable updates")
}
