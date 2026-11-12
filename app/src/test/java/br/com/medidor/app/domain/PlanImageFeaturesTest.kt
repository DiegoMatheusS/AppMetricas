package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanImageFeaturesTest {
    @Test fun pureWhiteImageHasNoEdges() {
        val pixels = IntArray(32 * 32) { 0xFFFFFFFF.toInt() }
        assertEquals(0.0, PlanImageFeatures.edgeFraction(pixels, 32, 32), 0.0)
    }
    @Test fun highContrastPatternHasEdges() {
        val pixels = IntArray(32 * 32) { index ->
            val x = index % 32
            val y = index / 32
            if ((x + y) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        assertTrue(PlanImageFeatures.edgeFraction(pixels, 32, 32) > 0.8)
    }
}
