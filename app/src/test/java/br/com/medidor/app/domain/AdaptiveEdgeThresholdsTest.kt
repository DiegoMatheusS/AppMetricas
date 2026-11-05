package br.com.medidor.app.domain

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveEdgeThresholdsTest {
    @Test fun flatImageHasNoUsefulContrast() {
        val pixels = IntArray(640) { 0xFF777777.toInt() }
        assertNull(AdaptiveEdgeThresholds.fromArgb(pixels))
    }

    @Test fun darkAndBrightEdgesProduceUsableThresholds() {
        val dark = IntArray(800) { if (it % 2 == 0) 0xFF222222.toInt() else 0xFF444444.toInt() }
        val high = IntArray(800) { if (it % 2 == 0) 0xFF202020.toInt() else 0xFFE0E0E0.toInt() }
        val d = AdaptiveEdgeThresholds.fromArgb(dark)
        val h = AdaptiveEdgeThresholds.fromArgb(high)
        assertNotNull(d)
        assertNotNull(h)
        assertTrue(d!!.low < d.high)
        assertTrue(h!!.low < h.high)
        assertTrue(h.contrast > d.contrast)
        assertTrue(h.high > d.high)
    }
}
