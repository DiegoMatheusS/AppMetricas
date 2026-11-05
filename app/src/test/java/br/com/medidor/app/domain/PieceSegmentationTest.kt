package br.com.medidor.app.domain

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PieceSegmentationTest {
    @Test fun rotatedRectanglesRemainSimilarButDifferentShapesAreRejected() {
        val width = 150
        val height = 120
        fun rectangle(centerX: Double, angle: Double): IntArray =
            IntArray(width * height) { index ->
                val x = index % width - centerX
                val y = index / width - 55.0
                val a = x * cos(angle) + y * sin(angle)
                val b = -x * sin(angle) + y * cos(angle)
                if (abs(a) < 19 && abs(b) < 8) 0xFF993322.toInt() else 0xFFFFFFFF.toInt()
            }
        val first = LiveObjectCounter.detect(rectangle(55.0, 0.0), width, height).single()
        val rotated = LiveObjectCounter.detect(rectangle(65.0, .73), width, height).single()
        assertTrue(SimilarObjectCounter.matches(SimilarObjectCounter.appearance(first)!!, rotated))
    }

    @Test fun movingCloserDoesNotSplitOneIntactLongPieceIntoMultiplePieces() {
        val width = 180
        val height = 120
        fun scene(halfWidth: Int, halfHeight: Int): IntArray =
            IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                if (x in 90 - halfWidth..90 + halfWidth && y in 60 - halfHeight..60 + halfHeight)
                    0xFF993322.toInt() else 0xFFFFFFFF.toInt()
            }
        val original = LiveObjectCounter.detect(scene(20, 6), width, height).single()
        val reference = SimilarObjectCounter.appearance(original)!!
        val closer = LiveObjectCounter.detect(scene(34, 10), width, height, reference = reference)
        assertEquals(1, closer.size)
        assertTrue(SimilarObjectCounter.matches(reference, closer.single()))
    }

    @Test fun distanceWatershedSplitsTwoTouchingVisiblePieces() {
        val width = 100
        val height = 80
        val radius = 12.0
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val inside = hypot(x - 39.0, y - 40.0) <= radius || hypot(x - 60.0, y - 40.0) <= radius
            if (inside) 0xFF993322.toInt() else 0xFFFFFFFF.toInt()
        }
        assertEquals(1, LiveObjectCounter.detect(pixels, width, height).size)
        val separated = LiveObjectCounter.detect(pixels, width, height, referenceArea = PI * radius * radius)
        assertEquals(2, separated.size)
        assertTrue(separated.all { it.area > 300 })
    }
}
