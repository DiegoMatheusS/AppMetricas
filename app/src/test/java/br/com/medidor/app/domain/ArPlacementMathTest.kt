package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArPlacementMathTest {
    val floor = PlanFloor(
        title = "Térreo",
        imageWidth = 1000, imageHeight = 700,
        knownMeters = 5.0,
        referenceStart = PlanPoint(.0f,.1f),
        referenceEnd = PlanPoint(.5f,.1f)
    )

    @Test fun maquetteIsScaledToPrintedImageWidth() {
        // 500px = 5m; 1000px = 10m, printed on 0.21m paper.
        assertEquals(.021, ArPlacementMath.imageModelScale(.21, floor)!!, 1e-6)
    }
    @Test fun invalidInputsDoNotProduceFabricatedScale() {
        assertNull(ArPlacementMath.imageModelScale(0.0, floor))
        assertNull(ArPlacementMath.imageModelScale(.21, floor.copy(referenceEnd = null)))
    }
}
