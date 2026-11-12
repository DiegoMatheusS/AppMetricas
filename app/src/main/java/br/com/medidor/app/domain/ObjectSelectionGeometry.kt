package br.com.medidor.app.domain

import kotlin.math.hypot

internal enum class MeasurementPointSize(val label: String, val radiusDp: Float, val hitRadiusDp: Float) {
    FINE("Fino", 4.5f, 20f), NORMAL("Médio", 9f, 28f), LARGE("Grande", 14f, 40f)
}

internal data class ObjectOutline(val corners: List<NormalizedPoint>, val trace: List<NormalizedPoint> = corners) {
    init { require(corners.size in 2..12 && trace.size in 2..2048) }
}

internal object ObjectSelectionGeometry {
    private fun segmentDistance(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val fraction = if (dx * dx + dy * dy < 1e-12) 0.0 else
            (((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return hypot(x - ax - dx * fraction, y - ay - dy * fraction)
    }

    fun contains(outline: ObjectOutline, point: NormalizedPoint): Boolean {
        if (outline.corners.size < 3) return false
        var inside = false
        val p = outline.trace
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            if ((a.y > point.y) != (b.y > point.y) &&
                point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        }
        return inside
    }

    fun areaPixels(outline: ObjectOutline, width: Int, height: Int): Double? {
        if (outline.trace.size < 3 || width <= 0 || height <= 0) return null
        val p = outline.trace
        val twice = p.indices.sumOf { i ->
            val a = p[i]; val b = p[(i+1)%p.size]
            a.x.toDouble()*b.y - b.x.toDouble()*a.y
        }
        return (kotlin.math.abs(twice)*width*height/2).takeIf { it.isFinite() && it >= 1 }
    }

    fun hits(outlines: List<ObjectOutline>, point: NormalizedPoint, width: Int, height: Int,
             radiusPixels: Double, areaOnly: Boolean = false): List<ObjectOutline> {
        require(width > 0 && height > 0 && radiusPixels.isFinite() && radiusPixels >= 0)
        fun area(outline: ObjectOutline): Double = areaPixels(outline, width, height) ?: 0.0
        return outlines.filter { outline ->
            (!areaOnly || outline.corners.size >= 3) && (contains(outline, point) ||
                outline.trace.indices.any { index ->
                    val a = outline.trace[index]; val b = outline.trace[(index + 1) % outline.trace.size]
                    segmentDistance(point.x * width.toDouble(), point.y * height.toDouble(),
                        a.x * width.toDouble(), a.y * height.toDouble(), b.x * width.toDouble(), b.y * height.toDouble()) <= radiusPixels
                })
        }.distinctBy { outline -> outline.corners.map { (it.x * width).toInt() to (it.y * height).toInt() }.toSet() }
            .sortedWith(compareBy<ObjectOutline> { if (contains(it, point)) 0 else 1 }.thenBy { outline ->
                if (outline.corners.size == 2) PhotoCalibration.distancePixels(outline.corners[0], outline.corners[1], width, height)
                else area(outline)
            })
    }

    fun ruler(outline: ObjectOutline, width: Int, height: Int): List<NormalizedPoint> {
        val points = outline.corners
        if (points.size == 2) return points
        val index = points.indices.maxBy { PhotoCalibration.distancePixels(points[it], points[(it + 1) % points.size], width, height) }
        return listOf(points[index], points[(index + 1) % points.size])
    }
}
