package br.com.medidor.app.domain

import kotlin.math.abs
import kotlin.math.sqrt

internal data class ReprojectionObservation(
    val pose: DoubleArray, val x: Double, val y: Double, val focal: Double
)

/** Refines measured 3D points against every camera observing them, with robust pixel residuals. */
internal object MultiViewRefinement {
    fun refine(point: Point3d, observations: List<ReprojectionObservation>): Point3d? {
        if (observations.size < 2 || observations.any { it.pose.size != 12 || it.focal <= 0 }) return null
        var value = doubleArrayOf(point.x, point.y, point.z)
        repeat(8) {
            val normal = Array(3) { DoubleArray(4) }
            var valid = 0
            for (observation in observations) {
                val p = observation.pose
                val xc = p[0] * value[0] + p[1] * value[1] + p[2] * value[2] + p[3]
                val yc = p[4] * value[0] + p[5] * value[1] + p[6] * value[2] + p[7]
                val zc = p[8] * value[0] + p[9] * value[1] + p[10] * value[2] + p[11]
                if (!zc.isFinite() || zc <= .001) continue
                val residuals = doubleArrayOf((observation.x - xc / zc) * observation.focal,
                    (observation.y - yc / zc) * observation.focal)
                val error = sqrt(residuals.sumOf { it * it })
                val weight = if (error <= 2.5) 1.0 else 2.5 / error
                val jacobian = arrayOf(
                    DoubleArray(3) { j -> observation.focal * (p[j] * zc - xc * p[8 + j]) / (zc * zc) },
                    DoubleArray(3) { j -> observation.focal * (p[4 + j] * zc - yc * p[8 + j]) / (zc * zc) })
                for (row in 0..1) for (a in 0..2) {
                    for (b in 0..2) normal[a][b] += weight * jacobian[row][a] * jacobian[row][b]
                    normal[a][3] += weight * jacobian[row][a] * residuals[row]
                }
                valid++
            }
            if (valid < 2) return null
            val change = solve3(normal) ?: return null
            if (change.any { !it.isFinite() || abs(it) > 100 }) return null
            value = DoubleArray(3) { value[it] + change[it] }
        }
        val refined = Point3d(value[0], value[1], value[2])
        val consistent = observations.count { residual(refined, it) <= 3.0 }
        return refined.takeIf { consistent >= 2 && consistent >= observations.size * .65 }
    }

    fun residual(point: Point3d, observation: ReprojectionObservation): Double {
        val p = observation.pose
        val z = p[8] * point.x + p[9] * point.y + p[10] * point.z + p[11]
        if (!z.isFinite() || z <= .001) return Double.POSITIVE_INFINITY
        val x = (p[0] * point.x + p[1] * point.y + p[2] * point.z + p[3]) / z
        val y = (p[4] * point.x + p[5] * point.y + p[6] * point.z + p[7]) / z
        return kotlin.math.hypot(x - observation.x, y - observation.y) * observation.focal
    }
}
