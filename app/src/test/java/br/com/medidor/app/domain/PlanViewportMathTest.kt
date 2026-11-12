package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanViewportMathTest {
    @Test fun zoomAboutCenterPreservesCenter() {
        val result = PlanViewportMath.change(
            PlanViewport(), 400f, 300f, 200f, 150f, 2f, 0f, 0f
        )
        assertEquals(2f, result.zoom, .0001f)
        assertEquals(0f, result.panX, .0001f)
        assertEquals(0f, result.panY, .0001f)
    }

    @Test fun pinchAroundOffCenterPointKeepsPointStationary() {
        val result = PlanViewportMath.change(
            PlanViewport(), 400f, 300f, 280f, 180f, 2f, 0f, 0f
        )
        // x = center + newZoom * (280-center) + pan = 280.
        assertEquals(280f, 200f + result.zoom * 80f + result.panX, .0001f)
        assertEquals(180f, 150f + result.zoom * 30f + result.panY, .0001f)
    }

    @Test fun zoomLimitsAndPanCannotEscapeCanvas() {
        val up = PlanViewportMath.change(
            PlanViewport(), 400f, 300f, 200f, 150f, 100f, 1000f, -1000f
        )
        assertEquals(6f, up.zoom, .0001f)
        assertTrue(up.panX in -1000f..1000f)
        assertTrue(up.panY in -750f..750f)
        val reset = PlanViewportMath.change(
            up, 400f, 300f, 200f, 150f, .0001f, 0f, 0f
        )
        assertEquals(1f, reset.zoom, .0001f)
        assertEquals(0f, reset.panX, .0001f)
        assertEquals(0f, reset.panY, .0001f)
    }

    @Test fun rejectsBadGestureData() {
        val original = PlanViewport(2f, 10f, 20f)
        assertEquals(original, PlanViewportMath.change(
            original, 300f, 400f, 100f, 100f, Float.NaN, 0f, 0f
        ))
    }
}
