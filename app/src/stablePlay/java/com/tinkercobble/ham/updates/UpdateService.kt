package com.tinkercobble.ham.updates

import android.content.Context
import android.content.Intent
import java.io.File

/** Play owns updates for this package variant; no direct APK installer is bundled. */
@Suppress("UNUSED_PARAMETER")
object UpdateService {
    fun shouldAutoCheck(context: Context) = false
    suspend fun check(context: Context, manual: Boolean) = UpdateCheckResult("Check Google Play for HAM updates.")
    fun later(context: Context, update: AvailableUpdate) = Unit
    suspend fun downloadAndVerify(context: Context, update: AvailableUpdate, onProgress: (Int) -> Unit): File = error("Use Google Play for updates")
    fun canInstall(context: Context) = false
    fun permissionIntent(context: Context): Intent = error("Use Google Play for updates")
    fun installerIntent(context: Context, file: File): Intent = error("Use Google Play for updates")
}
