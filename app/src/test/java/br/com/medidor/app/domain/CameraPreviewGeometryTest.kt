package br.com.medidor.app.domain

import org.junit.Assert.*
import org.junit.Test

class CameraPreviewGeometryTest {
    @Test fun matchingAspectRatioPreservesCenter() {
        val map = CameraPreviewGeometry(100, 200, 300f, 600f)
        assertEquals(50f to 100f, map.imagePoint(150f, 300f))
        assertEquals(150f to 300f, map.viewPoint(50f, 100f))
    }

    @Test fun centerCropMapsVisiblePixelsWithoutStretching() {
        // Wider source into a tall preview: the left and right parts are hidden.
        val map = CameraPreviewGeometry(200, 100, 100f, 100f)
        assertEquals(50f to 0f, map.imagePoint(0f, 0f))
        assertEquals(150f to 100f, map.imagePoint(100f, 100f))
        assertEquals(0f to 0f, map.viewPoint(50f, 0f))
        assertEquals(.5f to .5f, map.normalizedImagePoint(50f, 50f))
    }

    @Test fun tallSourceInWideViewIsCroppedVertically() {
        val map = CameraPreviewGeometry(100, 200, 200f, 100f)
        assertEquals(0f to 75f, map.imagePoint(0f, 0f))
        assertEquals(100f to 125f, map.imagePoint(200f, 100f))
    }

    @Test fun rejectNonFiniteOrOutsideTouches() {
        val map = CameraPreviewGeometry(100, 100, 100f, 100f)
        assertNull(map.imagePoint(Float.NaN, 10f))
        assertNull(map.imagePoint(-1f, 10f))
        assertNull(map.imagePoint(101f, 10f))
    }
}
