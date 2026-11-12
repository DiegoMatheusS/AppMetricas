package br.com.medidor.app.ui

import android.media.Image

internal object ArCameraImageSampler {
    /** Sensor orientation is retained for ARCore IMAGE_PIXELS coordinate conversion. */
    fun sample(image: Image): LiveRgbFrame {
        val width = (192.0 * image.width / maxOf(image.width, image.height)).toInt().coerceAtLeast(8)
        val height = (192.0 * image.height / maxOf(image.width, image.height)).toInt().coerceAtLeast(8)
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val output = IntArray(width * height)
        for (row in 0 until height) for (column in 0 until width) {
            val sx = ((column + .5) * image.width / width).toInt().coerceAtMost(image.width - 1)
            val sy = ((row + .5) * image.height / height).toInt().coerceAtMost(image.height - 1)
            val luma = y.buffer.get(sy * y.rowStride + sx * y.pixelStride).toInt() and 255
            val cb = (u.buffer.get(sy / 2 * u.rowStride + sx / 2 * u.pixelStride).toInt() and 255) - 128
            val cr = (v.buffer.get(sy / 2 * v.rowStride + sx / 2 * v.pixelStride).toInt() and 255) - 128
            val red = (luma + 1.402 * cr).toInt().coerceIn(0, 255)
            val green = (luma - .344136 * cb - .714136 * cr).toInt().coerceIn(0, 255)
            val blue = (luma + 1.772 * cb).toInt().coerceIn(0, 255)
            output[row * width + column] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return LiveRgbFrame(width, height, output)
    }
}
