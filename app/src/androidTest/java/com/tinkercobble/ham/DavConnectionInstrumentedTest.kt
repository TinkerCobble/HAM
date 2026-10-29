package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinkercobble.ham.data.DavConnection
import com.tinkercobble.ham.data.DavKind
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DavConnectionInstrumentedTest {
    private val home = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
        <d:response><d:propstat><d:prop><c:calendar-home-set><d:href>/user/calendars/</d:href>
        </c:calendar-home-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        </d:multistatus>""".trimIndent()
    private val collections = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
        <d:response><d:href>/user/calendars/family/</d:href><d:propstat><d:prop>
        <d:displayname>Family</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
        </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        </d:multistatus>""".trimIndent()
    private fun fake(first: String): OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val request = chain.request()
        val body = if (request.header("Depth") == "0") first else collections
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(207)
            .message("synthetic").body(body.toResponseBody()).build()
    }).build()

    @Test fun verifiesSyntheticHomeAndCalendarOnAndroidParser() {
        val found = DavConnection.verify(DavKind.CALDAV, "https://dav.example.test/",
            "sample@example.test", "synthetic-password", fake(home))
        assertEquals("https://dav.example.test/user/calendars/", found.home)
        assertEquals(1, found.calendars.size)
    }

    @Test fun refusesDoctypeEvenWhenPlatformFeatureIsUnavailable() {
        val malicious = "<!DOCTYPE d:multistatus [<!ENTITY x SYSTEM 'file:///dev/null'>]>" + home
        assertTrue(runCatching { DavConnection.verify(DavKind.CALDAV,
            "https://dav.example.test/", "sample@example.test", "synthetic-password", fake(malicious)) }
            .isFailure)
    }
}
