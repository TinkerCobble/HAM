package com.tinkercobble.ham.data

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class VenueCandidate(val name: String, val address: String, val latitude: Double, val longitude: Double)

/** One explicit, complete address query through the device's geocoder; never autocomplete. */
object AddressVerification {
    fun complete(address: String): Boolean =
        Regex("^\\d{1,6}\\s+[^,]{3,},\\s*[^,]{2,}.*$").matches(address.trim())

    @Suppress("DEPRECATION")
    suspend fun lookup(context: Context, address: String): List<VenueCandidate> = withContext(Dispatchers.IO) {
        require(complete(address)) { "Enter a street address and city before verification" }
        if (!Geocoder.isPresent()) return@withContext emptyList()
        Geocoder(context, Locale.getDefault()).getFromLocationName(address.trim(), 5).orEmpty()
            .mapNotNull { result ->
                val display = result.getAddressLine(0)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                VenueCandidate(result.featureName.orEmpty(), display, result.latitude, result.longitude)
            }.distinctBy { it.address }
    }
}
