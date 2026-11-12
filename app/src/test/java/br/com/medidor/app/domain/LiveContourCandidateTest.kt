package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveContourCandidateTest {
    private val width = 100
    private val height = 80

    private fun scene(): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        when {
            x in 35..60 && y in 20..55 -> 0xFF1645A8.toInt()
            x in 11..23 && y in 11..24 -> 0xFFAA4A22.toInt()
            else -> 0xFFECECEC.toInt()
        }
    }

    @Test fun automaticDiscoveryFindsCentralObjectWithoutTap() {
        val found = LiveContourCandidate.find(scene(), width, height, null)
        assertNotNull(found.result)
        assertEquals(35, found.result!!.minX)
        assertEquals(60, found.result!!.maxX)
        assertEquals(20, found.result!!.minY)
    }

    @Test fun tappingObjectSelectsThatObjectRatherThanUnrelatedLargerOne() {
        val found = LiveContourCandidate.find(
            scene(), width, height, NormalizedPoint(.17f,.20f)
        )
        assertNotNull(found.result)
        assertEquals(11, found.result!!.minX)
        assertEquals(23, found.result!!.maxX)
    }

    @Test fun backgroundTapDoesNotChooseAnUnrelatedObject() {
        val found = LiveContourCandidate.find(
            scene(), width, height, NormalizedPoint(.93f,.80f)
        )
        assertNull(found.result)
        assertTrue(found.label.contains("Não encontrei"))
    }

    @Test fun transientMissDoesNotPreventRetryingSameTarget() {
        val blank = IntArray(width * height) { 0xFFECECEC.toInt() }
        val target = NormalizedPoint(.5f,.4f)
        val miss = LiveContourCandidate.find(blank,width,height,target)
        assertNull(miss.result)
        val next = LiveContourCandidate.find(scene(),width,height,target)
        assertNotNull(next.result)
    }

    @Test fun lowContrastAreaIsFoundUsingAlternateThreshold() {
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (x in 30..62 && y in 19..54) 0xFF9F9F9F.toInt()
            else 0xFFBBBBBB.toInt()
        }
        val result = LiveContourCandidate.find(pixels, width, height, null)
        assertNotNull(result.result)
        assertEquals(30, result.result!!.minX)
    }

    @Test fun manualBoundRestrictsSelectionAndReportsInvalidTap() {
        val region = ContourSearchBounds(30,15,65,60)
        val rejected = LiveContourCandidate.find(
            scene(),width,height,NormalizedPoint(.17f,.2f),bounds=region)
        assertNull(rejected.result)
        assertTrue(rejected.label.contains("dentro da área"))
        val accepted = LiveContourCandidate.find(
            scene(),width,height,NormalizedPoint(.5f,.4f),bounds=region)
        assertNotNull(accepted.result)
    }
}
