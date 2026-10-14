package com.tinkercobble.ham.updates

import java.net.URI

enum class UpdateRoute { INTERNAL, GOOGLE_PLAY, F_DROID, DIRECT }

/** The installer decides which updater owns this installation. DEV never uses a public feed. */
object DirectUpdatePolicy {
    const val FEED_URL = "https://tinkercobble.com/ham/stable-update.json"
    const val PRODUCT_URL = "https://tinkercobble.com/ham/"
    const val STABLE_PACKAGE = "com.tinkercobble.ham"

    fun route(isDev: Boolean, installer: String?): UpdateRoute = when {
        isDev -> UpdateRoute.INTERNAL
        installer == "com.android.vending" -> UpdateRoute.GOOGLE_PLAY
        installer?.startsWith("org.fdroid.") == true -> UpdateRoute.F_DROID
        else -> UpdateRoute.DIRECT
    }

    fun validApkUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "tinkercobble.com" && uri.port == -1 &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            uri.rawPath == uri.path &&
            Regex("/downloads/ham/ham-[0-9]+(?:\\.[0-9]+){1,3}(?:-[A-Za-z0-9._-]+)?\\.apk").matches(uri.path)
    }.getOrDefault(false)

    fun validSha256(value: String): Boolean = Regex("[a-fA-F0-9]{64}").matches(value)

    fun checkDue(now: Long, last: Long): Boolean =
        last <= 0L || now < last || now - last >= 24L * 60L * 60L * 1000L

    fun shouldOffer(versionCode: Int, installedCode: Int, laterVersion: Int, manual: Boolean): Boolean =
        versionCode > installedCode && (manual || versionCode > laterVersion)
}
