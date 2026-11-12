package br.com.medidor.app.domain

import kotlin.math.min

/** The image, overlays, taps and pinch gestures all use this same transform. */
internal data class PhotoViewport(
    val viewWidth: Float, val viewHeight: Float, val imageWidth: Int, val imageHeight: Int,
    val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f
) {
    init {
        require(viewWidth.isFinite() && viewHeight.isFinite() && viewWidth > 0 && viewHeight > 0 && imageWidth > 0 && imageHeight > 0)
        require(zoom.isFinite() && zoom in 1f..8f && panX.isFinite() && panY.isFinite())
    }
    val fit = min(viewWidth / imageWidth, viewHeight / imageHeight)
    val width = imageWidth * fit * zoom
    val height = imageHeight * fit * zoom
    val left = (viewWidth - width) / 2 + panX
    val top = (viewHeight - height) / 2 + panY

    fun point(x: Float, y: Float): NormalizedPoint? {
        if (!x.isFinite() || !y.isFinite() || x !in 0f..viewWidth || y !in 0f..viewHeight) return null
        val nx = (x - left) / width; val ny = (y - top) / height
        return if (nx in 0f..1f && ny in 0f..1f) NormalizedPoint(nx, ny) else null
    }
    fun view(point: NormalizedPoint): Pair<Float, Float> = left + point.x * width to top + point.y * height

    fun transform(factor: Float, focusX: Float, focusY: Float, moveX: Float = 0f, moveY: Float = 0f): PhotoViewport {
        if (!factor.isFinite() || factor <= 0 || !focusX.isFinite() || !focusY.isFinite() ||
            !moveX.isFinite() || !moveY.isFinite()) return this
        val next = (zoom * factor).coerceIn(1f, 8f)
        val ratio = next / zoom
        val x = focusX - viewWidth / 2 - (focusX - viewWidth / 2 - panX) * ratio + moveX
        val y = focusY - viewHeight / 2 - (focusY - viewHeight / 2 - panY) * ratio + moveY
        val maxX = ((imageWidth * fit * next - viewWidth) / 2).coerceAtLeast(0f)
        val maxY = ((imageHeight * fit * next - viewHeight) / 2).coerceAtLeast(0f)
        return copy(zoom = next, panX = x.coerceIn(-maxX, maxX), panY = y.coerceIn(-maxY, maxY))
    }
}
