package br.com.medidor.app.vision

import android.graphics.Bitmap
import br.com.medidor.app.domain.NormalizedPoint
import br.com.medidor.app.domain.ObjectOutline
import kotlin.math.roundToInt

/** Visible high-resolution crops recover small edges without changing their photo coordinates. */
internal object PhotoObjectScanner {
    fun scan(photo: Bitmap, left: Float = 0f, top: Float = 0f, right: Float = 1f, bottom: Float = 1f): List<ObjectOutline> {
        val x = (left * photo.width).roundToInt().coerceIn(0, photo.width - 1)
        val y = (top * photo.height).roundToInt().coerceIn(0, photo.height - 1)
        val width = ((right * photo.width).roundToInt().coerceIn(x + 1, photo.width) - x)
        val height = ((bottom * photo.height).roundToInt().coerceIn(y + 1, photo.height) - y)
        if (width < 8 || height < 8) return emptyList()
        val crop = Bitmap.createBitmap(photo, x, y, width, height)
        val factor = minOf(1.0, 512.0 / maxOf(width, height))
        val sampled = Bitmap.createScaledBitmap(crop, (width * factor).roundToInt().coerceAtLeast(8),
            (height * factor).roundToInt().coerceAtLeast(8), true)
        try {
            val pixels = IntArray(sampled.width * sampled.height)
            sampled.getPixels(pixels, 0, sampled.width, 0, 0, sampled.width, sampled.height)
            return VisualBoundaryDetector.findAll(pixels, sampled.width, sampled.height).map { boundary ->
                fun point(point: br.com.medidor.app.domain.ContourPixel) = NormalizedPoint(
                    ((x + (point.x + .5f) * width / sampled.width) / photo.width).coerceIn(0f,1f),
                    ((y + (point.y + .5f) * height / sampled.height) / photo.height).coerceIn(0f,1f))
                ObjectOutline(boundary.corners.map(::point),boundary.trace.map(::point))
            }
        } finally {
            if (sampled !== crop && sampled !== photo) sampled.recycle()
            if (crop !== photo) crop.recycle()
        }
    }
}
