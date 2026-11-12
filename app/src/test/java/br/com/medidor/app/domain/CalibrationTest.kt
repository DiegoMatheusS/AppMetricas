package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CalibrationTest {
    @Test fun referenceCoinScalesObjectLength() {
        assertEquals(50.0, PhotoCalibration.millimetersFromReference(25.0, 100.0, 200.0), 0.001)
    }

    @Test fun normalizedPixelDistanceAccountsForAspectRatio() {
        assertEquals(100.0, PhotoCalibration.distancePixels(
            NormalizedPoint(0f, 0f), NormalizedPoint(0.5f, 0f), 200, 400), 0.001)
    }

    @Test fun zeroReferenceRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PhotoCalibration.millimetersFromReference(25.0, 0.0, 15.0)
        }
    }
}
