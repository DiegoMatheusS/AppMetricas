package br.com.medidor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContourGuidanceTest {
    @Test fun createsAreaWithEitherCornerOrderAndChecksLimits() {
        val region = ContourNormalizedArea.fromCorners(
            NormalizedPoint(.8f,.7f),NormalizedPoint(.2f,.1f))!!
        assertEquals(.2f,region.left,.0001f)
        assertEquals(.1f,region.top,.0001f)
        assertTrue(region.contains(NormalizedPoint(.5f,.5f)))
        assertFalse(region.contains(NormalizedPoint(.9f,.5f)))
        val bounds = region.pixels(100,80)
        assertTrue(bounds.contains(50,30))
        assertFalse(bounds.contains(0,0))
        assertNull(ContourNormalizedArea.fromCorners(
            NormalizedPoint(.5f,.5f),NormalizedPoint(.51f,.51f)))
    }

    @Test fun reportsVisualBorderAndFillingQualityWithoutClaimingAccuracy() {
        val touching = PlanarContourResult(100,80,0,10,20,40,630,emptyList())
        assertEquals(ContourVisualRating.LOW,
            ContourVisualQuality.assess(touching).rating)
        val good = PlanarContourResult(100,80,30,20,49,39,400,emptyList())
        assertEquals(ContourVisualRating.STABLE,
            ContourVisualQuality.assess(good).rating)
        val unstable = good.copy(pixelsInside=240)
        assertEquals(ContourVisualRating.CAUTION,
            ContourVisualQuality.assess(unstable,good).rating)
    }

    @Test fun detectsSelectionJumpEvenWhenPixelAreaIsUnchanged() {
        val first = PlanarContourResult(100,100,20,20,39,39,400,emptyList())
        val jumped = first.copy(minX=55,maxX=74)
        val rating = ContourVisualQuality.assess(jumped,first)
        assertEquals(ContourVisualRating.CAUTION,rating.rating)
        assertTrue(rating.explanation.contains("deslocou"))
    }

    @Test fun detectsBoundingBoxChangeWithSimilarPixelCount() {
        val original = PlanarContourResult(100,100,30,30,49,49,300,emptyList())
        val stretched = PlanarContourResult(100,100,25,34,54,45,300,emptyList())
        val rating = ContourVisualQuality.assess(stretched,original)
        assertEquals(ContourVisualRating.CAUTION,rating.rating)
        assertTrue(rating.explanation.contains("Formato"))
    }

    @Test fun sameContourRemainsApparentlyStableAcrossResolutions() {
        val small = PlanarContourResult(100,100,30,30,49,49,400,emptyList())
        val large = PlanarContourResult(200,200,60,60,99,99,1600,emptyList())
        assertEquals(ContourVisualRating.STABLE,
            ContourVisualQuality.assess(large,small).rating)
    }

    @Test fun foregroundConnectedToBackgroundIsCappedBySelectedArea() {
        val w=100
        val h=80
        val pixels=IntArray(w*h) { i ->
            val x=i%w; val y=i/w
            if (x in 25..75 && y in 15..65) 0xff2255aa.toInt()
            else 0xffdddddd.toInt()
        }
        val bounds=ContourSearchBounds(30,25,60,55)
        val contour=PlanarContourSegmentation.segment(pixels,w,h,40,35,
            tolerance=22,bounds=bounds)
        assertNotNull(contour)
        assertEquals(30,contour!!.minX)
        assertEquals(60,contour.maxX)
        assertEquals(25,contour.minY)
        assertEquals(55,contour.maxY)
        assertEquals(31*31,contour.pixelsInside)
        assertNull(PlanarContourSegmentation.segment(pixels,w,h,10,10,
            tolerance=22,bounds=bounds))
        assertEquals(ContourVisualRating.LOW,
            ContourVisualQuality.assess(contour,bounds=bounds).rating)
    }
}
