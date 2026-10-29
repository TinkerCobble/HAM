package com.tinkercobble.ham.auth

import android.net.Uri
import com.tinkercobble.ham.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class AuthRequest(val server: String, val state: String, val redirect: String,
    val codeVerifier: String?, val authorizeUrl: String) {
    val usesPkce get() = codeVerifier != null
}

object HomeAssistantAuth {
    val redirect: String get() = "${BuildConfig.APPLICATION_ID}://auth"

    private fun response(input: InputStream, maxBytes: Int = 64 * 1024): String = input.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            check(out.size() + count <= maxBytes) { "Home Assistant response is too large" }
            out.write(buffer, 0, count)
        }
        out.toString(Charsets.UTF_8.name())
    }

    /** Use S256 when advertised; a 200 metadata document without S256 permits legacy compatibility. */
    suspend fun prepare(server: String): AuthRequest = withContext(Dispatchers.IO) {
        val state = AuthIdentity.randomUrlSafe(24)
        val verifier = if (supportsPkce(server)) AuthIdentity.randomUrlSafe() else null
        val callback = redirect
        AuthRequest(server, state, callback, verifier,
            AuthIdentity.authorizationUrl(server, callback, state, verifier))
    }

    private fun supportsPkce(server: String): Boolean {
        val connection = URL("$server/.well-known/oauth-authorization-server").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 4000
        connection.readTimeout = 4000
        try {
            check(connection.responseCode == 200) { "Could not verify Home Assistant sign-in capabilities" }
            val metadata = JSONObject(response(connection.inputStream))
            val methods = metadata.optJSONArray("code_challenge_methods_supported") ?: return false
            check((0 until methods.length()).any { methods.optString(it) == "S256" }) {
                "Home Assistant does not support PKCE S256"
            }
            return true
        } finally { connection.disconnect() }
    }

    fun callback(url: String, request: AuthRequest): String =
        AuthIdentity.callbackCode(url, request.redirect, request.state)

    suspend fun exchange(request: AuthRequest, code: String): Session = withContext(Dispatchers.IO) {
        require(code.isNotBlank() && code.length <= 8192) { "Invalid authorization response" }
        val fields = mutableMapOf("grant_type" to "authorization_code", "code" to code,
            "client_id" to AuthIdentity.CLIENT_ID)
        request.codeVerifier?.let { fields["code_verifier"] = it }
        val json = token(request.server, fields)
        Session(request.server, json.getString("refresh_token").also { require(it.isNotBlank()) },
            json.getString("access_token").also { require(it.isNotBlank()) },
            expiresAt(json), AuthIdentity.CLIENT_ID)
    }

    suspend fun refresh(session: Session): Session = withContext(Dispatchers.IO) {
        val json = token(session.server, mapOf("grant_type" to "refresh_token",
            "refresh_token" to session.refreshToken, "client_id" to session.clientId))
        session.copy(accessToken = json.getString("access_token").also { require(it.isNotBlank()) },
            expiresAt = expiresAt(json))
    }

    /** A 200 means the server accepted the request; Home Assistant does not disclose token validity. */
    suspend fun requestRevocation(session: Session): Boolean = withContext(Dispatchers.IO) {
        runCatching { token(session.server, mapOf("token" to session.refreshToken), "/auth/revoke") }.isSuccess
    }

    private fun expiresAt(json: JSONObject): Long {
        val seconds = json.getLong("expires_in")
        require(seconds > 0 && seconds <= (Long.MAX_VALUE - System.currentTimeMillis()) / 1000) {
            "Invalid Home Assistant token lifetime"
        }
        return System.currentTimeMillis() + seconds * 1000
    }

    private fun token(server: String, fields: Map<String, String>, path: String = "/auth/token"): JSONObject {
        val connection = URL(server + path).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = fields.entries.joinToString("&") { "${Uri.encode(it.key)}=${Uri.encode(it.value)}" }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) error("Home Assistant authorization failed ($status)")
            val text = response(connection.inputStream)
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { connection.disconnect() }
    }
}
