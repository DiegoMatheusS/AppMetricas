package br.com.medidor.app.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class DxfDrawingUnit(val label: String, val meters: Double) {
    MILLIMETER("Milímetros", 0.001),
    CENTIMETER("Centímetros", 0.01),
    METER("Metros", 1.0),
    INCH("Polegadas", 0.0254),
    FOOT("Pés", 0.3048)
}

/**
 * Uses exactly the same raster transformation for visual preview and PlanWall
 * endpoints. Drawing units must be explicitly selected by the user.
 */
data class DxfDrawingLayout(
    val width: Int, val height: Int,
    val minX: Double, val minY: Double,
    val maxX: Double, val maxY: Double,
    val pixelsPerDrawingUnit: Double
) {
    fun point(x: Double, y: Double): PlanPoint {
        val px = 80 + (x - minX) * pixelsPerDrawingUnit
        val py = height - 80 - (y - minY) * pixelsPerDrawingUnit
        return PlanPoint((px / width).toFloat().coerceIn(0f,1f),
            (py / height).toFloat().coerceIn(0f,1f))
    }
    fun imageX(x: Double): Float =
        (80 + (x - minX) * pixelsPerDrawingUnit).toFloat()
    fun imageY(y: Double): Float =
        (height - 80 - (y - minY) * pixelsPerDrawingUnit).toFloat()
}

data class DxfWallConversion(
    val walls: List<PlanWall>,
    val referenceStart: PlanPoint,
    val referenceEnd: PlanPoint,
    val referenceMeters: Double
)

object DxfWallImport {
    const val MAX_WALLS = 300

    fun layout(lines: List<DxfLine>): DxfDrawingLayout {
        require(lines.isNotEmpty() && lines.size <= 1500)
        val minX = lines.minOf { min(it.x1,it.x2) }
        val maxX = lines.maxOf { max(it.x1,it.x2) }
        val minY = lines.minOf { min(it.y1,it.y2) }
        val maxY = lines.maxOf { max(it.y1,it.y2) }
        val spanX = maxX - minX
        val spanY = maxY - minY
        require(spanX.isFinite() && spanY.isFinite() && max(spanX,spanY) > 1e-6) {
            "DXF sem extensão geométrica válida."
        }
        val scale = 1840.0 / max(spanX,spanY)
        val width = (spanX * scale + 160).toInt().coerceIn(160,2048)
        val height = (spanY * scale + 160).toInt().coerceIn(160,2048)
        return DxfDrawingLayout(width,height,minX,minY,maxX,maxY,scale)
    }

    fun layers(lines: List<DxfLine>): Map<String, Int> =
        lines.groupingBy { it.layer }.eachCount().toSortedMap()

    fun convert(
        lines: List<DxfLine>,
        layout: DxfDrawingLayout,
        layer: String,
        unit: DxfDrawingUnit
    ): DxfWallConversion {
        val selected = lines.filter { it.layer == layer }
        require(selected.isNotEmpty()) { "Camada de paredes não encontrada." }
        require(selected.size <= MAX_WALLS) {
            "Esta camada possui mais de $MAX_WALLS segmentos; selecione ou simplifique outra camada."
        }
        val walls = selected.mapNotNull {
            if (!listOf(it.x1,it.x2,it.y1,it.y2).all(Double::isFinite)) return@mapNotNull null
            val physicalLength = kotlin.math.hypot(it.x2-it.x1,it.y2-it.y1) * unit.meters
            if (physicalLength < 0.02 || physicalLength > 1000.0) return@mapNotNull null
            PlanWall(layout.point(it.x1,it.y1),layout.point(it.x2,it.y2))
        }
        require(walls.isNotEmpty()) {
            "Nenhuma parede com comprimento entre 2 cm e 1000 m na camada."
        }
        val spanX = layout.maxX-layout.minX
        val spanY = layout.maxY-layout.minY
        val horizontal = spanX >= spanY
        val referenceMeters = (if (horizontal) spanX else spanY) * unit.meters
        require(referenceMeters.isFinite() && referenceMeters in 0.1..10000.0) {
            "Escala DXF fora da faixa válida; confira a unidade do desenho."
        }
        val a = layout.point(layout.minX, layout.minY)
        val b = if (horizontal) layout.point(layout.maxX,layout.minY)
            else layout.point(layout.minX,layout.maxY)
        return DxfWallConversion(walls,a,b,referenceMeters)
    }
}
