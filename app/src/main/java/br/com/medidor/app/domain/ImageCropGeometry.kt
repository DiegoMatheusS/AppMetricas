package br.com.medidor.app.domain

/**
 * Transform image-analysis crop pixels from an upright image to the original
 * YUV buffer. CameraX supplies the crop and clockwise rotation metadata.
 */
data class ImageCropGeometry(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int
) {
    init {
        require(left >= 0 && top >= 0 && width > 0 && height > 0)
        require(rotationDegrees in setOf(0,90,180,270))
    }

    val uprightWidth: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
    val uprightHeight: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

    fun rawCoordinates(x: Int, y: Int): Pair<Int,Int> {
        require(x in 0 until uprightWidth && y in 0 until uprightHeight)
        return when (rotationDegrees) {
            90 -> (left + y) to (top + height - 1 - x)
            180 -> (left + width - 1 - x) to (top + height - 1 - y)
            270 -> (left + width - 1 - y) to (top + x)
            else -> (left + x) to (top + y)
        }
    }
}

/** PreviewView FILL_CENTER with a shared CameraX ViewPort fills the overlay. */
object LiveViewportMapping {
    fun point(touchX: Float, touchY: Float, viewWidth: Float, viewHeight: Float):
        Pair<Float,Float>? {
        if (!touchX.isFinite() || !touchY.isFinite() ||
            viewWidth <= 0 || viewHeight <= 0 ||
            touchX < 0f || touchX > viewWidth ||
            touchY < 0f || touchY > viewHeight) return null
        return (touchX / viewWidth).coerceIn(0f,1f) to
            (touchY / viewHeight).coerceIn(0f,1f)
    }
}
