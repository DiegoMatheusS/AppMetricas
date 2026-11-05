package br.com.medidor.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import br.com.medidor.app.domain.Architect3dGeometry
import br.com.medidor.app.domain.ArchitectPlan
import br.com.medidor.app.domain.ModelFace
import br.com.medidor.app.domain.Point3d
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Interactive orthographic 3D wall model, rendered entirely offline in Compose.
 * Drag = orbit camera, pinch = zoom. This is not AR and not a CAD/BIM model.
 */
@Composable
fun OrbitModelPreview(plan: ArchitectPlan, allFloors: Boolean, showLines: Boolean) {
    val faces = remember(plan, allFloors) { Architect3dGeometry.model(plan, allFloors) }
    MeshModelPreview(faces, showLines)
}

@Composable
internal fun MeshModelPreview(faces: List<ModelFace>, showLines: Boolean = true) {
    var yaw by remember { mutableStateOf(.75f) }
    var elevation by remember { mutableStateOf(.65f) }
    var zoom by remember { mutableStateOf(1f) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Arraste para girar • use dois dedos para aproximar ou afastar")
        Canvas(
            Modifier.fillMaxWidth().height(340.dp)
                .background(Color(0xFFEDF1E8))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, scale, rotation ->
                        yaw += rotation + pan.x * .007f
                        elevation = (elevation - pan.y * .004f).coerceIn(.12f, 1.48f)
                        zoom = (zoom * scale).coerceIn(.35f, 5f)
                    }
                }
        ) {
            if (faces.isNotEmpty()) {
                val cosYaw = cos(yaw.toDouble())
                val sinYaw = sin(yaw.toDouble())
                val cosEl = cos(elevation.toDouble())
                val sinEl = sin(elevation.toDouble())
                fun transform(p: Point3d): Triple<Float, Float, Float> {
                    val xx = p.x * cosYaw - p.y * sinYaw
                    val yy = p.x * sinYaw + p.y * cosYaw
                    return Triple(xx.toFloat(),
                        (yy * sinEl - p.z * cosEl).toFloat(),
                        (yy * cosEl + p.z * sinEl).toFloat())
                }
                data class TransformedFace(
                    val polygon: List<Triple<Float, Float, Float>>,
                    val shape: ModelFace,
                    val depth: Float
                )
                val transformed = faces.map { face ->
                    val points = face.corners.map(::transform)
                    TransformedFace(points, face, points.map { it.third }.average().toFloat())
                }
                val all = transformed.flatMap { it.polygon }
                val minX = all.minOf { it.first }
                val maxX = all.maxOf { it.first }
                val minY = all.minOf { it.second }
                val maxY = all.maxOf { it.second }
                val fit = min(
                    (size.width * .86f) / (maxX - minX).coerceAtLeast(.1f),
                    (size.height * .86f) / (maxY - minY).coerceAtLeast(.1f)
                ).coerceAtMost(160f)
                val scale = fit * zoom
                val midX = (minX + maxX) / 2
                val midY = (minY + maxY) / 2
                val firstFloor = listOf(
                    Color(0xFFC5D1BC), Color(0xFFA9BAA0), Color(0xFFD9E2D2),
                    Color(0xFF93A58B), Color(0xFFE3EADA), Color(0xFF879C7F)
                )
                val secondFloor = listOf(
                    Color(0xFF91B7A1), Color(0xFF6F9C82), Color(0xFFA3C8AF),
                    Color(0xFF678D78), Color(0xFFBCD5C0), Color(0xFF587A6B)
                )
                // Painter ordering: back surfaces first for a translucent-free preview.
                transformed.sortedByDescending { it.depth }.forEach { entry ->
                    val path = Path()
                    entry.polygon.forEachIndexed { i, point ->
                        val x = size.width / 2 + (point.first - midX) * scale
                        val y = size.height / 2 + (point.second - midY) * scale
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    val palette = if (entry.shape.floorIndex % 2 == 0) firstFloor else secondFloor
                    drawPath(path, palette[entry.shape.shade % palette.size])
                    if (showLines) {
                        drawPath(path, Color(0xFF234653), style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 1.15.dp.toPx()
                        ))
                    }
                }
            }
        }
        if (faces.isEmpty()) {
            Text("Desenhe e calibre pelo menos uma parede para visualizar o modelo.")
        }
        OutlinedButton(onClick = {
            yaw = .75f
            elevation = .65f
            zoom = 1f
        }) { Text("Redefinir câmera") }
        Text("Prévia 3D geométrica baseada em paredes marcadas manualmente. " +
            "Portas e janelas são aberturas na malha. Ainda não há realidade aumentada.")
    }
}
