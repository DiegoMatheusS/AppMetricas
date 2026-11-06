package br.com.medidor.app.domain

import org.junit.Assert.*
import org.junit.Test

class CameraViewportMappingTest {
    @Test fun touchAndMarkerRoundTripWithDifferentTopInsets() {
        val p = CameraViewportMapping.cameraPoint(170f,280f,8,72,0,24,360,720)!!
        val marker = CameraViewportMapping.overlayPoint(p.first,p.second,8,72,0,24,360,720)!!
        assertEquals(170f,marker.first,.001f)
        assertEquals(280f,marker.second,.001f)
    }
    @Test fun normalizedCoordinatesSurviveADifferentRenderingBufferSize() {
        val p = CameraViewportMapping.cameraPoint(180f,350f,0,0,0,0,360,700)!!
        assertEquals(.5f,p.first,0f); assertEquals(.5f,p.second,0f)
        val projectedX = p.first*1080/1080
        val projectedY = p.second*2100/2100
        assertEquals(180f to 350f,CameraViewportMapping.overlayPoint(projectedX,projectedY,0,0,0,0,360,700))
        assertNull(CameraViewportMapping.cameraPoint(0f,-25f,0,0,0,24,360,700))
        assertNull(CameraViewportMapping.cameraPoint(0f,0f,0,0,0,0,0,0))
    }
}
