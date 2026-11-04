package br.com.medidor.app.domain

import kotlin.math.abs

/** Planar image-space measurements. Physical results require a coplanar reference. */
object PointMeasurementGeometry {
    /** Area of a simple polygon in image pixels². Returns null for invalid polygons. */
    fun polygonAreaPixels2(
        corners: List<NormalizedPoint>,
        imageWidth: Int,
        imageHeight: Int
    ): Double? {
        if (imageWidth <= 0 || imageHeight <= 0 || corners.size !in 3..12) return null
        val pixelPoints = corners.map { Pair(it.x.toDouble() * imageWidth, it.y.toDouble() * imageHeight) }
        if (!simplePolygon(pixelPoints)) return null
        var twice = 0.0
        for (i in pixelPoints.indices) {
            val a = pixelPoints[i]
            val b = pixelPoints[(i + 1) % pixelPoints.size]
            twice += a.first * b.second - b.first * a.second
        }
        return (abs(twice) / 2.0).takeIf { it.isFinite() && it >= 1.0 }
    }

    fun areaSquareMillimeters(
        polygonAreaPixels2: Double,
        referenceMillimeters: Double,
        referencePixels: Double
    ): Double? {
        if (!polygonAreaPixels2.isFinite() || polygonAreaPixels2 < 0 ||
            !referenceMillimeters.isFinite() || referenceMillimeters <= 0 ||
            !referencePixels.isFinite() || referencePixels <= 0) return null
        val mmPerPixel = referenceMillimeters / referencePixels
        return (polygonAreaPixels2 * mmPerPixel * mmPerPixel)
            .takeIf { it.isFinite() }
    }

    private fun simplePolygon(vertices: List<Pair<Double, Double>>): Boolean {
        fun cross(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>) =
            (b.first - a.first) * (c.second - a.second) -
                (b.second - a.second) * (c.first - a.first)
        fun intersects(a: Pair<Double, Double>, b: Pair<Double, Double>,
                       c: Pair<Double, Double>, d: Pair<Double, Double>): Boolean {
            val c1 = cross(a, b, c)
            val c2 = cross(a, b, d)
            val c3 = cross(c, d, a)
            val c4 = cross(c, d, b)
            // Collinear/touching nonadjacent edges are invalid as well.
            fun on(p: Pair<Double, Double>, q: Pair<Double, Double>,
                   r: Pair<Double, Double>): Boolean =
                abs(cross(p,q,r)) < 1e-8 &&
                    r.first in minOf(p.first, q.first)..maxOf(p.first, q.first) &&
                    r.second in minOf(p.second, q.second)..maxOf(p.second, q.second)
            if (abs(c1) < 1e-8 && on(a,b,c)) return true
            if (abs(c2) < 1e-8 && on(a,b,d)) return true
            if (abs(c3) < 1e-8 && on(c,d,a)) return true
            if (abs(c4) < 1e-8 && on(c,d,b)) return true
            return (c1 > 0) != (c2 > 0) && (c3 > 0) != (c4 > 0)
        }
        for (i in vertices.indices) {
            val a = vertices[i]
            val b = vertices[(i + 1) % vertices.size]
            if (abs(a.first - b.first) + abs(a.second - b.second) < 1e-6) return false
            for (j in i + 1 until vertices.size) {
                if (j == i + 1 || (i == 0 && j == vertices.lastIndex)) continue
                val c = vertices[j]
                val d = vertices[(j + 1) % vertices.size]
                if (intersects(a,b,c,d)) return false
            }
        }
        return true
    }
}
