package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.PI

class GeometryTest {
    @Test fun rectangularVolumeIsCorrect() {
        assertEquals(0.06, Geometry.rectangularVolume(0.5, 0.4, 0.3), 1e-10)
    }

    @Test fun cylinderCapacityIsCorrect() {
        assertEquals(PI * 4 * 12, Geometry.cylinderVolume(4.0, 12.0), 1e-8)
    }

    @Test fun sphereHalfFullIsHalfCapacity() {
        val full = Geometry.sphereVolume(8.0)
        assertEquals(full / 2, Geometry.sphereLiquidVolume(8.0, 4.0), 1e-8)
    }

    @Test fun horizontalHalfFullIsHalfCapacity() {
        val full = Geometry.cylinderVolume(8.0, 12.0)
        assertEquals(full / 2, Geometry.horizontalCylinderLiquidVolume(8.0, 12.0, 4.0), 1e-8)
    }

    @Test fun invalidLevelsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Geometry.verticalCylinderLiquidVolume(2.0, 3.0, 4.0)
        }
    }

    @Test fun zeroAndFullAreCorrect() {
        assertEquals(0.0, Geometry.horizontalCylinderLiquidVolume(2.0, 3.0, 0.0), 1e-8)
        assertEquals(Geometry.cylinderVolume(2.0, 3.0),
            Geometry.horizontalCylinderLiquidVolume(2.0, 3.0, 2.0), 1e-8)
    }
}
