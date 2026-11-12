package br.com.medidor.app.domain

import org.junit.Assert.*
import org.junit.Test

class AngledPlanWallsTest {
    private fun wall(ax: Float, ay: Float, bx: Float, by: Float) = PlanWall(PlanPoint(ax, ay), PlanPoint(bx, by))

    @Test fun duplicateEdgesMergeInANonSquareImage() {
        val merged = AngledPlanWalls.merge(listOf(wall(.1f,.2f,.6f,.7f), wall(.6f,.708f,.1f,.208f)), 1000, 500, 8.0)
        assertEquals(1, merged.size)
        assertEquals(.1, merged.single().start.x.toDouble(), .01)
        assertEquals(.7, merged.single().end.y.toDouble(), .02)
    }

    @Test fun crossingWallsRemainSeparate() {
        assertEquals(2, AngledPlanWalls.merge(listOf(wall(.1f,.1f,.8f,.8f), wall(.1f,.8f,.8f,.1f)), 500, 500, 5.0).size)
    }

    @Test fun largeGapsArePreserved() {
        assertEquals(2, AngledPlanWalls.merge(listOf(wall(.1f,.1f,.3f,.3f), wall(.6f,.6f,.8f,.8f)), 500, 500, 5.0).size)
    }
}
