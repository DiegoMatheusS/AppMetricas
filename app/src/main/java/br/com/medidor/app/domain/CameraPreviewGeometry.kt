package br.com.medidor.app.domain

import kotlin.math.max

/**
 * Maps the upright CameraX analysis crop to PreviewView.ScaleType.FILL_CENTER.
 * Also matches Compose Image(ContentScale.Crop) used for a frozen count.
 *
 * ViewPort normally gives both streams the same aspect ratio, but a device
 * may round the crop dimensions differently. Never stretch the overlay.
 */
internal class CameraPreviewGeometry(
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val viewWidth: Float,
    private val viewHeight: Float
) {
    init {
        require(frameWidth > 0 && frameHeight > 0)
        require(viewWidth.isFinite() && viewWidth > 0f)
        require(viewHeight.isFinite() && viewHeight > 0f)
    }

    private val scale = max(viewWidth / frameWidth, viewHeight / frameHeight)
    private val offsetX = (viewWidth - frameWidth * scale) / 2f
    private val offsetY = (viewHeight - frameHeight * scale) / 2f

    fun imagePoint(viewX: Float, viewY: Float): Pair<Float, Float>? {
        if (!viewX.isFinite() || !viewY.isFinite() ||
            viewX !in 0f..viewWidth || viewY !in 0f..viewHeight) return null
        val imageX = (viewX - offsetX) / scale
        val imageY = (viewY - offsetY) / scale
        return if (imageX in 0f..frameWidth.toFloat() &&
            imageY in 0f..frameHeight.toFloat()) imageX to imageY else null
    }

    fun viewPoint(imageX: Float, imageY: Float): Pair<Float, Float> =
        (offsetX + imageX * scale) to (offsetY + imageY * scale)

    fun normalizedImagePoint(viewX: Float, viewY: Float): Pair<Float, Float>? =
        imagePoint(viewX, viewY)?.let { (x, y) -> x / frameWidth to y / frameHeight }

    fun normalizedViewPoint(nx: Float, ny: Float): Pair<Float, Float> =
        viewPoint(nx * frameWidth, ny * frameHeight)
}
