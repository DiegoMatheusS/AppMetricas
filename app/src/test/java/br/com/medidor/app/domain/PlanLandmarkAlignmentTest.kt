package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanLandmarkAlignmentTest {
    private fun floor(point: PlanPoint?) = PlanFloor(
        title = "Planta",
        imageWidth = 1000,
        imageHeight = 1000,
        knownMeters = 4.0,
        referenceStart = PlanPoint(.1f, .1f),
        referenceEnd = PlanPoint(.5f, .1f),
        alignmentPoint = point
    )

    @Test fun offsetMatchesSameArchitecturalPointBetweenFloorImages() {
        val base = floor(PlanPoint(.6f, .5f))
        val upper = floor(PlanPoint(.4f, .5f))
        val result = PlanLandmarkAlignment.suggestedOffset(base, upper)!!
        assertEquals(2.0, result.first, .0001)
        assertEquals(0.0, result.second, .0001)
    }

    @Test fun manualBaseOffsetParticipatesInSuggestion() {
        val base = floor(PlanPoint(.6f, .4f))
            .copy(offsetXMeters = 1.5, offsetYMeters = -2.0)
        val upper = floor(PlanPoint(.4f, .6f))
        val result = PlanLandmarkAlignment.suggestedOffset(base, upper)!!
        assertEquals(3.5, result.first, .0001)
        assertEquals(-4.0, result.second, .0001)
    }

    @Test fun missingOrUncalibratedMarkersCannotBeAligned() {
        val base = floor(PlanPoint(.6f, .5f))
        assertNull(PlanLandmarkAlignment.suggestedOffset(base, floor(null)))
        assertNull(PlanLandmarkAlignment.suggestedOffset(
            base.copy(referenceEnd = null), floor(PlanPoint(.4f, .5f))
        ))
    }
}
