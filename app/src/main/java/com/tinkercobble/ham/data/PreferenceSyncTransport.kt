package com.tinkercobble.ham.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** User-entered endpoint and trusted out-of-band certificate fingerprint. No default server exists. */
data class PreferenceSyncEndpoint(val baseUrl: String, val certificateSha256: String) {
    init {
        val url = baseUrl.toHttpUrlOrNull()
        require(url != null && url.scheme == "https" && url.encodedPath == "/" &&
            url.encodedQuery == null && url.fragment == null && url.username.isEmpty() &&
            url.password.isEmpty() && url.port in 1..65535) { "Enter an HTTPS sync service address" }
        require(certificateSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "Verify the sync service certificate fingerprint"
        }
    }
}

/** Pure request bytes shared by the Android signer and the server's protocol tests. */
object PreferenceSyncRequest {
    fun canonical(method: String, path: String, timestamp: Long, nonce: String, body: ByteArray): ByteArray {
        require(method in setOf("GET", "POST") && path.startsWith("/v1/") &&
            !path.contains('?') && !path.contains('#') && nonce.matches(Regex("[A-Za-z0-9_-]{16,96}")))
        val digest = MessageDigest.getInstance("SHA-256").digest(body)
            .joinToString("") { "%02x".format(it) }
        return "$method\n$path\n$timestamp\n$nonce\n$digest".toByteArray(Charsets.US_ASCII)
    }
}

/** Random per-install signing identity; the private key never leaves Android Keystore. */
class KeystorePreferenceSyncIdentity(private val alias: String = "ham-preference-sync-device-v1") {
    private fun entry(): KeyStore.PrivateKeyEntry {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.let { return it }
        val generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or
            KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256).build())
        generator.generateKeyPair()
        return store.getEntry(alias, null) as KeyStore.PrivateKeyEntry
    }

    fun publicKeyPem(): String {
        val encoded = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(entry().certificate.publicKey.encoded)
        return "-----BEGIN PUBLIC KEY-----\n$encoded\n-----END PUBLIC KEY-----\n"
    }

    fun sign(message: ByteArray): String {
        val privateKey = entry().privateKey as PrivateKey
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(message)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
    }
}

class PreferenceSyncHttps(private val endpoint: PreferenceSyncEndpoint,
    private val identity: KeystorePreferenceSyncIdentity = KeystorePreferenceSyncIdentity()) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client: OkHttpClient by lazy {
        val expected = endpoint.certificateSha256.lowercase()
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Client certificates are not accepted")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val certificate = chain.firstOrNull() ?: throw CertificateException("Missing sync certificate")
                certificate.checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                    .joinToString("") { "%02x".format(it) }
                if (actual != expected) throw CertificateException("Sync certificate changed")
            }
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trust), null)
        OkHttpClient.Builder().sslSocketFactory(context.socketFactory, trust)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private fun request(path: String, body: JSONObject? = null,
        profileId: String? = null, deviceId: String? = null): JSONObject {
        require(path.matches(Regex("/v1/[a-z/]+")))
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        require(bytes.size <= 100_000)
        val builder = Request.Builder().url(endpoint.baseUrl.trimEnd('/') + path)
            .header("Accept", "application/json")
        if (body == null) builder.get() else builder.post(bytes.toRequestBody(jsonType))
        if (profileId != null || deviceId != null) {
            require(profileId != null && deviceId != null)
            UUID.fromString(profileId)
            UUID.fromString(deviceId)
            val timestamp = Instant.now().epochSecond
            val nonce = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(18).also(java.security.SecureRandom()::nextBytes))
            val canonical = PreferenceSyncRequest.canonical(if (body == null) "GET" else "POST",
                path, timestamp, nonce, bytes)
            builder.header("X-HAM-Profile", profileId).header("X-HAM-Device", deviceId)
                .header("X-HAM-Timestamp", timestamp.toString()).header("X-HAM-Nonce", nonce)
                .header("X-HAM-Signature", identity.sign(canonical))
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("Sync service rejected the request")
            val payload = response.body?.byteStream()?.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 3_000_000) { "Sync response is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
                ?: error("Sync service gave no response")
            require(payload.size <= 3_000_000) { "Sync response is too large" }
            return JSONObject(String(payload, Charsets.UTF_8))
        }
    }

    fun requestPairing(code: String, deviceLabel: String): String {
        require(deviceLabel.isNotBlank() && deviceLabel.length <= 80)
        return request("/v1/pair/request", JSONObject().put("code", code)
            .put("public_key", identity.publicKeyPem()).put("label", deviceLabel))
            .getString("request_id")
    }

    data class Enrollment(val profileId: String, val deviceId: String)

    fun completePairing(requestId: String): Enrollment {
        UUID.fromString(requestId)
        val proof = identity.sign("ham-sync-enroll:v1:$requestId".toByteArray(Charsets.US_ASCII))
        val result = request("/v1/pair/complete", JSONObject().put("request_id", requestId)
            .put("proof", proof))
        return Enrollment(result.getString("profile_id"), result.getString("device_id"))
    }

    fun issuePairingCode(profileId: String, deviceId: String): String =
        request("/v1/pair/issue", JSONObject(), profileId, deviceId).getString("code")

    fun approvePairing(profileId: String, deviceId: String, requestId: String) {
        request("/v1/pair/approve", JSONObject().put("request_id", requestId), profileId, deviceId)
    }

    fun pendingPairings(profileId: String, deviceId: String): JSONArray =
        request("/v1/pair/pending", profileId = profileId, deviceId = deviceId).getJSONArray("requests")

    fun devices(profileId: String, deviceId: String): JSONArray =
        request("/v1/devices", profileId = profileId, deviceId = deviceId).getJSONArray("devices")

    fun revokeDevice(profileId: String, deviceId: String, targetId: String) {
        request("/v1/devices/revoke", JSONObject().put("device_id", targetId), profileId, deviceId)
    }

    fun renameDevice(profileId: String, deviceId: String, targetId: String, label: String) {
        request("/v1/devices/rename", JSONObject().put("device_id", targetId)
            .put("label", label), profileId, deviceId)
    }

    fun exchange(profileId: String, deviceId: String, sinceRevision: Long,
        operations: List<PreferenceChange>): SyncExchange {
        val array = JSONArray()
        operations.forEach { op ->
            require(PreferenceSyncKeys.valid(op.key, op.value))
            val raw: Any? = when (val value = op.value) {
                is SyncSettingValue.Text -> value.value
                is SyncSettingValue.Flag -> value.value
                is SyncSettingValue.Number -> value.value
                is SyncSettingValue.TextList -> JSONArray(value.value)
                SyncSettingValue.Deleted -> JSONObject.NULL
            }
            array.put(JSONObject().put("id", op.id).put("key", op.key)
                .put("base_revision", op.baseRevision)
                .put("tombstone", op.value == SyncSettingValue.Deleted).put("value", raw))
        }
        val response = request("/v1/sync", JSONObject().put("since_revision", sinceRevision)
            .put("operations", array), profileId, deviceId)
        val resultArray = response.getJSONArray("results")
        val results = (0 until resultArray.length()).map { index ->
            val item = resultArray.getJSONObject(index)
            SyncOperationResult(item.getString("id"),
                when (item.getString("status")) {
                    "applied" -> SyncOperationResult.Status.APPLIED
                    "conflict" -> SyncOperationResult.Status.CONFLICT
                    else -> error("Unknown sync operation result")
                }, item.getLong("revision"))
        }
        val changeArray = response.getJSONArray("changes")
        val changes = (0 until changeArray.length()).map { index ->
            val item = changeArray.getJSONObject(index)
            val key = item.getString("key")
            val value = if (item.getBoolean("tombstone")) SyncSettingValue.Deleted else
                when (key.substringAfterLast(':')) {
                    "visible", "favorite", "mirror_hidden" -> SyncSettingValue.Flag(item.getBoolean("value"))
                    "order" -> SyncSettingValue.Number(item.getInt("value"))
                    "group_order", "groups" -> item.getJSONArray("value").let { list ->
                        SyncSettingValue.TextList((0 until list.length()).map(list::getString))
                    }
                    else -> SyncSettingValue.Text(item.getString("value"))
                }
            RemotePreference(key, item.getLong("revision"), value)
        }
        return SyncExchange(results, changes, response.getLong("head_revision"),
            response.getBoolean("has_more"))
    }
}

class HttpPreferenceSyncTransport(private val https: PreferenceSyncHttps,
    private val profileId: String, private val deviceId: String) : PreferenceSyncTransport {
    override fun exchange(sinceRevision: Long, operations: List<PreferenceChange>) =
        https.exchange(profileId, deviceId, sinceRevision, operations)
}
