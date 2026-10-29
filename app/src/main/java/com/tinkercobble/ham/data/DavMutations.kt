package com.tinkercobble.ham.data

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.UUID

enum class DavConflictCause { UNKNOWN, MISSING_ITEM, INVALID_CALENDAR_DATA, UID_CONFLICT,
    OTHER_PRECONDITION }

class DavConflictException(val reason: DavConflictCause = DavConflictCause.UNKNOWN) :
    Exception("Calendar change needs review")

/** Conditional CalDAV transport. The caller must retain the UUID and ETag across offline retries. */
object DavMutations {
    data class CurrentItem(val etag: String, val ics: ByteArray)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    private fun collectionUrl(account: DavAccount, collection: DavConnection.DiscoveredCalendar): HttpUrl {
        val home = DavConnection.normalize(DavKind.CALDAV, account.url)
        val url = collection.url.toHttpUrlOrNull() ?: error("Invalid calendar collection")
        check(DavConnection.permittedRedirect(account.kind, home, url) && url.encodedPath.endsWith('/')) {
            "Calendar collection changed destination"
        }
        return url
    }

    private fun itemUrl(collection: HttpUrl, supplied: String): HttpUrl {
        val item = supplied.toHttpUrlOrNull() ?: error("Invalid calendar item")
        check(item.scheme == "https" && item.host == collection.host && item.port == collection.port &&
            item.username.isEmpty() && item.password.isEmpty() && item.query == null && item.fragment == null &&
            item.encodedPath.startsWith(collection.encodedPath) && item.encodedPath != collection.encodedPath) {
            "Calendar item changed destination"
        }
        return item
    }

    private fun etag(value: String): String {
        require(Regex("^\"[^\"\\r\\n]{1,200}\"$").matches(value)) { "Calendar item has no usable ETag" }
        return value
    }

    /** Fetch the exact item from the discovered collection, with no redirects or weak ETags. */
    fun readCurrent(account: DavAccount, collection: DavConnection.DiscoveredCalendar,
        item: String, http: OkHttpClient = client): CurrentItem {
        val url = itemUrl(collectionUrl(account, collection), item)
        val request = Request.Builder().url(url).get().header("Authorization",
            Credentials.basic(account.username, account.secret, Charsets.UTF_8)).build()
        return http.newCall(request).execute().use { response ->
            check(response.code == 200) { "Calendar item could not be reviewed" }
            val strongEtag = etag(response.header("ETag") ?: "")
            val input = response.body?.byteStream() ?: error("Calendar item could not be reviewed")
            val out = java.io.ByteArrayOutputStream()
            input.use {
                val buffer = ByteArray(8192)
                while (true) {
                    val read = it.read(buffer)
                    if (read < 0) break
                    check(out.size() + read <= 512 * 1024) { "Calendar item is too large" }
                    out.write(buffer, 0, read)
                }
            }
            check(out.size() > 0) { "Calendar item could not be reviewed" }
            CurrentItem(strongEtag, out.toByteArray())
        }
    }

    private fun execute(account: DavAccount, request: Request, http: OkHttpClient,
        missingIsConflict: Boolean = false): String? =
        http.newCall(request.newBuilder().header("Authorization",
            Credentials.basic(account.username, account.secret, Charsets.UTF_8)).build())
            .execute().use { response ->
                if (response.code == 412) {
                    // Only a fixed, non-sensitive category leaves this response. The body is never logged.
                    val error = response.peekBody(8192).string()
                    val cause = when {
                        Regex("<[^>]*\\b(?:valid-calendar-data|valid-calendar-object-resource|supported-calendar-data|supported-calendar-component)\\b",
                            RegexOption.IGNORE_CASE).containsMatchIn(error) ->
                            DavConflictCause.INVALID_CALENDAR_DATA
                        Regex("<[^>]*\\bno-uid-conflict\\b", RegexOption.IGNORE_CASE)
                            .containsMatchIn(error) -> DavConflictCause.UID_CONFLICT
                        else -> DavConflictCause.OTHER_PRECONDITION
                    }
                    throw DavConflictException(cause)
                }
                if (missingIsConflict && response.code == 404)
                    throw DavConflictException(DavConflictCause.MISSING_ITEM)
                check(response.code == 200 || response.code == 201 || response.code == 204) {
                    "Calendar server refused the change"
                }
                response.header("ETag")
            }

    fun create(account: DavAccount, collection: DavConnection.DiscoveredCalendar, operationId: UUID,
        ics: ByteArray, http: OkHttpClient = client): String? {
        check(collection.canCreate) { "Calendar does not permit creation" }
        require(ics.isNotEmpty() && ics.size <= 512 * 1024) { "Calendar item is too large" }
        val url = collectionUrl(account, collection).newBuilder().addPathSegment("$operationId.ics").build()
        val request = Request.Builder().url(url).put(ics.toRequestBody("text/calendar; charset=utf-8".toMediaType()))
            .header("If-None-Match", "*").build()
        return execute(account, request, http)
    }

    /** A retry may get 412 after a server accepted an earlier PUT whose response was lost. */
    fun confirmedCreate(account: DavAccount, collection: DavConnection.DiscoveredCalendar,
        operationId: UUID, originalIcs: ByteArray, http: OkHttpClient = client): Boolean {
        val url = collectionUrl(account, collection).newBuilder().addPathSegment("$operationId.ics").build()
        val request = Request.Builder().url(url)
            .header("Authorization", Credentials.basic(account.username, account.secret, Charsets.UTF_8))
            .get().build()
        return http.newCall(request).execute().use responseUse@ { response ->
            if (response.code != 200) return@responseUse false
            val stream = response.body?.byteStream() ?: return@responseUse false
            val out = java.io.ByteArrayOutputStream()
            stream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > 512 * 1024) return@responseUse false
                    out.write(buffer, 0, read)
                }
            }
            val currentIcs = out.toByteArray()
            // A lost response for a series is confirmed only by exact bytes; a different RRULE
            // must never be mistaken for the same first occurrence.
            if (originalIcs.toString(Charsets.UTF_8).contains("\r\nRRULE:"))
                return@responseUse currentIcs.contentEquals(originalIcs)
            val source = DavConnection.source(account, collection)
            val original = IcsEventParser.parse(originalIcs, source).events.singleOrNull()
                ?: return@responseUse false
            val current = IcsEventParser.parse(currentIcs, source).events.singleOrNull()
                ?: return@responseUse false
            val sameTime = if (current.allDay) current.start == original.start &&
                current.end == original.end else
                AgendaEvent.instant(current.start) == AgendaEvent.instant(original.start) &&
                    AgendaEvent.instant(current.end) == AgendaEvent.instant(original.end)
            current.uid == "$operationId@ham.tinkercobble.com" && current.uid == original.uid &&
                current.summary == original.summary && current.allDay == original.allDay && sameTime &&
                current.description == original.description && current.location == original.location
        }
    }

    fun update(account: DavAccount, collection: DavConnection.DiscoveredCalendar,
        item: String, expectedEtag: String, ics: ByteArray, http: OkHttpClient = client): String? {
        check(collection.canUpdate) { "Calendar does not permit editing" }
        require(ics.isNotEmpty() && ics.size <= 512 * 1024) { "Calendar item is too large" }
        val url = itemUrl(collectionUrl(account, collection), item)
        val request = Request.Builder().url(url).put(ics.toRequestBody("text/calendar; charset=utf-8".toMediaType()))
            .header("If-Match", etag(expectedEtag)).build()
        return execute(account, request, http)
    }

    /** A 412 can follow an UPDATE that succeeded but whose response was lost. */
    fun confirmedUpdate(account: DavAccount, collection: DavConnection.DiscoveredCalendar,
        item: String, intendedIcs: ByteArray, http: OkHttpClient = client): Boolean {
        require(intendedIcs.size in 1..512 * 1024) { "Calendar item is too large" }
        val url = itemUrl(collectionUrl(account, collection), item)
        val request = Request.Builder().url(url)
            .header("Authorization", Credentials.basic(account.username, account.secret, Charsets.UTF_8))
            .get().build()
        return http.newCall(request).execute().use responseUse@ { response ->
            if (response.code != 200) return@responseUse false
            val stream = response.body?.byteStream() ?: return@responseUse false
            val out = java.io.ByteArrayOutputStream()
            stream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > 512 * 1024) return@responseUse false
                    out.write(buffer, 0, read)
                }
            }
            out.toByteArray().contentEquals(intendedIcs)
        }
    }

    fun delete(account: DavAccount, collection: DavConnection.DiscoveredCalendar,
        item: String, expectedEtag: String, http: OkHttpClient = client) {
        check(collection.canDelete) { "Calendar does not permit deletion" }
        val url = itemUrl(collectionUrl(account, collection), item)
        val request = Request.Builder().url(url).delete().header("If-Match", etag(expectedEtag)).build()
        execute(account, request, http, missingIsConflict = true)
    }
}
