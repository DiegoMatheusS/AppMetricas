package br.com.medidor.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import br.com.medidor.app.domain.PlanFloor
import br.com.medidor.app.domain.PlanPoint
import br.com.medidor.app.domain.PlanViewport
import br.com.medidor.app.domain.PlanViewportMath

/**
 * The bitmap AND annotation overlay share one graphics layer. A local tap
 * passes through the layer's inverse transform, keeping point picking exact
 * regardless of zoom. Only 2+ pointers transform, so one-finger page scrolling
 * and one-finger point placement are still available.
 */
@Composable
internal fun ZoomablePlanCanvas(
    bitmap: Bitmap,
    floor: PlanFloor,
    pending: PlanPoint?,
    perspectiveCorners: List<PlanPoint>,
    showLines: Boolean,
    showMeasurements: Boolean,
    onTap: (PlanPoint) -> Unit
) {
    var viewport by remember(bitmap) { mutableStateOf(PlanViewport()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                enabled = viewport.zoom > 1.01f,
                onClick = {
                    val next = (viewport.zoom / 1.5f).coerceAtLeast(1f)
                    viewport = PlanViewport(next)
                }
            ) { Text("−") }
            Text("Zoom: ${(viewport.zoom * 100).toInt()}%")
            OutlinedButton(
                enabled = viewport.zoom < 5.99f,
                onClick = {
                    viewport = PlanViewport((viewport.zoom * 1.5f).coerceAtMost(6f))
                }
            ) { Text("+") }
            OutlinedButton(
                enabled = viewport.zoom > 1.01f,
                onClick = { viewport = PlanViewport() }
            ) { Text("Centralizar") }
        }
        Text("Toque para marcar. Use dois dedos para ampliar e mover a planta.")
        Box(
            Modifier.fillMaxWidth().height(350.dp).clipToBounds()
                .pointerInput(bitmap) {
                    awaitEachGesture {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (centroid != Offset.Unspecified) viewport = PlanViewportMath.change(
                                    viewport,
                                    size.width.toFloat(),
                                    size.height.toFloat(),
                                    centroid.x, centroid.y, zoom, pan.x, pan.y
                                )
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    transformOrigin = TransformOrigin.Center
                    scaleX = viewport.zoom
                    scaleY = viewport.zoom
                    translationX = viewport.panX
                    translationY = viewport.panY
                }
            ) {
                FloorPlanCanvas(
                    bitmap, floor, pending, perspectiveCorners,
                    showLines, showMeasurements, onTap
                )
            }
        }
    }
}
