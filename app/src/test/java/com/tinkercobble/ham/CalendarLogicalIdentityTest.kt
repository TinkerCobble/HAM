package com.tinkercobble.ham

import com.tinkercobble.ham.data.CalendarLogicalIdentity
import org.junit.Assert.*
import org.junit.Test

class CalendarLogicalIdentityTest {
    @Test fun stableCollectionMetadataMatchesAcrossDifferentDeviceRows() {
        val first = CalendarLogicalIdentity.android("test@example.invalid", "com.example.sync",
            "collection-a", "server-collection-1")
        val second = CalendarLogicalIdentity.android("test@example.invalid", "com.example.sync",
            "collection-a", "server-collection-1")
        assertEquals(first, second)
        assertTrue(first!!.startsWith("android-provider:v1:"))
        assertFalse(first.contains("test@example.invalid"))
    }

    @Test fun sameNamesWithoutStableCollectionMetadataRemainUnresolved() {
        assertNull(CalendarLogicalIdentity.android("test@example.invalid", "com.example.sync",
            "Appointments", null))
        assertNull(CalendarLogicalIdentity.android("test@example.invalid", "LOCAL",
            "Appointments", "local-id"))
        assertNotEquals(
            CalendarLogicalIdentity.android("test@example.invalid", "com.example.sync",
                "Appointments", "collection-a"),
            CalendarLogicalIdentity.android("test@example.invalid", "com.example.sync",
                "Appointments", "collection-b"))
    }

    @Test fun providerCandidatesAreStableButSeparateAndNeverContainRawMetadata() {
        val ha = CalendarLogicalIdentity.homeAssistant("https://home.example.invalid/", "calendar.test")
        assertEquals(ha, CalendarLogicalIdentity.homeAssistant("https://home.example.invalid", "calendar.test"))
        val dav = CalendarLogicalIdentity.calDav("https://dav.example.invalid/user/collection/")
        assertEquals(dav, CalendarLogicalIdentity.calDav("https://dav.example.invalid/user/collection"))
        val ics = CalendarLogicalIdentity.ics("https://feed.example.invalid/private-token.ics")
        assertTrue(ha!!.startsWith("home-assistant:v1:"))
        assertTrue(dav!!.startsWith("caldav:v1:"))
        assertTrue(ics!!.startsWith("ics:v1:"))
        assertFalse(ics.contains("private-token"))
        assertNotEquals(dav, ics)
        assertNull(CalendarLogicalIdentity.homeAssistant(null, "calendar.test"))
        assertNull(CalendarLogicalIdentity.calDav(""))
    }
}
