package br.com.medidor.app.ui

import androidx.camera.core.ImageProxy
import br.com.medidor.app.domain.ImageCropGeometry
import kotlin.math.max

/**
 * Samples only the CameraX cropRect (shared with PreviewView through ViewPort),
 * rotating the cropped YUV frame upright before color conversion. The cropped
 * image is downsampled to at most 192 pixels on its longer side.
 */
internal data class LiveRgbFrame(val width: Int, val height: Int, val pixels: IntArray)

internal object LiveCameraFrameSampler {
    fun sample(image: ImageProxy): LiveRgbFrame? {
        val planes = image.planes
        if (planes.size < 3) return null
        val crop = image.cropRect
        if (crop.left < 0 || crop.top < 0 || crop.right > image.width ||
            crop.bottom > image.height || crop.width() < 8 || crop.height() < 8) return null
        val geometry = try {
            ImageCropGeometry(crop.left, crop.top, crop.width(), crop.height(),
                image.imageInfo.rotationDegrees)
        } catch (_: IllegalArgumentException) { return null }
        val scale = 192.0 / max(geometry.uprightWidth,geometry.uprightHeight)
        val width = (geometry.uprightWidth * scale).toInt().coerceIn(8,192)
        val height = (geometry.uprightHeight * scale).toInt().coerceIn(8,192)
        val output = IntArray(width * height)
        val luma = planes[0]
        val u = planes[1]
        val v = planes[2]
        val yBuffer = luma.buffer
        val uBuffer = u.buffer
        val vBuffer = v.buffer
        try {
            for (row in 0 until height) for (col in 0 until width) {
                val uprightX = ((col + .5) * geometry.uprightWidth / width)
                    .toInt().coerceIn(0,geometry.uprightWidth-1)
                val uprightY = ((row + .5) * geometry.uprightHeight / height)
                    .toInt().coerceIn(0,geometry.uprightHeight-1)
                val (sourceX,sourceY) = geometry.rawCoordinates(uprightX,uprightY)
                val yValue = yBuffer.get(sourceY*luma.rowStride +
                    sourceX*luma.pixelStride).toInt() and 255
                val chromaU = (uBuffer.get((sourceY/2)*u.rowStride +
                    (sourceX/2)*u.pixelStride).toInt() and 255)-128
                val chromaV = (vBuffer.get((sourceY/2)*v.rowStride +
                    (sourceX/2)*v.pixelStride).toInt() and 255)-128
                val c = (yValue-16).coerceAtLeast(0)
                val red = ((298*c+409*chromaV+128) shr 8).coerceIn(0,255)
                val green = ((298*c-100*chromaU-208*chromaV+128) shr 8)
                    .coerceIn(0,255)
                val blue = ((298*c+516*chromaU+128) shr 8).coerceIn(0,255)
                output[row*width+col] =
                    -0x1000000 or (red shl 16) or (green shl 8) or blue
            }
        } catch (_: IndexOutOfBoundsException) {
            // Some YUV devices expose unusual plane capacities. Drop the frame
            // rather than reading invalid pixels or crashing the camera.
            return null
        }
        return LiveRgbFrame(width,height,output)
    }
}
