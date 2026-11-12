package br.com.medidor.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import br.com.medidor.app.domain.TankShape

/** Silhouettes only; proportions are illustrative and do not affect the geometry formula. */
internal enum class TankIllustration(val title: String, val geometry: TankShape) {
    VERTICAL("Em pé", TankShape.VERTICAL),
    VERTICAL_LONG("Em pé longo", TankShape.VERTICAL),
    VERTICAL_WIDE("Em pé gordão", TankShape.VERTICAL),
    HORIZONTAL("Deitado", TankShape.HORIZONTAL),
    HORIZONTAL_LONG("Deitado longo", TankShape.HORIZONTAL),
    HORIZONTAL_WIDE("Deitado gordão", TankShape.HORIZONTAL),
    SPHERE("Esférico", TankShape.SPHERE)
}

@Composable
internal fun TankIllustrationPicker(selected: TankIllustration, onSelect: (TankIllustration) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(TankIllustration.entries) { option ->
            val active = option == selected
            Card(
                modifier = Modifier.width(138.dp).clickable { onSelect(option) },
                border = BorderStroke(if (active) 2.dp else 1.dp,
                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    TankOutline(option, Modifier.fillMaxWidth().height(86.dp))
                    Text(option.title, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
internal fun TankOutline(type: TankIllustration, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 3.4.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(ink, Offset(w*x1, h*y1), Offset(w*x2, h*y2), strokeWidth = stroke)
        when (type.geometry) {
            TankShape.SPHERE -> drawCircle(ink, radius = minOf(w*0.32f,h*0.43f),
                center = Offset(w*0.5f,h*0.5f), style = Stroke(stroke))
            TankShape.VERTICAL -> {
                val halfWidth = when(type) {
                    TankIllustration.VERTICAL_LONG -> 0.14f
                    TankIllustration.VERTICAL_WIDE -> 0.38f
                    else -> 0.25f
                }
                val left = 0.5f-halfWidth
                val right = 0.5f+halfWidth
                val top = if(type == TankIllustration.VERTICAL_LONG) 0.08f else 0.20f
                val bottom = 0.90f
                drawOval(ink, topLeft=Offset(w*left,h*top),size=Size(w*(right-left),h*0.18f),style=Stroke(stroke))
                line(left,top+0.09f,left,bottom-0.09f)
                line(right,top+0.09f,right,bottom-0.09f)
                drawArc(ink, startAngle=0f,sweepAngle=180f,useCenter=false,
                    topLeft=Offset(w*left,h*(bottom-0.18f)),
                    size=Size(w*(right-left),h*0.18f),style=Stroke(stroke))
            }
            TankShape.HORIZONTAL -> {
                val left = 0.18f
                val right = when(type) {
                    TankIllustration.HORIZONTAL_LONG -> 0.95f
                    TankIllustration.HORIZONTAL_WIDE -> 0.68f
                    else -> 0.85f
                }
                val halfHeight = if(type == TankIllustration.HORIZONTAL_WIDE) 0.40f else 0.29f
                val top = 0.5f-halfHeight
                val bottom = 0.5f+halfHeight
                drawOval(ink,Offset(w*(left-0.08f),h*top),Size(w*0.16f,h*(bottom-top)),style=Stroke(stroke))
                line(left,top,right,top)
                line(left,bottom,right,bottom)
                drawArc(ink,-90f,180f,false,Offset(w*(right-0.08f),h*top),
                    Size(w*0.16f,h*(bottom-top)),style=Stroke(stroke))
            }
        }
    }
}
