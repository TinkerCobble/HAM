package com.tinkercobble.ham

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class EventLocationIntentSpec(
    val action: String,
    val uri: String,
    val chooserTitle: String,
)

object EventLocationIntents {
    private const val VIEW_ACTION = "android.intent.action.VIEW"
    private const val CHOOSER_TITLE = "Open location with"

    fun visibleLocation(location: String): String? =
        location.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    fun spec(location: String): EventLocationIntentSpec? {
        val original = visibleLocation(location) ?: return null
        val encoded = URLEncoder.encode(original, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return EventLocationIntentSpec(VIEW_ACTION, "geo:0,0?q=$encoded", CHOOSER_TITLE)
    }

    fun chooser(location: String): Intent? = spec(location)?.let { spec ->
        val view = Intent(spec.action, Uri.parse(spec.uri))
        Intent.createChooser(view, spec.chooserTitle)
    }

    fun open(context: Context, location: String): Boolean {
        val chooser = chooser(location) ?: return false
        return try {
            context.startActivity(chooser)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
