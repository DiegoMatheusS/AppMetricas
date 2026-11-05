package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TankCameraCaptureTest {
    @Test fun cylinderNeedsBothDimensions() {
        assertNull(TankCameraCapture(TankShape.VERTICAL, listOf(2.5)).validated())
        assertNotNull(TankCameraCapture(TankShape.VERTICAL, listOf(2.5,3.1)).validated())
        assertNotNull(TankCameraCapture(TankShape.HORIZONTAL, listOf(1.8,5.0)).validated())
    }
    @Test fun sphereNeedsDiameterOnly() {
        assertNotNull(TankCameraCapture(TankShape.SPHERE,listOf(2.0)).validated())
        assertNull(TankCameraCapture(TankShape.SPHERE,listOf(2.0,2.0)).validated())
    }
    @Test fun impossibleAndNonfiniteMeasurementsAreRejected() {
        assertNull(TankCameraCapture(TankShape.VERTICAL,listOf(0.0,2.0)).validated())
        assertNull(TankCameraCapture(TankShape.VERTICAL,listOf(Double.NaN,2.0)).validated())
        assertNull(TankCameraCapture(TankShape.VERTICAL,listOf(999.0,2.0)).validated())
    }
    @Test fun capacityUsesConfirmedDimensions() {
        val capture=TankCameraCapture(TankShape.VERTICAL,listOf(2.0,3.0)).validated()!!
        val result=TankCalculator.calculate(capture.shape,capture.dimensions[0],capture.dimensions[1],0.0)
        assertEquals(Math.PI * 3.0,result.capacityM3,1e-6)
        assertEquals(0.0, result.occupiedM3, 1e-8)
    }
    @Test fun emptyTanksAllRemainComputable() {
        for (shape in TankShape.entries) {
            val result = TankCalculator.calculate(shape, 2.0, 3.0, 0.0)
            assertEquals(0.0, result.occupiedM3, 1e-8)
            assertEquals(result.capacityM3, result.freeM3, 1e-8)
        }
    }
}
