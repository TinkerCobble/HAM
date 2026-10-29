package com.tinkercobble.ham

import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.data.IcsConnection
import com.tinkercobble.ham.data.IcsSubscription
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class IcsConnectionTest {
    private val feed = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n" +
        "UID:synthetic-ics-1@example.test\r\nSUMMARY:Synthetic event\r\n" +
        "DTSTART;VALUE=DATE:20261112\r\nDTEND;VALUE=DATE:20261113\r\n" +
        "END:VEVENT\r\nEND:VCALENDAR\r\n"
    private val item = IcsSubscription("00000000-0000-4000-8000-000000000001", "Synthetic feed",
        "https://example.test/calendar.ics?access=synthetic", "reader", "synthetic-password")
    private val range = DateRange(LocalDate.parse("2026-11-12"), LocalDate.parse("2026-11-13"))

    @Test fun acceptsDirectHttpsWithProtectedQueryAndRejectsUnsafeAddresses() {
        assertEquals(item.url, IcsConnection.normalize(item.url).toString())
        assertTrue(runCatching { IcsConnection.normalize("http://example.test/calendar.ics") }.isFailure)
        assertTrue(runCatching { IcsConnection.normalize("https://user:pass@example.test/calendar.ics") }.isFailure)
        assertTrue(runCatching { IcsConnection.normalize("https://example.test/calendar.ics#fragment") }.isFailure)
    }

    @Test fun readsOneBoundedFeedWithoutMakingItWritable() {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("https", chain.request().url.scheme)
            assertEquals(item.url, chain.request().url.toString())
            assertTrue(chain.request().header("Authorization").orEmpty().startsWith("Basic "))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(feed.toResponseBody("text/calendar".toMediaType())).build()
        }.build()
        val result = IcsConnection.fetch(item, range, client)
        assertEquals(1, requests)
        assertEquals(0, result.unsupportedRecurring)
        assertEquals("Synthetic event", result.events.single().summary)
        assertEquals(CalendarProviderKind.ICS, result.events.single().provider)
        assertFalse(IcsConnection.source(item).canCreate)
    }

    @Test fun doesNotFollowRedirectsOrAcceptAnOversizedFeed() {
        val redirect = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302)
                .message("Found").header("Location", "https://another.example.test/feed.ics")
                .body(ByteArray(0).toResponseBody()).build()
        }.build()
        assertTrue(runCatching { IcsConnection.fetch(item, range, redirect) }.isFailure)
        val huge = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(ByteArray(IcsConnection.MAX_BYTES + 1).toResponseBody()).build()
        }.build()
        assertTrue(runCatching { IcsConnection.fetch(item, range, huge) }.isFailure)
    }
}
