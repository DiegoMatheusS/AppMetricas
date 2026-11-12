package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerFeatureMatchingTest {
    private val n = ScannerFeatureMatching.IMAGE_SIZE

    /** Repeatable, high-texture input with stable high-contrast corners. */
    private fun textured(): IntArray {
        var seed = 987654321
        return IntArray(n * n) {
            seed = seed * 1664525 + 1013904223
            (seed ushr 23) and 255
        }
    }

    private fun translated(input: IntArray, dx: Int, dy: Int): IntArray =
        IntArray(n * n) { i ->
            val x = i % n - dx
            val y = i / n - dy
            if (x in 0 until n && y in 0 until n) input[y * n + x] else 127
        }

    @Test fun blankPhotoHasNoTrackableCorners() {
        val empty = ScannerFeatureMatching.detect(IntArray(n * n) { 128 }, n, n)
        assertTrue(empty.points.isEmpty())
        val compared = ScannerFeatureMatching.compare(0, 1, empty, empty)
        assertFalse(compared.supported)
        assertEquals(0, compared.inliers)
    }

    @Test fun identifiesCorrespondencesForSmallKnownImageTranslation() {
        val pixels = textured()
        val first = ScannerFeatureMatching.detect(pixels, n, n)
        val second = ScannerFeatureMatching.detect(translated(pixels, 4, 3), n, n)
        val result = ScannerFeatureMatching.compare(0, 1, first, second)
        assertTrue("Need some discriminative points", first.points.size >= 40)
        assertTrue("Tentative matches: ${result.tentative}", result.tentative >= 7)
        assertTrue("Geometric inliers: ${result.inliers}", result.inliers >= 7)
        assertTrue("Translation must be geometrically consistent", result.supported)
        assertTrue(result.averageResidualPx!! <= 3.5)
        assertTrue(kotlin.math.abs(result.approximateRotationDegrees!!) < 8.0)
    }

    @Test fun uniformVsTexturedImageIsNotAValidPhotoPair() {
        val a = ScannerFeatureMatching.detect(textured(), n, n)
        val b = ScannerFeatureMatching.detect(IntArray(n * n) { 255 }, n, n)
        assertFalse(ScannerFeatureMatching.compare(0, 2, a, b).supported)
    }

    @Test fun rejectsMalformedInputAndHasBoundedFeatureCount() {
        val result = ScannerFeatureMatching.detect(textured(), n, n)
        assertTrue(result.points.size <= ScannerFeatureMatching.MAX_FEATURES)
        try {
            ScannerFeatureMatching.detect(IntArray(17), n, n)
            throw AssertionError("Malformed image should fail")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun recommendedPairsStayWithinGuidedCaptureSlots() {
        val pairs = ScannerFeatureMatching.recommendedPairs()
        assertEquals(28, pairs.size)
        assertEquals(pairs.size, pairs.distinct().size)
        assertTrue(pairs.all { (a,b) -> a in 0 until 20 && b in 0 until 20 && a != b })
        assertTrue(pairs.contains(11 to 0))
        assertTrue(pairs.contains(19 to 12))
    }
}
