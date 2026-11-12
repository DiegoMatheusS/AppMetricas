package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TankFillInputTest {
    @Test fun convertsMetersCentimetersAndHeightPercentage() {
        assertEquals(1.25,TankFillInput.heightMeters(125.0,TankLevelUnit.CENTIMETERS,2.0)!!,1e-9)
        assertEquals(1.25,TankFillInput.heightMeters(1.25,TankLevelUnit.METERS,2.0)!!,1e-9)
        assertEquals(1.25,TankFillInput.heightMeters(62.5,TankLevelUnit.HEIGHT_PERCENT,2.0)!!,1e-9)
    }
    @Test fun rejectsOverfilledAndInvalid() {
        assertNull(TankFillInput.heightMeters(110.0,TankLevelUnit.HEIGHT_PERCENT,2.0))
        assertNull(TankFillInput.heightMeters(220.0,TankLevelUnit.CENTIMETERS,2.0))
        assertNull(TankFillInput.heightMeters(-1.0,TankLevelUnit.METERS,2.0))
    }
    @Test fun productMassFromUserDensity() {
        assertEquals(800.0,TankFillInput.estimatedMassKg(1.0,800.0)!!,1e-9)
        assertNull(TankFillInput.estimatedMassKg(1.0,0.0))
    }
}
