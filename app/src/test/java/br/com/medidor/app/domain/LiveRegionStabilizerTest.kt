package br.com.medidor.app.domain

import org.junit.Assert.*
import org.junit.Test

class LiveRegionStabilizerTest {
    private fun region(x: Int, y: Int, size: Int = 10) =
        CountRegion(x, y, x + size - 1, y + size - 1, size * size)

    @Test fun fixedObjectsConfirmAfterThreeConsistentFrames() {
        val tracker = LiveRegionStabilizer()
        assertFalse(tracker.update(listOf(region(10, 20), region(60, 20)), 100, 100))
        assertFalse(tracker.update(listOf(region(61, 20), region(11, 19)), 100, 100))
        assertTrue(tracker.update(listOf(region(10, 21), region(60, 21)), 100, 100))
    }

    @Test fun sameCountAtDifferentPositionsDoesNotFreezeFrame() {
        val tracker = LiveRegionStabilizer()
        assertFalse(tracker.update(listOf(region(10, 20)), 100, 100))
        assertFalse(tracker.update(listOf(region(80, 20)), 100, 100))
        assertFalse(tracker.update(listOf(region(80, 20)), 100, 100))
        assertTrue(tracker.update(listOf(region(80, 20)), 100, 100))
    }

    @Test fun areaChangesAndMissingObjectsRestartConfirmation() {
        val tracker = LiveRegionStabilizer()
        assertFalse(tracker.update(listOf(region(15, 15)), 100, 100))
        assertFalse(tracker.update(listOf(region(15, 15, 25)), 100, 100))
        assertFalse(tracker.update(emptyList(), 100, 100))
        assertFalse(tracker.update(listOf(region(15, 15)), 100, 100))
        assertFalse(tracker.update(listOf(region(15, 15)), 100, 100))
        assertTrue(tracker.update(listOf(region(15, 15)), 100, 100))
    }
}
