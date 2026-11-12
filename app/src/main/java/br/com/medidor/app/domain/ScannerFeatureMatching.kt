package br.com.medidor.app.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bounded, JVM-testable visual correspondence prototype.
 *
 * Input is an already downsampled grayscale photograph. This is NOT SfM:
 * pair transforms live in pixel/image coordinates and provide no camera pose,
 * focal length, depth, scale in metres, point cloud, or 3D mesh.
 */
data class VisualFeature(
    val x: Int,
    val y: Int,
    val score: Int,
    val lowBits: Long,
    val highBits: Long
)

data class ImageFeatureSet(val points: List<VisualFeature>)

data class FeatureMatch(val first: Int, val second: Int, val hamming: Int)

data class PairFeatureResult(
    val firstSlot: Int,
    val secondSlot: Int,
    val pointsFirst: Int,
    val pointsSecond: Int,
    val tentative: Int,
    val inliers: Int,
    val averageResidualPx: Double?,
    val approximateRotationDegrees: Double?,
    val approximateScale: Double?,
    val epipolar: EpipolarEvidence? = null
) {
    val supported: Boolean get() = inliers >= 7 && tentative >= 7 &&
        inliers.toDouble() / tentative >= .4
}

data class ScanCorrespondenceReport(
    val pairs: List<PairFeatureResult>,
    val missingSlots: List<Int>,
    val unavailableSlots: List<Int>
) {
    val supportedPairs: Int get() = pairs.count { it.supported }
}

object ScannerFeatureMatching {
    const val IMAGE_SIZE = 192
    const val MAX_FEATURES = 120
    private const val PATCH = 10

    // Deterministic binary intensity descriptor: 128 pseudo-random pairs in a
    // fixed 15x15 patch. Avoid dependency on OpenCV / paid inference services.
    private val pattern: IntArray = IntArray(128 * 4).also { values ->
        var state = 0x1ba53ee
        fun offset(): Int {
            state = state * 1664525 + 1013904223
            return ((state ushr 8) % 15) - 7
        }
        for (i in 0 until 128) {
            values[i * 4] = offset()
            values[i * 4 + 1] = offset()
            values[i * 4 + 2] = offset()
            values[i * 4 + 3] = offset()
        }
    }

    fun detect(gray: IntArray, width: Int, height: Int): ImageFeatureSet {
        require(width in 40..512 && height in 40..512 && gray.size == width * height)
        val candidates = ArrayList<Triple<Int, Int, Int>>()
        for (y in PATCH until height - PATCH) {
            for (x in PATCH until width - PATCH) {
                val k = y * width + x
                val dx = abs(gray[k + 2] - gray[k - 2])
                val dy = abs(gray[k + 2 * width] - gray[k - 2 * width])
                val score = dx * dy
                if (score >= 850) candidates += Triple(x, y, score)
            }
        }
        candidates.sortWith(compareByDescending<Triple<Int, Int, Int>> { it.third }
            .thenBy { it.second }.thenBy { it.first })

        val chosen = ArrayList<VisualFeature>(MAX_FEATURES)
        val occupied = IntArray(6 * 6)
        for ((x, y, score) in candidates) {
            if (chosen.size >= MAX_FEATURES) break
            val cell = (y * 6 / height) * 6 + x * 6 / width
            if (occupied[cell] >= 7) continue
            if (chosen.any { abs(it.x - x) <= 5 && abs(it.y - y) <= 5 }) continue
            var low = 0L
            var high = 0L
            for (bit in 0 until 128) {
                val k = bit * 4
                val a = gray[(y + pattern[k + 1]) * width + x + pattern[k]]
                val b = gray[(y + pattern[k + 3]) * width + x + pattern[k + 2]]
                if (a > b) {
                    if (bit < 64) low = low or (1L shl bit)
                    else high = high or (1L shl (bit - 64))
                }
            }
            occupied[cell]++
            chosen += VisualFeature(x, y, score, low, high)
        }
        return ImageFeatureSet(chosen)
    }

    private fun distance(a: VisualFeature, b: VisualFeature): Int =
        java.lang.Long.bitCount(a.lowBits xor b.lowBits) +
            java.lang.Long.bitCount(a.highBits xor b.highBits)

    /** Mutual nearest-neighbor matches with a ratio test and hard Hamming cap. */
    fun match(first: ImageFeatureSet, second: ImageFeatureSet): List<FeatureMatch> {
        if (first.points.size < 2 || second.points.size < 2) return emptyList()
        val reverse = IntArray(second.points.size) { b ->
            first.points.indices.minByOrNull { a ->
                distance(second.points[b], first.points[a])
            } ?: -1
        }
        return first.points.mapIndexedNotNull { a, point ->
            var best = Int.MAX_VALUE
            var next = Int.MAX_VALUE
            var index = -1
            second.points.forEachIndexed { b, candidate ->
                val d = distance(point, candidate)
                if (d < best) {
                    next = best
                    best = d
                    index = b
                } else if (d < next) next = d
            }
            if (index >= 0 && best <= 38 && best * 5 < next * 4 &&
                reverse[index] == a) FeatureMatch(a, index, best) else null
        }
    }

    /**
     * Deterministic RANSAC for 2D similarity only, using two point matches.
     * Geometric consensus rejects many accidental descriptor matches. A
     * similarity is not the perspective or epipolar geometry of a 3D scene.
     */
    fun compare(
        firstSlot: Int, secondSlot: Int,
        first: ImageFeatureSet, second: ImageFeatureSet
    ): PairFeatureResult {
        val matches = match(first, second)
        if (matches.size < 2) return PairFeatureResult(firstSlot, secondSlot,
            first.points.size, second.points.size, matches.size, 0, null, null, null)
        var bestInliers = 0
        var bestError = Double.POSITIVE_INFINITY
        var bestAngle: Double? = null
        var bestScale: Double? = null
        var iterations = 0
        outer@ for (i in matches.indices) for (j in i + 1 until matches.size) {
            if (++iterations > 360) break@outer
            val a = first.points[matches[i].first]
            val b = first.points[matches[j].first]
            val c = second.points[matches[i].second]
            val d = second.points[matches[j].second]
            val ux = (b.x - a.x).toDouble()
            val uy = (b.y - a.y).toDouble()
            val vx = (d.x - c.x).toDouble()
            val vy = (d.y - c.y).toDouble()
            val length1 = hypot(ux, uy)
            val length2 = hypot(vx, vy)
            if (length1 < 12 || length2 < 8) continue
            val scale = length2 / length1
            if (scale !in .65..1.5) continue
            val angle = atan2(vy, vx) - atan2(uy, ux)
            val cosine = cos(angle) * scale
            val sine = sin(angle) * scale
            val tx = c.x - (cosine * a.x - sine * a.y)
            val ty = c.y - (sine * a.x + cosine * a.y)
            var inliers = 0
            var residual = 0.0
            for (candidate in matches) {
                val src = first.points[candidate.first]
                val dst = second.points[candidate.second]
                val ex = cosine * src.x - sine * src.y + tx - dst.x
                val ey = sine * src.x + cosine * src.y + ty - dst.y
                val e = hypot(ex, ey)
                if (e <= 3.5) { inliers++; residual += e }
            }
            if (inliers > bestInliers || (inliers == bestInliers && residual < bestError)) {
                bestInliers = inliers
                bestError = residual
                bestAngle = Math.toDegrees(angle)
                bestScale = scale
            }
        }
        return PairFeatureResult(firstSlot, secondSlot, first.points.size,
            second.points.size, matches.size, bestInliers,
            bestAngle?.let { bestError / bestInliers.coerceAtLeast(1) },
            bestAngle, bestScale,
            ScannerEpipolarGeometry.estimate(first, second, matches))
    }

    /** Adjacent positions on both rings and closest nominal cross-elevation links. */
    fun recommendedPairs(): List<Pair<Int, Int>> {
        val result = LinkedHashSet<Pair<Int, Int>>()
        for (i in 0 until 12) result += i to (i + 1) % 12
        for (i in 0 until 8) result += (12 + i) to (12 + (i + 1) % 8)
        for (i in 0 until 8) result += ((i * 45 + 15) / 30 % 12) to (12 + i)
        return result.toList()
    }
}
