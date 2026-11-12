package br.com.medidor.app.domain

import kotlin.math.hypot

/**
 * Geometry of a photographed floor plan. The image must be orthogonal (top-down)
 * and the reference/walls must be on the same flat surface. Otherwise the lengths
 * and the preview are approximations, not engineering measurements.
 */
data class PlanPoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
    }
}

data class PlanWall(val start: PlanPoint, val end: PlanPoint)

/** Apertures are intentionally created and positioned by the user, without AI. */
enum class OpeningType(val label: String) {
    DOOR("Porta"), WINDOW("Janela")
}

data class PlanOpening(
    val wallIndex: Int,
    val type: OpeningType,
    val centerFraction: Double,
    val widthMeters: Double,
    val sillMeters: Double,
    val heightMeters: Double
)

data class PlanFloor(
    val title: String,
    val imageFile: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val knownMeters: Double = 4.0,
    val referenceStart: PlanPoint? = null,
    val referenceEnd: PlanPoint? = null,
    val wallHeightMeters: Double = 2.7,
    val walls: List<PlanWall> = emptyList(),
    val openings: List<PlanOpening> = emptyList(),
    val offsetXMeters: Double = 0.0,
    val offsetYMeters: Double = 0.0,
    val alignmentPoint: PlanPoint? = null,
    val secondAlignmentPoint: PlanPoint? = null,
    val rotationDegrees: Double = 0.0
) {
    val calibrated: Boolean get() = FloorPlanMath.pixelsPerMeter(this) != null
}

data class ArchitectPlan(
    val floors: List<PlanFloor> = listOf(PlanFloor("Térreo")),
    val selectedFloor: Int = 0
)

object FloorPlanMath {
    fun pointDistancePx(a: PlanPoint, b: PlanPoint, width: Int, height: Int): Double {
        require(width > 0 && height > 0)
        return hypot((a.x - b.x).toDouble() * width, (a.y - b.y).toDouble() * height)
    }

    /** Null = invalid scale; avoid outputting misleading measurements. */
    fun pixelsPerMeter(floor: PlanFloor): Double? {
        val a = floor.referenceStart ?: return null
        val b = floor.referenceEnd ?: return null
        if (floor.imageWidth <= 0 || floor.imageHeight <= 0 ||
            !floor.knownMeters.isFinite() || floor.knownMeters <= 0.0) return null
        val pixels = pointDistancePx(a, b, floor.imageWidth, floor.imageHeight)
        return if (pixels > 1.0) pixels / floor.knownMeters else null
    }

    fun wallLengthMeters(floor: PlanFloor, wall: PlanWall): Double? =
        pixelsPerMeter(floor)?.let { pxPerMeter ->
            pointDistancePx(wall.start, wall.end, floor.imageWidth, floor.imageHeight) / pxPerMeter
        }

    fun totalWallMeters(floor: PlanFloor): Double? {
        if (!floor.calibrated) return null
        return floor.walls.sumOf { wallLengthMeters(floor, it) ?: 0.0 }
    }

    fun openingRange(floor: PlanFloor, opening: PlanOpening): ClosedFloatingPointRange<Double>? {
        val wall = floor.walls.getOrNull(opening.wallIndex) ?: return null
        val length = wallLengthMeters(floor, wall) ?: return null
        if (!length.isFinite() || length <= 0.0 || !opening.centerFraction.isFinite() ||
            !opening.widthMeters.isFinite() || opening.widthMeters <= 0.0 ||
            opening.widthMeters >= length ||
            opening.centerFraction !in 0.0..1.0 ||
            !opening.sillMeters.isFinite() || opening.sillMeters < 0.0 ||
            !opening.heightMeters.isFinite() || opening.heightMeters <= 0.0 ||
            !floor.wallHeightMeters.isFinite() || floor.wallHeightMeters <= 0.0 ||
            opening.sillMeters + opening.heightMeters > floor.wallHeightMeters + 1e-6
        ) return null
        if (opening.type == OpeningType.DOOR && opening.sillMeters > 1e-6) return null
        val center = length * opening.centerFraction
        val start = center - opening.widthMeters / 2
        val end = center + opening.widthMeters / 2
        if (start < -1e-6 || end > length + 1e-6) return null
        return start.coerceAtLeast(0.0)..end.coerceAtMost(length)
    }

    /** Allow apertures on one wall only when their horizontal spans do not overlap. */
    fun canAddOpening(floor: PlanFloor, opening: PlanOpening): Boolean {
        val range = openingRange(floor, opening) ?: return false
        return floor.openings.none { existing ->
            if (existing.wallIndex != opening.wallIndex) return@none false
            val other = openingRange(floor, existing) ?: return@none false
            range.start < other.endInclusive - 1e-6 && other.start < range.endInclusive - 1e-6
        }
    }

    /** Image normalized point into rough world x/y coordinates in meters. */
    fun worldPointMeters(floor: PlanFloor, point: PlanPoint): Pair<Double, Double>? {
        val pxPerMeter = pixelsPerMeter(floor) ?: return null
        return (point.x.toDouble() * floor.imageWidth / pxPerMeter) to
            (point.y.toDouble() * floor.imageHeight / pxPerMeter)
    }
}
