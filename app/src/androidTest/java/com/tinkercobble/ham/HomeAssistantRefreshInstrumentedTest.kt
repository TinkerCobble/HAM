package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkercobble.ham.auth.SecureSession
import com.tinkercobble.ham.auth.Session
import com.tinkercobble.ham.data.HomeAssistantClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class HomeAssistantRefreshInstrumentedTest {
    @Test fun rejectedReadRefreshesOnceAndPersistsBeforeNextClient() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureSession(context)
        store.clear()
        val reads = AtomicInteger()
        val refreshes = AtomicInteger()
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        val worker = Thread {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    socket.use {
                        it.soTimeout = 5000
                        val input = it.getInputStream().bufferedReader()
                        val request = input.readLine() ?: return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            val split = line.indexOf(':')
                            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
                        }
                        val size = headers["content-length"]?.toIntOrNull() ?: 0
                        if (size in 1..4096) repeat(size) { input.read() }
                        val path = request.split(' ').getOrNull(1)
                        val (status, body) = when (path) {
                            "/auth/token" -> {
                                refreshes.incrementAndGet()
                                200 to """{"access_token":"fresh-synthetic","expires_in":1800}"""
                            }
                            "/api/" -> {
                                reads.incrementAndGet()
                                if (headers["authorization"] == "Bearer fresh-synthetic") 200 to "{}"
                                else 401 to "{}"
                            }
                            else -> 404 to "{}"
                        }
                        val bytes = body.toByteArray()
                        it.getOutputStream().apply {
                            write("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            } catch (_: SocketTimeoutException) {
                // A failed assertion will close the server in finally.
            } catch (_: java.net.SocketException) {
                // Server closed by the test.
            }
        }.apply { start() }
        try {
            val client = HomeAssistantClient(context)
            client.setSession(Session("http://127.0.0.1:${server.localPort}", "refresh-synthetic",
                "expired-synthetic", System.currentTimeMillis() + 3_600_000))
            runBlocking { client.ping() }
            assertEquals(2, reads.get())
            assertEquals(1, refreshes.get())
            runBlocking { HomeAssistantClient(context).ping() }
            assertEquals(3, reads.get())
            assertEquals(1, refreshes.get())
            assertEquals("fresh-synthetic", store.load()?.accessToken)
            val saved = context.getSharedPreferences("ham-secure-session", 0)
                .getString("session", "").orEmpty()
            assertTrue(saved.isNotBlank())
            assertFalse(saved.contains("fresh-synthetic"))
            assertFalse(saved.contains("refresh-synthetic"))
            client.setSession(Session("http://127.0.0.1:${server.localPort}", "refresh-synthetic",
                "expired-synthetic", System.currentTimeMillis() - 1))
            runBlocking { client.ping() }
            assertEquals(4, reads.get())
            assertEquals(2, refreshes.get())
            assertEquals("fresh-synthetic", store.load()?.accessToken)
            runBlocking { HomeAssistantClient(context).ping() }
            assertEquals(5, reads.get())
            assertEquals(2, refreshes.get())
        } finally {
            store.clear()
            server.close()
            worker.join(5000)
        }
    }
}
