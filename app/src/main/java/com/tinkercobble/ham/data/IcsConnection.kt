package com.tinkercobble.ham.data

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Direct, read-only HTTPS feed transport. Never follow a URL carrying a private feed token. */
object IcsConnection {
    const val MAX_BYTES = IcsEventParser.MAX_BYTES
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(25, TimeUnit.SECONDS).build()

    fun normalize(supplied: String): HttpUrl {
        val url = supplied.trim().toHttpUrlOrNull() ?: error("Enter a valid HTTPS calendar URL")
        require(url.scheme == "https" && url.host.isNotBlank() && url.username.isEmpty() &&
            url.password.isEmpty() && url.fragment == null) {
            "Use a direct HTTPS calendar URL without embedded credentials or a fragment"
        }
        return url
    }

    fun source(item: IcsSubscription) = CalendarSource("ics:${item.id}", item.name, 0,
        "ICS subscription", provider = CalendarProviderKind.ICS)

    fun fetch(item: IcsSubscription, range: DateRange,
        http: OkHttpClient = client): IcsRead {
        val url = normalize(item.url)
        require(item.username.isBlank() == item.password.isBlank()) {
            "Enter both a username and password, or neither"
        }
        val request = Request.Builder().url(url).get().header("Accept", "text/calendar")
            .apply { if (item.username.isNotBlank()) header("Authorization",
                Credentials.basic(item.username, item.password, Charsets.UTF_8)) }.build()
        val bytes = try {
            http.newCall(request).execute().use { response ->
                check(response.code == 200) { "Calendar feed did not return a readable calendar" }
                val body = response.body ?: error("Calendar feed was empty")
                body.byteStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        check(output.size() + read <= MAX_BYTES) { "Calendar feed is too large" }
                        output.write(chunk, 0, read)
                    }
                    output.toByteArray()
                }
            }
        } catch (e: IllegalStateException) { throw e }
        catch (_: Exception) { error("Could not reach the HTTPS calendar feed") }
        return IcsEventParser.parse(bytes, source(item), range)
    }

    fun verificationRange(today: LocalDate = LocalDate.now()) =
        DateRange(today.minusDays(7), today.plusDays(90))
}
