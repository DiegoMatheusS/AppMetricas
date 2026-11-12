package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectOpeningsTest {
    private val room = PlanFloor(
        title = "Térreo",
        imageWidth = 1000,
        imageHeight = 500,
        knownMeters = 4.0,
        referenceStart = PlanPoint(.1f, .1f),
        referenceEnd = PlanPoint(.5f, .1f),
        wallHeightMeters = 2.7,
        walls = listOf(PlanWall(PlanPoint(.1f, .25f), PlanPoint(.5f, .25f)))
    )

    @Test fun doorCutsWallToGround() {
        val door = PlanOpening(0, OpeningType.DOOR, .5, .9, 0.0, 2.1)
        assertTrue(FloorPlanMath.canAddOpening(room, door))
        val withDoor = room.copy(openings = listOf(door))
        assertEquals(4.0 * 2.7 - .9 * 2.1,
            Architect3dGeometry.wallSurfaceAreaM2(withDoor, 0), .001)
        assertEquals(3, Architect3dGeometry.wallPieces(withDoor, 0).size)
        assertEquals(18, Architect3dGeometry.model(ArchitectPlan(listOf(withDoor)), true).size)
    }

    @Test fun windowLeavesUpperAndLowerWalls() {
        val window = PlanOpening(0, OpeningType.WINDOW, .5, 1.2, .9, 1.2)
        assertTrue(FloorPlanMath.canAddOpening(room, window))
        val withWindow = room.copy(openings = listOf(window))
        assertEquals(4.0 * 2.7 - 1.2 * 1.2,
            Architect3dGeometry.wallSurfaceAreaM2(withWindow, 0), .001)
        assertEquals(4, Architect3dGeometry.wallPieces(withWindow, 0).size)
    }

    @Test fun overlappingAndOutOfBoundsAperturesRejected() {
        val door = PlanOpening(0, OpeningType.DOOR, .5, 1.0, 0.0, 2.1)
        val first = room.copy(openings = listOf(door))
        val overlapping = PlanOpening(0, OpeningType.WINDOW, .6, 1.0, 1.0, 1.0)
        assertFalse(FloorPlanMath.canAddOpening(first, overlapping))
        assertFalse(FloorPlanMath.canAddOpening(first, door.copy(centerFraction = .05)))
        assertFalse(FloorPlanMath.canAddOpening(first, door.copy(heightMeters = 3.0)))
        assertFalse(FloorPlanMath.canAddOpening(first, door.copy(wallIndex = 2)))
    }

    @Test fun doorRequiresGroundContactAndValidScale() {
        val floating = PlanOpening(0, OpeningType.DOOR, .5, .9, .3, 2.0)
        assertFalse(FloorPlanMath.canAddOpening(room, floating))
        assertFalse(FloorPlanMath.canAddOpening(
            room.copy(referenceEnd = null), floating.copy(sillMeters = 0.0)))
    }

    @Test fun duplicatedFloorsAreStacked() {
        val upper = room.copy(title = "2º", openings = listOf(
            PlanOpening(0, OpeningType.DOOR, .5, .9, 0.0, 2.1)
        ))
        val faces = Architect3dGeometry.model(ArchitectPlan(listOf(room, upper)), true)
        assertTrue(faces.any { it.floorIndex == 1 && it.corners.any { p -> p.z > 2.7 } })
        val isolated = Architect3dGeometry.model(ArchitectPlan(listOf(room, upper), 1), false)
        assertTrue(isolated.all { it.floorIndex == 1 })
        assertTrue(isolated.flatMap { it.corners }.any { it.z == 0.0 })
    }
}
