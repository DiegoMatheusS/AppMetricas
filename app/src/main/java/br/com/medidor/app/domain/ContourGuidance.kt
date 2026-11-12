package br.com.medidor.app.domain

import kotlin.math.abs

/**
 * Optional user-chosen 2D search region. This limits flood fill, but does not
 * identify objects or create a depth/metric measurement.
 */
data class ContourSearchBounds(
    val left: Int, val top: Int, val right: Int, val bottom: Int
) {
    init { require(left >= 0 && top >= 0 && right >= left && bottom >= top) }
    val area: Int get() = (right-left+1)*(bottom-top+1)
    fun contains(x: Int, y: Int) = x in left..right && y in top..bottom
}

data class ContourNormalizedArea(
    val left: Float, val top: Float, val right: Float, val bottom: Float
) {
    init {
        require(listOf(left,top,right,bottom).all { it.isFinite() && it in 0f..1f })
        require(left < right && top < bottom)
    }

    fun contains(point: NormalizedPoint): Boolean =
        point.x in left..right && point.y in top..bottom

    fun pixels(width: Int, height: Int): ContourSearchBounds {
        require(width in 8..512 && height in 8..512)
        return ContourSearchBounds(
            (left*(width-1)).toInt().coerceIn(0,width-1),
            (top*(height-1)).toInt().coerceIn(0,height-1),
            (right*(width-1)).toInt().coerceIn(0,width-1),
            (bottom*(height-1)).toInt().coerceIn(0,height-1)
        )
    }

    companion object {
        fun fromCorners(first: NormalizedPoint, second: NormalizedPoint):
            ContourNormalizedArea? {
            val x0 = minOf(first.x,second.x)
            val y0 = minOf(first.y,second.y)
            val x1 = maxOf(first.x,second.x)
            val y1 = maxOf(first.y,second.y)
            if (listOf(x0,x1,y0,y1).any { !it.isFinite() || it !in 0f..1f })
                return null
            if (x1-x0 < .07f || y1-y0 < .07f) return null
            return ContourNormalizedArea(x0,y0,x1,y1)
        }
    }
}

enum class ContourVisualRating(val display: String) {
    LOW("Baixa"),
    CAUTION("Moderada"),
    STABLE("Aparentemente estável")
}

data class ContourVisualAssessment(
    val rating: ContourVisualRating,
    val explanation: String
)

/** Descriptive heuristic only, not a probability of correct object detection. */
object ContourVisualQuality {
    fun assess(
        result: PlanarContourResult,
        previous: PlanarContourResult? = null,
        bounds: ContourSearchBounds? = null
    ): ContourVisualAssessment {
        val x0 = bounds?.left ?: 0
        val y0 = bounds?.top ?: 0
        val x1 = bounds?.right ?: result.width-1
        val y1 = bounds?.bottom ?: result.height-1
        val border = result.minX <= x0+1 || result.maxX >= x1-1 ||
            result.minY <= y0+1 || result.maxY >= y1-1
        if (border) return ContourVisualAssessment(ContourVisualRating.LOW,
            "Contorno encosta no limite visível; amplie a região ou toque novamente.")
        val bboxArea = (result.maxX-result.minX+1)*(result.maxY-result.minY+1)
        val coverage = result.pixelsInside.toDouble() / bboxArea.coerceAtLeast(1)
        if (coverage < .35) return ContourVisualAssessment(ContourVisualRating.LOW,
            "Região fragmentada ou pouco preenchida; confira as bordas.")
        if (previous != null) {
            val before = previous.pixelsInside.toDouble() /
                (previous.width*previous.height).coerceAtLeast(1)
            val now = result.pixelsInside.toDouble() /
                (result.width*result.height).coerceAtLeast(1)
            val ratio = if (before > 0) now/before else 0.0
            if (ratio !in .8..1.25) return ContourVisualAssessment(
                ContourVisualRating.CAUTION,
                "Área oscilando entre quadros; resultado aproximado.")
            // A similar pixel count does not imply the same object remains selected.
            val previousCenterX = (previous.minX + previous.maxX) / (2.0 * previous.width)
            val previousCenterY = (previous.minY + previous.maxY) / (2.0 * previous.height)
            val centerX = (result.minX + result.maxX) / (2.0 * result.width)
            val centerY = (result.minY + result.maxY) / (2.0 * result.height)
            if (abs(centerX - previousCenterX) > .12 ||
                abs(centerY - previousCenterY) > .12) {
                return ContourVisualAssessment(ContourVisualRating.CAUTION,
                    "Contorno deslocou entre quadros; confirme o objeto selecionado.")
            }
            val oldWidth = (previous.maxX - previous.minX + 1).toDouble() / previous.width
            val oldHeight = (previous.maxY - previous.minY + 1).toDouble() / previous.height
            val newWidth = (result.maxX - result.minX + 1).toDouble() / result.width
            val newHeight = (result.maxY - result.minY + 1).toDouble() / result.height
            if (newWidth / oldWidth !in .75..1.33 ||
                newHeight / oldHeight !in .75..1.33) {
                return ContourVisualAssessment(ContourVisualRating.CAUTION,
                    "Formato do contorno mudou entre quadros; confira a seleção.")
            }
        }
        return if (coverage < .65) ContourVisualAssessment(
            ContourVisualRating.CAUTION, "Região irregular; confira a seleção.")
        else ContourVisualAssessment(
            ContourVisualRating.STABLE,
            "Aparência consistente neste quadro; não comprova medida correta.")
    }
}
