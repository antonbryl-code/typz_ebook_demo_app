package com.example.typzyreader

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Decodes a book's cover image, shrunk to about [maxWidthPx] by [maxHeightPx] since .typz files
 * are untrusted and their images can be huge. Null if it can't be decoded, e.g. isn't an image.
 */
fun decodeCover(data: ByteArray, maxWidthPx: Int, maxHeightPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = coverSampleSize(bounds.outWidth, bounds.outHeight, maxWidthPx, maxHeightPx)
    }
    return try {
        BitmapFactory.decodeByteArray(data, 0, data.size, options)?.asImageBitmap()
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
