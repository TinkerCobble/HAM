package com.tinkercobble.ham

import com.tinkercobble.ham.auth.AuthIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AuthIdentityTest {
    private val callback = "com.tinkercobble.ham.dev://auth"

    @Test fun pkceS256MatchesPublishedRfcExample() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            AuthIdentity.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun callbackRequiresExactAppDestinationAndState() {
        assertEquals("one", AuthIdentity.callbackCode("$callback?code=one&state=expected", callback, "expected"))
        assertThrows(IllegalArgumentException::class.java) {
            AuthIdentity.callbackCode("com.tinkercobble.ham.dev://auth.evil?code=one&state=expected", callback, "expected")
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
