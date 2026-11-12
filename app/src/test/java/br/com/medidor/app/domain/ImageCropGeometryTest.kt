package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageCropGeometryTest {
    @Test fun rotatesCroppedRectangleToUprightImage() {
        val zero = ImageCropGeometry(20,10,4,3,0)
        assertEquals(4,zero.uprightWidth)
        assertEquals(3,zero.uprightHeight)
        assertEquals(20 to 10,zero.rawCoordinates(0,0))
        assertEquals(23 to 12,zero.rawCoordinates(3,2))

        val ninety = ImageCropGeometry(20,10,4,3,90)
        assertEquals(3,ninety.uprightWidth)
        assertEquals(4,ninety.uprightHeight)
        assertEquals(20 to 12,ninety.rawCoordinates(0,0))
        assertEquals(23 to 10,ninety.rawCoordinates(2,3))

        val oneEighty = ImageCropGeometry(20,10,4,3,180)
        assertEquals(23 to 12,oneEighty.rawCoordinates(0,0))
        assertEquals(20 to 10,oneEighty.rawCoordinates(3,2))

        val twoSeventy = ImageCropGeometry(20,10,4,3,270)
        assertEquals(23 to 10,twoSeventy.rawCoordinates(0,0))
        assertEquals(20 to 12,twoSeventy.rawCoordinates(2,3))
    }

    @Test fun rejectsInvalidMetadataAndOutOfCropPixels() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageCropGeometry(0,0,80,60,45)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageCropGeometry(3,3,0,40,0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageCropGeometry(0,0,4,3,0).rawCoordinates(4,0)
        }
    }

    @Test fun normalizedOverlayCoordinatesUseFullSharedViewport() {
        assertEquals(.5f to .25f,LiveViewportMapping.point(200f,50f,400f,200f))
        assertEquals(0f to 1f,LiveViewportMapping.point(0f,200f,400f,200f))
        assertNull(LiveViewportMapping.point(-1f,20f,400f,200f))
        assertNull(LiveViewportMapping.point(300f,220f,400f,200f))
        assertNull(LiveViewportMapping.point(0f,0f,0f,200f))
    }
}
