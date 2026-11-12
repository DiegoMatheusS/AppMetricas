package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class ArchitectGeometryTest {
    private val calibratedFloor = PlanFloor(
        title = "Térreo",
        imageWidth = 1000,
        imageHeight = 500,
        knownMeters = 4.0,
        referenceStart = PlanPoint(0.1f, 0.1f),
        referenceEnd = PlanPoint(0.5f, 0.1f)
    )

    @Test fun knownSegmentSetsPixelsPerMeter() {
        assertEquals(100.0, FloorPlanMath.pixelsPerMeter(calibratedFloor)!!, 0.0001)
    }

    @Test fun wallLengthUsesImageDimensionsNotScreenAspectRatio() {
        val wall = PlanWall(PlanPoint(0.1f, 0.2f), PlanPoint(0.1f, 0.6f))
        assertEquals(2.0, FloorPlanMath.wallLengthMeters(calibratedFloor, wall)!!, 0.001)
    }

    @Test fun totalsWorkAcrossWalls() {
        val walls = listOf(
            PlanWall(PlanPoint(0.0f, 0.0f), PlanPoint(0.2f, 0.0f)),
            PlanWall(PlanPoint(0.2f, 0.0f), PlanPoint(0.2f, 0.4f))
        )
        assertEquals(4.0, FloorPlanMath.totalWallMeters(calibratedFloor.copy(walls = walls))!!, 0.0001)
    }

    @Test fun uncalibratedPlanNeverPretendsToBeScaled() {
        val unscaled = calibratedFloor.copy(referenceEnd = null)
        assertFalse(unscaled.calibrated)
        assertNull(FloorPlanMath.totalWallMeters(unscaled))
        assertNull(FloorPlanMath.worldPointMeters(unscaled, PlanPoint(.5f, .5f)))
    }

    @Test fun zeroLengthReferenceRejected() {
        val bad = calibratedFloor.copy(referenceEnd = calibratedFloor.referenceStart)
        assertNull(FloorPlanMath.pixelsPerMeter(bad))
    }
}
