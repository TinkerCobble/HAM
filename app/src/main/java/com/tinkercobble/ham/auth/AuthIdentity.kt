package com.tinkercobble.ham.auth

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object AuthIdentity {
    const val CLIENT_ID = "https://tinkercobble.com/ham/"
    const val LEGACY_CLIENT_ID = "https://tinkercobble.com/"

    fun randomUrlSafe(bytes: Int = 48): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorizationUrl(server: String, redirect: String, state: String, verifier: String?): String {
        fun parameter(value: String) = URLEncoder.encode(value, "UTF-8")
        return "$server/auth/authorize?client_id=${parameter(CLIENT_ID)}" +
            "&redirect_uri=${parameter(redirect)}&state=${parameter(state)}" +
            (verifier?.let { "&code_challenge=${parameter(challenge(it))}&code_challenge_method=S256" } ?: "")
    }

    fun callbackCode(url: String, redirect: String, expectedState: String): String {
        val actual = URI(url)
        val expected = URI(redirect)
        require(actual.scheme == expected.scheme && actual.rawAuthority == expected.rawAuthority &&
            actual.rawPath == expected.rawPath && actual.rawFragment == null && actual.userInfo == null) {
            "Authentication returned to the wrong address"
        }
        val params = (actual.rawQuery ?: "").split('&').filter { it.isNotBlank() }.map {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        require(params.count { it.first == "state" } == 1 && params.first { it.first == "state" }.second == expectedState) {
            "Authentication response did not match this request"
        }
        require(params.count { it.first == "code" } <= 1 && params.count { it.first == "error" } <= 1) {
            "Invalid authentication response"
        }
        require(!(params.any { it.first == "code" } && params.any { it.first == "error" })) {
            "Invalid authentication response"
        }
        return params.firstOrNull { it.first == "code" }?.second?.takeIf { it.isNotBlank() }
            ?: error(params.firstOrNull { it.first == "error" }?.second ?: "No authorization code returned")
    }
}
