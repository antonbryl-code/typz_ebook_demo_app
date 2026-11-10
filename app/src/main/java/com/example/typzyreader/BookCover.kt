package com.example.typzyreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt

/**
 * Decodes a book's cover image, shrunk to about [maxWidthPx] by [maxHeightPx] since .typz files
 * are untrusted and their images can be huge. Null if it can't be decoded, e.g. isn't an image.
 */
fun decodeCover(data: ByteArray, maxWidthPx: Int, maxHeightPx: Int): ImageBitmap? =
    decodeCoverBitmap(data, maxWidthPx, maxHeightPx)?.asImageBitmap()

/** A book's cover image scaled to [heightPx] tall, e.g. to keep with a recent file. */
fun coverThumbnail(data: ByteArray, heightPx: Int): Bitmap? {
    // Wide enough for any but a panorama of a cover.
    val decoded = decodeCoverBitmap(data, heightPx, heightPx) ?: return null
    val widthPx = (decoded.width * heightPx.toFloat() / decoded.height).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(decoded, widthPx, heightPx, true)
}

private fun decodeCoverBitmap(data: ByteArray, maxWidthPx: Int, maxHeightPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = coverSampleSize(bounds.outWidth, bounds.outHeight, maxWidthPx, maxHeightPx)
    }
    return try {
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
    } catch (_: OutOfMemoryError) {
        null
    }
}

/** The largest power of two to shrink an image by that keeps it at least as large as the maximum. */
internal fun coverSampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
    var sampleSize = 1
    while (width / (sampleSize * 2) >= maxWidth && height / (sampleSize * 2) >= maxHeight) sampleSize *= 2
    return sampleSize
}
