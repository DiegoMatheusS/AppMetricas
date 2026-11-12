package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerCaptureGuideTest {
    @Test fun allStepsAreUniqueAndInOrder() {
        val list = ScannerCaptureGuide.positions
        assertEquals(20, list.size)
        assertEquals((0 until 20).toList(), list.map { it.index })
        assertEquals(12, list.count { it.pass == ScanPass.HORIZON })
        assertEquals(8, list.count { it.pass == ScanPass.ELEVATED })
        assertEquals((0..11).map { it * 30 }, list.take(12).map { it.angleDegrees })
        assertEquals((0..7).map { it * 45 }, list.drop(12).map { it.angleDegrees })
    }

    @Test fun progressFollowsMissingSlotsRatherThanCaptureOrder() {
        val filled = setOf(0, 2, 10, 19)
        assertEquals(1, ScannerCaptureGuide.nextMissing(filled))
        assertFalse(ScannerCaptureGuide.complete(filled))
        assertNull(ScannerCaptureGuide.nextMissing((0 until 20).toSet()))
        assertTrue(ScannerCaptureGuide.complete((0 until 20).toList()))
    }

    @Test fun unexpectedOrRepeatedSlotsAreNotValid() {
        assertFalse(ScannerCaptureGuide.validSlots(listOf(1, 1)))
        assertFalse(ScannerCaptureGuide.validSlots(listOf(0, 20)))
        assertFalse(ScannerCaptureGuide.validSlots(listOf(-1)))
        assertFalse(ScannerCaptureGuide.complete((0..18).toList() + 18))
    }
}
