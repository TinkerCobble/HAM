package com.tinkercobble.ham.auth

import android.net.Uri
import com.tinkercobble.ham.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AuthRequest(val server: String, val state: String, val redirect: String,
    val codeVerifier: String?, val authorizeUrl: String)

object HomeAssistantAuth {
    val redirect: String get() = "${BuildConfig.APPLICATION_ID}://auth"

    /** Discover PKCE support; older Home Assistant versions omit it. No token is sent in this probe. */
    suspend fun prepare(server: String): AuthRequest = withContext(Dispatchers.IO) {
        val state = AuthIdentity.randomUrlSafe(24)
        val verifier = if (supportsPkce(server)) AuthIdentity.randomUrlSafe() else null
        val callback = redirect
        val builder = Uri.parse("$server/auth/authorize").buildUpon()
            .appendQueryParameter("client_id", AuthIdentity.CLIENT_ID)
            .appendQueryParameter("redirect_uri", callback)
            .appendQueryParameter("state", state)
        if (verifier != null) builder.appendQueryParameter("code_challenge", AuthIdentity.challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
        AuthRequest(server, state, callback, verifier, builder.build().toString())
    }

    private fun supportsPkce(server: String): Boolean = runCatching {
        val connection = URL("$server/.well-known/oauth-authorization-server").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 4000
        connection.readTimeout = 4000
        try {
            if (connection.responseCode != 200) return@runCatching false
            val metadata = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val methods = metadata.optJSONArray("code_challenge_methods_supported")
            methods != null && (0 until methods.length()).any { methods.optString(it) == "S256" }
        } finally { connection.disconnect() }
    }.getOrDefault(false)

    fun callback(url: String, request: AuthRequest): String =
        AuthIdentity.callbackCode(url, request.redirect, request.state)

    suspend fun exchange(request: AuthRequest, code: String): Session = withContext(Dispatchers.IO) {
        val fields = mutableMapOf("grant_type" to "authorization_code", "code" to code,
            "client_id" to AuthIdentity.CLIENT_ID)
        request.codeVerifier?.let { fields["code_verifier"] = it }
        val json = token(request.server, fields)
        Session(request.server, json.getString("refresh_token"), json.getString("access_token"),
            System.currentTimeMillis() + json.getLong("expires_in") * 1000, AuthIdentity.CLIENT_ID)
    }

    suspend fun refresh(session: Session): Session = withContext(Dispatchers.IO) {
        val json = token(session.server, mapOf("grant_type" to "refresh_token",
            "refresh_token" to session.refreshToken, "client_id" to session.clientId))
        session.copy(accessToken = json.getString("access_token"),
            expiresAt = System.currentTimeMillis() + json.getLong("expires_in") * 1000)
    }

    /** A 200 means the server accepted the request; Home Assistant does not disclose token validity. */
    suspend fun requestRevocation(session: Session): Boolean = withContext(Dispatchers.IO) {
        runCatching { token(session.server, mapOf("token" to session.refreshToken), "/auth/revoke") }.isSuccess
    }

    private fun token(server: String, fields: Map<String, String>, path: String = "/auth/token"): JSONObject {
        val connection = URL(server + path).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = fields.entries.joinToString("&") { "${Uri.encode(it.key)}=${Uri.encode(it.value)}" }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()
        if (status !in 200..299) error("Home Assistant authorization failed ($status)")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
