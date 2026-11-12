package br.com.medidor.app.domain

import kotlin.math.abs
import kotlin.math.hypot

/** Central sections follow an arbitrary display direction, including a tilted camera. */
internal object AutomaticContourGeometry {
    fun axisEndpoints(polygon: List<Pair<Double, Double>>, vertical: Boolean): List<Pair<Double, Double>>? =
        axisEndpoints(polygon, if (vertical) 0.0 to 1.0 else 1.0 to 0.0)

    fun axisEndpoints(
        polygon: List<Pair<Double, Double>>, direction: Pair<Double, Double>
    ): List<Pair<Double, Double>>? {
        if (polygon.size !in 3..12 || polygon.any { !it.first.isFinite() || !it.second.isFinite() }) return null
        val norm = hypot(direction.first, direction.second)
        if (!norm.isFinite() || norm < 1e-8) return null
        val dx = direction.first / norm
        val dy = direction.second / norm
        val (cx, cy) = centroid(polygon) ?: return null
        val positions = mutableListOf<Double>()
        for (index in polygon.indices) {
            val a = polygon[index]
            val b = polygon[(index + 1) % polygon.size]
            val an = -(a.first - cx) * dy + (a.second - cy) * dx
            val bn = -(b.first - cx) * dy + (b.second - cy) * dx
            if (abs(bn - an) < 1e-8 || minOf(an, bn) > 0 || maxOf(an, bn) <= 0) continue
            val fraction = -an / (bn - an)
            val x = a.first + fraction * (b.first - a.first)
            val y = a.second + fraction * (b.second - a.second)
            positions += (x - cx) * dx + (y - cy) * dy
        }
        val ordered = positions.sorted()
        if (ordered.size < 2 || ordered.size % 2 != 0) return null
        // Select a filled interval containing the centroid, preserving concave gaps.
        for (index in ordered.indices step 2) {
            val low = ordered[index]
            val high = ordered[index + 1]
            if (low <= 0 && high >= 0 && high - low > 1e-6)
                return listOf((cx + dx * low) to (cy + dy * low), (cx + dx * high) to (cy + dy * high))
        }
        return null
    }
    fun centroid(polygon: List<Pair<Double, Double>>): Pair<Double, Double>? {
        if (polygon.size !in 3..12 || polygon.any { !it.first.isFinite() || !it.second.isFinite() }) return null
        var twiceArea = 0.0
        var weightedX = 0.0
        var weightedY = 0.0
        for (index in polygon.indices) {
            val a = polygon[index]
            val b = polygon[(index + 1) % polygon.size]
            val cross = a.first * b.second - b.first * a.second
            twiceArea += cross
            weightedX += (a.first + b.first) * cross
            weightedY += (a.second + b.second) * cross
        }
        if (!twiceArea.isFinite() || abs(twiceArea) < 1e-8) return null
        return weightedX / (3 * twiceArea) to weightedY / (3 * twiceArea)
    }

}
