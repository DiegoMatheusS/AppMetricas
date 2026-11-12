package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualReferenceTest {
    @Test fun convertsUnits() {
        assertEquals(25.0, PhotoCalibration.manualReferenceMillimeters("2,5", LengthUnit.CENTIMETERS)!!, 1e-5)
        assertEquals(1500.0, PhotoCalibration.manualReferenceMillimeters("1.5", LengthUnit.METERS)!!, 1e-5)
        assertEquals(12.0, PhotoCalibration.manualReferenceMillimeters("12", LengthUnit.MILLIMETERS)!!, 1e-5)
    }
    @Test fun rejectsInvalidReference() {
        for (text in listOf("", "0", "-2", "NaN", "Infinity")) {
            assertNull(PhotoCalibration.manualReferenceMillimeters(text, LengthUnit.CENTIMETERS))
        }
    }
    @Test fun derivedLength() {
        val ref = PhotoCalibration.manualReferenceMillimeters("10", LengthUnit.CENTIMETERS)!!
        assertEquals(250.0, PhotoCalibration.millimetersFromReference(ref, 40.0, 100.0), 1e-5)
    }
}
