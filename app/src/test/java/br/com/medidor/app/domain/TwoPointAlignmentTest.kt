package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoPointAlignmentTest {
    private fun floor(p1: PlanPoint, p2: PlanPoint) = PlanFloor(
        title = "Planta",
        imageWidth = 1000, imageHeight = 1000, knownMeters = 4.0,
        referenceStart = PlanPoint(.1f, .1f),
        referenceEnd = PlanPoint(.5f, .1f),
        alignmentPoint = p1, secondAlignmentPoint = p2
    )

    @Test fun twoLandmarksRotateAndTranslateUpperFloor() {
        val base = floor(PlanPoint(.6f, .5f), PlanPoint(.6f, .7f))
        val upper = floor(PlanPoint(.5f, .4f), PlanPoint(.7f, .4f))
        val pose = PlanLandmarkAlignment.suggestedPose(base, upper)
        assertNotNull(pose)
        assertEquals(90.0, pose!!.rotationDegrees, 1e-5)
        assertEquals(0.0, pose.offsetXMeters, 1e-4)
        assertEquals(0.0, pose.offsetYMeters, 1e-4)
        assertEquals(0.0, pose.landmarkDifferenceMeters, 1e-4)
    }

    @Test fun invalidOrTooShortReferenceIsRejected() {
        val base = floor(PlanPoint(.5f, .5f), PlanPoint(.501f, .5f))
        val upper = floor(PlanPoint(.5f, .5f), PlanPoint(.7f, .5f))
        assertNull(PlanLandmarkAlignment.suggestedPose(base, upper))
        assertNull(PlanLandmarkAlignment.suggestedPose(
            upper, base.copy(secondAlignmentPoint = null)))
    }

    @Test fun scaleMismatchDoesNotPretendToBeSolvedByRotation() {
        val base = floor(PlanPoint(.4f, .5f), PlanPoint(.7f, .5f))
        val upper = base.copy(knownMeters = 5.0)
        assertNull(PlanLandmarkAlignment.suggestedPose(base, upper))
    }

    @Test fun onePointAlignmentAccountsForManualRotation() {
        val base = floor(PlanPoint(.6f, .5f), PlanPoint(.6f,.7f))
        val upper = floor(PlanPoint(.5f, .4f), PlanPoint(.7f,.4f))
            .copy(rotationDegrees = 90.0)
        val offset = PlanLandmarkAlignment.suggestedOffset(base, upper)!!
        assertEquals(0.0, offset.first, 1e-4)
        assertEquals(0.0, offset.second, 1e-4)
    }

    @Test fun rotateEntireSolidAroundPlanCenter() {
        val wall = PlanWall(PlanPoint(.5f, .5f), PlanPoint(.7f, .5f))
        val normal = floor(PlanPoint(.5f, .5f), PlanPoint(.7f, .5f))
            .copy(walls = listOf(wall))
        val turned = normal.copy(rotationDegrees = 90.0)
        val original = Architect3dGeometry.model(ArchitectPlan(listOf(normal)), true)
        val rotated = Architect3dGeometry.model(ArchitectPlan(listOf(turned)), true)
        assertTrue(original.isNotEmpty())
        original.zip(rotated).forEach { (a, b) ->
            a.corners.zip(b.corners).forEach { (p, q) ->
                assertEquals(-p.y, q.x, 1e-6)
                assertEquals(p.x, q.y, 1e-6)
                assertEquals(p.z, q.z, 1e-6)
            }
        }
    }

    @Test fun normalizesAngles() {
        assertEquals(-90.0, PlanFloorPose.normalizeDegrees(270.0), 1e-8)
        assertEquals(90.0, PlanFloorPose.normalizeDegrees(-270.0), 1e-8)
    }
}
