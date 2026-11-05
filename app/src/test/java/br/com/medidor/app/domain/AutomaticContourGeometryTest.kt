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
    @Test fun centralSectionRotatesWithTheCameraAndUsesTheAreaCentroid() {
        val rectangle = listOf(-2.0 to -1.0, 2.0 to -1.0, 2.0 to 1.0, -2.0 to 1.0)
        val angle = .6
        fun rotate(point: Pair<Double, Double>) =
            (point.first * kotlin.math.cos(angle) - point.second * kotlin.math.sin(angle)) to
                (point.first * kotlin.math.sin(angle) + point.second * kotlin.math.cos(angle))
        val endpoints = AutomaticContourGeometry.axisEndpoints(rectangle.map(::rotate),
            kotlin.math.cos(angle) to kotlin.math.sin(angle))!!
        assertEquals(4.0, kotlin.math.hypot(endpoints[0].first - endpoints[1].first,
            endpoints[0].second - endpoints[1].second), .000001)
        assertNull(AutomaticContourGeometry.axisEndpoints(rectangle, 0.0 to 0.0))
    }

    @Test fun concaveGapsAreNotBridgedByTheTankSection() {
        val u = listOf(0.0 to 0.0, 4.0 to 0.0, 4.0 to 4.0, 3.0 to 4.0,
            3.0 to 1.0, 1.0 to 1.0, 1.0 to 4.0, 0.0 to 4.0)
        assertNull(AutomaticContourGeometry.axisEndpoints(u, false))
        assertNull(AutomaticContourGeometry.centroid(listOf(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0)))
    }

}
