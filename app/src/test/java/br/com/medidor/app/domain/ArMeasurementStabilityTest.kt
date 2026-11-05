package br.com.medidor.app.domain

import org.junit.Assert.*
import org.junit.Test

class ArMeasurementStabilityTest {
    private val segment = listOf(Point3d(0.0, 0.0, 0.0), Point3d(1.0, 0.0, 0.0))
    private fun shifted(points: List<Point3d>, x: Double) = points.map { it.copy(x = it.x + x) }

    @Test fun averagesThreeCoherentMetricObservationsAndAcceptsReversedEndpoints() {
        val stability = ArMeasurementStability()
        assertNull(stability.observe(segment, false, 100, 0))
        assertNull(stability.observe(shifted(segment, .003).reversed(), false, 200, 650))
        val result = stability.observe(shifted(segment, -.003), false, 300, 1300)!!
        assertTrue(stability.stable)
        assertEquals(0.0, result[0].x, .00001)
        assertEquals(1.0, result[1].x, .00001)
    }

    @Test fun alignsCyclicAndReversedAreaCorners() {
        val polygon = listOf(Point3d(0.0,0.0,0.0), Point3d(2.0,0.0,0.0),
            Point3d(2.0,0.0,1.0), Point3d(0.0,0.0,1.0))
        val stability = ArMeasurementStability()
        stability.observe(polygon, true, 100, 0)
        stability.observe(polygon.drop(2) + polygon.take(2), true, 200, 650)
        val result = stability.observe(polygon.reversed(), true, 300, 1300)
        assertEquals(polygon, result)
    }

    @Test fun matchingImagePositionDoesNotAcceptAJumpInDepth() {
        val stability = ArMeasurementStability()
        stability.observe(segment, false, 100, 0)
        stability.observe(segment, false, 200, 650)
        val jump = segment.map { it.copy(z = 1.0) }
        assertNull(stability.observe(jump, false, 300, 1300))
        assertFalse(stability.stable)
        assertEquals(1, stability.count)
    }

    @Test fun freezesAndRapidSamplesCannotCreateAStableMeasurement() {
        val stability = ArMeasurementStability()
        stability.observe(segment, false, 100, 0)
        assertNull(stability.observe(segment, false, 100, 650))
        assertEquals(1, stability.count)
        stability.observe(segment, false, 200, 700)
        assertNull(stability.observe(segment, false, 300, 720))
        assertEquals(2, stability.count)
        assertFalse(stability.stable)
        assertNotNull(stability.observe(segment, false, 400, 900))
    }

    @Test fun transientLossCornerCountChangesAndNonfinitePointsResetTheWindow() {
        val stability = ArMeasurementStability()
        stability.observe(segment, false, 100, 0)
        stability.observe(segment, false, 200, 650)
        assertNull(stability.observe(segment, false, 300, 3000))
        assertEquals(1, stability.count)
        assertNull(stability.observe(listOf(Point3d(Double.NaN,0.0,0.0), segment[1]), false, 400, 3500))
        assertEquals(0, stability.count)
        stability.observe(segment, false, 500, 4000)
        stability.reset()
        assertNull(stability.referencePoints)
        assertFalse(stability.stable)
    }

    @Test fun aStretchingContourAndSlowCumulativeDriftAreRejected() {
        val stability = ArMeasurementStability()
        stability.observe(segment, false, 100, 0)
        stability.observe(shifted(segment,.009), false, 200, 650)
        assertNull(stability.observe(shifted(segment,.018), false, 300, 1300))
        assertEquals(1, stability.count)
        assertNull(stability.observe(listOf(segment[0], segment[1].copy(x = 1.3)), false, 400, 1950))
        assertFalse(stability.stable)
    }

    @Test fun trackingInvalidationAndDuplicateCameraFramesPreventConfirmation() {
        val validity = ArFrameValidity()
        assertFalse(validity.canConfirm(0))
        assertTrue(validity.update(50, true, 100))
        assertTrue(validity.update(50, true, 500))
        assertFalse(validity.canConfirm(851))
        assertFalse(validity.update(50, true, 900))
        assertTrue(validity.update(60, true, 950))
        validity.invalidate()
        assertFalse(validity.update(60, true, 960))
        assertTrue(validity.update(70, true, 980))
        assertFalse(validity.update(80, false, 1000))
        assertTrue(validity.update(90, true, 1100))
        assertFalse(validity.update(85, true, 1200))
        assertFalse(validity.canConfirm(1200))
        validity.reset()
        assertTrue(validity.update(1, true, 1300))
    }
}
