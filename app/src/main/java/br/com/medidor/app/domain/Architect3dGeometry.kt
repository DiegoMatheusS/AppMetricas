package br.com.medidor.app.domain

import kotlin.math.hypot

data class Point3d(val x: Double, val y: Double, val z: Double)
data class WallPiece(val fromMeters: Double, val toMeters: Double, val bottomMeters: Double, val topMeters: Double) {
    val areaM2: Double get() = (toMeters - fromMeters) * (topMeters - bottomMeters)
}
data class ModelFace(val corners: List<Point3d>, val floorIndex: Int, val shade: Int)

/** Geometric 3D surfaces constructed locally from manually traced 2D walls. */
object Architect3dGeometry {
    private const val epsilon = 1e-6
    private const val wallThickness = 0.14

    fun wallPieces(floor: PlanFloor, wallIndex: Int): List<WallPiece> {
        val wall = floor.walls.getOrNull(wallIndex) ?: return emptyList()
        val length = FloorPlanMath.wallLengthMeters(floor, wall) ?: return emptyList()
        val height = floor.wallHeightMeters
        if (!length.isFinite() || length <= epsilon || !height.isFinite() || height <= epsilon) return emptyList()
        val pieces = mutableListOf<WallPiece>()
        val sorted = floor.openings
            .filter { it.wallIndex == wallIndex }
            .mapNotNull { opening ->
                FloorPlanMath.openingRange(floor, opening)?.let { range -> opening to range }
            }.sortedBy { it.second.start }

        var cursor = 0.0
        for ((opening, range) in sorted) {
            // Invalid overlapping stored apertures are ignored to preserve original geometry.
            if (range.start < cursor - epsilon) continue
            if (range.start > cursor + epsilon) {
                pieces += WallPiece(cursor, range.start, 0.0, height)
            }
            if (opening.sillMeters > epsilon) {
                pieces += WallPiece(range.start, range.endInclusive, 0.0, opening.sillMeters)
            }
            val top = opening.sillMeters + opening.heightMeters
            if (top < height - epsilon) {
                pieces += WallPiece(range.start, range.endInclusive, top, height)
            }
            cursor = range.endInclusive
        }
        if (cursor < length - epsilon) {
            pieces += WallPiece(cursor, length, 0.0, height)
        }
        return pieces.filter { it.toMeters - it.fromMeters > epsilon &&
            it.topMeters - it.bottomMeters > epsilon }
    }

    fun wallSurfaceAreaM2(floor: PlanFloor, wallIndex: Int): Double =
        wallPieces(floor, wallIndex).sumOf { it.areaM2 }

    /**
     * Creates 6 faces for each wall solid, with physical 14cm preview thickness.
     * A separate origin is used for each floor to center imported image coordinates.
     */
    fun model(plan: ArchitectPlan, allFloors: Boolean): List<ModelFace> {
        val result = mutableListOf<ModelFace>()
        val floorIndices = if (allFloors) plan.floors.indices.toList()
            else listOf(plan.selectedFloor).filter { it in plan.floors.indices }
        for (index in floorIndices) {
            val floor = plan.floors[index]
            if (!floor.calibrated) continue
            val origin = FloorPlanMath.worldPointMeters(floor, PlanPoint(.5f, .5f)) ?: continue
            val offsetX = floor.offsetXMeters.takeIf { it.isFinite() } ?: 0.0
            val offsetY = floor.offsetYMeters.takeIf { it.isFinite() } ?: 0.0
            val rotation = floor.rotationDegrees.takeIf { it.isFinite() } ?: 0.0
            val zBase = if (allFloors) plan.floors.take(index).sumOf {
                it.wallHeightMeters.takeIf { h -> h.isFinite() && h > 0.0 } ?: 0.0
            } else 0.0
            floor.walls.forEachIndexed { wallIndex, wall ->
                val a = FloorPlanMath.worldPointMeters(floor, wall.start) ?: return@forEachIndexed
                val b = FloorPlanMath.worldPointMeters(floor, wall.end) ?: return@forEachIndexed
                val dx = b.first - a.first
                val dy = b.second - a.second
                val length = hypot(dx, dy)
                if (length <= epsilon) return@forEachIndexed
                val ux = dx / length
                val uy = dy / length
                val perpendicularX = -uy * wallThickness / 2
                val perpendicularY = ux * wallThickness / 2
                fun corner(distance: Double, side: Double, z: Double): Point3d {
                    val localX = a.first - origin.first + ux * distance + perpendicularX * side
                    val localY = a.second - origin.second + uy * distance + perpendicularY * side
                    val rotated = PlanFloorPose.rotate(localX, localY, rotation)
                    return Point3d(rotated.first + offsetX, rotated.second + offsetY, zBase + z)
                }

                for (piece in wallPieces(floor, wallIndex)) {
                    val f = piece.fromMeters
                    val t = piece.toMeters
                    val low = piece.bottomMeters
                    val high = piece.topMeters
                    val p0 = corner(f, -1.0, low)
                    val p1 = corner(t, -1.0, low)
                    val p2 = corner(t, -1.0, high)
                    val p3 = corner(f, -1.0, high)
                    val q0 = corner(f, 1.0, low)
                    val q1 = corner(t, 1.0, low)
                    val q2 = corner(t, 1.0, high)
                    val q3 = corner(f, 1.0, high)
                    result += ModelFace(listOf(p0, p1, p2, p3), index, 0)
                    result += ModelFace(listOf(q1, q0, q3, q2), index, 1)
                    result += ModelFace(listOf(q0, p0, p3, q3), index, 2)
                    result += ModelFace(listOf(p1, q1, q2, p2), index, 3)
                    result += ModelFace(listOf(p3, p2, q2, q3), index, 4)
                    result += ModelFace(listOf(q0, q1, p1, p0), index, 5)
                }
            }
        }
        return result
    }
}
