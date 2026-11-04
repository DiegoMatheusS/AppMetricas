package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LiveContourTrackingTest {
    private fun contour(x: Int, y: Int, w: Int=20,h: Int=20) =
        PlanarContourResult(160,120,x,y,x+w-1,y+h-1,w*h,emptyList())

    @Test fun acceptsSimilarObjectPositionFromConsecutiveFrames() {
        val p=contour(60,40)
        val q=contour(65,43)
        assertNotNull(LiveContourTracking.check(p,q).result)
        assertEquals("Contorno aproximado atualizado.",LiveContourTracking.check(p,q).label)
    }
    @Test fun rejectsSuddenRegionJumpOrAreaExplosion() {
        assertNull(LiveContourTracking.check(contour(10,10),contour(105,70)).result)
        assertNull(LiveContourTracking.check(contour(60,40),contour(50,25,60,65)).result)
    }
    @Test fun followsModeratelyMovingContourWithinBounds() {
        val prior=contour(60,40)
        val next=contour(68,46)
        val from=NormalizedPoint(.43f,.42f)
        val seed=LiveContourTracking.followSeed(prior,next,from)!!
        assertEquals(.48f,seed.x,.0001f)
        assertEquals(.47f,seed.y,.0001f)
    }

    @Test fun refusesToFollowLostObject() {
        assertNull(LiveContourTracking.followSeed(contour(10,10),contour(105,70),
            NormalizedPoint(.15f,.15f)))
    }

    @Test fun doesNotInventMeasurementsOrKeepStaleContourWhenLost() {
        assertNull(LiveContourTracking.check(contour(50,50),null).result)
        assertNotNull(LiveContourTracking.check(null,contour(50,50)).result)
    }
    @Test fun automaticTrackingConfirmsAcrossFramesAndRecoversAfterBriefLoss() {
        val tracker = LiveContourStabilizer()
        val first = contour(55, 40)
        val nearby = contour(57, 42)
        val distant = contour(108, 69)
        val miss = LiveContourState(null, "Sem contorno")

        assertNull(tracker.update(LiveContourState(first, ""), automatic = true).result)
        assertNull(tracker.update(LiveContourState(distant, ""), automatic = true).result)
        assertNull(tracker.update(LiveContourState(first, ""), automatic = true).result)
        assertEquals(nearby, tracker.update(LiveContourState(nearby, ""), automatic = true).result)
        assertNull(tracker.update(miss, automatic = true).result)
        assertEquals(nearby, tracker.previous)
        assertEquals(first, tracker.update(LiveContourState(first, ""), automatic = true).result)
        repeat(3) { tracker.update(miss, automatic = true) }
        assertNull(tracker.previous)
        assertNull(tracker.update(LiveContourState(distant, ""), automatic = true).result)
    }
}
