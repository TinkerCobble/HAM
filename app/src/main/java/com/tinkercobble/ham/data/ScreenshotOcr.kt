package com.tinkercobble.ham.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object ScreenshotOcr {
    private const val LIMIT = 10L * 1024 * 1024

    /** Caller owns the private temporary copy. Original content URI is never changed. */
    suspend fun copy(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        // Some sharesheet providers omit MIME on the URI despite an image/* intent.
        // The bounded file and bitmap decoder below validate actual image content.
        val folder = File(context.filesDir, "screenshot-drafts").apply { mkdirs() }
        val file = File(folder, "${UUID.randomUUID()}.img")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= LIMIT) { "Image exceeds 10 MB" }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("Could not open image")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000) { "Unsupported image" }
            file
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    suspend fun read(context: Context, file: File): String = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val scale = generateSequence(1) { it * 2 }.first { bounds.outWidth / it <= 2400 && bounds.outHeight / it <= 2400 }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = scale })
                ?: error("Could not decode image")
            val base = File(context.filesDir, "ocr")
            val tessdata = File(base, "tessdata").apply { mkdirs() }
            val trained = File(tessdata, "eng.traineddata")
            if (!trained.exists() || trained.length() != 4_113_088L) {
                context.assets.open("tessdata/eng.traineddata").use { src -> trained.outputStream().use(src::copyTo) }
            }
            val tess = TessBaseAPI()
            try {
                check(tess.init(base.absolutePath, "eng")) { "OCR could not start" }
                tess.setImage(bitmap)
                tess.getUTF8Text().orEmpty()
            } finally {
                tess.recycle()
                bitmap.recycle()
            }
    }
}
