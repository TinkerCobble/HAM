package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinkercobble.ham.auth.AuthIdentity
import com.tinkercobble.ham.auth.HomeAssistantAuth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.InetAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class HomeAssistantAuthInstrumentedTest {
    private data class Exchange(val path: String, val body: String)

    private fun form(body: String): Map<String, String> = body.split('&').filter { it.isNotBlank() }
        .associate { part ->
            val pieces = part.split('=', limit = 2)
            URLDecoder.decode(pieces[0], "UTF-8") to
                URLDecoder.decode(pieces.getOrElse(1) { "" }, "UTF-8")
        }

    private fun fixture(responses: List<Pair<Int, String>>, block: (String, List<Exchange>) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 10_000
        val requests = mutableListOf<Exchange>()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                responses.forEach { (status, json) ->
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                        val path = reader.readLine().split(' ')[1]
                        var length = 0
                        while (true) {
                            val header = reader.readLine()
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true))
                                length = header.substringAfter(':').trim().toInt()
                        }
                        val body = CharArray(length).also { chars ->
                            var read = 0
                            while (read < chars.size) {
                                val count = reader.read(chars, read, chars.size - read)
                                check(count > 0)
                                read += count
                            }
                        }.concatToString()
                        requests += Exchange(path, body)
                        val bytes = json.toByteArray(StandardCharsets.UTF_8)
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n".toByteArray())
                            write("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            } catch (error: Throwable) { failure.set(error) }
        }.apply { isDaemon = true; start() }
        try {
            block("http://127.0.0.1:${server.localPort}", requests)
            worker.join(10_000)
            check(!worker.isAlive) { "Synthetic Home Assistant fixture did not finish" }
            failure.get()?.let { throw it }
        } finally { server.close(); worker.join(1_000) }
    }

    @Test fun s256AuthorizationCodeAndRefreshUseSameClientIdentity() {
        fixture(listOf(
            200 to """{"code_challenge_methods_supported":["S256"]}""",
            200 to """{"access_token":"synthetic-access-1","refresh_token":"synthetic-refresh","expires_in":1800,"token_type":"Bearer"}""",
            200 to """{"access_token":"synthetic-access-2","expires_in":1800,"token_type":"Bearer"}"""
        )) { server, requests ->
            runBlocking {
                val authorization = HomeAssistantAuth.prepare(server)
                assertTrue(authorization.usesPkce)
                val challenge = form(authorization.authorizeUrl.substringAfter('?'))
                assertEquals(AuthIdentity.CLIENT_ID, challenge["client_id"])
                assertEquals(HomeAssistantAuth.redirect, challenge["redirect_uri"])
                assertEquals(authorization.state, challenge["state"])
                assertEquals("S256", challenge["code_challenge_method"])
                assertEquals(AuthIdentity.challenge(checkNotNull(authorization.codeVerifier)),
                    challenge["code_challenge"])
                assertTrue(runCatching { HomeAssistantAuth.callback(
                    "${authorization.redirect}?code=synthetic-code&state=wrong", authorization) }.isFailure)
                val code = HomeAssistantAuth.callback(
                    "${authorization.redirect}?code=synthetic-code&state=${authorization.state}", authorization)
                val session = HomeAssistantAuth.exchange(authorization, code)
                assertEquals("synthetic-refresh", session.refreshToken)
                assertTrue(session.expiresAt > System.currentTimeMillis())
                val refreshed = HomeAssistantAuth.refresh(session)
                assertEquals("synthetic-access-2", refreshed.accessToken)
                assertEquals(session.refreshToken, refreshed.refreshToken)
            }
            assertEquals(3, requests.size)
            assertEquals("/.well-known/oauth-authorization-server", requests[0].path)
            assertEquals("/auth/token", requests[1].path)
            assertEquals("authorization_code", form(requests[1].body)["grant_type"])
            assertEquals(AuthIdentity.CLIENT_ID, form(requests[1].body)["client_id"])
            assertNotNull(form(requests[1].body)["code_verifier"])
            assertEquals("refresh_token", form(requests[2].body)["grant_type"])
            assertEquals("synthetic-refresh", form(requests[2].body)["refresh_token"])
        }
    }

    @Test fun metadataWithoutS256KeepsExplicitCompatibilityPath() {
        fixture(listOf(200 to "{}")) { server, requests ->
            val request = runBlocking { HomeAssistantAuth.prepare(server) }
            assertFalse(request.usesPkce)
            assertFalse(request.authorizeUrl.contains("code_challenge="))
            assertEquals(1, requests.size)
        }
    }

    @Test fun oversizedMetadataFailsClosedBeforeAuthorization() {
        fixture(listOf(200 to "{\"padding\":\"${"x".repeat(70_000)}\"}")) { server, requests ->
            assertTrue(runCatching { runBlocking { HomeAssistantAuth.prepare(server) } }.isFailure)
            assertEquals(1, requests.size)
        }
    }
}
