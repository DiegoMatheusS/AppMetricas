package br.com.medidor.app.domain

import org.junit.Assert.*
import org.junit.Test

class MultiViewRefinementTest {
    private fun observation(translation: Double, noise: Double = 0.0) = ReprojectionObservation(
        doubleArrayOf(1.0,0.0,0.0,translation, 0.0,1.0,0.0,0.0, 0.0,0.0,1.0,0.0),
        (.2 + translation) / 4 + noise, .1 / 4 - noise, 500.0)

    @Test fun refinesOnePointAgainstThreeCameras() {
        val views = listOf(observation(0.0, .0002), observation(-.6, -.0001), observation(.4, .0001))
        val start = Point3d(.23, .12, 4.2)
        val result = MultiViewRefinement.refine(start, views)!!
        assertEquals(.2, result.x, .01)
        assertEquals(.1, result.y, .01)
        assertEquals(4.0, result.z, .03)
        assertTrue(views.sumOf { MultiViewRefinement.residual(result, it) } <
            views.sumOf { MultiViewRefinement.residual(start, it) })
    }

    @Test fun pointsBehindTheCamerasAreRejected() {
        assertNull(MultiViewRefinement.refine(Point3d(.2, .1, -4.0), listOf(observation(0.0), observation(-.6))))
    }

    @Test fun repeatedCameraPositionDoesNotInventDepth() {
        assertNull(MultiViewRefinement.refine(Point3d(.2, .1, 4.0), List(3) { observation(0.0) }))
    }
}
