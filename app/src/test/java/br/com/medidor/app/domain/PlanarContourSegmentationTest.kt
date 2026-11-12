package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanarContourSegmentationTest {
    private val width = 80
    private val height = 60
    private fun scene(): IntArray = IntArray(width*height){ i->
        val x=i%width
        val y=i/width
        if(x in 20..39 && y in 10..29) 0xff2244bb.toInt()
        else 0xffdedede.toInt()
    }
    @Test fun extractsSelectedObjectAndItsBoundingBox() {
        val result=PlanarContourSegmentation.segment(scene(),width,height,24,16)!!
        assertEquals(20,result.minX)
        assertEquals(39,result.maxX)
        assertEquals(10,result.minY)
        assertEquals(29,result.maxY)
        assertEquals(400,result.pixelsInside)
        assertTrue(result.outline.isNotEmpty())
        assertEquals(.25,result.widthFraction,0.00001)
    }
    @Test fun backgroundIsRejected() {
        assertNull(PlanarContourSegmentation.segment(scene(),width,height,1,1))
    }
    @Test fun badInputsRejected() {
        try { PlanarContourSegmentation.segment(IntArray(10),width,height,1,1)
              throw AssertionError("invalid bitmap") }
        catch(_:IllegalArgumentException){}
    }
}
