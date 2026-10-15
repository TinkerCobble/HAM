package com.tinkercobble.ham.updates

/** Keeps a brief Android pause from causing another network check or prompt. */
class UpdateSessionGate {
    private var checked = false
    private var stoppedAt = 0L
    private var dismissedVersion = 0

    fun stopped(now: Long) { stoppedAt = now }

    fun beginNormalLaunch(now: Long, installPending: Boolean): Boolean {
        if (installPending) return false
        val newLaunch = !checked || (stoppedAt > 0L && now - stoppedAt >= 30_000L)
        stoppedAt = 0L
        if (newLaunch) {
            checked = true
            dismissedVersion = 0
        }
        return newLaunch
    }

    fun dismiss(versionCode: Int) { dismissedVersion = versionCode }

    fun shouldPrompt(versionCode: Int, installedCode: Int, manual: Boolean): Boolean =
        versionCode > installedCode && (manual || versionCode != dismissedVersion)
}
