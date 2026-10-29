package com.tinkercobble.ham.data

/** RFC 5545 content-line folding. Never split a UTF-8 code point across physical lines. */
object IcsContentLines {
    fun fold(line: String): String {
        val out = StringBuilder()
        var width = 0
        var offset = 0
        while (offset < line.length) {
            val point = line.codePointAt(offset)
            val part = String(Character.toChars(point))
            val bytes = part.toByteArray(Charsets.UTF_8).size
            if (width + bytes > 75) {
                out.append("\r\n ")
                width = 1
            }
            out.append(part)
            width += bytes
            offset += Character.charCount(point)
        }
        return out.toString()
    }
}
