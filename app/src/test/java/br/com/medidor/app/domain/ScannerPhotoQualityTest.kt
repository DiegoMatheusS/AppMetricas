package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerPhotoQualityTest {
    private val size = ScannerPhotoQuality.THUMB_SIZE
    private fun image(pixel: (Int, Int) -> Int): IntArray =
        IntArray(size * size) { idx -> pixel(idx % size, idx / size) }
    private fun gray(v: Int): Int = 0xff000000.toInt() or (v shl 16) or (v shl 8) or v

    @Test fun detectsClippedPhotosAndBlankAreas() {
        val black = ScannerPhotoQuality.evaluate(image { _, _ -> gray(0) }, size, size)
        val white = ScannerPhotoQuality.evaluate(image { _, _ -> gray(255) }, size, size)
        assertTrue(black.warnings().any { it.contains("escura") })
        assertTrue(white.warnings().any { it.contains("clara") })
        assertTrue(black.warnings().any { it.contains("detalhes") })
        assertEquals(0.0, black.edgeEnergy, .000001)
        assertEquals(1.0, white.brightFraction, .000001)
    }

    @Test fun highContrastDetailHasHigherEnergy() {
        val checker = ScannerPhotoQuality.evaluate(
            image { x, y -> gray(if ((x + y) % 2 == 0) 0 else 255) }, size, size)
        assertTrue(checker.edgeEnergy > .8)
        assertFalse(checker.warnings().any { it.contains("detalhes") })
    }

    @Test fun similarImagesAreFlaggedAsPossibleRepeats() {
        val image = image { x, y -> gray(if ((x / 4 + y / 7) % 2 == 0) 40 else 210) }
        val first = ScannerPhotoQuality.evaluate(image, size, size)
        val altered = image.copyOf()
        altered[20 * size + 20] = gray(55)
        val second = ScannerPhotoQuality.evaluate(altered, size, size)
        assertEquals(listOf(0 to 1), ScannerPhotoQuality.similarPairs(mapOf(0 to first, 1 to second)))
    }

    @Test fun invalidInputIsRejected() {
        try {
            ScannerPhotoQuality.evaluate(IntArray(8), 96, 96)
            throw AssertionError("Invalid image should fail")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun missingSlotsAreEnumeratedWithoutInventingPhotos() {
        val m = ScannerPhotoQuality.evaluate(
            image { x, y -> gray(if (x == y) 0 else 180) }, size, size)
        val report = ScannerPhotoQuality.summarize(
            listOf(ScannerPhotoCheck(0, m, emptyList()), ScannerPhotoCheck(5, null, listOf("Imagem indisponível")))
        )
        assertEquals(listOf(0, 5), report.checks.map { it.slot })
        assertTrue(report.missingSlots.contains(1))
        assertTrue(report.missingSlots.size == 18)
        assertTrue(report.warningsCount >= 1)
    }
}
