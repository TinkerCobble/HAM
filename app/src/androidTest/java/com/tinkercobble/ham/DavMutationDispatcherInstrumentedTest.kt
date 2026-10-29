package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.data.DavAccount
import com.tinkercobble.ham.data.DavConnection
import com.tinkercobble.ham.data.DavEventPayload
import com.tinkercobble.ham.data.DavKind
import com.tinkercobble.ham.data.DavMutationDispatcher
import com.tinkercobble.ham.data.DavMutations
import com.tinkercobble.ham.data.DavSeriesValidation
import com.tinkercobble.ham.data.DavTitleConflict
import com.tinkercobble.ham.data.DavDetailsConflict
import com.tinkercobble.ham.data.DavOperation
import com.tinkercobble.ham.data.DavPendingState
import com.tinkercobble.ham.data.PendingDavMutation
import com.tinkercobble.ham.data.PendingDavMutations
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DavMutationDispatcherInstrumentedTest {
    @Test fun reviewedMetadataConflictUsesCurrentEtagAndPreservesServerTitle() {
        val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
        val accountId = "synthetic-${UUID.randomUUID()}"
        val testAccount = account.copy(id = accountId)
        val source = DavConnection.source(testAccount, collection)
        val eventId = UUID.randomUUID()
        val changeId = UUID.randomUUID()
        val itemUrl = "https://dav.example.test/user/calendars/family/item.ics"
        val base = DavEventPayload.create(eventId, LocalDate.parse("2026-11-12"),
            "Original", true, now = Instant.parse("2026-10-27T12:00:00Z"),
            description = "Old notes")
        val pending = DavEventPayload.updateDetails(base, "My notes", "My room")
        val server = DavEventPayload.updateTitle(base, "Server title")
        var puts = 0
        val http = OkHttpClient.Builder().followRedirects(false).addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val code = when (request.method) {
                "PROPFIND" -> 207
                "GET" -> { assertEquals(itemUrl, request.url.toString()); 200 }
                "PUT" -> {
                    puts++
                    assertEquals("\"v2\"", request.header("If-Match"))
                    assertEquals(itemUrl, request.url.toString())
                    204
                }
                else -> error("Unexpected method")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic").apply { if (code == 200) header("ETag", "\"v2\"") }
                .body((when (code) { 207 -> collectionsXml.toByteArray()
                    200 -> server; else -> byteArrayOf() }).toResponseBody()).build()
        }).build()
        try {
            queue.enqueue(PendingDavMutation(changeId, accountId, source.id, DavOperation.UPDATE,
                itemUrl, "\"v1\"", pending, baseIcs = base))
            queue.markConflict(changeId)
            val current = DavMutations.readCurrent(testAccount, collection, itemUrl, http)
            val review = DavDetailsConflict.review(base, pending, current.ics, source)
            assertTrue(review.mergedIcs.toString(Charsets.UTF_8).contains("SUMMARY:Server title"))
            assertTrue(review.mergedIcs.toString(Charsets.UTF_8).contains("DESCRIPTION:My notes"))
            queue.requeueReviewedDetails(changeId, "\"v1\"", current.etag,
                current.ics, review.mergedIcs)
            val result = DavMutationDispatcher.flush(testAccount, queue, http)
            assertEquals(1, result.confirmed)
            assertEquals(1, puts)
            assertTrue(queue.list().none { it.id == changeId })
        } finally { queue.discardAccount(accountId) }
    }
    @Test fun reviewedDeleteUsesNewEtagOnlyForUnchangedResource() {
        val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
        val accountId = "synthetic-${UUID.randomUUID()}"
        val testAccount = account.copy(id = accountId)
        val source = DavConnection.source(testAccount, collection)
        val eventId = UUID.randomUUID()
        val changeId = UUID.randomUUID()
        val itemUrl = "https://dav.example.test/user/calendars/family/item.ics"
        val base = DavEventPayload.create(eventId, LocalDate.parse("2026-11-12"),
            "Original", true, now = Instant.parse("2026-10-27T12:00:00Z"))
        var deletes = 0
        val http = OkHttpClient.Builder().followRedirects(false).addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val code = when (request.method) {
                "PROPFIND" -> 207
                "GET" -> { assertEquals(itemUrl, request.url.toString()); 200 }
                "DELETE" -> {
                    deletes++
                    assertEquals(itemUrl, request.url.toString())
                    assertEquals("\"v2\"", request.header("If-Match"))
                    204
                }
                else -> error("Unexpected method")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic").apply { if (code == 200) header("ETag", "\"v2\"") }
                .body((when (code) { 207 -> collectionsXml.toByteArray()
                    200 -> base; else -> byteArrayOf() }).toResponseBody()).build()
        }).build()
        try {
            queue.enqueue(PendingDavMutation(changeId, accountId, source.id, DavOperation.DELETE,
                itemUrl, "\"v1\"", null, uid = "$eventId@ham.tinkercobble.com",
                baseIcs = base))
            queue.markConflict(changeId)
            val current = DavMutations.readCurrent(testAccount, collection, itemUrl, http)
            queue.requeueReviewedDelete(changeId, "\"v1\"", current.etag, current.ics)
            val result = DavMutationDispatcher.flush(testAccount, queue, http)
            assertEquals(1, result.confirmed)
            assertEquals(1, deletes)
            assertTrue(queue.list().none { it.id == changeId })
        } finally { queue.discardAccount(accountId) }
    }
    private val account = DavAccount("synthetic-dispatch", DavKind.CALDAV,
        "https://dav.example.test/user/calendars/", "sample@example.test", "synthetic-password")
    private val collection = DavConnection.DiscoveredCalendar(
        "https://dav.example.test/user/calendars/family/", "Family", true, true, true)
    private val collectionsXml = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
        <d:response><d:href>/user/calendars/family/</d:href><d:propstat><d:prop>
        <d:displayname>Family</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
        <d:current-user-privilege-set><d:privilege><d:bind/></d:privilege>
        <d:privilege><d:write-content/></d:privilege><d:privilege><d:unbind/></d:privilege>
        </d:current-user-privilege-set></d:prop><d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat></d:response></d:multistatus>""".trimIndent()
    private val emptyEventsXml = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"/>"""
    private fun createPayload(id: UUID, title: String = "Synthetic event") =
        DavEventPayload.create(id, LocalDate.parse("2026-11-12"), title, true,
            now = Instant.parse("2026-10-27T12:00:00Z"))
    private fun eventsXml(payload: ByteArray): String =
        """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
        <d:response><d:href>/user/calendars/family/existing.ics</d:href><d:propstat><d:prop>
        <d:getetag>&quot;existing&quot;</d:getetag><c:calendar-data><![CDATA[${payload.toString(Charsets.UTF_8)}]]></c:calendar-data>
        </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""".trimIndent()

    @Test fun multiDayCreateChecksExactCollectionBeforePut() {
        for (collision in listOf(false, true)) {
            val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
            val accountId = "synthetic-${UUID.randomUUID()}"
            val testAccount = account.copy(id = accountId)
            val source = DavConnection.source(testAccount, collection)
            val id = UUID.randomUUID()
            val first = LocalDate.parse("2026-11-12")
            val payload = DavEventPayload.create(id, first, "Synthetic trip", true,
                now = Instant.parse("2026-10-27T12:00:00Z"), endExclusive = first.plusDays(4))
            var reports = 0
            var puts = 0
            val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val code = when (request.method) {
                    "PROPFIND", "REPORT" -> 207
                    "PUT" -> {
                        puts++
                        assertEquals("*", request.header("If-None-Match"))
                        val body = okio.Buffer().also { request.body!!.writeTo(it) }.readByteArray()
                        assertTrue(body.contentEquals(payload))
                        201
                    }
                    else -> error("Unexpected method")
                }
                if (request.method == "REPORT") reports++
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                    .message("synthetic").body((when (request.method) {
                        "PROPFIND" -> collectionsXml
                        "REPORT" -> if (collision) eventsXml(payload) else emptyEventsXml
                        else -> ""
                    }).toResponseBody()).build()
            }).build()
            try {
                queue.enqueue(PendingDavMutation(id, accountId, source.id, DavOperation.CREATE,
                    null, null, payload))
                val result = DavMutationDispatcher.flush(testAccount, queue, http)
                assertEquals(1, reports)
                assertEquals(if (collision) 0 else 1, puts)
                assertEquals(if (collision) 1 else 0, result.conflicts)
                assertEquals(if (collision) 0 else 1, result.confirmed)
                if (collision) assertEquals(DavPendingState.CONFLICT,
                    queue.list().single { it.id == id }.state)
                else assertTrue(queue.list().none { it.id == id })
            } finally { queue.discardAccount(accountId) }
        }
    }

    @Test fun reviewedTimedSeriesTitleAndRemovalUseExactConditionalResource() {
        val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
        val accountId = "synthetic-${UUID.randomUUID()}"
        val testAccount = account.copy(id = accountId)
        val source = DavConnection.source(testAccount, collection)
        val eventId = UUID.randomUUID()
        val original = DavEventPayload.create(eventId, LocalDate.parse("2026-11-12"),
            "Synthetic timed series", false, "09:00", "10:00",
            now = Instant.parse("2026-10-27T12:00:00Z"), zone = java.time.ZoneOffset.UTC,
            repeat = com.tinkercobble.ham.data.RepeatSpec(
                com.tinkercobble.ham.data.RepeatFrequency.DAILY, 3))
        val chosen = com.tinkercobble.ham.data.IcsEventParser.parse(original, source,
            com.tinkercobble.ham.data.DateRange(LocalDate.parse("2026-11-12"),
                LocalDate.parse("2026-11-16"))).events.single {
                    it.date == LocalDate.parse("2026-11-13") }
        DavSeriesValidation.requireUnchanged(original, source, chosen)
        val updated = DavEventPayload.updateSeriesTitle(original, "Synthetic renamed series")
        val itemUrl = "https://dav.example.test/user/calendars/family/series.ics"
        var puts = 0
        var deletes = 0
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val code = when (request.method) {
                "PROPFIND" -> 207
                "PUT" -> {
                    puts++
                    assertEquals(itemUrl, request.url.toString())
                    assertEquals("\"v1\"", request.header("If-Match"))
                    val body = okio.Buffer()
                    request.body?.writeTo(body)
                    assertTrue(body.readByteArray().contentEquals(updated))
                    204
                }
                "DELETE" -> {
                    deletes++
                    assertEquals(itemUrl, request.url.toString())
                    assertEquals("\"v2\"", request.header("If-Match"))
                    204
                }
                else -> error("Unexpected method")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic")
                .body((if (code == 207) collectionsXml else "").toResponseBody()).build()
        }).build()
        try {
            val updateId = UUID.randomUUID()
            queue.enqueue(PendingDavMutation(updateId, accountId, source.id, DavOperation.UPDATE,
                itemUrl, "\"v1\"", updated, baseIcs = original))
            assertEquals(1, DavMutationDispatcher.flush(testAccount, queue, http).confirmed)
            val deleteId = UUID.randomUUID()
            queue.enqueue(PendingDavMutation(deleteId, accountId, source.id, DavOperation.DELETE,
                itemUrl, "\"v2\"", null, uid = chosen.uid, baseIcs = updated))
            assertEquals(1, DavMutationDispatcher.flush(testAccount, queue, http).confirmed)
            assertEquals(1, puts)
            assertEquals(1, deletes)
            assertTrue(queue.list().none { it.id == updateId || it.id == deleteId })
        } finally { queue.discardAccount(accountId) }
    }

    @Test fun timedSeriesClockUpdateChecksFreshCollectionBeforeConditionalPut() {
        for (collision in listOf(false, true)) {
            val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
            val accountId = "synthetic-${UUID.randomUUID()}"
            val testAccount = account.copy(id = accountId)
            val source = DavConnection.source(testAccount, collection)
            val eventId = UUID.randomUUID()
            val base = DavEventPayload.create(eventId, LocalDate.parse("2026-11-12"),
                "Synthetic time change", false, "09:00", "10:00",
                now = Instant.parse("2026-10-27T12:00:00Z"), zone = java.time.ZoneOffset.UTC,
                repeat = com.tinkercobble.ham.data.RepeatSpec(
                    com.tinkercobble.ham.data.RepeatFrequency.DAILY, 3))
            val changed = DavEventPayload.updateTimedSeries(base, "Synthetic time change",
                "11:00", "12:00", java.time.ZoneOffset.UTC)
            val other = DavEventPayload.create(UUID.randomUUID(), LocalDate.parse("2026-11-13"),
                "Synthetic time change", false, "11:00", "12:00",
                now = Instant.parse("2026-10-27T12:00:00Z"), zone = java.time.ZoneOffset.UTC)
            val itemUrl = "https://dav.example.test/user/calendars/family/" +
                if (collision) "series.ics" else "existing.ics"
            var reports = 0
            var puts = 0
            val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val code = when (request.method) {
                    "PROPFIND", "REPORT" -> 207
                    "PUT" -> {
                        puts++
                        assertEquals(itemUrl, request.url.toString())
                        assertEquals("\"v1\"", request.header("If-Match"))
                        val body = okio.Buffer().also { request.body!!.writeTo(it) }.readByteArray()
                        assertTrue(body.contentEquals(changed))
                        204
                    }
                    "GET" -> 404
                    else -> error("Unexpected method")
                }
                if (request.method == "REPORT") reports++
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                    .message("synthetic").body((when (request.method) {
                        "PROPFIND" -> collectionsXml
                        "REPORT" -> eventsXml(if (collision) other else base)
                        else -> ""
                    }).toResponseBody()).build()
            }).build()
            try {
                val changeId = UUID.randomUUID()
                queue.enqueue(PendingDavMutation(changeId, accountId, source.id,
                    DavOperation.UPDATE, itemUrl, "\"v1\"", changed, baseIcs = base))
                val result = DavMutationDispatcher.flush(testAccount, queue, http)
                assertEquals(1, reports)
                assertEquals(if (collision) 0 else 1, puts)
                assertEquals(if (collision) 1 else 0, result.conflicts)
                assertEquals(if (collision) 0 else 1, result.confirmed)
                if (collision) assertEquals(DavPendingState.CONFLICT,
                    queue.list().single { it.id == changeId }.state)
                else assertTrue(queue.list().none { it.id == changeId })
            } finally { queue.discardAccount(accountId) }
        }
    }

    @Test fun movedTimedEventChecksNewDateBeforeConditionalPut() {
        for (collision in listOf(false, true)) {
            val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
            val accountId = "synthetic-${UUID.randomUUID()}"
            val testAccount = account.copy(id = accountId)
            val source = DavConnection.source(testAccount, collection)
            val base = DavEventPayload.create(UUID.randomUUID(), LocalDate.parse("2026-11-12"),
                "Synthetic moved event", false, "09:00", "10:00",
                now = Instant.parse("2026-10-27T12:00:00Z"), zone = java.time.ZoneOffset.UTC)
            val moved = DavEventPayload.updateTimed(base, LocalDate.parse("2026-11-13"),
                "Synthetic moved event", "09:00", "10:00", java.time.ZoneOffset.UTC)
            val other = DavEventPayload.create(UUID.randomUUID(), LocalDate.parse("2026-11-13"),
                "Synthetic moved event", false, "09:00", "10:00",
                now = Instant.parse("2026-10-27T12:00:00Z"), zone = java.time.ZoneOffset.UTC)
            val itemUrl = "https://dav.example.test/user/calendars/family/" +
                if (collision) "moved.ics" else "existing.ics"
            var reports = 0
            var puts = 0
            val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val code = when (request.method) {
                    "PROPFIND", "REPORT" -> 207
                    "PUT" -> {
                        puts++
                        assertEquals(itemUrl, request.url.toString())
                        assertEquals("\"v1\"", request.header("If-Match"))
                        val body = okio.Buffer().also { request.body!!.writeTo(it) }.readByteArray()
                        assertTrue(body.contentEquals(moved))
                        204
                    }
                    "GET" -> 404
                    else -> error("Unexpected method")
                }
                if (request.method == "REPORT") reports++
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                    .message("synthetic").body((when (request.method) {
                        "PROPFIND" -> collectionsXml
                        "REPORT" -> eventsXml(if (collision) other else base)
                        else -> ""
                    }).toResponseBody()).build()
            }).build()
            try {
                val changeId = UUID.randomUUID()
                queue.enqueue(PendingDavMutation(changeId, accountId, source.id,
                    DavOperation.UPDATE, itemUrl, "\"v1\"", moved, baseIcs = base))
                val result = DavMutationDispatcher.flush(testAccount, queue, http)
                assertEquals(1, reports)
                assertEquals(if (collision) 0 else 1, puts)
                assertEquals(if (collision) 1 else 0, result.conflicts)
                assertEquals(if (collision) 0 else 1, result.confirmed)
                if (collision) assertEquals(DavPendingState.CONFLICT,
                    queue.list().single { it.id == changeId }.state)
                else assertTrue(queue.list().none { it.id == changeId })
            } finally { queue.discardAccount(accountId) }
        }
    }

    @Test fun movedAllDayEventChecksNewDateBeforeConditionalPut() {
        for (days in listOf(1, 3)) for (collision in listOf(false, true)) {
            val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
            val accountId = "synthetic-${UUID.randomUUID()}"
            val testAccount = account.copy(id = accountId)
            val source = DavConnection.source(testAccount, collection)
            val base = DavEventPayload.create(UUID.randomUUID(), LocalDate.parse("2026-11-12"),
                "Synthetic moved day", true, now = Instant.parse("2026-10-27T12:00:00Z"))
                .toString(Charsets.UTF_8).replace("DTEND;VALUE=DATE:20261113",
                    "DTEND;VALUE=DATE:${if (days == 1) "20261113" else "20261115"}")
                .toByteArray()
            val moved = DavEventPayload.updateAllDay(base, LocalDate.parse("2026-11-13"),
                "Synthetic moved day")
            val other = DavEventPayload.create(UUID.randomUUID(), LocalDate.parse("2026-11-13"),
                "Synthetic moved day", true, now = Instant.parse("2026-10-27T12:00:00Z"))
                .toString(Charsets.UTF_8).replace("DTEND;VALUE=DATE:20261114",
                    "DTEND;VALUE=DATE:${if (days == 1) "20261114" else "20261116"}")
                .toByteArray()
            val itemUrl = "https://dav.example.test/user/calendars/family/" +
                if (collision) "moved-day.ics" else "existing.ics"
            var reports = 0
            var puts = 0
            val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val code = when (request.method) {
                    "PROPFIND", "REPORT" -> 207
                    "PUT" -> {
                        puts++
                        assertEquals(itemUrl, request.url.toString())
                        assertEquals("\"v1\"", request.header("If-Match"))
                        val body = okio.Buffer().also { request.body!!.writeTo(it) }.readByteArray()
                        assertTrue(body.contentEquals(moved))
                        204
                    }
                    "GET" -> 404
                    else -> error("Unexpected method")
                }
                if (request.method == "REPORT") reports++
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                    .message("synthetic").body((when (request.method) {
                        "PROPFIND" -> collectionsXml
                        "REPORT" -> eventsXml(if (collision) other else base)
                        else -> ""
                    }).toResponseBody()).build()
            }).build()
            try {
                val changeId = UUID.randomUUID()
                queue.enqueue(PendingDavMutation(changeId, accountId, source.id,
                    DavOperation.UPDATE, itemUrl, "\"v1\"", moved, baseIcs = base))
                val result = DavMutationDispatcher.flush(testAccount, queue, http)
                assertEquals(1, reports)
                assertEquals(if (collision) 0 else 1, puts)
                assertEquals(if (collision) 1 else 0, result.conflicts)
                assertEquals(if (collision) 0 else 1, result.confirmed)
                if (collision) assertEquals(DavPendingState.CONFLICT,
                    queue.list().single { it.id == changeId }.state)
                else assertTrue(queue.list().none { it.id == changeId })
            } finally { queue.discardAccount(accountId) }
        }
    }

    @Test fun namedZoneClockUpdateChecksEveryOccurrenceBeforeConditionalPut() {
        for (collision in listOf(false, true)) {
            val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
            val accountId = "synthetic-${UUID.randomUUID()}"
            val testAccount = account.copy(id = accountId)
            val source = DavConnection.source(testAccount, collection)
            val zone = java.time.ZoneId.of("America/New_York")
            val stamp = Instant.parse("2026-10-27T12:00:00Z")
            val base = DavEventPayload.create(UUID.randomUUID(), LocalDate.parse("2026-10-30"),
                "Synthetic zone series", false, "09:00", "10:00", stamp, zone,
                com.tinkercobble.ham.data.RepeatSpec(
                    com.tinkercobble.ham.data.RepeatFrequency.DAILY, 5))
            val changed = DavEventPayload.updateTimedSeries(base, "Synthetic zone series",
                "11:00", "12:30", zone)
            val other = DavEventPayload.create(UUID.randomUUID(), LocalDate.parse("2026-11-02"),
                "Synthetic zone series", false, "11:00", "12:30", stamp, zone)
            val itemUrl = "https://dav.example.test/user/calendars/family/" +
                if (collision) "zone-series.ics" else "existing.ics"
            var reports = 0
            var puts = 0
            val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val code = when (request.method) {
                    "PROPFIND", "REPORT" -> 207
                    "PUT" -> {
                        puts++
                        assertEquals(itemUrl, request.url.toString())
                        assertEquals("\"v1\"", request.header("If-Match"))
                        val body = okio.Buffer().also { request.body!!.writeTo(it) }.readByteArray()
                        assertTrue(body.contentEquals(changed))
                        204
                    }
                    "GET" -> 404
                    else -> error("Unexpected method")
                }
                if (request.method == "REPORT") reports++
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                    .message("synthetic").body((when (request.method) {
                        "PROPFIND" -> collectionsXml
                        "REPORT" -> eventsXml(if (collision) other else base)
                        else -> ""
                    }).toResponseBody()).build()
            }).build()
            try {
                val changeId = UUID.randomUUID()
                queue.enqueue(PendingDavMutation(changeId, accountId, source.id,
                    DavOperation.UPDATE, itemUrl, "\"v1\"", changed, baseIcs = base))
                val result = DavMutationDispatcher.flush(testAccount, queue, http)
                assertEquals(1, reports)
                assertEquals(if (collision) 0 else 1, puts)
                assertEquals(if (collision) 1 else 0, result.conflicts)
                assertEquals(if (collision) 0 else 1, result.confirmed)
                if (collision) assertEquals(DavPendingState.CONFLICT,
                    queue.list().single { it.id == changeId }.state)
                else assertTrue(queue.list().none { it.id == changeId })
            } finally { queue.discardAccount(accountId) }
        }
    }

    @Test fun freshServerDuplicateStopsQueuedCreateWithoutPut() {
        val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
        val accountId = "synthetic-${UUID.randomUUID()}"
        val testAccount = account.copy(id = accountId)
        val source = DavConnection.source(testAccount, collection)
        val id = UUID.randomUUID()
        val pending = createPayload(id, "Synthetic duplicate")
        val existing = createPayload(UUID.randomUUID(), "Synthetic duplicate")
        var puts = 0
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val code = when (request.method) {
                "PROPFIND", "REPORT" -> 207
                "GET" -> 404
                "PUT" -> { puts++; 201 }
                else -> error("Unexpected method")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic").body((when (request.method) {
                    "PROPFIND" -> collectionsXml
                    "REPORT" -> eventsXml(existing)
                    else -> ""
                }).toResponseBody()).build()
        }).build()
        try {
            queue.enqueue(PendingDavMutation(id, accountId, source.id, DavOperation.CREATE,
                null, null, pending))
            val result = DavMutationDispatcher.flush(testAccount, queue, http)
            assertEquals(0, puts)
            assertEquals(0, result.confirmed)
            assertEquals(1, result.conflicts)
            assertEquals(DavPendingState.CONFLICT, queue.list().single { it.id == id }.state)
        } finally { queue.discardAccount(accountId) }
    }

    @Test fun laterTimedSeriesOccurrenceDuplicateStopsQueuedCreate() {
        val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
        val accountId = "synthetic-${UUID.randomUUID()}"
        val testAccount = account.copy(id = accountId)
        val source = DavConnection.source(testAccount, collection)
        val id = UUID.randomUUID()
        val pending = DavEventPayload.create(id, LocalDate.parse("2026-11-12"),
            "Synthetic series overlap", false, "09:00", "10:00",
            now = Instant.parse("2026-10-27T12:00:00Z"), zone = java.time.ZoneOffset.UTC,
            repeat = com.tinkercobble.ham.data.RepeatSpec(
                com.tinkercobble.ham.data.RepeatFrequency.DAILY, 3))
        val existing = DavEventPayload.create(UUID.randomUUID(), LocalDate.parse("2026-11-13"),
            "Synthetic series overlap", false, "09:00", "10:00",
            now = Instant.parse("2026-10-27T12:00:00Z"), zone = java.time.ZoneOffset.UTC)
        var puts = 0
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            if (request.method == "REPORT") {
                val body = okio.Buffer()
                request.body?.writeTo(body)
                assertTrue(body.readUtf8().contains("end=\"20261121T000000Z\""))
            }
            val code = when (request.method) {
                "PROPFIND", "REPORT" -> 207
                "GET" -> 404
                "PUT" -> { puts++; 201 }
                else -> error("Unexpected method")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic").body((when (request.method) {
                    "PROPFIND" -> collectionsXml
                    "REPORT" -> eventsXml(existing)
                    else -> ""
                }).toResponseBody()).build()
        }).build()
        try {
            queue.enqueue(PendingDavMutation(id, accountId, source.id, DavOperation.CREATE,
                null, null, pending))
            val result = DavMutationDispatcher.flush(testAccount, queue, http)
            assertEquals(0, puts)
            assertEquals(0, result.confirmed)
            assertEquals(1, result.conflicts)
            assertEquals(DavPendingState.CONFLICT, queue.list().single { it.id == id }.state)
        } finally { queue.discardAccount(accountId) }
    }

    @Test fun namedZoneTimedSeriesCrossingDstUsesConditionalCreateEditAndDelete() {
        val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
        val accountId = "synthetic-${UUID.randomUUID()}"
        val testAccount = account.copy(id = accountId)
        val source = DavConnection.source(testAccount, collection)
        val id = UUID.randomUUID()
        val payload = DavEventPayload.create(id, LocalDate.parse("2026-10-30"),
            "Synthetic zone series", false, "09:00", "10:00",
            now = Instant.parse("2026-10-27T12:00:00Z"),
            zone = java.time.ZoneId.of("America/New_York"),
            repeat = com.tinkercobble.ham.data.RepeatSpec(
                com.tinkercobble.ham.data.RepeatFrequency.DAILY, 5))
        assertTrue(payload.toString(Charsets.UTF_8).contains("BEGIN:VTIMEZONE\r\n"))
        assertEquals(5, com.tinkercobble.ham.data.IcsEventParser.parse(payload, source,
            DavEventPayload.createSearchRange(payload)).events.size)
        val updated = DavEventPayload.updateSeriesTitle(payload, "Synthetic renamed zone series")
        val itemUrl = "https://dav.example.test/user/calendars/family/$id.ics"
        var reports = 0
        var creates = 0
        var updates = 0
        var deletes = 0
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val code = when (request.method) {
                "PROPFIND", "REPORT" -> 207
                "PUT" -> {
                    assertEquals(itemUrl, request.url.toString())
                    val sent = okio.Buffer().also { request.body!!.writeTo(it) }.readByteArray()
                    if (request.header("If-None-Match") == "*") {
                        creates++
                        assertTrue(sent.contentEquals(payload))
                        201
                    } else {
                        updates++
                        assertEquals("\"v1\"", request.header("If-Match"))
                        assertTrue(sent.contentEquals(updated))
                        204
                    }
                }
                "DELETE" -> {
                    deletes++
                    assertEquals(itemUrl, request.url.toString())
                    assertEquals("\"v2\"", request.header("If-Match"))
                    204
                }
                else -> error("Unexpected method")
            }
            if (request.method == "REPORT") reports++
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic").body((when (request.method) {
                    "PROPFIND" -> collectionsXml
                    "REPORT" -> emptyEventsXml
                    else -> ""
                }).toResponseBody()).build()
        }).build()
        try {
            queue.enqueue(PendingDavMutation(id, accountId, source.id, DavOperation.CREATE,
                null, null, payload))
            val result = DavMutationDispatcher.flush(testAccount, queue, http)
            assertEquals(1, reports)
            assertEquals(1, creates)
            assertEquals(1, result.confirmed)
            assertTrue(queue.list().none { it.id == id })
            val editId = UUID.randomUUID()
            queue.enqueue(PendingDavMutation(editId, accountId, source.id, DavOperation.UPDATE,
                itemUrl, "\"v1\"", updated, baseIcs = payload))
            assertEquals(1, DavMutationDispatcher.flush(testAccount, queue, http).confirmed)
            val deleteId = UUID.randomUUID()
            queue.enqueue(PendingDavMutation(deleteId, accountId, source.id, DavOperation.DELETE,
                itemUrl, "\"v2\"", null, baseIcs = updated))
            assertEquals(1, DavMutationDispatcher.flush(testAccount, queue, http).confirmed)
            assertEquals(1, updates)
            assertEquals(1, deletes)
            assertTrue(queue.list().isEmpty())
        } finally { queue.discardAccount(accountId) }
    }

    @Test fun freshOwnResourceConfirmsLostCreateWithoutAnotherPut() {
        val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
        val accountId = "synthetic-${UUID.randomUUID()}"
        val testAccount = account.copy(id = accountId)
        val source = DavConnection.source(testAccount, collection)
        val id = UUID.randomUUID()
        val payload = createPayload(id, "Synthetic accepted")
        var puts = 0
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val code = when (request.method) {
                "PROPFIND", "REPORT" -> 207
                "GET" -> { assertTrue(request.url.toString().endsWith("/$id.ics")); 200 }
                "PUT" -> { puts++; 201 }
                else -> error("Unexpected method")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic").body((when (request.method) {
                    "PROPFIND" -> collectionsXml.toByteArray()
                    "REPORT" -> eventsXml(payload).toByteArray()
                    "GET" -> payload
                    else -> byteArrayOf()
                }).toResponseBody()).build()
        }).build()
        try {
            queue.enqueue(PendingDavMutation(id, accountId, source.id, DavOperation.CREATE,
                null, null, payload))
            val result = DavMutationDispatcher.flush(testAccount, queue, http)
            assertEquals(0, puts)
            assertEquals(1, result.confirmed)
            assertEquals(0, result.conflicts)
            assertTrue(queue.list().none { it.id == id })
        } finally { queue.discardAccount(accountId) }
    }

    @Test fun unreadableServerQueryWithholdsQueuedCreate() {
        val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
        val accountId = "synthetic-${UUID.randomUUID()}"
        val testAccount = account.copy(id = accountId)
        val source = DavConnection.source(testAccount, collection)
        val id = UUID.randomUUID()
        var puts = 0
        val unreadable = eventsXml("not an iCalendar item".toByteArray())
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val code = when (request.method) {
                "PROPFIND", "REPORT" -> 207
                "GET" -> 404
                "PUT" -> { puts++; 201 }
                else -> error("Unexpected method")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic").body((when (request.method) {
                    "PROPFIND" -> collectionsXml
                    "REPORT" -> unreadable
                    else -> ""
                }).toResponseBody()).build()
        }).build()
        try {
            queue.enqueue(PendingDavMutation(id, accountId, source.id, DavOperation.CREATE,
                null, null, createPayload(id)))
            val result = DavMutationDispatcher.flush(testAccount, queue, http)
            assertEquals(0, puts)
            assertEquals(1, result.conflicts)
            assertEquals(DavPendingState.CONFLICT, queue.list().single { it.id == id }.state)
        } finally { queue.discardAccount(accountId) }
    }

    @Test fun reviewedConflictPreservesServerDetailsAndUsesCurrentEtag() {
        val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
        val accountId = "synthetic-${UUID.randomUUID()}"
        val testAccount = account.copy(id = accountId)
        val source = DavConnection.source(testAccount, collection)
        val eventId = UUID.randomUUID()
        val changeId = UUID.randomUUID()
        val itemUrl = "https://dav.example.test/user/calendars/family/item.ics"
        val base = DavEventPayload.create(eventId, LocalDate.parse("2026-11-12"),
            "Original", true, now = Instant.parse("2026-10-27T12:00:00Z"))
        val pending = DavEventPayload.updateTitle(base, "Mine")
        val server = DavEventPayload.updateTitle(base, "Theirs")
            .toString(Charsets.UTF_8).replace("END:VEVENT", "DESCRIPTION:Server detail\r\nEND:VEVENT")
            .toByteArray(Charsets.UTF_8)
        var puts = 0
        val http = OkHttpClient.Builder().followRedirects(false).addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val code = when (request.method) {
                "PROPFIND" -> 207
                "GET" -> { assertEquals(itemUrl, request.url.toString()); 200 }
                "PUT" -> {
                    puts++
                    assertEquals("\"v2\"", request.header("If-Match"))
                    assertEquals(itemUrl, request.url.toString())
                    204
                }
                else -> error("Unexpected method")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic")
                .apply { if (code == 200) header("ETag", "\"v2\"") }
                .body((when (code) { 207 -> collectionsXml.toByteArray()
                    200 -> server; else -> byteArrayOf() }).toResponseBody()).build()
        }).build()
        try {
            queue.enqueue(PendingDavMutation(changeId, accountId, source.id, DavOperation.UPDATE,
                itemUrl, "\"v1\"", pending, baseIcs = base))
            queue.markConflict(changeId)
            val current = DavMutations.readCurrent(testAccount, collection, itemUrl, http)
            val review = DavTitleConflict.review(base, pending, current.ics, source)
            assertTrue(review.mergedIcs.toString(Charsets.UTF_8).contains("DESCRIPTION:Server detail"))
            queue.requeueReviewedTitle(changeId, "\"v1\"", current.etag,
                current.ics, review.mergedIcs)
            val result = DavMutationDispatcher.flush(testAccount, queue, http)
            assertEquals(1, result.confirmed)
            assertEquals(1, puts)
            assertTrue(queue.list().none { it.id == changeId })
        } finally { queue.discardAccount(accountId) }
    }

    @Test fun lostCreateResponseReconcilesOnlyMatchingServerResource() {
        val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
        val accountId = "synthetic-${UUID.randomUUID()}"
        val testAccount = account.copy(id = accountId)
        val id = UUID.randomUUID()
        val payload = DavEventPayload.create(id, LocalDate.parse("2026-11-12"),
            "Synthetic retry", true, now = Instant.parse("2026-10-27T12:00:00Z"))
        var gets = 0
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val code = when (request.method) {
                "PROPFIND" -> 207
                "REPORT" -> 207
                "PUT" -> 412
                "GET" -> { gets++; assertTrue(request.url.toString().endsWith("/$id.ics")); 200 }
                else -> error("Unexpected method")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic").body((when (code) {
                    207 -> (if (request.method == "REPORT") emptyEventsXml else collectionsXml).toByteArray()
                    200 -> payload
                    else -> byteArrayOf()
                }).toResponseBody()).build()
        }).build()
        try {
            queue.enqueue(PendingDavMutation(id, accountId, DavConnection.source(testAccount, collection).id,
                DavOperation.CREATE, null, null, payload))
            val result = DavMutationDispatcher.flush(testAccount, queue, http)
            assertEquals(1, result.confirmed)
            assertEquals(0, result.conflicts)
            assertEquals(1, gets)
            assertTrue(queue.list().none { it.id == id })
        } finally { queue.discardAccount(accountId) }
    }

    @Test fun differentServerResourceStaysConflict() {
        val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
        val accountId = "synthetic-${UUID.randomUUID()}"
        val testAccount = account.copy(id = accountId)
        val id = UUID.randomUUID()
        val payload = DavEventPayload.create(id, LocalDate.parse("2026-11-12"),
            "Original synthetic", true, now = Instant.parse("2026-10-27T12:00:00Z"))
        val different = DavEventPayload.create(id, LocalDate.parse("2026-11-12"),
            "Different synthetic", true, now = Instant.parse("2026-10-27T12:00:00Z"))
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val code = when (request.method) { "PROPFIND", "REPORT" -> 207; "PUT" -> 412; "GET" -> 200
                else -> error("Unexpected method") }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic").body((when (code) {
                    207 -> (if (request.method == "REPORT") emptyEventsXml else collectionsXml).toByteArray()
                    200 -> different
                    else -> byteArrayOf()
                }).toResponseBody()).build()
        }).build()
        try {
            queue.enqueue(PendingDavMutation(id, accountId, DavConnection.source(testAccount, collection).id,
                DavOperation.CREATE, null, null, payload))
            val result = DavMutationDispatcher.flush(testAccount, queue, http)
            assertEquals(0, result.confirmed)
            assertEquals(1, result.conflicts)
            assertEquals(DavPendingState.CONFLICT, queue.list().single { it.id == id }.state)
        } finally { queue.discardAccount(accountId) }
    }

    @Test fun failedVerificationAfterCreatePreconditionRetainsQueueForRetry() {
        val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
        val accountId = "synthetic-${UUID.randomUUID()}"
        val testAccount = account.copy(id = accountId)
        val id = UUID.randomUUID()
        val payload = DavEventPayload.create(id, LocalDate.parse("2026-11-12"),
            "Synthetic uncertain create", true, now = Instant.parse("2026-10-27T12:00:00Z"))
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") throw IOException("synthetic verification outage")
            val code = if (request.method == "PROPFIND" || request.method == "REPORT") 207 else 412
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic").body((if (request.method == "REPORT") emptyEventsXml else
                    if (code == 207) collectionsXml else "").toResponseBody()).build()
        }).build()
        try {
            queue.enqueue(PendingDavMutation(id, accountId, DavConnection.source(testAccount, collection).id,
                DavOperation.CREATE, null, null, payload))
            val result = DavMutationDispatcher.flush(testAccount, queue, http)
            assertEquals(0, result.conflicts)
            assertTrue(result.networkFailure)
            assertTrue(result.stoppedForRetry)
            assertEquals(DavPendingState.QUEUED, queue.list().single { it.id == id }.state)
        } finally { queue.discardAccount(accountId) }
    }

    @Test fun confirmedMutationLeavesQueueAndRechecksPrivileges() {
        val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
        val accountId = "synthetic-${UUID.randomUUID()}"
        val testAccount = account.copy(id = accountId)
        val source = DavConnection.source(testAccount, collection)
        val id = UUID.randomUUID()
        val item = PendingDavMutation(id, accountId, source.id, DavOperation.CREATE,
            null, null, createPayload(id))
        var discovery = 0
        var put = 0
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val code = when (request.method) {
                "PROPFIND" -> { discovery++; 207 }
                "REPORT" -> 207
                "PUT" -> {
                    put++
                    assertEquals("*", request.header("If-None-Match"))
                    assertTrue(request.url.toString().endsWith("/$id.ics"))
                    201
                }
                else -> error("Unexpected method")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic").body((if (request.method == "REPORT") emptyEventsXml else
                    if (code == 207) collectionsXml else "").toResponseBody()).build()
        }).build()
        try {
            queue.enqueue(item)
            assertEquals(1, DavConnection.listAccount(testAccount, http).size)
            val result = DavMutationDispatcher.flush(testAccount, queue, http)
            assertEquals("$result discovery=$discovery put=$put", 1, result.confirmed)
            assertFalse(result.stoppedForRetry)
            assertEquals(2, discovery)
            assertEquals(1, put)
            assertTrue(queue.list().none { it.id == id })
        } finally { queue.discardAccount(accountId) }
    }

    @Test fun unknownNetworkOutcomeRetainsPendingMutation() {
        val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
        val accountId = "synthetic-${UUID.randomUUID()}"
        val testAccount = account.copy(id = accountId)
        val id = UUID.randomUUID()
        val item = PendingDavMutation(id, accountId, DavConnection.source(testAccount, collection).id,
            DavOperation.CREATE, null, null, createPayload(id))
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { throw IOException("synthetic outage") }).build()
        try {
            queue.enqueue(item)
            val result = DavMutationDispatcher.flush(testAccount, queue, http)
            assertTrue(result.stoppedForRetry)
            assertTrue(result.networkFailure)
            assertEquals(0, result.confirmed)
            assertEquals(DavPendingState.QUEUED, queue.list().single { it.id == id }.state)
        } finally { queue.discardAccount(accountId) }
    }

    @Test fun preconditionFailureKeepsChangeForReview() {
        val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
        val accountId = "synthetic-${UUID.randomUUID()}"
        val testAccount = account.copy(id = accountId)
        val id = UUID.randomUUID()
        val item = PendingDavMutation(id, accountId, DavConnection.source(testAccount, collection).id,
            DavOperation.CREATE, null, null, createPayload(id))
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val code = if (request.method == "PROPFIND" || request.method == "REPORT") 207 else 412
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic").body((if (request.method == "REPORT") emptyEventsXml else
                    if (code == 207) collectionsXml else "").toResponseBody()).build()
        }).build()
        try {
            queue.enqueue(item)
            val result = DavMutationDispatcher.flush(testAccount, queue, http)
            assertEquals(0, result.confirmed)
            assertEquals(1, result.conflicts)
            assertEquals(DavPendingState.CONFLICT, queue.list().single { it.id == id }.state)
        } finally { queue.discardAccount(accountId) }
    }

    @Test fun boundedDispatchLeavesRemainingWorkForRetryAndHonorsAccountRemoval() {
        val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
        val accountId = "synthetic-${UUID.randomUUID()}"
        val testAccount = account.copy(id = accountId)
        val source = DavConnection.source(testAccount, collection)
        val ids = List(3) { UUID.randomUUID() }
        var puts = 0
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val code = when (request.method) {
                "PROPFIND", "REPORT" -> 207
                "PUT" -> { puts++; 201 }
                else -> error("Unexpected method")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic").body((if (request.method == "REPORT") emptyEventsXml else
                    if (code == 207) collectionsXml else "").toResponseBody()).build()
        }).build()
        try {
            ids.forEach { id -> queue.enqueue(PendingDavMutation(id, accountId, source.id,
                DavOperation.CREATE, null, null, createPayload(id, "Synthetic $id"))) }
            val first = DavMutationDispatcher.flush(testAccount, queue, http, maxItems = 2)
            assertEquals(2, first.confirmed)
            assertTrue(first.stoppedForRetry)
            assertFalse(first.networkFailure)
            assertEquals(1, queue.list().count { it.accountId == accountId })
            val stopped = DavMutationDispatcher.flush(testAccount, queue, http, maxItems = 2,
                accountStillCurrent = { false })
            assertEquals(0, stopped.confirmed)
            assertEquals(2, puts)
            val last = DavMutationDispatcher.flush(testAccount, queue, http, maxItems = 2)
            assertEquals(1, last.confirmed)
            assertFalse(last.stoppedForRetry)
            assertTrue(queue.list().none { it.accountId == accountId })
        } finally { queue.discardAccount(accountId) }
    }

    @Test fun queuedTimedUpdateUsesConditionalWriteAndClearsOnConfirmation() {
        val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
        val accountId = "synthetic-${UUID.randomUUID()}"
        val testAccount = account.copy(id = accountId)
        val id = UUID.randomUUID()
        val source = DavConnection.source(testAccount, collection)
        val original = DavEventPayload.create(id, LocalDate.parse("2026-10-27"),
            "Original", false, "09:00", "10:00", now = Instant.parse("2026-10-27T12:00:00Z"),
            zone = java.time.ZoneId.of("America/New_York"))
        val updated = DavEventPayload.updateTimed(original, LocalDate.parse("2026-10-27"),
            "Updated", "11:00", "12:00", java.time.ZoneId.of("America/New_York"))
        val resource = "https://dav.example.test/user/calendars/family/$id.ics"
        var puts = 0
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val code = if (request.method == "PROPFIND") 207 else {
                assertEquals("PUT", request.method)
                assertEquals("\"v1\"", request.header("If-Match"))
                assertEquals(resource, request.url.toString())
                val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                assertTrue(body.contains("DTSTART:20261027T150000Z"))
                assertTrue(body.contains("SUMMARY:Updated"))
                puts++
                204
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic").body((if (code == 207) collectionsXml else "").toResponseBody()).build()
        }).build()
        try {
            queue.enqueue(PendingDavMutation(UUID.randomUUID(), accountId, source.id,
                DavOperation.UPDATE, resource, "\"v1\"", updated))
            val result = DavMutationDispatcher.flush(testAccount, queue, http)
            assertEquals(1, result.confirmed)
            assertEquals(1, puts)
            assertTrue(queue.list().none { it.accountId == accountId })
        } finally { queue.discardAccount(accountId) }
    }

    @Test fun lostUpdateResponseConfirmsOnlyExactServerBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (matching in listOf(true, false)) {
            val queue = PendingDavMutations(context)
            val accountId = "synthetic-${UUID.randomUUID()}"
            val testAccount = account.copy(id = accountId)
            val id = UUID.randomUUID()
            val resource = "https://dav.example.test/user/calendars/family/$id.ics"
            val payload = DavEventPayload.create(id, LocalDate.parse("2026-11-12"),
                "Synthetic update", true, now = Instant.parse("2026-10-27T12:00:00Z"))
            var gets = 0
            val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val code = when (request.method) {
                    "PROPFIND" -> 207
                    "PUT" -> { assertEquals("\"v1\"", request.header("If-Match")); 412 }
                    "GET" -> { gets++; assertEquals(resource, request.url.toString()); 200 }
                    else -> error("Unexpected method")
                }
                val body = when (request.method) {
                    "PROPFIND" -> collectionsXml.toByteArray()
                    "GET" -> if (matching) payload else payload + byteArrayOf(10)
                    else -> byteArrayOf()
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                    .message("synthetic").body(body.toResponseBody()).build()
            }).build()
            try {
                queue.enqueue(PendingDavMutation(id, accountId,
                    DavConnection.source(testAccount, collection).id,
                    DavOperation.UPDATE, resource, "\"v1\"", payload))
                val result = DavMutationDispatcher.flush(testAccount, queue, http)
                assertEquals(1, gets)
                assertEquals(if (matching) 1 else 0, result.confirmed)
                assertEquals(if (matching) 0 else 1, result.conflicts)
                if (matching) assertTrue(queue.list().none { it.id == id })
                else assertEquals(DavPendingState.CONFLICT, queue.list().single { it.id == id }.state)
            } finally { queue.discardAccount(accountId) }
        }
    }

    @Test fun lostUpdateVerificationNetworkFailureKeepsQueue() {
        val queue = PendingDavMutations(InstrumentationRegistry.getInstrumentation().targetContext)
        val accountId = "synthetic-${UUID.randomUUID()}"
        val testAccount = account.copy(id = accountId)
        val id = UUID.randomUUID()
        val resource = "https://dav.example.test/user/calendars/family/$id.ics"
        val payload = DavEventPayload.create(id, LocalDate.parse("2026-11-12"),
            "Synthetic update", true, now = Instant.parse("2026-10-27T12:00:00Z"))
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") throw IOException("synthetic verification outage")
            val code = if (request.method == "PROPFIND") 207 else 412
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic").body((if (code == 207) collectionsXml else "").toResponseBody()).build()
        }).build()
        try {
            queue.enqueue(PendingDavMutation(id, accountId,
                DavConnection.source(testAccount, collection).id,
                DavOperation.UPDATE, resource, "\"v1\"", payload))
            val result = DavMutationDispatcher.flush(testAccount, queue, http)
            assertTrue(result.networkFailure)
            assertTrue(result.stoppedForRetry)
            assertEquals(0, result.conflicts)
            assertEquals(DavPendingState.QUEUED, queue.list().single { it.id == id }.state)
        } finally { queue.discardAccount(accountId) }
    }
}
