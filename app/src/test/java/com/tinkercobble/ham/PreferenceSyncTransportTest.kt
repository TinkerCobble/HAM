package com.tinkercobble.ham

import com.tinkercobble.ham.data.PreferenceSyncEndpoint
import com.tinkercobble.ham.data.PreferenceSyncRequest
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class PreferenceSyncTransportTest {
    @Test fun signedRequestBytesMatchProtocolAndIncludeExactBody() {
        val body = "{\"operations\":[]}".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(body)
            .joinToString("") { "%02x".format(it) }
        val expected = "POST\n/v1/sync\n1790000000\n0123456789abcdef\n$hash"
        assertEquals(expected, String(PreferenceSyncRequest.canonical("POST", "/v1/sync",
            1790000000, "0123456789abcdef", body)))
        assertNotEquals(expected, String(PreferenceSyncRequest.canonical("POST", "/v1/sync",
            1790000000, "0123456789abcdef", "{}".toByteArray())))
    }

    @Test fun endpointRequiresExplicitHttpsAndPinnedCertificate() {
        val fingerprint = "a".repeat(64)
        assertEquals("https://example.invalid/",
            PreferenceSyncEndpoint("https://example.invalid/", fingerprint).baseUrl)
        listOf("http://example.invalid/", "https://user@example.invalid/",
            "https://example.invalid/secret", "https://example.invalid/?token=1")
            .forEach { address ->
                try { PreferenceSyncEndpoint(address, fingerprint); fail(address) }
                catch (_: IllegalArgumentException) { }
            }
        try { PreferenceSyncEndpoint("https://example.invalid/", ""); fail() }
        catch (_: IllegalArgumentException) { }
    }
}
