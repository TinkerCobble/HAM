package com.tinkercobble.ham.updates

import java.net.URI
import java.security.MessageDigest

/** Pure validation for the single HAM release feed and its unit tests. */
object UpdatePolicy {
    const val FEED_URL = "https://tinkercobble.com/ham/update.json"
    const val PACKAGE = "com.tinkercobble.ham"
    const val CHANNEL = "ham"

    fun validApkUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "tinkercobble.com" && uri.port == -1 &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            uri.rawPath == uri.path &&
            Regex("/downloads/ham/ham-[0-9]+(?:\\.[0-9]+){1,3}-code[0-9]+\\.apk").matches(uri.path)
    }.getOrDefault(false)

    fun validSha256(value: String): Boolean = Regex("[a-fA-F0-9]{64}").matches(value)

    fun checksumMatches(expected: String, actual: String): Boolean =
        validSha256(expected) && validSha256(actual) &&
            MessageDigest.isEqual(expected.lowercase().toByteArray(), actual.lowercase().toByteArray())

    fun validMetadata(packageName: String, channel: String, versionCode: Int,
                      versionName: String, minSdk: Int, apkUrl: String, sha256: String): Boolean =
        packageName == PACKAGE && channel == CHANNEL && versionCode > 0 &&
            versionName.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}")) &&
            minSdk in 28..100 && validApkUrl(apkUrl) &&
            apkUrl.substringAfterLast('/') == "ham-$versionName-code$versionCode.apk" &&
            validSha256(sha256)

    fun shouldOffer(versionCode: Int, installedCode: Int, dismissedThisSession: Int): Boolean =
        versionCode > installedCode && versionCode != dismissedThisSession

    fun compatibleAndroid(minSdk: Int, deviceSdk: Int): Boolean =
        minSdk in 28..100 && deviceSdk >= minSdk

    fun sameSigner(installed: ByteArray?, candidate: ByteArray?): Boolean =
        installed != null && candidate != null && MessageDigest.isEqual(installed, candidate)
}
