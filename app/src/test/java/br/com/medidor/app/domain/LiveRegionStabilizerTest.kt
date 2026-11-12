package br.com.medidor.app.domain

import org.junit.Assert.*
import org.junit.Test

class LiveRegionStabilizerTest {
    private fun region(x: Int, y: Int, size: Int = 10) =
        CountRegion(x, y, x + size - 1, y + size - 1, size * size)

    @Test fun fixedObjectsConfirmAfterThreeConsistentFrames() {
        val tracker = LiveRegionStabilizer()
        assertFalse(tracker.update(listOf(region(10, 20), region(60, 20)), 100, 100, 0))
        assertFalse(tracker.update(listOf(region(61, 20), region(11, 19)), 100, 100, 300))
        assertTrue(tracker.update(listOf(region(10, 21), region(60, 21)), 100, 100, 600))
    }

    @Test fun sameCountAtDifferentPositionsDoesNotFreezeFrame() {
        val tracker = LiveRegionStabilizer()
        assertFalse(tracker.update(listOf(region(10, 20)), 100, 100, 0))
        assertFalse(tracker.update(listOf(region(80, 20)), 100, 100, 300))
        assertFalse(tracker.update(listOf(region(80, 20)), 100, 100, 600))
        assertTrue(tracker.update(listOf(region(80, 20)), 100, 100, 900))
    }

    @Test fun areaChangesAndMissingObjectsRestartConfirmation() {
        val tracker = LiveRegionStabilizer()
        assertFalse(tracker.update(listOf(region(15, 15)), 100, 100, 0))
        assertFalse(tracker.update(listOf(region(15, 15, 25)), 100, 100, 300))
        assertFalse(tracker.update(emptyList(), 100, 100, 600))
        assertFalse(tracker.update(listOf(region(15, 15)), 100, 100, 900))
        assertFalse(tracker.update(listOf(region(15, 15)), 100, 100, 1_200))
        assertTrue(tracker.update(listOf(region(15, 15)), 100, 100, 1_500))
    }

    @Test fun emptySceneConfirmsZeroAndContinuesLookingForObjects() {
        val tracker = LiveRegionStabilizer()
        assertFalse(tracker.update(emptyList(), 100, 100, 0))
        assertFalse(tracker.update(emptyList(), 100, 100, 300))
        assertTrue(tracker.update(emptyList(), 100, 100, 600))
        assertFalse(tracker.update(listOf(region(20, 20)), 100, 100, 900))
        assertFalse(tracker.update(listOf(region(20, 20)), 100, 100, 1_200))
        assertTrue(tracker.update(listOf(region(20, 20)), 100, 100, 1_500))
    }

    @Test fun cameraPauseRequiresThreeFreshFrames() {
        val tracker = LiveRegionStabilizer()
        val pieces = listOf(region(20, 20))
        assertFalse(tracker.update(pieces, 100, 100, 0))
        assertFalse(tracker.update(pieces, 100, 100, 300))
        assertFalse(tracker.update(pieces, 100, 100, 3_000))
        assertFalse(tracker.update(pieces, 100, 100, 3_300))
        assertTrue(tracker.update(pieces, 100, 100, 3_600))
    }

    @Test fun duplicateOrOlderFrameCannotConfirmCount() {
        val tracker = LiveRegionStabilizer()
        val pieces = listOf(region(20, 20))
        assertFalse(tracker.update(pieces, 100, 100, 1_000))
        assertFalse(tracker.update(pieces, 100, 100, 1_300))
        assertFalse(tracker.update(pieces, 100, 100, 1_300))
        assertFalse(tracker.update(pieces, 100, 100, 1_200))
        assertFalse(tracker.update(pieces, 100, 100, 1_600))
        assertFalse(tracker.update(pieces, 100, 100, 1_900))
        assertTrue(tracker.update(pieces, 100, 100, 2_200))
    }

    @Test fun changedResolutionRestartsConfirmation() {
        val tracker = LiveRegionStabilizer()
        val pieces = listOf(region(20, 20))
        assertFalse(tracker.update(pieces, 100, 100, 0))
        assertFalse(tracker.update(pieces, 100, 100, 300))
        assertFalse(tracker.update(pieces, 200, 100, 600))
        assertFalse(tracker.update(pieces, 200, 100, 900))
        assertTrue(tracker.update(pieces, 200, 100, 1_200))
    }

    @Test fun unreadableFrameDiscardsEarlierConfirmation() {
        val tracker = LiveRegionStabilizer()
        val pieces = listOf(region(20, 20))
        assertFalse(tracker.update(pieces, 100, 100, 0))
        assertFalse(tracker.update(pieces, 100, 100, 300))
        tracker.reset()
        assertFalse(tracker.update(pieces, 100, 100, 600))
        assertFalse(tracker.update(pieces, 100, 100, 900))
        assertTrue(tracker.update(pieces, 100, 100, 1_200))
    }
}
