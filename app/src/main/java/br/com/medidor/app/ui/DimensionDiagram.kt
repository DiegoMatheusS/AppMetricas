package br.com.medidor.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import br.com.medidor.app.domain.TankShape
import kotlin.math.min

/** Dimension arrows follow the selected geometry and stay independent of the calculated values. */
@Composable
internal fun DimensionDiagram(shape: TankShape? = null) {
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.secondary
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(132.dp)) {
            val width = size.width
            val height = size.height
            val stroke = 2.dp.toPx()
            fun point(x: Float, y: Float) = Offset(width * x, height * y)
            fun edge(x: Float, y: Float, toX: Float, toY: Float) {
                drawLine(ink, point(x, y), point(toX, toY), stroke)
            }
            fun arrow(start: Offset, end: Offset) {
                val direction = end - start
                val unit = direction / direction.getDistance().coerceAtLeast(1f)
                val perpendicular = Offset(-unit.y, unit.x)
                val head = 7.dp.toPx()
                drawLine(accent, start, end, stroke)
                for ((tip, sign) in listOf(start to 1f, end to -1f)) {
                    val base = tip + unit * head * sign
                    drawLine(accent, tip, base + perpendicular * head * .5f, stroke)
                    drawLine(accent, tip, base - perpendicular * head * .5f, stroke)
                }
            }
            when (shape) {
                null -> {
                    edge(.24f, .3f, .65f, .3f)
                    edge(.65f, .3f, .8f, .14f)
                    edge(.8f, .14f, .39f, .14f)
                    edge(.39f, .14f, .24f, .3f)
                    edge(.24f, .3f, .24f, .74f)
                    edge(.65f, .3f, .65f, .74f)
                    edge(.8f, .14f, .8f, .58f)
                    edge(.24f, .74f, .65f, .74f)
                    edge(.65f, .74f, .8f, .58f)
                    arrow(point(.14f, .3f), point(.14f, .74f))
                    arrow(point(.24f, .9f), point(.65f, .9f))
                    arrow(point(.72f, .25f), point(.87f, .09f))
                }
                TankShape.VERTICAL -> {
                    drawOval(ink, point(.3f, .16f), Size(width * .36f, height * .14f), style = Stroke(stroke))
                    edge(.3f, .23f, .3f, .78f)
                    edge(.66f, .23f, .66f, .78f)
                    drawArc(ink, 0f, 180f, false, point(.3f, .71f),
                        Size(width * .36f, height * .14f), style = Stroke(stroke))
                    arrow(point(.3f, .07f), point(.66f, .07f))
                    arrow(point(.78f, .23f), point(.78f, .78f))
                }
                TankShape.HORIZONTAL -> {
                    drawOval(ink, point(.2f, .22f), Size(width * .13f, height * .54f), style = Stroke(stroke))
                    edge(.265f, .22f, .78f, .22f)
                    edge(.265f, .76f, .78f, .76f)
                    drawArc(ink, -90f, 180f, false, point(.715f, .22f),
                        Size(width * .13f, height * .54f), style = Stroke(stroke))
                    arrow(point(.1f, .22f), point(.1f, .76f))
                    arrow(point(.265f, .9f), point(.78f, .9f))
                }
                TankShape.SPHERE -> {
                    val center = point(.5f, .5f)
                    val radius = min(width, height) * .36f
                    drawCircle(ink, radius, center, style = Stroke(stroke))
                    arrow(center - Offset(radius, 0f), center + Offset(radius, 0f))
                }
            }
        }
        Text(
            when (shape) {
                null -> "Altura na vertical • Comprimento na base • Largura na lateral"
                TankShape.VERTICAL -> "Diâmetro na horizontal • Altura na vertical"
                TankShape.HORIZONTAL -> "Diâmetro na vertical • Comprimento na horizontal"
                TankShape.SPHERE -> "Diâmetro de uma borda à outra, passando pelo centro"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
