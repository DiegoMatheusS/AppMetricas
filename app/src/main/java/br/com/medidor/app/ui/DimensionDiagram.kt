package br.com.medidor.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import br.com.medidor.app.domain.TankShape

/** Schematic dimension arrows, not a scaled representation of a physical tank. */
@Composable
internal fun DimensionDiagram(shape: TankShape? = null) {
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.fillMaxWidth().height(112.dp)) {
            val w = size.width; val h = size.height
            val stroke = 2.5.dp.toPx()
            fun line(x:Float,y:Float,x2:Float,y2:Float,mark:Boolean=false) {
                drawLine(if(mark) accent else ink,Offset(x*w,y*h),Offset(x2*w,y2*h),stroke)
            }
            fun arrow(x:Float,y:Float,x2:Float,y2:Float) {
                line(x,y,x2,y2,true)
                val dx=x2-x; val dy=y2-y
                val mag=kotlin.math.sqrt(dx*dx+dy*dy).coerceAtLeast(0.001f)
                val ax=dx/mag*0.025f; val ay=dy/mag*0.025f
                line(x,y,x+ax-ay*0.65f,y+ay+ax*0.65f,true)
                line(x,y,x+ax+ay*0.65f,y+ay-ax*0.65f,true)
                line(x2,y2,x2-ax-ay*0.65f,y2-ay+ax*0.65f,true)
                line(x2,y2,x2-ax+ay*0.65f,y2-ay-ax*0.65f,true)
            }
            when(shape) {
                null -> {
                    line(0.22f,0.27f,0.65f,0.27f); line(0.65f,0.27f,0.80f,0.12f)
                    line(0.80f,0.12f,0.37f,0.12f);line(0.37f,0.12f,0.22f,0.27f)
                    line(0.22f,0.27f,0.22f,0.74f);line(0.65f,0.27f,0.65f,0.74f)
                    line(0.22f,0.74f,0.65f,0.74f);line(0.65f,0.74f,0.80f,0.59f)
                    line(0.80f,0.12f,0.80f,0.59f)
                    arrow(0.22f,0.88f,0.65f,0.88f)
                    arrow(0.12f,0.27f,0.12f,0.74f)
                    arrow(0.70f,0.24f,0.86f,0.08f)
                }
                TankShape.VERTICAL -> {
                    drawOval(ink,Offset(w*0.30f,h*0.13f),androidx.compose.ui.geometry.Size(w*0.36f,h*0.16f),style=Stroke(stroke))
                    line(0.30f,0.21f,0.30f,0.79f);line(0.66f,0.21f,0.66f,0.79f)
                    drawArc(ink,0f,180f,false,Offset(w*0.30f,h*0.71f),
                        androidx.compose.ui.geometry.Size(w*0.36f,h*0.16f),style=Stroke(stroke))
                    arrow(0.30f,0.07f,0.66f,0.07f)
                    arrow(0.78f,0.21f,0.78f,0.79f)
                }
                TankShape.HORIZONTAL -> {
                    drawOval(ink,Offset(w*0.20f,h*0.21f),androidx.compose.ui.geometry.Size(w*0.13f,h*0.57f),style=Stroke(stroke))
                    line(0.265f,0.21f,0.80f,0.21f);line(0.265f,0.78f,0.80f,0.78f)
                    drawArc(ink,-90f,180f,false,Offset(w*0.735f,h*0.21f),
                        androidx.compose.ui.geometry.Size(w*0.13f,h*0.57f),style=Stroke(stroke))
                    arrow(0.13f,0.21f,0.13f,0.78f)
                    arrow(0.265f,0.89f,0.80f,0.89f)
                }
                TankShape.SPHERE -> {
                    drawCircle(ink,w.coerceAtMost(h)*0.35f,Offset(w*0.49f,h*0.5f),style=Stroke(stroke))
                    arrow(0.35f,0.50f,0.63f,0.50f)
                }
            }
        }
        Text(when(shape) {
            null -> "Setas: altura (vertical), comprimento (base) e largura (lateral)."
            TankShape.VERTICAL -> "Setas: diâmetro (horizontal) e altura (vertical)."
            TankShape.HORIZONTAL -> "Setas: diâmetro (vertical) e comprimento (horizontal)."
            TankShape.SPHERE -> "Seta: diâmetro da esfera, passando pelo centro."
        }, style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
