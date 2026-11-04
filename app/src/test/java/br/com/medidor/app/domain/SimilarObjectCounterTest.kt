package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarObjectCounterTest {
    private fun region(
        width: Int, height: Int, red: Int, green: Int, blue: Int,
        filled: Int = width * height
    ) = CountRegion(
        left = 0, top = 0, right = width - 1, bottom = height - 1,
        area = filled,
        meanRed = red, meanGreen = green, meanBlue = blue
    )

    @Test fun sameColorAndShapeCanMatchAtDifferentSizes() {
        val selected = SimilarObjectCounter.appearance(region(12, 12, 195, 25, 20))!!
        assertTrue(SimilarObjectCounter.matches(selected, region(18, 17, 170, 40, 26)))
        assertFalse(SimilarObjectCounter.matches(selected, region(12, 12, 15, 20, 200)))
        assertFalse(SimilarObjectCounter.matches(selected, region(24, 6, 195, 25, 20)))
    }

    @Test fun unlikeFillDensityRejected() {
        val solid = SimilarObjectCounter.appearance(region(20, 20, 35, 50, 65))!!
        assertFalse(SimilarObjectCounter.matches(solid, region(20, 20, 35, 50, 65, 120)))
    }

    @Test fun noColorDescriptorMeansNoSelectionOrMatch() {
        val unknown = CountRegion(0, 0, 10, 10, 100)
        assertEquals(null, SimilarObjectCounter.appearance(unknown))
        val sample = SimilarObjectCounter.appearance(region(9, 9, 110, 120, 130))!!
        assertFalse(SimilarObjectCounter.matches(sample, unknown))
        assertEquals(null, SimilarObjectCounter.at(listOf(unknown), 40, 40))
    }

    @Test fun selectedRedPiecesExcludeBluePiecesFromRealSegmentation() {
        val w = 100; val h = 80
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
            for (y in y0..y1) for (x in x0..x1) pixels[y * w + x] = color
        }
        fill(10, 14, 23, 27, 0xFFFF2211.toInt())
        fill(39, 16, 52, 29, 0xFFEE2918.toInt())
        fill(73, 43, 86, 56, 0xFF2044EE.toInt())
        val found = LiveObjectCounter.detect(pixels, w, h)
        assertEquals(3, found.size)
        val chosen = SimilarObjectCounter.at(found, 15, 20)
        assertNotNull(chosen)
        val descriptor = SimilarObjectCounter.appearance(chosen!!)!!
        val matching = SimilarObjectCounter.matches(descriptor, found)
        assertEquals(2, matching.size)
    }
}
