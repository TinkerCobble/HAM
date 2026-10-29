package com.tinkercobble.ham

import com.tinkercobble.ham.updates.UpdatePolicy
import com.tinkercobble.ham.updates.UpdateSessionGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val url = "https://tinkercobble.com/downloads/ham/ham-0.2.0-code16.apk"
    private val hash = "a".repeat(64)

    @Test fun acceptsOnlyMatchingDevReleaseAndSafeMetadata() {
        fun valid(pkg: String = UpdatePolicy.PACKAGE, channel: String = "ham",
                  name: String = "0.2.0", sdk: Int = 28, link: String = url,
                  digest: String = hash) = UpdatePolicy.validMetadata(pkg, channel, 16, name, sdk, link, digest)
        assertTrue(valid())
        assertFalse(valid(pkg = "com.tinkercobble.ham.dev"))
        assertFalse(valid(channel = "dev"))
        assertFalse(valid(name = "0.2.0-dev"))
        assertFalse(valid(name = "0.2.1"))
        assertFalse(valid(sdk = 0))
        assertFalse(valid(link = "http://tinkercobble.com/downloads/ham/ham-0.2.0-code16.apk"))
        assertFalse(valid(link = "https://evil.example/downloads/ham/ham-0.2.0-code16.apk"))
        assertFalse(valid(link = "$url?token=secret"))
        assertFalse(valid(link = "https://tinkercobble.com/downloads/ham/ham-0.2.0-code11%2Fbad.apk"))
        assertFalse(valid(digest = "b".repeat(63)))
    }

    @Test fun onlyNewerUndismissedVersionCanPrompt() {
        assertTrue(UpdatePolicy.shouldOffer(11, 10, 0))
        assertFalse(UpdatePolicy.shouldOffer(10, 10, 0))
        assertFalse(UpdatePolicy.shouldOffer(9, 10, 0))
        assertFalse(UpdatePolicy.shouldOffer(11, 10, 11))
        assertTrue(UpdatePolicy.shouldOffer(12, 10, 11))
    }

    @Test fun signerMustMatchExactly() {
        assertTrue(UpdatePolicy.sameSigner(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(UpdatePolicy.sameSigner(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(UpdatePolicy.sameSigner(null, byteArrayOf(1, 2)))
    }

    @Test fun downloadedChecksumMustMatchTheFeed() {
        assertTrue(UpdatePolicy.checksumMatches(hash.uppercase(), hash))
        assertFalse(UpdatePolicy.checksumMatches(hash, "b".repeat(64)))
        assertFalse(UpdatePolicy.checksumMatches("not-a-hash", hash))
    }

    @Test fun incompatibleAndroidIsRejected() {
        assertTrue(UpdatePolicy.compatibleAndroid(28, 28))
        assertFalse(UpdatePolicy.compatibleAndroid(29, 28))
        assertFalse(UpdatePolicy.compatibleAndroid(0, 36))
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
