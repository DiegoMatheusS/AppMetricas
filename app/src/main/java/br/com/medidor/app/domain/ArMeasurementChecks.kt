package br.com.medidor.app.domain

import kotlin.math.abs

internal object ArMeasurementChecks {
    /** Select a long edge close to the pending one when equivalent rectangle edges exchange order. */
    fun rulerEdge(points: List<Point3d>, preferred: List<Point3d>?): List<Point3d>? {
        if (points.size !in 3..12 || points.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) return null
        val edges = points.indices.map { listOf(points[it], points[(it + 1) % points.size]) }
        val longest = edges.maxOf { MeshGeometry.distance(it[0], it[1]) }
        if (longest < .01) return null
        val eligible = edges.filter { MeshGeometry.distance(it[0], it[1]) >= longest * .95 }
        if (preferred?.size != 2) return eligible.first()
        return eligible.minBy { edge ->
            minOf(MeshGeometry.distance(edge[0], preferred[0]) + MeshGeometry.distance(edge[1], preferred[1]),
                MeshGeometry.distance(edge[1], preferred[0]) + MeshGeometry.distance(edge[0], preferred[1]))
        }
    }

    fun tankSection(ends: List<Point3d>, center: Point3d, vertical: Boolean): Boolean {
        if (ends.size != 2 || (ends + center).any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) return false
        val length = MeshGeometry.distance(ends[0], ends[1])
        if (length !in .02..30.0) return false
        val verticalFraction = abs(ends[0].y - ends[1].y) / length
        if (if (vertical) verticalFraction < .85 else verticalFraction > .35) return false
        return ends.all { MeshGeometry.distance(it, center) <= length * .85 }
    }
}
