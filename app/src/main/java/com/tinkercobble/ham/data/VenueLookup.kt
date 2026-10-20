package com.tinkercobble.ham.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class VenueCandidate(val name: String, val address: String, val latitude: Double, val longitude: Double)

/** One user-initiated lookup, never autocomplete or background geocoding. */
object VenueLookup {
    private var lastQueryAt = 0L
    suspend fun search(query: String): List<VenueCandidate> = withContext(Dispatchers.IO) {
        require(query.trim().length >= 4) { "Enter a venue or address to search" }
        synchronized(this@VenueLookup) {
            val now = System.currentTimeMillis()
            require(now - lastQueryAt >= 1_100) { "Wait a moment before searching again" }
            lastQueryAt = now
        }
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val connection = URL("https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=5&q=$encoded")
            .openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("User-Agent", "HAM-HomeAgendaManager/0.1 (https://tinkercobble.com/ham/)")
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode !in 200..299) error("Address search is unavailable")
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(response)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                VenueCandidate(item.optString("name").ifBlank { item.optString("display_name") },
                    item.optString("display_name"), item.getDouble("lat"), item.getDouble("lon"))
            }
        } finally { connection.disconnect() }
    }
}
