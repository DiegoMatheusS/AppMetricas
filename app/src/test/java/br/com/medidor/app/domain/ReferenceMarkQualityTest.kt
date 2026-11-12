package br.com.medidor.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceMarkQualityTest {
    @Test fun rejectsShortAndInvalidReferences() {
        assertFalse(ReferenceMarkQuality.isReadable(0.0))
        assertFalse(ReferenceMarkQuality.isReadable(19.99))
        assertFalse(ReferenceMarkQuality.isReadable(Double.NaN))
        assertFalse(ReferenceMarkQuality.isReadable(Double.POSITIVE_INFINITY))
        assertFalse(ReferenceMarkQuality.isReadable(-4.0))
    }

    @Test fun distinguishesReadableButShortReferences() {
        assertFalse(ReferenceMarkQuality.needsLargerReference(19.0))
        assertTrue(ReferenceMarkQuality.needsLargerReference(20.0))
        assertTrue(ReferenceMarkQuality.needsLargerReference(49.99))
        assertFalse(ReferenceMarkQuality.needsLargerReference(50.0))
        assertFalse(ReferenceMarkQuality.needsLargerReference(Double.NaN))
    }

    @Test fun acceptsReferencesFromTwentyPixels() {
        assertTrue(ReferenceMarkQuality.isReadable(20.0))
        assertTrue(ReferenceMarkQuality.isReadable(100.0))
    }
}
