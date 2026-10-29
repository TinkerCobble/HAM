package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.DavConnection
import com.tinkercobble.ham.data.DavResourceStore
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DavResourceStoreInstrumentedTest {
    @Test fun encryptedIndexReplacesRangeAndRejectsAmbiguousUid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DavResourceStore(context)
        val account = "synthetic-${UUID.randomUUID()}"
        val calendar = "synthetic-calendar"
        val marker = "synthetic-private-resource-marker"
        val ics = marker.toByteArray()
        try {
            store.replaceCalendar(account, calendar, listOf(
                DavConnection.Resource("https://example.test/a.ics", "\"a\"", ics, "uid-a")))
            assertFalse(String(File(context.filesDir, "dav-resources-v1.bin").readBytes(),
                Charsets.ISO_8859_1).contains(marker))
            assertTrue(DavResourceStore(context).uniqueForUid(account, calendar, "uid-a")!!
                .ics.contentEquals(ics))
            store.replaceCalendar(account, calendar, listOf(
                DavConnection.Resource("https://example.test/b.ics", "\"b\"", ics, "uid-b"),
                DavConnection.Resource("https://example.test/c.ics", "\"c\"", ics, "uid-b")))
            assertNull(store.uniqueForUid(account, calendar, "uid-a"))
            assertNull(store.uniqueForUid(account, calendar, "uid-b"))
            assertEquals(2, DavResourceStore(context).list().count { it.accountId == account })
        } finally { store.removeAccount(account) }
    }

    @Test fun accountRemovalPreservesOtherAccount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DavResourceStore(context)
        val first = "synthetic-a-${UUID.randomUUID()}"
        val second = "synthetic-b-${UUID.randomUUID()}"
        val resource = DavConnection.Resource("https://example.test/a.ics", "\"a\"",
            byteArrayOf(65), "uid")
        try {
            store.replaceCalendar(first, "calendar", listOf(resource))
            store.replaceCalendar(second, "calendar", listOf(resource))
            store.removeAccount(first)
            assertNull(store.uniqueForUid(first, "calendar", "uid"))
            assertEquals(second, store.uniqueForUid(second, "calendar", "uid")?.accountId)
        } finally {
            store.removeAccount(first)
            store.removeAccount(second)
        }
    }

    @Test fun retainSelectedCalendarsDropsDeselectedResources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DavResourceStore(context)
        val account = "synthetic-${UUID.randomUUID()}"
        val resource = DavConnection.Resource("https://example.test/a.ics", "\"a\"",
            byteArrayOf(65), "uid")
        try {
            store.replaceCalendar(account, "keep", listOf(resource))
            store.replaceCalendar(account, "drop", listOf(resource))
            store.retainCalendars(account, setOf("keep"))
            assertEquals(account, store.uniqueForUid(account, "keep", "uid")?.accountId)
            assertNull(store.uniqueForUid(account, "drop", "uid"))
        } finally { store.removeAccount(account) }
    }
}
