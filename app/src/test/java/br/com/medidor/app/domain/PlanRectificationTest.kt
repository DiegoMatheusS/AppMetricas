package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanRectificationTest {
    @Test fun quadCorrectedToKnownSheetProportions() {
        val quad = listOf(
            PlanPoint(.1f, .2f), PlanPoint(.85f, .15f),
            PlanPoint(.9f, .85f), PlanPoint(.15f, .9f)
        )
        val size = PlanRectification.targetSize(quad, 1600, 1200, 21.0, 29.7)
        assertNotNull(size)
        assertEquals(21.0 / 29.7, size!!.width.toDouble() / size.height, .002)
        assertTrue(size.width <= 2048 && size.height <= 2048)
    }

    @Test fun reversedOrCrossedCornersRejected() {
        val reversed = listOf(
            PlanPoint(.1f,.1f), PlanPoint(.1f,.9f),
            PlanPoint(.9f,.9f), PlanPoint(.9f,.1f)
        )
        assertNull(PlanRectification.targetSize(reversed, 1024, 1024, 21.0, 29.7))
        val selfCrossing = listOf(
            PlanPoint(.1f,.1f), PlanPoint(.9f,.1f),
            PlanPoint(.1f,.9f), PlanPoint(.9f,.9f)
        )
        assertNull(PlanRectification.targetSize(selfCrossing, 1024, 1024, 21.0, 29.7))
    }

    @Test fun badDimensionsOrTinySelectionRejected() {
        val quad = listOf(
            PlanPoint(.4f,.4f), PlanPoint(.45f,.4f),
            PlanPoint(.45f,.45f), PlanPoint(.4f,.45f)
        )
        assertNull(PlanRectification.targetSize(quad, 1024, 1024, 21.0, 29.7))
        val full = listOf(
            PlanPoint(.1f,.1f), PlanPoint(.9f,.1f),
            PlanPoint(.9f,.9f), PlanPoint(.1f,.9f)
        )
        assertNull(PlanRectification.targetSize(full, 1024, 1024, 0.0, 29.7))
        assertNull(PlanRectification.targetSize(full, 1024, 1024, 21.0, Double.NaN))
        assertNull(PlanRectification.targetSize(full, 1024, 1024, 1.0, 100.0))
    }
}
