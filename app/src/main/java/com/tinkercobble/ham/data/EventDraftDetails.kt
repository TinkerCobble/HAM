package com.tinkercobble.ham.data

/** User-entered optional event fields, normalized before any provider write. */
data class EventDraftDetails(val description: String, val location: String) {
    companion object {
        fun of(description: String, location: String): EventDraftDetails {
            fun clean(value: String, max: Int, name: String): String {
                val text = value.replace("\r\n", "\n").replace('\r', '\n')
                    .replace('\t', ' ').trim()
                require(text.length <= max && text.none { it.code < 32 && it != '\n' ||
                    it.code == 127 }) { "$name contains unsupported text or is too long" }
                return text
            }
            return EventDraftDetails(clean(description, 65_536, "Description"),
                clean(location, 8_192, "Location"))
        }
    }
}
