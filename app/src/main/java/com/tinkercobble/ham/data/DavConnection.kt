package com.tinkercobble.ham.data

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

object DavConnection {
    const val ICLOUD_URL = "https://caldav.icloud.com/"
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    data class DiscoveredCalendar(val url: String, val name: String,
        val canCreate: Boolean = false, val canUpdate: Boolean = false, val canDelete: Boolean = false)
    data class Discovery(val home: String, val calendars: List<DiscoveredCalendar>)
    data class Resource(val url: String, val etag: String, val ics: ByteArray, val uid: String?)
    data class EventRead(val events: List<AgendaEvent>, val unsupportedRecurring: Int,
        val resources: List<Resource>, val unreadableItems: Int = 0) {
        val partial get() = unsupportedRecurring > 0 || unreadableItems > 0
    }
    fun source(account: DavAccount, collection: DiscoveredCalendar): CalendarSource {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(collection.url.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return CalendarSource("dav:${account.id}:$digest", collection.name,
            (if (collection.canCreate) 1 else 0) or (if (collection.canUpdate) 4 else 0) or
                (if (collection.canDelete) 2 else 0),
            if (account.kind == DavKind.ICLOUD) "iCloud" else "CalDAV",
            provider = CalendarProviderKind.CALDAV)
    }
    private val propfind = """<?xml version="1.0" encoding="utf-8"?>
        <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
        <d:prop><d:current-user-principal/><c:calendar-home-set/></d:prop>
        </d:propfind>""".trimIndent()
    private val listCalendars = """<?xml version="1.0" encoding="utf-8"?>
        <d:propfind xmlns:d="DAV:">
        <d:prop><d:displayname/><d:resourcetype/><d:current-user-privilege-set/></d:prop>
        </d:propfind>""".trimIndent()
    private fun calendarQuery(range: DateRange): String {
        val format = DateTimeFormatter.ofPattern("yyyyMMdd")
        // Expand the UTC query around local dates; the final result is filtered by occursOn.
        val start = range.start.minusDays(2).format(format) + "T000000Z"
        val end = range.endExclusive.plusDays(2).format(format) + "T000000Z"
        return """<?xml version="1.0" encoding="utf-8"?>
            <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
            <d:prop><d:getetag/><c:calendar-data/></d:prop><c:filter><c:comp-filter name="VCALENDAR">
            <c:comp-filter name="VEVENT"><c:time-range start="$start" end="$end"/>
            </c:comp-filter></c:comp-filter></c:filter></c:calendar-query>""".trimIndent()
    }

    fun normalize(kind: DavKind, supplied: String): HttpUrl {
        val url = (if (kind == DavKind.ICLOUD) ICLOUD_URL else supplied.trim()).toHttpUrlOrNull()
            ?: error("Enter a valid HTTPS CalDAV address")
        require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null) { "Enter an HTTPS address without credentials" }
        return url
    }

    fun permittedRedirect(kind: DavKind, original: HttpUrl, next: HttpUrl): Boolean {
        if (next.scheme != "https" || next.username.isNotEmpty() || next.password.isNotEmpty() ||
            next.query != null || next.fragment != null) return false
        return if (kind == DavKind.ICLOUD) next.host == "icloud.com" || next.host.endsWith(".icloud.com")
            else next.host == original.host && next.port == original.port
    }

    private data class Properties(val principal: String?, val home: String?)
    private fun document(bytes: ByteArray): org.w3c.dom.Document {
        // Android's platform parser does not implement every JAXP feature. Decode strictly and
        // reject declarations before parsing, then block external resolution at the builder.
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        check('\u0000' !in text && !Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            "Calendar XML declarations are not allowed"
        }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.isExpandEntityReferences = false
        runCatching { factory.isXIncludeAware = false }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        val document = builder.parse(InputSource(StringReader(text)))
        check(document.documentElement.localName == "multistatus" &&
            document.documentElement.namespaceURI == "DAV:") { "Calendar server did not return DAV discovery" }
        return document
    }
    private fun Element.direct(namespace: String, name: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
            .filter { it.namespaceURI == namespace && it.localName == name }
    }
    private fun Element.goodProperties(): List<Element> = direct("DAV:", "propstat").mapNotNull { stat ->
        val status = stat.direct("DAV:", "status").singleOrNull()?.textContent?.trim().orEmpty()
        if (Regex("^HTTP/1\\.[01] 200(?: |$)", RegexOption.IGNORE_CASE).containsMatchIn(status))
            stat.direct("DAV:", "prop").singleOrNull() else null
    }
    private fun properties(bytes: ByteArray): Properties {
        val responses = document(bytes).documentElement.direct("DAV:", "response")
        val props = responses.firstOrNull()?.goodProperties().orEmpty()
        fun href(namespace: String, name: String): String? {
            return props.asSequence().flatMap { it.direct(namespace, name).asSequence() }
                .flatMap { it.direct("DAV:", "href").asSequence() }
                .map { it.textContent.trim() }.firstOrNull { it.isNotBlank() }
        }
        return Properties(href("DAV:", "current-user-principal"),
            href("urn:ietf:params:xml:ns:caldav", "calendar-home-set"))
    }
    private fun calendars(bytes: ByteArray, kind: DavKind, original: HttpUrl, home: HttpUrl): List<DiscoveredCalendar> {
        val responses = document(bytes).documentElement.direct("DAV:", "response")
        check(responses.size <= 500) { "Too many calendar collections" }
        return responses.mapNotNull { response ->
            val href = response.direct("DAV:", "href").singleOrNull()?.textContent?.trim() ?: return@mapNotNull null
            val url = home.resolve(href) ?: return@mapNotNull null
            check(permittedRedirect(kind, original, url)) { "Calendar collection changed destination" }
            val props = response.goodProperties()
            val isCalendar = props.any { prop -> prop.direct("DAV:", "resourcetype").any { type ->
                type.direct("urn:ietf:params:xml:ns:caldav", "calendar").isNotEmpty() } }
            if (!isCalendar) return@mapNotNull null
            val name = props.asSequence().flatMap { it.direct("DAV:", "displayname").asSequence() }
                .map { it.textContent.trim() }.firstOrNull { it.isNotBlank() } ?: "Calendar"
            val privileges = props.flatMap { it.direct("DAV:", "current-user-privilege-set") }
                .flatMap { it.direct("DAV:", "privilege") }
            val write = privileges.any { it.direct("DAV:", "write").isNotEmpty() }
            DiscoveredCalendar(url.toString(), name.take(200),
                write || privileges.any { it.direct("DAV:", "bind").isNotEmpty() },
                write || privileges.any { it.direct("DAV:", "write-content").isNotEmpty() },
                write || privileges.any { it.direct("DAV:", "unbind").isNotEmpty() })
        }.distinctBy { it.url }
    }

    private fun responseBytes(response: okhttp3.Response, maximum: Int = 256 * 1024): ByteArray {
        val stream = response.body?.byteStream() ?: error("Calendar server returned no discovery document")
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            check(out.size() + read <= maximum) { "Calendar response is too large" }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Discover an authenticated CalDAV home; never log headers, URLs, or response bodies. */
    fun verify(kind: DavKind, supplied: String, username: String, secret: String,
        http: OkHttpClient = client): Discovery {
        require(username.isNotBlank() && secret.isNotBlank()) { "Enter an account name and app password" }
        val original = normalize(kind, supplied)
        val auth = Credentials.basic(username.trim(), secret, Charsets.UTF_8)
        fun fetch(start: HttpUrl, depth: String, body: String): Pair<HttpUrl, ByteArray> {
            var url = start
            repeat(4) {
                val request = Request.Builder().url(url).method("PROPFIND",
                    body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                    .header("Depth", depth).header("Authorization", auth).build()
                http.newCall(request).execute().use { response ->
                    if (response.code in 300..399) {
                        val next = response.header("Location")?.let { url.resolve(it) }
                            ?: error("Calendar server returned an invalid redirect")
                        check(permittedRedirect(kind, original, next)) { "Calendar server changed destination" }
                        url = next
                    } else {
                        check(response.code == 207) { if (response.code == 401 || response.code == 403)
                            "Calendar sign-in was refused" else "Calendar server did not accept CalDAV discovery" }
                        return url to responseBytes(response)
                    }
                }
            }
            error("Too many calendar server redirects")
        }
        val (base, firstBytes) = fetch(original, "0", propfind)
        val first = properties(firstBytes)
        val home = first.home ?: first.principal?.let { principal ->
            val principalUrl = base.resolve(principal) ?: error("Invalid calendar principal")
            check(permittedRedirect(kind, original, principalUrl)) { "Calendar principal changed destination" }
            val (resolvedPrincipal, principalBytes) = fetch(principalUrl, "0", propfind)
            val principalHome = properties(principalBytes).home ?: return@let null
            val resolvedHome = resolvedPrincipal.resolve(principalHome) ?: error("Invalid calendar home")
            check(permittedRedirect(kind, original, resolvedHome)) { "Calendar home changed destination" }
            resolvedHome.toString()
        } ?: error("Calendar home was not advertised")
        val homeUrl = base.resolve(home) ?: error("Invalid calendar home")
        check(permittedRedirect(kind, original, homeUrl)) { "Calendar home changed destination" }
        val (_, collections) = fetch(homeUrl, "1", listCalendars)
        val found = calendars(collections, kind, original, homeUrl)
        check(found.isNotEmpty()) { "No CalDAV calendars were found" }
        return Discovery(homeUrl.toString(), found)
    }

    fun listAccount(account: DavAccount, http: OkHttpClient = client): List<DiscoveredCalendar> {
        val home = normalize(DavKind.CALDAV, account.url)
        if (account.kind == DavKind.ICLOUD) {
            check(home.host == "icloud.com" || home.host.endsWith(".icloud.com")) {
                "Calendar account changed destination"
            }
        }
        val request = Request.Builder().url(home).method("PROPFIND",
            listCalendars.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .header("Authorization", Credentials.basic(account.username, account.secret, Charsets.UTF_8))
            .build()
        val bytes = http.newCall(request).execute().use { response ->
            check(response.code == 207) { "Calendar collection query failed" }
            responseBytes(response)
        }
        return calendars(bytes, account.kind, home, home)
    }

    /** Reads only the requested collection and date range. A recurrence gap is reported to the caller. */
    fun readEvents(account: DavAccount, collection: DiscoveredCalendar, source: CalendarSource,
        range: DateRange, http: OkHttpClient = client): EventRead {
        val home = normalize(DavKind.CALDAV, account.url)
        val url = collection.url.toHttpUrlOrNull() ?: error("Invalid calendar collection")
        check(permittedRedirect(account.kind, home, url)) { "Calendar collection changed destination" }
        val request = Request.Builder().url(url).method("REPORT",
            calendarQuery(range).toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .header("Authorization", Credentials.basic(account.username, account.secret, Charsets.UTF_8))
            .build()
        val bytes = http.newCall(request).execute().use { response ->
            check(response.code == 207) { "Calendar event query failed" }
            responseBytes(response, 4 * 1024 * 1024)
        }
        val responses = document(bytes).documentElement.direct("DAV:", "response")
        check(responses.size <= 2000) { "Too many calendar items" }
        val received = mutableListOf<AgendaEvent>()
        val resources = mutableListOf<Resource>()
        var unsupported = 0
        var unreadable = 0
        for (response in responses) {
            val props = response.goodProperties()
            val data = props.asSequence()
                .flatMap { it.direct("urn:ietf:params:xml:ns:caldav", "calendar-data").asSequence() }
                .map { it.textContent }.firstOrNull()
            if (data == null) { unreadable++; continue }
            val ics = data.toByteArray(Charsets.UTF_8)
            val item = try { IcsEventParser.parse(ics, source, range) }
                catch (_: Exception) { unreadable++; continue }
            received += item.events.filter { event -> event.occursIn(range) }
            unsupported += item.unsupportedRecurring
            val etag = props.asSequence().flatMap { it.direct("DAV:", "getetag").asSequence() }
                .map { it.textContent.trim() }.firstOrNull { it.isNotBlank() }
            val href = response.direct("DAV:", "href").singleOrNull()?.textContent?.trim()
            val itemUrl = href?.let { url.resolve(it) }
            if (etag != null && itemUrl != null && itemUrl.scheme == "https" &&
                itemUrl.host == url.host && itemUrl.port == url.port &&
                itemUrl.encodedPath.startsWith(url.encodedPath) && itemUrl.encodedPath != url.encodedPath &&
                itemUrl.query == null && itemUrl.fragment == null) {
                resources += Resource(itemUrl.toString(), etag, ics,
                    item.events.mapNotNull { it.uid }.distinct().singleOrNull())
            } else unreadable++
        }
        return EventRead(distinctEvents(received), unsupported, resources, unreadable)
    }
}
