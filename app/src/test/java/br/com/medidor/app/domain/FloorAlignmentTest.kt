package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorAlignmentTest {
    private val floor = PlanFloor(
        title = "Térreo",
        imageWidth = 1000, imageHeight = 700,
        knownMeters = 5.0,
        referenceStart = PlanPoint(.0f, .1f),
        referenceEnd = PlanPoint(.5f, .1f),
        walls = listOf(
            PlanWall(PlanPoint(.25f, .25f), PlanPoint(.7f, .25f))
        )
    )

    @Test fun manualFloorAlignmentOffsetsAllVertices() {
        val origin = Architect3dGeometry.model(ArchitectPlan(listOf(floor)), true)
        val shifted = Architect3dGeometry.model(
            ArchitectPlan(listOf(floor.copy(offsetXMeters = 1.5, offsetYMeters = -.75))), true
        )
        assertEquals(origin.size, shifted.size)
        assertTrue(origin.isNotEmpty())
        origin.zip(shifted).forEach { (a, b) ->
            a.corners.zip(b.corners).forEach { (p, q) ->
                assertEquals(1.5, q.x - p.x, 1e-8)
                assertEquals(-.75, q.y - p.y, 1e-8)
                assertEquals(0.0, q.z - p.z, 1e-8)
            }
        }
    }

    @Test fun twoFloorsKeepSeparateAlignmentAndStackHeight() {
        val upper = floor.copy(
            title = "2º andar", offsetXMeters = 2.0, offsetYMeters = 1.0
        )
        val faces = Architect3dGeometry.model(ArchitectPlan(listOf(floor, upper)), true)
        assertTrue(faces.any { it.floorIndex == 1 })
        assertTrue(faces.filter { it.floorIndex == 1 }
            .flatMap { it.corners }.all { it.z >= floor.wallHeightMeters })
        val baseX = faces.first { it.floorIndex == 0 }.corners[0].x
        val upperX = faces.first { it.floorIndex == 1 }.corners[0].x
        assertEquals(2.0, upperX - baseX, 1e-8)
    }
}
