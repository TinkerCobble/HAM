package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.CalendarReadDiagnosticStore
import com.tinkercobble.ham.data.CalendarReadError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CalendarReadDiagnosticsInstrumentedTest {
    @Test fun keepsIndependentSourceHistoryWithoutPrivateIdentifiers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CalendarReadDiagnosticStore(context)
        val first = "ics:fixture-${UUID.randomUUID()}"
        val second = "dav:fixture-${UUID.randomUUID()}"
        val at = Instant.parse("2026-01-02T03:04:05Z")
        try {
            store.record(setOf(first, second), setOf(first),
                mapOf(second to "Network response with private.example.invalid/calendar"), at)
            assertEquals(at, store.read(first).lastSuccess)
            assertTrue(store.read(first).latestAttemptSucceeded == true)
            assertEquals(CalendarReadError.UNAVAILABLE, store.read(second).lastError)
            assertNull(store.read(second).lastSuccess)
            store.record(setOf(second), setOf(second), emptyMap(), at.plusSeconds(60))
            assertEquals(at, store.read(first).lastSuccess)
            assertEquals(at.plusSeconds(60), store.read(second).lastSuccess)
            val serialized = context.getSharedPreferences("ham-calendar-read-diagnostics", 0)
                .all.entries.joinToString("|") { "${it.key}=${it.value}" }
            assertFalse(serialized.contains(first))
            assertFalse(serialized.contains(second))
            assertFalse(serialized.contains("private.example.invalid"))
        } finally {
            store.remove(first)
            store.remove(second)
        }
    }
}
