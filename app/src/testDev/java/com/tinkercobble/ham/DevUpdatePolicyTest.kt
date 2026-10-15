package com.tinkercobble.ham

import com.tinkercobble.ham.updates.DevUpdatePolicy
import com.tinkercobble.ham.updates.UpdateSessionGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevUpdatePolicyTest {
    private val url = "https://tinkercobble.com/downloads/ham/ham-0.1.10-dev-code11.apk"
    private val hash = "a".repeat(64)

    @Test fun acceptsOnlyMatchingDevReleaseAndSafeMetadata() {
        fun valid(pkg: String = DevUpdatePolicy.PACKAGE, channel: String = "dev",
                  name: String = "0.1.10-dev", sdk: Int = 28, link: String = url,
                  digest: String = hash) = DevUpdatePolicy.validMetadata(pkg, channel, 11, name, sdk, link, digest)
        assertTrue(valid())
        assertFalse(valid(pkg = "com.tinkercobble.ham"))
        assertFalse(valid(channel = "stable"))
        assertFalse(valid(name = "0.1.10"))
        assertFalse(valid(sdk = 0))
        assertFalse(valid(link = "http://tinkercobble.com/downloads/ham/ham-0.1.10-dev-code11.apk"))
        assertFalse(valid(link = "https://evil.example/downloads/ham/ham-0.1.10-dev-code11.apk"))
        assertFalse(valid(link = "$url?token=secret"))
        assertFalse(valid(link = "https://tinkercobble.com/downloads/ham/ham-0.1.10-dev-code11%2Fbad.apk"))
        assertFalse(valid(digest = "b".repeat(63)))
    }

    @Test fun onlyNewerUndismissedVersionCanPrompt() {
        assertTrue(DevUpdatePolicy.shouldOffer(11, 10, 0))
        assertFalse(DevUpdatePolicy.shouldOffer(10, 10, 0))
        assertFalse(DevUpdatePolicy.shouldOffer(9, 10, 0))
        assertFalse(DevUpdatePolicy.shouldOffer(11, 10, 11))
        assertTrue(DevUpdatePolicy.shouldOffer(12, 10, 11))
    }

    @Test fun signerMustMatchExactly() {
        assertTrue(DevUpdatePolicy.sameSigner(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(DevUpdatePolicy.sameSigner(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(DevUpdatePolicy.sameSigner(null, byteArrayOf(1, 2)))
    }

    @Test fun downloadedChecksumMustMatchTheFeed() {
        assertTrue(DevUpdatePolicy.checksumMatches(hash.uppercase(), hash))
        assertFalse(DevUpdatePolicy.checksumMatches(hash, "b".repeat(64)))
        assertFalse(DevUpdatePolicy.checksumMatches("not-a-hash", hash))
    }

    @Test fun incompatibleAndroidIsRejected() {
        assertTrue(DevUpdatePolicy.compatibleAndroid(28, 28))
        assertFalse(DevUpdatePolicy.compatibleAndroid(29, 28))
        assertFalse(DevUpdatePolicy.compatibleAndroid(0, 36))
    }

    @Test fun launchGateIgnoresBriefStopsAndLaterUntilNextNormalLaunch() {
        val gate = UpdateSessionGate()
        assertTrue(gate.beginNormalLaunch(1_000, false))
        assertFalse(gate.beginNormalLaunch(2_000, false))
        gate.dismiss(11)
        assertFalse(gate.shouldPrompt(11, 10, false))
        assertTrue(gate.shouldPrompt(11, 10, true))
        gate.stopped(3_000)
        assertFalse(gate.beginNormalLaunch(12_000, false))
        assertFalse(gate.shouldPrompt(11, 10, false))
        gate.stopped(13_000)
        assertFalse(gate.beginNormalLaunch(50_000, true))
        assertFalse(gate.shouldPrompt(11, 10, false))
        assertTrue(gate.beginNormalLaunch(51_000, false))
        assertTrue(gate.shouldPrompt(11, 10, false))
    }
}
