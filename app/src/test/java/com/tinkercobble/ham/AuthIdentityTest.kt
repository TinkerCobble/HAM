package com.tinkercobble.ham

import com.tinkercobble.ham.auth.AuthIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class AuthIdentityTest {
    private val callback = "com.tinkercobble.ham://auth"

    @Test fun pkceS256MatchesPublishedRfcExample() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            AuthIdentity.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun pkceAuthorizeUrlBindsExactCallbackStateAndChallenge() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val url = AuthIdentity.authorizationUrl("https://example.invalid", callback, "random-state", verifier)
        val params = URI(url).rawQuery.split('&').associate { part ->
            val (name, value) = part.split('=', limit = 2)
            name to URLDecoder.decode(value, "UTF-8")
        }
        assertEquals("https://example.invalid/auth/authorize", url.substringBefore('?'))
        assertEquals(AuthIdentity.CLIENT_ID, params["client_id"])
        assertEquals(callback, params["redirect_uri"])
        assertEquals("random-state", params["state"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals(AuthIdentity.challenge(verifier), params["code_challenge"])
        assertTrue(url.indexOf("code_challenge=") > 0)
    }

    @Test fun compatibleAuthorizeUrlStillBindsExactCallbackAndRandomState() {
        val url = AuthIdentity.authorizationUrl("https://example.invalid", callback, "random-state", null)
        val query = URI(url).rawQuery
        assertTrue(query.contains("redirect_uri="))
        assertTrue(query.contains("state=random-state"))
        assertEquals(false, query.contains("code_challenge="))
        assertEquals(false, query.contains("code_challenge_method="))
    }

    @Test fun callbackRequiresExactAppDestinationAndState() {
        assertEquals("one", AuthIdentity.callbackCode("$callback?code=one&state=expected", callback, "expected"))
        assertThrows(IllegalArgumentException::class.java) {
            AuthIdentity.callbackCode("com.tinkercobble.ham://auth.evil?code=one&state=expected", callback, "expected")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuthIdentity.callbackCode("$callback?code=one&state=wrong", callback, "expected")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuthIdentity.callbackCode("$callback?code=one&state=expected&state=expected", callback, "expected")
        }
    }

    @Test fun callbackCannotCarryFragmentOrDuplicateCode() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthIdentity.callbackCode("$callback?code=one&state=expected#fragment", callback, "expected")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuthIdentity.callbackCode("$callback?code=one&code=two&state=expected", callback, "expected")
        }
    }
}
