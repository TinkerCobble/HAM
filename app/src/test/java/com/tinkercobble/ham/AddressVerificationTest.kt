package com.tinkercobble.ham

import com.tinkercobble.ham.data.AddressVerification
import org.junit.Assert.*
import org.junit.Test

class AddressVerificationTest {
    @Test fun lookupNeedsCompletedAddress() {
        assertFalse(AddressVerification.complete(""))
        assertFalse(AddressVerification.complete("Example School"))
        assertFalse(AddressVerification.complete("123 Fictional Street"))
        assertTrue(AddressVerification.complete("123 Fictional Street, Exampletown"))
    }
}
