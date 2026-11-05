package br.com.medidor.app.domain

import kotlin.math.abs

/** Intersections through the polygon centre avoid measuring a box diagonal as a tank diameter. */
internal object AutomaticContourGeometry {
    fun axisEndpoints(polygon: List<Pair<Double, Double>>, vertical: Boolean): List<Pair<Double, Double>>? {
        if (polygon.size !in 3..12 || polygon.any { !it.first.isFinite() || !it.second.isFinite() }) return null
        val cross = polygon.sumOf { if (vertical) it.first else it.second } / polygon.size
        val positions = mutableListOf<Double>()
        for (index in polygon.indices) {
            val a = polygon[index]
            val b = polygon[(index + 1) % polygon.size]
            val ac = if (vertical) a.first else a.second
            val bc = if (vertical) b.first else b.second
            if (abs(bc - ac) < 1e-8 || cross < minOf(ac, bc) || cross >= maxOf(ac, bc)) continue
            val fraction = (cross - ac) / (bc - ac)
            positions += if (vertical) a.second + fraction * (b.second - a.second)
                else a.first + fraction * (b.first - a.first)
        }
        if (positions.size < 2) return null
        val low = positions.min()
        val high = positions.max()
        if (high - low < 1e-6) return null
        return if (vertical) listOf(cross to low, cross to high) else listOf(low to cross, high to cross)
    }
}
