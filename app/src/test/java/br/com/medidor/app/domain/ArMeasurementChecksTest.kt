package br.com.medidor.app.domain

import org.junit.Assert.*
import org.junit.Test

class ArMeasurementChecksTest {
    @Test fun aSquareKeepsTheSameRulerEdgeWhenContourOrderChanges() {
        val square = listOf(Point3d(0.0,0.0,0.0), Point3d(1.0,0.0,0.0),
            Point3d(1.0,0.0,1.0), Point3d(0.0,0.0,1.0))
        val first = ArMeasurementChecks.rulerEdge(square, null)!!
        val rotated = square.drop(2) + square.take(2)
        assertEquals(first.toSet(), ArMeasurementChecks.rulerEdge(rotated, first)!!.toSet())
    }

    @Test fun tankSectionsRejectTheBackgroundAndWrongWorldAxis() {
        val horizontal = listOf(Point3d(-1.0,0.0,0.0), Point3d(1.0,0.0,0.0))
        assertTrue(ArMeasurementChecks.tankSection(horizontal, Point3d(0.0,0.0,-.7), false))
        assertFalse(ArMeasurementChecks.tankSection(horizontal, Point3d(0.0,0.0,-4.0), false))
        assertFalse(ArMeasurementChecks.tankSection(horizontal, Point3d(0.0,0.0,0.0), true))
        val vertical = listOf(Point3d(0.0,0.0,0.0), Point3d(0.0,2.0,0.0))
        assertTrue(ArMeasurementChecks.tankSection(vertical, Point3d(0.0,1.0,0.0), true))
        assertFalse(ArMeasurementChecks.tankSection(vertical, Point3d(0.0,1.0,0.0), false))
        assertFalse(ArMeasurementChecks.tankSection(horizontal, Point3d(Double.NaN,0.0,0.0), false))
    }
}
