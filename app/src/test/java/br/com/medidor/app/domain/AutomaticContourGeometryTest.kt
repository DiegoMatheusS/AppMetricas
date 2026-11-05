package br.com.medidor.app.domain

import org.junit.Assert.*
import org.junit.Test

class AutomaticContourGeometryTest {
    @Test fun tankDimensionsUseCentralSectionsInsteadOfDiagonals() {
        val rectangle = listOf(10.0 to 10.0, 90.0 to 10.0, 90.0 to 50.0, 10.0 to 50.0)
        assertEquals(listOf(10.0 to 30.0, 90.0 to 30.0), AutomaticContourGeometry.axisEndpoints(rectangle, false))
        assertEquals(listOf(50.0 to 10.0, 50.0 to 50.0), AutomaticContourGeometry.axisEndpoints(rectangle, true))
        assertNull(AutomaticContourGeometry.axisEndpoints(listOf(0.0 to 0.0), false))
    }
}
