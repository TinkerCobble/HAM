package com.tinkercobble.ham.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Reads a scoped image URI in memory. The selected medical photo is never copied or uploaded. */
object PhotoAppointmentOcr {
    private const val MAX_BYTES = 10 * 1024 * 1024
    private fun bitmap(context: Context, uri: Uri, angle: Float, cropPercent: Int): Bitmap {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= MAX_BYTES) { "Image exceeds 10 MB" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: error("Could not open photo")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000) { "Unsupported photo" }
        val sample = generateSequence(1) { it * 2 }.first {
            bounds.outWidth / it <= 2600 && bounds.outHeight / it <= 2600 }
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Could not decode photo")
        val cut = cropPercent.coerceIn(0, 40)
        val insetX = original.width * cut / 100
        val insetY = original.height * cut / 100
        val cropped = if (cut == 0) original else Bitmap.createBitmap(original, insetX, insetY,
            original.width - insetX * 2, original.height - insetY * 2)
        if (cropped !== original) original.recycle()
        if (angle == 0f) return cropped
        val rotated = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height,
            Matrix().apply { postRotate(angle) }, true)
        if (rotated !== cropped) cropped.recycle()
        return rotated
    }

    suspend fun preview(context: Context, uri: Uri, angle: Float, cropPercent: Int): Bitmap =
        withContext(Dispatchers.IO) { bitmap(context, uri, angle, cropPercent) }

    suspend fun read(context: Context, uri: Uri, angle: Float, cropPercent: Int): String =
        withContext(Dispatchers.IO) {
            val image = bitmap(context, uri, angle, cropPercent)
            val base = File(context.filesDir, "ocr")
            val tessdata = File(base, "tessdata").apply { mkdirs() }
            val trained = File(tessdata, "eng.traineddata")
            if (!trained.exists() || trained.length() != 4_113_088L) {
                context.assets.open("tessdata/eng.traineddata").use { src -> trained.outputStream().use(src::copyTo) }
            }
            val tess = TessBaseAPI()
            try {
                check(tess.init(base.absolutePath, "eng")) { "OCR could not start" }
                tess.setImage(image)
                tess.getUTF8Text().orEmpty()
            } finally {
                tess.recycle()
                image.recycle()
            }
        }
}
