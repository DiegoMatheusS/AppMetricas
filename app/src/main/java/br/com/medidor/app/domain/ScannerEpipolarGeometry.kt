package br.com.medidor.app.domain

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Experimental two-view epipolar consistency checker, based on the normalized
 * eight-point method and deterministic RANSAC in IMAGE PIXELS.
 *
 * The fundamental matrix is not a calibrated essential matrix. Neither camera
 * extrinsics nor 3D positions can be recovered from this result alone.
 */
data class EpipolarEvidence(
    val matches: Int,
    val inliers: Int,
    val meanResidualPixels: Double?
) {
    val plausible: Boolean
        get() = matches >= 12 && inliers >= 12 && inliers.toDouble() / matches >= .55
}

object ScannerEpipolarGeometry {
    private const val MAX_PAIRS = 120
    private const val RANSAC_ATTEMPTS = 90
    private const val PIXEL_THRESHOLD = 2.5

    private data class Point(val x: Double, val y: Double)
    private data class Normalized(
        val transformed: List<Point>,
        val centerX: Double,
        val centerY: Double,
        val scale: Double
    )

    /**
     * Accepts pre-matched features (including tentative descriptor outliers).
     * A positive result means only that the 2D points approximately respect
     * one epipolar constraint; it does not demonstrate useful triangulation.
     */
    fun estimate(
        first: ImageFeatureSet,
        second: ImageFeatureSet,
        matches: List<FeatureMatch>
    ): EpipolarEvidence {
        if (matches.size < 8 || matches.size > MAX_PAIRS) {
            return EpipolarEvidence(matches.size, 0, null)
        }
        if (matches.any { it.first !in first.points.indices ||
                it.second !in second.points.indices }) {
            return EpipolarEvidence(matches.size, 0, null)
        }
        val a = matches.map { first.points[it.first].let { f ->
            Point(f.x.toDouble(), f.y.toDouble()) } }
        val b = matches.map { second.points[it.second].let { f ->
            Point(f.x.toDouble(), f.y.toDouble()) } }
        var best: IntArray = intArrayOf()
        var bestError = Double.POSITIVE_INFINITY
        var seed = 84931
        repeat(RANSAC_ATTEMPTS) {
            val selected = linkedSetOf<Int>()
            while (selected.size < 8) {
                seed = seed * 1664525 + 1013904223
                selected += (seed ushr 1) % matches.size
            }
            val sample = selected.toIntArray()
            val fundamental = fundamental(sample.map { a[it] }, sample.map { b[it] })
                ?: return@repeat
            val consistent = ArrayList<Int>()
            var errors = 0.0
            for (i in a.indices) {
                val residual = sampson(fundamental, a[i], b[i])
                if (residual <= PIXEL_THRESHOLD) {
                    consistent += i
                    errors += residual
                }
            }
            if (consistent.size > best.size ||
                (consistent.size == best.size && errors < bestError)) {
                best = consistent.toIntArray()
                bestError = errors
            }
        }
        if (best.size < 8) return EpipolarEvidence(matches.size, best.size, null)
        // Refit with all provisional inliers for a more stable residual estimate.
        val refined = fundamental(best.map { a[it] }, best.map { b[it] })
            ?: return EpipolarEvidence(matches.size, best.size, bestError / best.size)
        var inliers = 0
        var total = 0.0
        for (i in a.indices) {
            val err = sampson(refined, a[i], b[i])
            if (err <= PIXEL_THRESHOLD) { inliers++; total += err }
        }
        return EpipolarEvidence(
            matches.size, inliers,
            if (inliers > 0) total / inliers else null
        )
    }

    private fun normalize(points: List<Point>): Normalized? {
        val cx = points.sumOf { it.x } / points.size
        val cy = points.sumOf { it.y } / points.size
        val average = points.sumOf { hypot(it.x - cx, it.y - cy) } / points.size
        if (!average.isFinite() || average < .0001) return null
        val scale = sqrt(2.0) / average
        return Normalized(points.map { Point((it.x - cx) * scale, (it.y - cy) * scale) },
            cx, cy, scale)
    }

    private fun fundamental(a: List<Point>, b: List<Point>): DoubleArray? {
        if (a.size < 8 || a.size != b.size) return null
        val na = normalize(a) ?: return null
        val nb = normalize(b) ?: return null
        val ata = Array(9) { DoubleArray(9) }
        for (i in a.indices) {
            val x = na.transformed[i].x
            val y = na.transformed[i].y
            val u = nb.transformed[i].x
            val v = nb.transformed[i].y
            val row = doubleArrayOf(u*x, u*y, u, v*x, v*y, v, x, y, 1.0)
            for (j in 0..8) for (k in j..8) ata[j][k] += row[j] * row[k]
        }
        for (j in 0..8) for (k in 0 until j) ata[j][k] = ata[k][j]
        val unit = smallestEigenvector(ata) ?: return null

        // F = T2^T * F_normalized * T1, where T is a centering transform.
        val t1 = doubleArrayOf(na.scale,0.0,-na.scale*na.centerX,
            0.0,na.scale,-na.scale*na.centerY,0.0,0.0,1.0)
        val t2 = doubleArrayOf(nb.scale,0.0,-nb.scale*nb.centerX,
            0.0,nb.scale,-nb.scale*nb.centerY,0.0,0.0,1.0)
        val f = multiply(transpose(t2), multiply(unit, t1))
        val norm = sqrt(f.sumOf { it * it })
        return f.takeIf { norm.isFinite() && norm > 1e-12 }
            ?.map { it / norm }?.toDoubleArray()
    }

    private fun multiply(a: DoubleArray, b: DoubleArray) =
        DoubleArray(9) { index ->
            val row = index / 3
            val col = index % 3
            (0..2).sumOf { k -> a[row * 3 + k] * b[k * 3 + col] }
        }

    private fun transpose(a: DoubleArray) =
        DoubleArray(9) { i -> a[i % 3 * 3 + i / 3] }

    /**
     * Symmetric 9x9 Jacobi diagonalization (smallest right singular vector of
     * A). Fixed iteration limit prevents unbounded work on degenerate data.
     */
    private fun smallestEigenvector(input: Array<DoubleArray>): DoubleArray? {
        val a = Array(9) { input[it].copyOf() }
        val eigenvectors = Array(9) { i ->
            DoubleArray(9) { j -> if (i == j) 1.0 else 0.0 }
        }
        repeat(240) {
            var p = 0
            var q = 1
            var largest = 0.0
            for (i in 0..8) for (j in i + 1..8) {
                val magnitude = abs(a[i][j])
                if (magnitude > largest) { p = i; q = j; largest = magnitude }
            }
            if (largest < 1e-10) {
                val column = (0..8).minByOrNull { a[it][it] } ?: return null
                val result = DoubleArray(9) { eigenvectors[it][column] }
                val norm = sqrt(result.sumOf { it * it })
                return if (norm > 1e-12 && norm.isFinite()) {
                    result.map { it / norm }.toDoubleArray()
                } else null
            }
            val tau = (a[q][q] - a[p][p]) / (2.0 * a[p][q])
            val tangent = if (tau >= 0) 1.0 / (tau + sqrt(1 + tau * tau))
                else -1.0 / (-tau + sqrt(1 + tau * tau))
            val cosine = 1.0 / sqrt(1 + tangent * tangent)
            val sine = tangent * cosine
            val app = a[p][p]
            val aqq = a[q][q]
            val apq = a[p][q]
            a[p][p] = app - tangent * apq
            a[q][q] = aqq + tangent * apq
            a[p][q] = 0.0
            a[q][p] = 0.0
            for (k in 0..8) {
                if (k != p && k != q) {
                    val akp = a[k][p]
                    val akq = a[k][q]
                    a[k][p] = cosine * akp - sine * akq
                    a[p][k] = a[k][p]
                    a[k][q] = sine * akp + cosine * akq
                    a[q][k] = a[k][q]
                }
                val vkP = eigenvectors[k][p]
                val vkQ = eigenvectors[k][q]
                eigenvectors[k][p] = cosine * vkP - sine * vkQ
                eigenvectors[k][q] = sine * vkP + cosine * vkQ
            }
        }
        return null
    }

    /** Square-root Sampson error: approximated reprojection distance in pixels. */
    private fun sampson(f: DoubleArray, p: Point, q: Point): Double {
        val fx0 = f[0]*p.x + f[1]*p.y + f[2]
        val fx1 = f[3]*p.x + f[4]*p.y + f[5]
        val fx2 = f[6]*p.x + f[7]*p.y + f[8]
        val ftx0 = f[0]*q.x + f[3]*q.y + f[6]
        val ftx1 = f[1]*q.x + f[4]*q.y + f[7]
        val numerator = abs(q.x*fx0 + q.y*fx1 + fx2)
        val denominator = sqrt(fx0*fx0 + fx1*fx1 + ftx0*ftx0 + ftx1*ftx1)
        return if (denominator > 1e-12 && numerator.isFinite()) numerator / denominator
            else Double.POSITIVE_INFINITY
    }
}
