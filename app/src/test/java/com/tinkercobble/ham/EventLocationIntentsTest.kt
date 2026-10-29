package com.tinkercobble.ham

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventLocationIntentsTest {
    @Test
    fun `street address is encoded into a geo view intent`() {
        val spec = EventLocationIntents.spec("125 Example Avenue, Suite #4")!!

        assertEquals("android.intent.action.VIEW", spec.action)
        assertEquals("geo:0,0?q=125%20Example%20Avenue%2C%20Suite%20%234", spec.uri)
        assertEquals("Open location with", spec.chooserTitle)
    }

    @Test
    fun `venue punctuation and multiline location are preserved through encoding`() {
        val spec = EventLocationIntents.spec("Fictional Children's Museum\n7 Make-Believe Square")!!

        assertEquals(
            "geo:0,0?q=Fictional%20Children%27s%20Museum%0A7%20Make-Believe%20Square",
            spec.uri,
        )
    }

    @Test
    fun `venue only location creates an intent spec`() {
        assertEquals(
            "geo:0,0?q=Northstar%20Community%20Hall",
            EventLocationIntents.spec("Northstar Community Hall")?.uri,
        )
    }

    @Test
    fun `missing and null placeholder locations create no intent spec`() {
        assertNull(EventLocationIntents.spec(""))
        assertNull(EventLocationIntents.spec("  \n "))
        assertNull(EventLocationIntents.spec("null"))
    }
}
