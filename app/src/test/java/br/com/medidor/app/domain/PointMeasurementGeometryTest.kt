package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PointMeasurementGeometryTest {
    @Test fun rectangleAreaUsesImageDimensions() {
        val rectangle = listOf(
            NormalizedPoint(.1f,.2f), NormalizedPoint(.5f,.2f),
            NormalizedPoint(.5f,.6f), NormalizedPoint(.1f,.6f)
        )
        val pixels = PointMeasurementGeometry.polygonAreaPixels2(rectangle, 100, 100)
        assertTrue(pixels != null)
        assertEquals(1600.0, pixels!!, .01)
        val area = PointMeasurementGeometry.areaSquareMillimeters(pixels,100.0,20.0)
        assertEquals(40000.0, area!!, .01)
    }
    @Test fun triangleAndReverseOrderingHaveSameArea() {
        val triangle = listOf(
            NormalizedPoint(0f,0f),NormalizedPoint(.5f,0f),NormalizedPoint(0f,.5f)
        )
        val a = PointMeasurementGeometry.polygonAreaPixels2(triangle, 100, 100)
        val b = PointMeasurementGeometry.polygonAreaPixels2(triangle.reversed(), 100, 100)
        assertEquals(1250.0,a!!,.01)
        assertEquals(a,b!!,.01)
    }
    @Test fun crossingAndCollinearPolygonsAreRejected() {
        assertNull(PointMeasurementGeometry.polygonAreaPixels2(listOf(
            NormalizedPoint(.1f,.1f),NormalizedPoint(.9f,.9f),
            NormalizedPoint(.1f,.9f),NormalizedPoint(.9f,.1f)
        ), 100,100))
        assertNull(PointMeasurementGeometry.polygonAreaPixels2(listOf(
            NormalizedPoint(.1f,.1f),NormalizedPoint(.5f,.5f),
            NormalizedPoint(.9f,.9f)
        ),100,100))
    }
    @Test fun areaInArUsesMetersOnTheSurfaceAndRejectsCrossedCorners() {
        val corners = listOf(0.0 to 0.0, 2.5 to 0.0, 2.5 to -0.6, 0.0 to -0.6)
        assertEquals(1.5, PointMeasurementGeometry.polygonAreaSquareMeters(corners)!!, .0001)
        assertEquals(1.5, PointMeasurementGeometry.polygonAreaSquareMeters(corners.reversed())!!, .0001)
        assertNull(PointMeasurementGeometry.polygonAreaSquareMeters(
            listOf(corners[0], corners[2], corners[1], corners[3])
        ))
    }

    @Test fun invalidReferenceCannotProduceArea() {
        assertNull(PointMeasurementGeometry.areaSquareMillimeters(100.0,100.0,0.0))
        assertNull(PointMeasurementGeometry.polygonAreaPixels2(emptyList(),100,100))
    }
}
