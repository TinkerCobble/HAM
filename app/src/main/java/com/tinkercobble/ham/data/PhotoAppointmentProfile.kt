package com.tinkercobble.ham.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PhotoAppointmentRule(val code: String, val title: String, val detail: String,
    val remote: Boolean = false, val needsConfirmation: Boolean = false)

/** User-supplied facility directory. No facility or patient data is bundled with HAM. */
data class PhotoAppointmentProfile(val facility: String, val address: String, val zone: String,
    val rules: List<PhotoAppointmentRule>, val verifiedRows: List<VerifiedPhotoRow> = emptyList()) {
    fun reconcileAll(items: List<PhotoAppointment>): List<PhotoAppointment> =
        PhotoTranscriptReconciliation.reconcileAll(items, verifiedRows)
    fun rule(code: String): PhotoAppointmentRule? = rules.firstOrNull {
        code.trim().equals(it.code.trim(), ignoreCase = true) ||
            code.trim().startsWith(it.code.trim() + " ", ignoreCase = true) }
    fun title(item: PhotoAppointment): String = rule(item.originalCode)?.title
        ?: item.originalCode.ifBlank { "Appointment — review title" }
    fun location(item: PhotoAppointment): String {
        val match = rule(item.originalCode)
        if (match?.remote == true) return "Video visit"
        return if (match != null && address.isNotBlank()) "$facility, $address" else item.printedLocation
    }
    fun description(item: PhotoAppointment): String = buildString {
        append("Printed appointment code: ").append(item.originalCode)
        append("\nPrinted location: ").append(item.printedLocation)
        item.durationMinutes?.let { append("\nPrinted duration: ").append(it).append(" minutes") }
        rule(item.originalCode)?.detail?.takeIf(String::isNotBlank)?.let { append("\nClinic: ").append(it) }
        if (rule(item.originalCode)?.remote == true) append("\nRemote video appointment; no travel destination.")
    }
    fun zoneId(): ZoneId = ZoneId.of(zone)

    companion object {
        fun fromJson(text: String): PhotoAppointmentProfile {
            require(text.length <= 100_000) { "Directory file is too large" }
            val obj = JSONObject(text)
            val facility = obj.getString("facility").trim()
            val address = obj.optString("address").trim()
            val zone = obj.optString("zone", ZoneId.systemDefault().id).trim()
            require(facility.length in 2..120 && address.length <= 250) { "Check the facility directory" }
            ZoneId.of(zone)
            val entries = obj.optJSONArray("rules") ?: JSONArray()
            require(entries.length() <= 100) { "Too many directory rules" }
            val rules = (0 until entries.length()).map { index ->
                val item = entries.getJSONObject(index)
                PhotoAppointmentRule(item.getString("code").trim(), item.getString("title").trim(),
                    item.optString("detail").trim(), item.optBoolean("remote"),
                    item.optBoolean("needsConfirmation"))
            }
            require(rules.all { it.code.length in 2..100 && it.title.length in 2..180 }) {
                "Check the directory rules"
            }
            require(rules.map { it.code.lowercase() }.distinct().size == rules.size) { "Duplicate directory code" }
            val verified = obj.optJSONArray("verifiedRows") ?: JSONArray()
            require(verified.length() <= 200) { "Too many verified appointment rows" }
            val verifiedRows = (0 until verified.length()).map { index ->
                val row = verified.getJSONObject(index)
                VerifiedPhotoRow(java.time.LocalDate.parse(row.getString("date")),
                    java.time.LocalTime.parse(row.getString("start")),
                    row.getInt("durationMinutes").also { require(it in 1..720) },
                    row.getString("originalCode").trim().also { require(it.length in 2..120) },
                    row.getString("printedLocation").trim().also { require(it.length in 2..160) })
            }
            return PhotoAppointmentProfile(facility, address, zone, rules, verifiedRows)
        }
    }
}

/** Keystore-protected device-private directory; Android backup is disabled for this app. */
class PhotoAppointmentProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("ham-photo-directory", Context.MODE_PRIVATE)
    private val alias = "ham-photo-directory-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun save(text: String) {
        PhotoAppointmentProfile.fromJson(text)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("profile", encoded).commit()) { "Could not save the private directory" }
    }
    fun load(): PhotoAppointmentProfile? = try {
        val raw = Base64.decode(prefs.getString("profile", null) ?: return null, Base64.NO_WRAP)
        require(raw.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        PhotoAppointmentProfile.fromJson(String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8))
    } catch (_: Exception) { null }
}
