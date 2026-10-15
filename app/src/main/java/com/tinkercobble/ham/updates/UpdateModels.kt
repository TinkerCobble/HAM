package com.tinkercobble.ham.updates

data class AvailableUpdate(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val minSdk: Int = 26,
    val releaseNotes: String = "",
)

data class UpdateCheckResult(val message: String, val update: AvailableUpdate? = null)
