package com.tinkercobble.ham

import com.tinkercobble.ham.data.DavConnection
import com.tinkercobble.ham.data.DavKind
import com.tinkercobble.ham.data.DavAccount
import com.tinkercobble.ham.data.DavEventPayload
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.DateRange
import java.time.LocalDate
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DavConnectionTest {
    private val homeXml = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
        <d:response><d:propstat><d:prop><c:calendar-home-set><d:href>/user/calendars/</d:href>
        </c:calendar-home-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        </d:multistatus>""".trimIndent()
    private val collectionsXml = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
        <d:response><d:href>/user/calendars/</d:href><d:propstat><d:prop><d:resourcetype>
        <d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        <d:response><d:href>/user/calendars/family/</d:href><d:propstat><d:prop>
        <d:displayname>Family</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
        <d:current-user-privilege-set><d:privilege><d:bind/></d:privilege>
        <d:privilege><d:write-content/></d:privilege></d:current-user-privilege-set>
        </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        </d:multistatus>""".trimIndent()
    @Test fun acceptsOnlyHttpsAddressesWithoutEmbeddedSecrets() {
        assertEquals("https://dav.example.test/calendars/",
            DavConnection.normalize(DavKind.CALDAV, "https://dav.example.test/calendars/").toString())
        assertTrue(runCatching { DavConnection.normalize(DavKind.CALDAV, "http://dav.example.test/") }.isFailure)
        assertTrue(runCatching { DavConnection.normalize(DavKind.CALDAV, "https://user:pass@dav.example.test/") }.isFailure)
        assertTrue(runCatching { DavConnection.normalize(DavKind.CALDAV, "https://dav.example.test/?token=abc") }.isFailure)
    }

    @Test fun redirectsNeverSendCredentialsToAnUnapprovedHost() {
        val original = DavConnection.normalize(DavKind.CALDAV, "https://dav.example.test/")
        val same = DavConnection.normalize(DavKind.CALDAV, "https://dav.example.test/user/")
        val other = DavConnection.normalize(DavKind.CALDAV, "https://other.example.test/")
        assertTrue(DavConnection.permittedRedirect(DavKind.CALDAV, original, same))
        assertTrue(!DavConnection.permittedRedirect(DavKind.CALDAV, original, other))
        assertTrue(!DavConnection.permittedRedirect(DavKind.ICLOUD,
            DavConnection.normalize(DavKind.ICLOUD, ""), other))
    }

    @Test fun verifiesDavStatusWithoutFollowingAnExternalRedirect() {
        var requests = 0
        val accepted = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            requests++
            assertEquals("PROPFIND", chain.request().method)
            val depth = chain.request().header("Depth")
            assertTrue(depth == "0" || depth == "1")
            assertTrue(chain.request().header("Authorization")?.startsWith("Basic ") == true)
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(207).message("Multi-Status")
                .body((if (depth == "0") homeXml else collectionsXml).toResponseBody()).build()
        }).build()
        val discovery = DavConnection.verify(DavKind.CALDAV,
            "https://dav.example.test/", "sample@example.test", "synthetic-password", accepted)
        assertEquals("https://dav.example.test/user/calendars/", discovery.home)
        assertEquals(listOf("Family"), discovery.calendars.map { it.name })
        assertTrue(discovery.calendars.single().canCreate)
        assertTrue(discovery.calendars.single().canUpdate)
        assertTrue(!discovery.calendars.single().canDelete)
        assertEquals(2, requests)
        val redirected = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(302).message("Found").header("Location", "https://other.example.test/")
                .body("".toResponseBody()).build()
        }).build()
        assertTrue(runCatching { DavConnection.verify(DavKind.CALDAV,
            "https://dav.example.test/", "sample@example.test", "synthetic-password", redirected) }.isFailure)
        assertEquals(3, requests)
    }

    @Test fun refusesFailedPropertiesAndForeignCalendarCollections() {
        val deniedHome = homeXml.replace("200 OK", "404 Not Found")
        val foreignCollection = collectionsXml.replace("/user/calendars/family/",
            "https://other.example.test/private/")
        fun fake(home: String, collections: String) = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val document = if (chain.request().header("Depth") == "0") home else collections
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(207).message("Multi-Status").body(document.toResponseBody()).build()
        }).build()
        assertTrue(runCatching { DavConnection.verify(DavKind.CALDAV,
            "https://dav.example.test/", "sample@example.test", "synthetic-password",
            fake(deniedHome, collectionsXml)) }.isFailure)
        assertTrue(runCatching { DavConnection.verify(DavKind.CALDAV,
            "https://dav.example.test/", "sample@example.test", "synthetic-password",
            fake(homeXml, foreignCollection)) }.isFailure)
        assertTrue(runCatching { DavConnection.verify(DavKind.CALDAV,
            "https://dav.example.test/", "sample@example.test", "synthetic-password",
            fake(homeXml, "<!DOCTYPE foo [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>" + collectionsXml))
        }.isFailure)
        val emptyCollections = collectionsXml.substringBefore("<d:response><d:href>/user/calendars/family/") +
            "</d:multistatus>"
        assertTrue(runCatching { DavConnection.verify(DavKind.CALDAV,
            "https://dav.example.test/", "sample@example.test", "synthetic-password",
            fake(homeXml, emptyCollections)) }.isFailure)
    }

    @Test fun readsBoundedCalendarQueryWithoutCrossHostCredentials() {
        val ics = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//HAM Test//EN
BEGIN:VEVENT
UID:example@example.test
DTSTAMP:20261027T120000Z
DTSTART;VALUE=DATE:20261101
DTEND;VALUE=DATE:20261102
SUMMARY:Sample day
END:VEVENT
END:VCALENDAR""".trimIndent().replace("\n", "\r\n")
        val xml = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
            <d:response><d:href>/user/calendars/family/item.ics</d:href><d:propstat><d:prop>
            <d:getetag>&quot;v1&quot;</d:getetag>
            <c:calendar-data><![CDATA[$ics]]></c:calendar-data></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""".trimIndent()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            assertEquals("REPORT", chain.request().method)
            assertEquals("1", chain.request().header("Depth"))
            assertTrue(chain.request().body.toString().isNotBlank())
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(207).message("Multi-Status").body(xml.toResponseBody()).build()
        }).build()
        val account = DavAccount("id", DavKind.CALDAV, "https://dav.example.test/user/calendars/",
            "sample@example.test", "synthetic-password")
        val collection = DavConnection.DiscoveredCalendar("https://dav.example.test/user/calendars/family/", "Family")
        val source = DavConnection.source(account, collection)
        assertTrue(source.id.startsWith("dav:id:"))
        assertEquals(31, source.id.length)
        assertTrue(!source.id.contains("dav.example.test"))
        val range = DateRange(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02"))
        val read = DavConnection.readEvents(account, collection, source, range, http)
        assertEquals(1, read.events.size)
        assertEquals(0, read.unsupportedRecurring)
        assertEquals("https://dav.example.test/user/calendars/family/item.ics", read.resources.single().url)
        assertEquals("\"v1\"", read.resources.single().etag)
        assertTrue(runCatching { DavConnection.readEvents(account,
            collection.copy(url = "https://other.example.test/"), CalendarSource("synthetic", "Family", 0),
            range, http) }.isFailure)
    }

    @Test fun unreadableResourceDoesNotHideValidResource() {
        val valid = DavEventPayload.create(java.util.UUID.fromString(
            "5b88b039-53d1-4f59-9855-1425271c8a19"), LocalDate.parse("2026-11-01"),
            "Valid synthetic event", true, now = java.time.Instant.parse("2026-10-27T12:00:00Z"))
            .toString(Charsets.UTF_8)
        val xml = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
            <d:response><d:href>/user/calendars/family/good.ics</d:href><d:propstat><d:prop>
            <d:getetag>&quot;good&quot;</d:getetag><c:calendar-data><![CDATA[$valid]]></c:calendar-data>
            </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            <d:response><d:href>/user/calendars/family/bad.ics</d:href><d:propstat><d:prop>
            <d:getetag>&quot;bad&quot;</d:getetag><c:calendar-data>not an iCalendar item</c:calendar-data>
            </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            </d:multistatus>""".trimIndent()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(207).message("Multi-Status").body(xml.toResponseBody()).build()
        }).build()
        val account = DavAccount("id", DavKind.CALDAV, "https://dav.example.test/user/calendars/",
            "sample@example.test", "synthetic-password")
        val collection = DavConnection.DiscoveredCalendar("https://dav.example.test/user/calendars/family/", "Family")
        val source = DavConnection.source(account, collection)
        val read = DavConnection.readEvents(account, collection, source,
            DateRange(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02")), http)
        assertEquals(1, read.events.size)
        assertEquals("Valid synthetic event", read.events.single().summary)
        assertEquals(1, read.resources.size)
        assertEquals(1, read.unreadableItems)
        assertTrue(read.partial)
    }

    @Test fun followsPrincipalDiscoveryWithoutChangingCredentialDestination() {
        val principalXml = """<d:multistatus xmlns:d="DAV:"><d:response><d:propstat><d:prop>
            <d:current-user-principal><d:href>/principals/synthetic/</d:href></d:current-user-principal>
            </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""".trimIndent()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val document = when (chain.request().url.encodedPath) {
                "/" -> principalXml
                "/principals/synthetic/" -> homeXml
                "/user/calendars/" -> collectionsXml
                else -> error("Unexpected synthetic path")
            }
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(207).message("Multi-Status").body(document.toResponseBody()).build()
        }).build()
        val discovery = DavConnection.verify(DavKind.CALDAV, "https://dav.example.test/",
            "sample@example.test", "synthetic-password", http)
        assertEquals("https://dav.example.test/user/calendars/", discovery.home)
        assertEquals(1, discovery.calendars.size)
    }

    @Test fun icloudAllowsOnlyItsHttpsShardRedirects() {
        val http = OkHttpClient.Builder().followRedirects(false).addInterceptor(Interceptor { chain ->
            val url = chain.request().url
            assertTrue(url.host.endsWith(".icloud.com"))
            if (url.host == "caldav.icloud.com") Response.Builder().request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", "https://p01-caldav.icloud.com/")
                .body("".toResponseBody()).build()
            else Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(207).message("Multi-Status")
                .body((if (chain.request().header("Depth") == "1") collectionsXml else homeXml).toResponseBody())
                .build()
        }).build()
        val discovery = DavConnection.verify(DavKind.ICLOUD, "",
            "sample@example.test", "synthetic-password", http)
        assertTrue(discovery.home.startsWith("https://p01-caldav.icloud.com/"))
        assertEquals(1, discovery.calendars.size)
        val original = DavConnection.normalize(DavKind.ICLOUD, "")
        assertTrue(!DavConnection.permittedRedirect(DavKind.ICLOUD, original,
            DavConnection.normalize(DavKind.CALDAV, "https://icloud.com.example.test/")))
    }
}
