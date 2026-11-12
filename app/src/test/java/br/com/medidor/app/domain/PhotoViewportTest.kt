package br.com.medidor.app.domain

import org.junit.Assert.*
import org.junit.Test

class PhotoViewportTest {
    @Test fun letterboxIsExcludedAndVisiblePixelsRoundTrip() {
        val map = PhotoViewport(400f,600f,800,600)
        assertNull(map.point(200f,100f))
        assertNull(map.point(-1f,300f))
        val p = NormalizedPoint(.13f,.78f)
        val screen = map.view(p)
        val restored = map.point(screen.first,screen.second)!!
        assertEquals(p.x,restored.x,.00001f)
        assertEquals(p.y,restored.y,.00001f)
    }

    @Test fun zoomAnchorsThePhotoPixelUnderTheFingers() {
        val map = PhotoViewport(400f,300f,800,600)
        val before = map.point(100f,120f)!!
        val after = map.transform(4f,100f,120f)
        val under = after.point(100f,120f)!!
        assertEquals(before.x,under.x,.00001f)
        assertEquals(before.y,under.y,.00001f)
        val moved = after.transform(1f,200f,150f,30f,-25f)
        val visible = NormalizedPoint(.3f,.4f)
        val screen = moved.view(visible)
        assertEquals(visible.x,moved.point(screen.first,screen.second)!!.x,.00001f)
        assertEquals(visible.y,moved.point(screen.first,screen.second)!!.y,.00001f)
    }

    @Test fun panStaysOnThePhotoAndResetCentersIt() {
        val map = PhotoViewport(400f,300f,800,600).transform(8f,200f,150f)
            .transform(1f,200f,150f,10000f,-10000f)
        assertEquals(0f,map.left,.0001f)
        assertEquals(300f,map.top+map.height,.0001f)
        val reset = map.transform(.01f,100f,100f)
        assertEquals(1f,reset.zoom,0f)
        assertEquals(0f,reset.panX,0f)
        assertEquals(0f,reset.panY,0f)
    }

    @Test fun invalidGesturesDoNotCorruptTheTransform() {
        val map = PhotoViewport(400f,300f,800,600)
        assertEquals(map,map.transform(Float.NaN,100f,100f))
        assertEquals(map,map.transform(-1f,100f,100f))
        assertEquals(map,map.transform(2f,Float.POSITIVE_INFINITY,100f))
        assertNull(map.point(Float.NaN,20f))
    }
}
