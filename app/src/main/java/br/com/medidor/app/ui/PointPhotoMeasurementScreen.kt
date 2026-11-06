package br.com.medidor.app.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.medidor.app.domain.*
import java.text.NumberFormat
import java.util.Locale

/** A frozen photograph keeps taps aligned even on devices without ARCore. */
@Composable
internal fun PointPhotoMeasurementScreen(
    photo: Bitmap,
    initialArea: Boolean,
    onBack: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var areaMode by remember(photo) { mutableStateOf(initialArea) }
    val reference = remember(photo) { mutableStateListOf<NormalizedPoint>() }
    val points = remember(photo) { mutableStateListOf<NormalizedPoint>() }
    var scaleMm by remember(photo) { mutableStateOf<Double?>(null) }
    var scaleDialog by remember { mutableStateOf(false) }
    var known by remember { mutableStateOf("10") }
    var unit by remember { mutableStateOf(LengthUnit.CENTIMETERS) }
    var saved by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val number = remember { NumberFormat.getNumberInstance(Locale.forLanguageTag("pt-BR")).apply { maximumFractionDigits = 2 } }
    fun length(mm: Double) = "${number.format(mm / 10)} cm"
    val span = if (reference.size == 2) PhotoCalibration.distancePixels(reference[0], reference[1], photo.width, photo.height) else null
    val ratio = if (scaleMm != null && span != null && ReferenceMarkQuality.isReadable(span)) scaleMm!! / span else null
    val distance = if (!areaMode && points.size == 2 && ratio != null)
        PhotoCalibration.distancePixels(points[0], points[1], photo.width, photo.height) * ratio else null
    val pixelsArea = if (areaMode) PointMeasurementGeometry.polygonAreaPixels2(points, photo.width, photo.height) else null
    val area = if (pixelsArea != null && ratio != null) pixelsArea * ratio * ratio else null
    val result = distance?.let { "Distância: ${length(it)}" } ?: area?.let {
        "Área: " + if (it >= 1_000_000) "${number.format(it / 1_000_000)} m²" else "${number.format(it / 100)} cm²"
    }
    val instruction = when {
        scaleMm == null && reference.size < 2 -> "1. Toque nos dois extremos de uma referência conhecida na foto."
        scaleMm == null -> "2. Informe quanto mede a linha amarela para definir a escala."
        ratio == null -> "Afaste os pontos da referência para calcular a medida."
        areaMode && points.size < 3 -> "3. Toque em pelo menos três cantos, na ordem, para calcular a área."
        areaMode && area == null -> "As linhas se cruzam. Desfaça o último canto."
        !areaMode && points.size < 2 -> "3. Toque no início e no fim da distância."
        else -> "Medida fixada. Arraste as pontas para ajustar ou salve."
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("‹ Câmera") }
            Row {
                FilterChip(selected = !areaMode, onClick = { areaMode = false; points.clear(); saved = false }, label = { Text("Régua") })
                FilterChip(selected = areaMode, onClick = { areaMode = true; points.clear(); saved = false }, label = { Text("Área") })
            }
        }
        Text(result ?: if (areaMode) "Área por pontos" else "Régua por pontos",
            modifier = Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(notice ?: instruction, modifier = Modifier.padding(12.dp), fontSize = 14.sp)
        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF202722))) {
            Image(photo.asImageBitmap(), "Fotografia fixa para medir", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Área de marcação da foto" }
                .pointerInput(photo, scaleMm, areaMode) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val rect = imageRect(size.width.toFloat(),size.height.toFloat(),photo.width,photo.height)
                        val candidates = reference.indices.map { true to it } + points.indices.map { false to it }
                        fun location(candidate: Pair<Boolean,Int>): Offset {
                            val p = if (candidate.first) reference[candidate.second] else points[candidate.second]
                            return Offset(rect.left+p.x*rect.width,rect.top+p.y*rect.height)
                        }
                        val selected = candidates.minByOrNull { (location(it)-down.position).getDistance() }
                            ?.takeIf { (location(it)-down.position).getDistance() <= 28.dp.toPx() }
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if ((change.position-down.position).getDistance() > if (selected == null) viewConfiguration.touchSlop else 1f) moved = true
                            val mapped = photoPointFromTouch(change.position,size,photo.width,photo.height)
                            if (selected != null && moved && mapped != null) {
                                if (selected.first) reference[selected.second] = mapped else points[selected.second] = mapped
                                saved = false; notice = null
                                change.consume()
                            }
                            if (!change.pressed) {
                                if (!moved && selected == null && mapped != null) {
                                    val destination = if (scaleMm == null) reference else points
                                    val limit = if (scaleMm == null || !areaMode) 2 else 12
                                    if (destination.size < limit && destination.none {
                                        PhotoCalibration.distancePixels(it,mapped,photo.width,photo.height) < 2
                                    }) {
                                        destination.add(mapped); saved = false; notice = null
                                        if (scaleMm == null && reference.size == 2) scaleDialog = true
                                    } else notice = "Arraste uma ponta existente para ajustar."
                                } else if (!moved && selected != null) notice = "Arraste esta ponta para ajustar."
                                break
                            }
                        }
                    }
                }) {
                val rect = imageRect(size.width, size.height, photo.width, photo.height)
                fun position(p: NormalizedPoint) = Offset(rect.left + p.x * rect.width, rect.top + p.y * rect.height)
                val lime = Color(0xFFC0F565)
                val amber = Color(0xFFFFB443)
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE; textSize = 14.sp.toPx(); textAlign = Paint.Align.CENTER
                }
                val background = Paint().apply { color = android.graphics.Color.rgb(20, 37, 31) }
                fun label(text: String, center: Offset) {
                    val width = textPaint.measureText(text) + 16.dp.toPx()
                    val height = 26.dp.toPx()
                    val x = center.x.coerceIn(width / 2, maxOf(width / 2, size.width - width / 2))
                    val y = center.y.coerceIn(height / 2, maxOf(height / 2, size.height - height / 2))
                    drawContext.canvas.nativeCanvas.drawRoundRect(x-width/2,y-height/2,x+width/2,y+height/2,8f,8f,background)
                    drawContext.canvas.nativeCanvas.drawText(text,x,y-(textPaint.ascent()+textPaint.descent())/2,textPaint)
                }
                fun edge(a: NormalizedPoint, b: NormalizedPoint, color: Color, caption: String?) {
                    val start = position(a); val end = position(b)
                    drawLine(color,start,end,3.dp.toPx())
                    caption?.let { label(it,(start+end)/2f) }
                }
                if (reference.size == 2) edge(reference[0],reference[1],amber,scaleMm?.let { "REF ${length(it)}" } ?: "Referência")
                val edges = if (areaMode && points.size >= 3) points.size else (points.size-1).coerceAtLeast(0)
                repeat(edges) { index ->
                    val a = points[index]; val b = points[(index+1)%points.size]
                    edge(a,b,lime,ratio?.let { length(PhotoCalibration.distancePixels(a,b,photo.width,photo.height)*it) })
                }
                if (area != null) label(result!!,Offset(points.map { position(it).x }.average().toFloat(),points.map { position(it).y }.average().toFloat()))
                reference.forEach { drawCircle(amber,6.dp.toPx(),position(it)) }
                points.forEachIndexed { index, p ->
                    val center = position(p)
                    drawCircle(lime,9.dp.toPx(),center)
                    textPaint.color = android.graphics.Color.rgb(20,37,31)
                    textPaint.textSize = 11.sp.toPx()
                    drawContext.canvas.nativeCanvas.drawText("${index+1}",center.x,
                        center.y-(textPaint.ascent()+textPaint.descent())/2,textPaint)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (scaleMm == null) Button(onClick = { scaleDialog = true }, enabled = reference.size == 2, modifier = Modifier.fillMaxWidth()) { Text("Definir escala") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = {
                    if (points.isNotEmpty()) points.removeAt(points.lastIndex)
                    else { scaleMm = null; if (reference.isNotEmpty()) reference.removeAt(reference.lastIndex) }
                    saved = false; notice = null
                }, modifier = Modifier.weight(1f), enabled = reference.isNotEmpty()) { Text("Desfazer") }
                OutlinedButton(onClick = { scaleMm = null; reference.clear(); points.clear(); saved = false; notice = null }, modifier = Modifier.weight(1f)) { Text("Refazer") }
                Button(onClick = { result?.let { onSave(if (areaMode) "Área por pontos" else "Régua por pontos", "$it (estimativa por foto calibrada)"); saved = true } },
                    enabled = result != null && !saved, modifier = Modifier.weight(1f)) { Text(if (saved) "Salvo" else "Salvar") }
            }
            Text("Referência e objeto precisam estar no mesmo plano.", fontSize = 11.sp)
        }
    }
    if (scaleDialog) AlertDialog(
        onDismissRequest = { scaleDialog = false },
        title = { Text("Quanto mede a linha amarela?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Informe o tamanho real da referência. A câmera precisa dessa escala para calcular centímetros e área.")
                OutlinedTextField(known,{ known = it },label = { Text("Medida da referência") },singleLine = true,keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Row { LengthUnit.entries.forEach { choice -> FilterChip(selected = unit == choice,onClick = { unit = choice },label = { Text(choice.label) }) } }
                Text("Ou use o diâmetro de uma moeda:", fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(BrazilianCoin.entries) { coin -> OutlinedButton(onClick = { known = coin.diameterMm.toString(); unit = LengthUnit.MILLIMETERS }) { Text(coin.label) } }
                }
                if (span != null && !ReferenceMarkQuality.isReadable(span)) Text("Referência curta demais. Refaça os pontos mais afastados.",color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = {
            scaleMm = PhotoCalibration.manualReferenceMillimeters(known,unit)
            points.clear(); saved = false; notice = null; scaleDialog = false
        },enabled = span != null && ReferenceMarkQuality.isReadable(span) && PhotoCalibration.manualReferenceMillimeters(known,unit) != null) { Text("Aplicar escala") } },
        dismissButton = { TextButton(onClick = { scaleDialog = false }) { Text("Voltar à foto") } }
    )
}
