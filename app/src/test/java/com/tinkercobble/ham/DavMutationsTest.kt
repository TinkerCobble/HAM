package com.tinkercobble.ham

import com.tinkercobble.ham.data.DavAccount
import com.tinkercobble.ham.data.DavConflictException
import com.tinkercobble.ham.data.DavConflictCause
import com.tinkercobble.ham.data.DavConnection
import com.tinkercobble.ham.data.DavKind
import com.tinkercobble.ham.data.DavMutations
import com.tinkercobble.ham.data.DavEventPayload
import com.tinkercobble.ham.data.RepeatFrequency
import com.tinkercobble.ham.data.RepeatSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.time.LocalDate
import java.time.Instant

class DavMutationsTest {
    private val account = DavAccount("synthetic", DavKind.CALDAV,
        "https://dav.example.test/user/calendars/", "sample@example.test", "synthetic-password")
    private val collection = DavConnection.DiscoveredCalendar(
        "https://dav.example.test/user/calendars/family/", "Family", true, true, true)
    private val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n".toByteArray()

    @Test fun currentItemReviewRequiresConfinedUrlStrongEtagAndBoundedResponse() {
        val item = "https://dav.example.test/user/calendars/family/item.ics"
        fun response(etag: String, body: ByteArray = ics) = OkHttpClient.Builder()
            .followRedirects(false).addInterceptor(Interceptor { chain ->
                assertEquals("GET", chain.request().method)
                assertEquals(item, chain.request().url.toString())
                Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("synthetic").header("ETag", etag)
                    .body(body.toResponseBody("text/calendar".toMediaType())).build()
            }).build()
        val current = DavMutations.readCurrent(account, collection, item, response("\"v2\""))
        assertEquals("\"v2\"", current.etag)
        assertTrue(current.ics.contentEquals(ics))
        assertTrue(runCatching { DavMutations.readCurrent(account, collection, item,
            response("W/\"v2\"")) }.isFailure)
        assertTrue(runCatching { DavMutations.readCurrent(account, collection, item,
            response("\"v2\"", ByteArray(512 * 1024 + 1))) }.isFailure)
        assertTrue(runCatching { DavMutations.readCurrent(account, collection,
            "https://other.example.test/item.ics", response("\"v2\"")) }.isFailure)
    }

    @Test fun lostResponseForSeriesRequiresExactResourceBytes() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val original = DavEventPayload.create(id, LocalDate.parse("2026-11-02"),
            "Synthetic series", true, now = Instant.parse("2026-10-27T12:00:00Z"),
            repeat = RepeatSpec(RepeatFrequency.WEEKLY, 4))
        fun response(body: ByteArray) = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            assertEquals("GET", chain.request().method)
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("synthetic")
                .body(body.toResponseBody("text/calendar".toMediaType())).build()
        }).build()
        assertTrue(DavMutations.confirmedCreate(account, collection, id, original, response(original)))
        val different = original.toString(Charsets.UTF_8).replace("COUNT=4", "COUNT=5")
            .toByteArray(Charsets.UTF_8)
        assertTrue(!DavMutations.confirmedCreate(account, collection, id, original, response(different)))
    }

    @Test fun lostCreateResponseCannotConfirmChangedDetails() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000004")
        val original = DavEventPayload.create(id, LocalDate.parse("2026-11-02"),
            "Synthetic details", true, now = Instant.parse("2026-10-27T12:00:00Z"),
            description = "Original description", location = "Original location")
        fun response(body: ByteArray) = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("synthetic")
                .body(body.toResponseBody("text/calendar".toMediaType())).build()
        }).build()
        assertTrue(DavMutations.confirmedCreate(account, collection, id, original, response(original)))
        val changedLocation = original.toString(Charsets.UTF_8)
            .replace("LOCATION:Original location", "LOCATION:Changed location")
            .toByteArray(Charsets.UTF_8)
        val changedDescription = original.toString(Charsets.UTF_8)
            .replace("DESCRIPTION:Original description", "DESCRIPTION:Changed description")
            .toByteArray(Charsets.UTF_8)
        assertTrue(!DavMutations.confirmedCreate(account, collection, id, original,
            response(changedLocation)))
        assertTrue(!DavMutations.confirmedCreate(account, collection, id, original,
            response(changedDescription)))
    }

    private fun fake(code: Int, check: (okhttp3.Request) -> Unit): OkHttpClient =
        OkHttpClient.Builder().followRedirects(false).addInterceptor(Interceptor { chain ->
            check(chain.request())
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code).message("synthetic").body("".toResponseBody()).build()
        }).build()

    @Test fun conditionalUpdateClassifiesOnlySafeServerPrecondition() {
        val item = "https://dav.example.test/user/calendars/family/item.ics"
        fun rejected(body: String): DavConflictException {
            val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                assertEquals("\"v1\"", chain.request().header("If-Match"))
                Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(412).message("synthetic")
                    .body(body.toResponseBody("application/xml".toMediaType())).build()
            }).build()
            val failure = runCatching {
                DavMutations.update(account, collection, item, "\"v1\"", ics, http)
            }.exceptionOrNull()
            assertTrue(failure is DavConflictException)
            return failure as DavConflictException
        }
        assertEquals(DavConflictCause.INVALID_CALENDAR_DATA,
            rejected("<d:error xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><c:valid-calendar-data/></d:error>").reason)
        assertEquals(DavConflictCause.UID_CONFLICT,
            rejected("<d:error><c:no-uid-conflict/></d:error>").reason)
        val unknown = rejected("<d:error><private-account>synthetic-secret</private-account></d:error>")
        assertEquals(DavConflictCause.OTHER_PRECONDITION, unknown.reason)
        assertTrue(!unknown.message.orEmpty().contains("synthetic-secret"))
    }

    @Test fun createUsesStableResourceAndIfNoneMatch() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        var calls = 0
        val http = fake(201) { request ->
            calls++
            assertEquals("PUT", request.method)
            assertEquals("*", request.header("If-None-Match"))
            assertEquals("https://dav.example.test/user/calendars/family/$id.ics", request.url.toString())
            assertTrue(request.header("Authorization")?.startsWith("Basic ") == true)
        }
        DavMutations.create(account, collection, id, ics, http)
        DavMutations.create(account, collection, id, ics, http)
        assertEquals(2, calls)
        assertTrue(runCatching { DavMutations.create(account,
            collection.copy(canCreate = false), id, ics, http) }.isFailure)
    }

    @Test fun updateAndDeleteRequireExactCollectionAndStrongEtag() {
        val item = "https://dav.example.test/user/calendars/family/item.ics"
        val http = fake(204) { request -> assertEquals("\"v1\"", request.header("If-Match")) }
        DavMutations.update(account, collection, item, "\"v1\"", ics, http)
        DavMutations.delete(account, collection, item, "\"v1\"", http)
        assertTrue(runCatching { DavMutations.update(account, collection,
            "https://other.example.test/item.ics", "\"v1\"", ics, http) }.isFailure)
        assertTrue(runCatching { DavMutations.delete(account, collection,
            "https://dav.example.test/user/calendars/other.ics", "\"v1\"", http) }.isFailure)
        assertTrue(runCatching { DavMutations.delete(account, collection, item, "W/\"v1\"", http) }.isFailure)
    }

    @Test fun preconditionFailureIsAConflictAndRedirectIsNeverFollowed() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000002")
        assertTrue(runCatching { DavMutations.create(account, collection, id, ics, fake(412) {}) }
            .exceptionOrNull() is DavConflictException)
        assertTrue(runCatching { DavMutations.create(account, collection, id, ics, fake(302) {}) }.isFailure)
        val item = "https://dav.example.test/user/calendars/family/item.ics"
        assertTrue(runCatching { DavMutations.delete(account, collection, item, "\"v1\"", fake(404) {}) }
            .exceptionOrNull() is DavConflictException)
    }
}
