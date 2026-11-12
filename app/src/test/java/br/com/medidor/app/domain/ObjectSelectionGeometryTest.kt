package br.com.medidor.app.domain

import org.junit.Assert.*
import org.junit.Test

class ObjectSelectionGeometryTest {
    private fun p(x: Float,y: Float) = NormalizedPoint(x,y)
    private fun rectangle(l: Float,t: Float,r: Float,b: Float) = ObjectOutline(listOf(p(l,t),p(r,t),p(r,b),p(l,b)))

    @Test fun tapSelectsOnlyTheObjectUnderIt() {
        val left = rectangle(.1f,.2f,.3f,.6f)
        val right = rectangle(.55f,.2f,.9f,.7f)
        assertEquals(listOf(right),ObjectSelectionGeometry.hits(listOf(left,right),p(.7f,.4f),800,600,2.0))
        assertTrue(ObjectSelectionGeometry.hits(listOf(left,right),p(.45f,.9f),800,600,2.0).isEmpty())
    }

    @Test fun nestedContoursCanExpandFromSmallestToLargestWithoutDuplicates() {
        val inner = rectangle(.3f,.3f,.5f,.5f)
        val outer = rectangle(.1f,.1f,.9f,.9f)
        assertEquals(listOf(inner,outer),ObjectSelectionGeometry.hits(listOf(outer,inner,inner),p(.4f,.4f),1000,1000,2.0))
    }

    @Test fun concaveContourDoesNotSelectItsEmptyBoundingBox() {
        val outline = ObjectOutline(listOf(p(.1f,.1f),p(.8f,.1f),p(.8f,.3f),p(.3f,.3f),p(.3f,.8f),p(.1f,.8f)))
        assertTrue(ObjectSelectionGeometry.contains(outline,p(.2f,.6f)))
        assertFalse(ObjectSelectionGeometry.contains(outline,p(.6f,.6f)))
        assertTrue(ObjectSelectionGeometry.hits(listOf(outline),p(.6f,.6f),100,100,1.0).isEmpty())
    }

    @Test fun rulerUsesPixelAspectRatioAndDoesNotIncludeOtherObjects() {
        val outline = rectangle(.1f,.1f,.8f,.9f)
        val ends = ObjectSelectionGeometry.ruler(outline,1000,100)
        assertEquals(700.0,PhotoCalibration.distancePixels(ends[0],ends[1],1000,100),.01)
    }

    @Test fun openFenceWireCanBeSelectedButCannotBecomeAnArea() {
        val wire = ObjectOutline(listOf(p(.1f,.4f),p(.9f,.4f)))
        assertEquals(listOf(wire),ObjectSelectionGeometry.hits(listOf(wire),p(.5f,.405f),1000,1000,6.0))
        assertTrue(ObjectSelectionGeometry.hits(listOf(wire),p(.5f,.405f),1000,1000,6.0,true).isEmpty())
        assertNull(ObjectSelectionGeometry.areaPixels(wire,1000,1000))
    }

    @Test fun preciseTraceControlsAreaAndEdgeSelection() {
        val corners = rectangle(.1f,.1f,.9f,.9f).corners
        val trace = listOf(p(.1f,.1f),p(.9f,.1f),p(.9f,.9f),p(.5f,.5f),p(.1f,.9f))
        val outline = ObjectOutline(corners,trace)
        assertEquals(4800.0,ObjectSelectionGeometry.areaPixels(outline,100,100)!!,.01)
        assertTrue(ObjectSelectionGeometry.hits(listOf(outline),p(.5f,.51f),100,100,2.0).isNotEmpty())
        assertTrue(ObjectSelectionGeometry.hits(listOf(outline),p(.5f,.85f),100,100,2.0).isEmpty())
    }
}
