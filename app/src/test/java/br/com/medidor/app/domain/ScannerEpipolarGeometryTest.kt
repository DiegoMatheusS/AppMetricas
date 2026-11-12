package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ScannerEpipolarGeometryTest {
    private fun feature(x: Double, y: Double) =
        VisualFeature(x.toInt(), y.toInt(), 1000, 0L, 0L)

    /** Two synthetic pinhole cameras observing points with varying depth. */
    private fun synthetic(outliers: Int = 0): Triple<ImageFeatureSet, ImageFeatureSet, List<FeatureMatch>> {
        val a = mutableListOf<VisualFeature>()
        val b = mutableListOf<VisualFeature>()
        val total = 48
        for (i in 0 until total) {
            val x = sin(i * 2.39) * .68
            val y = cos(i * 1.73) * .42
            val z = 2.1 + (i % 9) * .18
            val focal = 158.0
            val yaw = .055
            val x2 = cos(yaw) * (x - .28) + sin(yaw) * (z - .03)
            val z2 = -sin(yaw) * (x - .28) + cos(yaw) * (z - .03)
            a += feature(96 + focal * x / z, 96 + focal * y / z)
            b += feature(96 + focal * x2 / z2, 96 + focal * (y - .035) / z2)
        }
        repeat(outliers.coerceAtMost(total)) { index ->
            val i = total - 1 - index
            b[i] = feature(32.0 + (i * 19 % 125), 20.0 + (i * 37 % 146))
        }
        return Triple(ImageFeatureSet(a), ImageFeatureSet(b),
            a.indices.map { FeatureMatch(it, it, 8) })
    }

    @Test fun recognizesEpipolarConsistencyInTwoCameraSyntheticScene() {
        val (first, second, matches) = synthetic(7)
        val result = ScannerEpipolarGeometry.estimate(first, second, matches)
        assertEquals(48, result.matches)
        assertTrue("Expected robust inliers, got ${result.inliers}", result.inliers >= 28)
        assertTrue("Expected image geometry evidence", result.plausible)
        assertTrue("Sampson error should be small", result.meanResidualPixels!! < 1.5)
    }

    @Test fun eightPointMinimumAndInvalidCorrespondencesHandled() {
        val (first, second, matches) = synthetic()
        val insufficient = ScannerEpipolarGeometry.estimate(first, second, matches.take(7))
        assertFalse(insufficient.plausible)
        assertEquals(0, insufficient.inliers)
        assertNull(insufficient.meanResidualPixels)
        val invalid = ScannerEpipolarGeometry.estimate(first, second,
            matches.take(12) + FeatureMatch(999, 0, 0))
        assertEquals(0, invalid.inliers)
    }

    @Test fun degenerateSinglePointCloudCannotProduceEvidence() {
        val first = ImageFeatureSet((0 until 20).map { feature(96.0, 96.0) })
        val second = ImageFeatureSet((0 until 20).map { feature(95.0, 96.0) })
        val matches = (0 until 20).map { FeatureMatch(it, it, 0) }
        val result = ScannerEpipolarGeometry.estimate(first, second, matches)
        assertFalse(result.plausible)
        assertNull(result.meanResidualPixels)
    }
}
