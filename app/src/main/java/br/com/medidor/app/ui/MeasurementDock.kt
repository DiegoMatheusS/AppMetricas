package br.com.medidor.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class MeasureIcon { BACK, RULER, AREA, AUTO, PHOTO, MORE, POINT, GALLERY, OBJECT, UNDO, RESET, SAVE, EDGES, SCALE, HELP }

internal data class MeasureAction(
    val label: String,
    val description: String = label,
    val icon: MeasureIcon? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val action: () -> Unit
)

/** Opaque, content-sized panel: camera pixels never sit behind the instructions. */
@Composable
internal fun MeasurementDock(
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth().clipToBounds()
        .background(Color(0xFF14251F), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
        .padding(horizontal = 10.dp, vertical = 10.dp)
        .semantics { contentDescription = "Controles inferiores de medição" },
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp)
        val messageHeight = with(LocalDensity.current) { 54.sp.toDp() } + 6.dp
        Box(Modifier.fillMaxWidth().heightIn(min = messageHeight)) {
            if (!message.isNullOrBlank()) Text(message, color = Color.White.copy(alpha = .9f),
                fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp))
        }
        content()
    }
}

@Composable
internal fun MeasurementToolRow(actions: List<MeasureAction>, columns: Int = 6) {
    require(actions.size <= columns)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(columns) { index ->
            val item = actions.getOrNull(index)
            if (item == null) Spacer(Modifier.weight(1f).height(54.dp))
            else {
                val ink = if (item.selected) Color(0xFF14251F) else Color(0xFFEAF3EC)
                Surface(onClick = item.action, enabled = item.enabled,
                    modifier = Modifier.weight(1f).height(54.dp).semantics { contentDescription = item.description },
                    shape = RoundedCornerShape(11.dp),
                    color = if (item.selected) Color(0xFFC0F565) else Color(0xFF26392E),
                    contentColor = if (item.enabled) ink else ink.copy(alpha = .35f)) {
                    Column(Modifier.fillMaxSize().padding(2.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        item.icon?.let { MeasurementIcon(it, if (item.enabled) ink else ink.copy(alpha = .35f)) }
                        Text(item.label, fontSize = if (item.icon == null) 15.sp else 10.sp,
                            lineHeight = if (item.icon == null) 19.sp else 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun MeasurementIcon(icon: MeasureIcon, color: Color) {
    Canvas(Modifier.size(21.dp)) {
        val unit = size.width/24f
        fun line(x: Float,y: Float,x2: Float,y2: Float) =
            drawLine(color,Offset(x*unit,y*unit),Offset(x2*unit,y2*unit),1.7f*unit,StrokeCap.Round)
        fun box(x: Float,y: Float,w: Float,h: Float) =
            drawRect(color,Offset(x*unit,y*unit),Size(w*unit,h*unit),style = Stroke(1.7f*unit))
        when (icon) {
            MeasureIcon.BACK -> { line(15f,5f,8f,12f); line(8f,12f,15f,19f) }
            MeasureIcon.RULER -> { box(3f,7f,18f,10f); listOf(7f,11f,15f).forEach { line(it,7f,it,11f) } }
            MeasureIcon.AREA, MeasureIcon.OBJECT -> { box(4f,4f,16f,16f); if (icon == MeasureIcon.AREA) line(4f,20f,20f,4f) }
            MeasureIcon.AUTO, MeasureIcon.EDGES -> {
                listOf(4f to 4f,20f to 4f,4f to 20f,20f to 20f).forEach { (x,y) ->
                    line(x,y,x+if (x<12) 4 else -4,y); line(x,y,x,y+if (y<12) 4 else -4)
                }
                if (icon == MeasureIcon.AUTO) { line(12f,8f,12f,16f); line(8f,12f,16f,12f) }
            }
            MeasureIcon.PHOTO -> { box(3f,6f,18f,14f); box(8f,3f,8f,3f); drawCircle(color,4*unit,Offset(12*unit,13*unit),style=Stroke(1.7f*unit)) }
            MeasureIcon.GALLERY -> { box(3f,4f,18f,16f); line(4f,18f,10f,11f); line(10f,11f,15f,16f); line(15f,16f,19f,12f) }
            MeasureIcon.MORE -> listOf(5f,12f,19f).forEach { drawCircle(color,1.7f*unit,Offset(it*unit,12*unit)) }
            MeasureIcon.POINT -> { drawCircle(color,6*unit,Offset(12*unit,12*unit),style=Stroke(1.7f*unit)); drawCircle(color,2*unit,Offset(12*unit,12*unit)) }
            MeasureIcon.UNDO, MeasureIcon.RESET -> {
                val path = Path().apply { moveTo(6*unit,8*unit); cubicTo(23*unit,2*unit,25*unit,23*unit,8*unit,19*unit) }
                drawPath(path,color,style=Stroke(1.7f*unit)); line(6f,8f,6f,3f); line(6f,8f,11f,9f)
            }
            MeasureIcon.SAVE -> { box(4f,3f,16f,18f); box(8f,3f,8f,6f); box(8f,14f,8f,7f) }
            MeasureIcon.SCALE -> { line(4f,18f,20f,6f); line(4f,14f,4f,20f); line(20f,4f,20f,10f); line(7f,17f,9f,20f); line(12f,13f,14f,16f) }
            MeasureIcon.HELP -> { drawCircle(color,9*unit,Offset(12*unit,12*unit),style=Stroke(1.7f*unit)); line(12f,11f,12f,17f); drawCircle(color,unit,Offset(12*unit,7*unit)) }
        }
    }
}
