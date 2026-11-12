package br.com.medidor.app.domain

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class LightingCounterTest {
    private val width = 140
    private val height = 130
    private fun gradient() = IntArray(width * height) { index ->
        val light = 55 + index % width + index / width / 3
        -0x1000000 or (light shl 16) or (light shl 8) or light
    }

    @Test fun emptyLightingGradientDoesNotBecomeAnObject() {
        assertTrue(LiveObjectCounter.detect(gradient(), width, height).isEmpty())
    }

    @Test fun objectsAreSeparatedFromTheirLocalBackground() {
        val pixels = gradient()
        for (y in 20..40) for (x in 15..35) pixels[y * width + x] = 0xFFEA2010.toInt()
        for (y in 70..90) for (x in 95..115) pixels[y * width + x] = 0xFFEA2010.toInt()
        assertEquals(2, LiveObjectCounter.detect(pixels, width, height).size)
    }

    @Test fun intactPiecesProvideScaleForATouchingPair() {
        val pixels = IntArray(width * height) { index ->
            val x = index % width; val y = index / width
            val inside = listOf(25.0 to 25.0, 95.0 to 25.0, 50.0 to 85.0, 71.0 to 85.0)
                .any { hypot(x - it.first, y - it.second) <= 12 }
            if (inside) 0xFF993322.toInt() else 0xFFFFFFFF.toInt()
        }
        assertEquals(4, LiveObjectCounter.detect(pixels, width, height).size)
    }

    @Test fun unrelatedColorsDoNotBorrowTheExemplarSize() {
        val pixels = IntArray(width * height) { index ->
            val x = index % width; val y = index / width
            when {
                hypot(x - 25.0, y - 25.0) <= 12 || hypot(x - 95.0, y - 25.0) <= 12 -> 0xFF993322.toInt()
                hypot(x - 50.0, y - 85.0) <= 12 || hypot(x - 71.0, y - 85.0) <= 12 -> 0xFF2255CC.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
        }
        assertEquals(3, LiveObjectCounter.detect(pixels, width, height).size)
    }
}
