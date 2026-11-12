package br.com.medidor.app.domain

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class RectifiedSize(val width: Int, val height: Int)

/**
 * Users must select corners of a flat, rectangular sheet clockwise:
 * upper-left, upper-right, lower-right, lower-left.
 * Output aspect ratio comes from the sheet's KNOWN width / height.
 */
object PlanRectification {
    fun targetSize(
        corners: List<PlanPoint>,
        imageWidth: Int,
        imageHeight: Int,
        sheetWidthCm: Double,
        sheetHeightCm: Double
    ): RectifiedSize? {
        if (corners.size != 4 || imageWidth < 32 || imageHeight < 32 ||
            !sheetWidthCm.isFinite() || !sheetHeightCm.isFinite() ||
            sheetWidthCm <= 0 || sheetHeightCm <= 0) return null

        val aspect = sheetWidthCm / sheetHeightCm
        if (!aspect.isFinite() || aspect !in .2..5.0) return null

        // Convex, non-self-intersecting quadrilateral of meaningful size.
        fun cross(a: PlanPoint, b: PlanPoint, c: PlanPoint): Double =
            (b.x - a.x).toDouble() * (c.y - b.y) -
                (b.y - a.y).toDouble() * (c.x - b.x)
        val turns = (0..3).map { i ->
            cross(corners[i], corners[(i + 1) % 4], corners[(i + 2) % 4])
        }
        if (turns.any { !it.isFinite() || it < .0008 }) return null
        val area = kotlin.math.abs((0..3).sumOf { i ->
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }) / 2
        if (area < .025) return null

        fun length(a: PlanPoint, b: PlanPoint) =
            hypot((a.x - b.x).toDouble() * imageWidth, (a.y - b.y).toDouble() * imageHeight)
        val rawWidth = max(length(corners[0], corners[1]), length(corners[3], corners[2]))
        val rawHeight = rawWidth / aspect
        val ratio = min(2048.0 / rawWidth, 2048.0 / rawHeight).coerceAtMost(1.0)
        val width = (rawWidth * ratio).roundToInt().coerceIn(64, 2048)
        val height = (width / aspect).roundToInt().coerceIn(64, 2048)
        return RectifiedSize(width, height)
    }
}

/**
 * All plans are centered around their image midpoint, then rotated in the
 * plan XY axes (screen Y increases downward), then translated by X/Y meters.
 */
object PlanFloorPose {
    fun rotate(x: Double, y: Double, degrees: Double): Pair<Double, Double> {
        require(x.isFinite() && y.isFinite() && degrees.isFinite())
        val a = Math.toRadians(degrees)
        val c = kotlin.math.cos(a)
        val s = kotlin.math.sin(a)
        return (x * c - y * s) to (x * s + y * c)
    }

    fun normalizeDegrees(degrees: Double): Double {
        require(degrees.isFinite())
        val result = (degrees + 180.0) % 360.0
        return (if (result < 0) result + 360 else result) - 180.0
    }

    fun localMeters(floor: PlanFloor, point: PlanPoint): Pair<Double, Double>? {
        val world = FloorPlanMath.worldPointMeters(floor, point) ?: return null
        val center = FloorPlanMath.worldPointMeters(floor, PlanPoint(.5f, .5f)) ?: return null
        return (world.first - center.first) to (world.second - center.second)
    }
}

data class AlignmentPose(
    val offsetXMeters: Double,
    val offsetYMeters: Double,
    val rotationDegrees: Double,
    val landmarkDifferenceMeters: Double
)

/**
 * One landmark: translation with CURRENT rotations (cannot infer rotation).
 * Two sufficiently separated corresponding landmarks: rotation and translation.
 * Never claims that a mismatched scale has been fixed by a rotation.
 */
object PlanLandmarkAlignment {
    fun suggestedOffset(base: PlanFloor, upper: PlanFloor): Pair<Double, Double>? {
        val a = base.alignmentPoint ?: return null
        val b = upper.alignmentPoint ?: return null
        if (!base.rotationDegrees.isFinite() || !upper.rotationDegrees.isFinite()) return null
        val aLocal = PlanFloorPose.localMeters(base, a) ?: return null
        val bLocal = PlanFloorPose.localMeters(upper, b) ?: return null
        val aRotated = PlanFloorPose.rotate(aLocal.first, aLocal.second, base.rotationDegrees)
        val bRotated = PlanFloorPose.rotate(bLocal.first, bLocal.second, upper.rotationDegrees)
        val x = aRotated.first + base.offsetXMeters - bRotated.first
        val y = aRotated.second + base.offsetYMeters - bRotated.second
        return if (x.isFinite() && y.isFinite()) x to y else null
    }

    fun suggestedPose(base: PlanFloor, upper: PlanFloor): AlignmentPose? {
        val a0 = base.alignmentPoint ?: return null
        val a1 = base.secondAlignmentPoint ?: return null
        val b0 = upper.alignmentPoint ?: return null
        val b1 = upper.secondAlignmentPoint ?: return null
        if (!base.rotationDegrees.isFinite() || !base.offsetXMeters.isFinite() ||
            !base.offsetYMeters.isFinite()) return null
        val base0 = PlanFloorPose.localMeters(base, a0) ?: return null
        val base1 = PlanFloorPose.localMeters(base, a1) ?: return null
        val upper0 = PlanFloorPose.localMeters(upper, b0) ?: return null
        val upper1 = PlanFloorPose.localMeters(upper, b1) ?: return null
        val ax = base1.first - base0.first
        val ay = base1.second - base0.second
        val bx = upper1.first - upper0.first
        val by = upper1.second - upper0.second
        val aDistance = hypot(ax, ay)
        val bDistance = hypot(bx, by)
        // Close points make inferred rotation extremely unstable.
        if (aDistance < .5 || bDistance < .5) return null
        val mismatch = kotlin.math.abs(aDistance - bDistance)
        // Different scale needs recalibration, not rotation.
        if (mismatch / kotlin.math.max(aDistance, bDistance) > .08) return null
        val baseAngle = kotlin.math.atan2(ay, ax)
        val upperAngle = kotlin.math.atan2(by, bx)
        val theta = PlanFloorPose.normalizeDegrees(
            base.rotationDegrees + Math.toDegrees(baseAngle - upperAngle)
        )
        val baseRotated = PlanFloorPose.rotate(base0.first, base0.second, base.rotationDegrees)
        val upperRotated = PlanFloorPose.rotate(upper0.first, upper0.second, theta)
        val x = baseRotated.first + base.offsetXMeters - upperRotated.first
        val y = baseRotated.second + base.offsetYMeters - upperRotated.second
        return if (x.isFinite() && y.isFinite()) {
            AlignmentPose(x, y, theta, mismatch)
        } else null
    }
}
