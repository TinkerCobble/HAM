package com.tinkercobble.ham

import com.tinkercobble.ham.updates.DirectUpdatePolicy
import com.tinkercobble.ham.updates.UpdateRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectUpdatePolicyTest {
    @Test fun onlyStableDirectInstallsUseTheDirectFeed() {
        assertEquals(UpdateRoute.INTERNAL, DirectUpdatePolicy.route(true, null))
        assertEquals(UpdateRoute.INTERNAL, DirectUpdatePolicy.route(true, "com.android.vending"))
        assertEquals(UpdateRoute.GOOGLE_PLAY, DirectUpdatePolicy.route(false, "com.android.vending"))
        assertEquals(UpdateRoute.F_DROID, DirectUpdatePolicy.route(false, "org.fdroid.fdroid"))
        assertEquals(UpdateRoute.DIRECT, DirectUpdatePolicy.route(false, null))
        assertEquals(UpdateRoute.DIRECT, DirectUpdatePolicy.route(false, "com.google.android.packageinstaller"))
    }

    @Test fun updateArtifactMustBeAnOfficialHttpsHamApk() {
        assertTrue(DirectUpdatePolicy.validApkUrl("https://tinkercobble.com/downloads/ham/ham-1.0.0.apk"))
        assertFalse(DirectUpdatePolicy.validApkUrl("http://tinkercobble.com/downloads/ham/ham.apk"))
        assertFalse(DirectUpdatePolicy.validApkUrl("https://evil.example/downloads/ham/ham.apk"))
        assertFalse(DirectUpdatePolicy.validApkUrl("https://tinkercobble.com/downloads/cobblebucket/stable.apk"))
        assertFalse(DirectUpdatePolicy.validApkUrl("https://tinkercobble.com/downloads/ham/ham.apk?token=abc"))
        assertFalse(DirectUpdatePolicy.validApkUrl("https://tinkercobble.com/downloads/ham/stable.apk"))
        assertFalse(DirectUpdatePolicy.validApkUrl("https://tinkercobble.com/downloads/ham/ham-1.0.0%2Fextra.apk"))
        assertTrue(DirectUpdatePolicy.validSha256("a".repeat(64)))
        assertFalse(DirectUpdatePolicy.validSha256("a".repeat(63)))
    }

    @Test fun automaticChecksAndLaterAreRateLimited() {
        val day = 24L * 60L * 60L * 1000L
        assertTrue(DirectUpdatePolicy.checkDue(day, 0))
        assertFalse(DirectUpdatePolicy.checkDue(day + 1_000, day))
        assertTrue(DirectUpdatePolicy.checkDue(day * 2, day))
        assertTrue(DirectUpdatePolicy.checkDue(day - 1, day))
        assertFalse(DirectUpdatePolicy.shouldOffer(2, 1, 2, manual = false))
        assertTrue(DirectUpdatePolicy.shouldOffer(2, 1, 2, manual = true))
        assertFalse(DirectUpdatePolicy.shouldOffer(1, 1, 0, manual = true))
    }
}
