package br.com.medidor.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.medidor.app.domain.*
import br.com.medidor.app.vision.PhotoObjectScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun InteractiveMeasurementPhoto(
    photo: Bitmap,
    reference: MutableList<NormalizedPoint>,
    points: MutableList<NormalizedPoint>,
    calibrated: Boolean,
    areaMode: Boolean,
    pointSize: MeasurementPointSize,
    title: String,
    message: String,
    leadingActions: List<MeasureAction>,
    trailingActions: List<MeasureAction>,
    referencePicking: Boolean,
    resetKey: Int,
    onReferenceModeChange: (Boolean) -> Unit,
    automaticAvailable: Boolean,
    referenceCaption: String?,
    edgeCaption: (Double) -> String?,
    areaCaption: String?,
    initialSeed: NormalizedPoint?,
    onReferenceComplete: () -> Unit,
    onChanged: () -> Unit,
    onAutomaticArea: (Double?) -> Unit,
    onNotice: (String?) -> Unit,
    onPointOptions: () -> Unit,
    modifier: Modifier = Modifier
) {
    var zoom by remember(photo) { mutableStateOf(1f) }
    var panX by remember(photo) { mutableStateOf(0f) }
    var panY by remember(photo) { mutableStateOf(0f) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var objectMode by remember(photo) { mutableStateOf(true) }
    var showEdges by remember(photo) { mutableStateOf(false) }
    var global by remember(photo) { mutableStateOf(emptyList<ObjectOutline>()) }
    var detail by remember(photo) { mutableStateOf(emptyList<ObjectOutline>()) }
    var scanning by remember(photo) { mutableStateOf(true) }
    var selected by remember(photo) { mutableStateOf<ObjectOutline?>(null) }
    var pendingObject by remember(photo) { mutableStateOf<ObjectOutline?>(null) }
    var matches by remember(photo) { mutableStateOf(emptyList<ObjectOutline>()) }
    var selectionIndex by remember(photo) { mutableStateOf(0) }
    var selectionSerial by remember(photo) { mutableStateOf(0) }
    var seedUsed by remember(photo) { mutableStateOf(false) }
    var loupe by remember(photo) { mutableStateOf<NormalizedPoint?>(null) }
    val shine = remember { Animatable(1f) }
    val loupeShader = remember(photo) { BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    fun viewport() = if (viewSize.width > 0 && viewSize.height > 0)
        PhotoViewport(viewSize.width.toFloat(), viewSize.height.toFloat(), photo.width, photo.height, zoom, panX, panY) else null
    fun move(next: PhotoViewport) { zoom = next.zoom; panX = next.panX; panY = next.panY }
    fun applySelection(outline: ObjectOutline) {
        selected = outline
        pendingObject = outline
        selectionSerial++
        if (calibrated) {
            points.clear()
            points.addAll(if (areaMode) outline.corners else ObjectSelectionGeometry.ruler(outline, photo.width, photo.height))
            onChanged()
            onAutomaticArea(if (areaMode) ObjectSelectionGeometry.areaPixels(outline, photo.width, photo.height) else null)
            onNotice("Objeto selecionado")
        } else onNotice("Objeto selecionado sem escala. Auto mede no ambiente; Escala calibra a foto.")
    }

    LaunchedEffect(photo) {
        scanning = true
        try { global = withContext(Dispatchers.Default) { PhotoObjectScanner.scan(photo) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { onNotice("Você pode marcar as pontas manualmente nesta fotografia.") }
        finally { scanning = false }
    }
    LaunchedEffect(photo, zoom, panX, panY, viewSize) {
        if (zoom < 1.4f) { detail = emptyList(); return@LaunchedEffect }
        delay(250)
        val map = viewport() ?: return@LaunchedEffect
        val l = (-map.left / map.width).coerceIn(0f,1f)
        val t = (-map.top / map.height).coerceIn(0f,1f)
        val r = ((map.viewWidth - map.left) / map.width).coerceIn(0f,1f)
        val b = ((map.viewHeight - map.top) / map.height).coerceIn(0f,1f)
        try { detail = withContext(Dispatchers.Default) { PhotoObjectScanner.scan(photo,l,t,r,b) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { detail = emptyList() }
    }
    LaunchedEffect(areaMode, calibrated) {
        onAutomaticArea(null)
        if (calibrated) pendingObject?.let(::applySelection)
    }
    LaunchedEffect(resetKey) {
        selected = null; pendingObject = null; matches = emptyList(); onAutomaticArea(null)
    }
    LaunchedEffect(points.size, calibrated) {
        val outline = selected
        if (calibrated && outline != null && points.size < (if (areaMode) outline.corners.size else 2)) {
            selected = null; pendingObject = null; matches = emptyList(); onAutomaticArea(null)
        }
    }
    LaunchedEffect(initialSeed, global, areaMode) {
        if (initialSeed != null && !seedUsed && global.isNotEmpty()) {
            val found = ObjectSelectionGeometry.hits(global,initialSeed,photo.width,photo.height,
                maxOf(photo.width,photo.height) * .02,areaMode)
            if (found.isNotEmpty()) {
                matches = found; selectionIndex = 0; applySelection(found.first())
                seedUsed = true
            }
        }
    }
    LaunchedEffect(selectionSerial) {
        if (selected == null) shine.snapTo(1f)
        else { shine.snapTo(0f); shine.animateTo(1f,tween(950)) }
    }

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f).clipToBounds().background(Color(0xFF202722))
            .onSizeChanged { viewSize = it }
            .semantics { contentDescription = "Área de marcação da foto" }
            .pointerInput(photo,calibrated,areaMode,pointSize,referencePicking) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    fun map() = PhotoViewport(size.width.toFloat(),size.height.toFloat(),photo.width,photo.height,zoom,panX,panY)
                    val candidates = reference.indices.map { true to it } + points.indices.map { false to it }
                    fun at(candidate: Pair<Boolean,Int>): Offset {
                        val point = if (candidate.first) reference[candidate.second] else points[candidate.second]
                        val position = map().view(point)
                        return Offset(position.first,position.second)
                    }
                    val selectedPoint = candidates.minByOrNull { (at(it)-down.position).getDistance() }
                        ?.takeIf { (at(it)-down.position).getDistance() <= pointSize.hitRadiusDp.dp.toPx() }
                    var moved = false
                    var multiple = false
                    var previous = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed || it.previousPressed } >= 2) {
                            multiple = true; loupe = null
                            val center = event.calculateCentroid(useCurrent = false)
                            val shift = event.calculatePan()
                            move(map().transform(event.calculateZoom(),center.x,center.y,shift.x,shift.y))
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                            continue
                        }
                        if (multiple) {
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position-down.position).getDistance() > if (selectedPoint == null) viewConfiguration.touchSlop else 1f) moved = true
                        val mapped = map().point(change.position.x,change.position.y)
                        if (moved && selectedPoint != null && mapped != null) {
                            if (selectedPoint.first) reference[selectedPoint.second] = mapped
                            else {
                                points[selectedPoint.second] = mapped
                                onAutomaticArea(null)
                                if (areaMode && selected != null && points.size >= 3) selected = ObjectOutline(points.toList())
                            }
                            loupe = mapped; onChanged(); onNotice(null); change.consume()
                        } else if (moved && selectedPoint == null && zoom > 1f) {
                            val shift = change.position-previous
                            move(map().transform(1f,change.position.x,change.position.y,shift.x,shift.y))
                            change.consume()
                        }
                        previous = change.position
                        if (!change.pressed) {
                            loupe = null
                            if (!moved && mapped != null) {
                                if (selectedPoint != null) {
                                    onPointOptions()
                                } else {
                                    val scene = (detail + global)
                                    val found = if (objectMode && (calibrated || !referencePicking))
                                        ObjectSelectionGeometry.hits(scene,mapped,photo.width,photo.height,
                                            (pointSize.radiusDp.dp.toPx() / (map().fit * zoom)).toDouble(),areaMode) else emptyList()
                                    if (found.isNotEmpty()) {
                                        matches = found; selectionIndex = 0; applySelection(found.first())
                                    } else {
                                        val destination = if (!calibrated) reference else points
                                        val limit = if (!calibrated || !areaMode) 2 else 12
                                        if (destination.size < limit && destination.none {
                                            PhotoCalibration.distancePixels(it,mapped,photo.width,photo.height) < 2
                                        }) {
                                            if (calibrated) { selected = null; matches = emptyList(); onAutomaticArea(null) }
                                            destination.add(mapped); onChanged(); onNotice(null)
                                            if (!calibrated && reference.size == 2) onReferenceComplete()
                                        } else onNotice("Arraste uma ponta ou toque em outro objeto.")
                                    }
                                }
                            }
                            break
                        }
                    }
                    loupe = null
                }
            }) {
            if (size.width <= 0 || size.height <= 0) return@Canvas
            val map = PhotoViewport(size.width,size.height,photo.width,photo.height,zoom,panX,panY)
            val native = drawContext.canvas.nativeCanvas
            native.drawBitmap(photo,null,RectF(map.left,map.top,map.left+map.width,map.top+map.height),Paint(Paint.FILTER_BITMAP_FLAG))
            fun position(point: NormalizedPoint): Offset = map.view(point).let { Offset(it.first,it.second) }
            val lime = Color(0xFFC0F565)
            val amber = Color(0xFFFFB443)
            if (showEdges) (detail + global).take(64).forEach { outline ->
                val path = Path()
                outline.corners.forEachIndexed { index, point ->
                    val p = position(point)
                    if (index == 0) path.moveTo(p.x,p.y) else path.lineTo(p.x,p.y)
                }
                if (outline.corners.size >= 3) path.close()
                drawPath(path,Color.White.copy(alpha = .33f),style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
            selected?.let { outline ->
                ObjectSelectionEffect.draw(native,outline.corners.map { position(it).let { p -> PointF(p.x,p.y) } },
                    RectF(0f,0f,size.width,size.height),shine.value,density)
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE; textSize = 14.sp.toPx(); textAlign = Paint.Align.CENTER
            }
            val background = Paint().apply { color = android.graphics.Color.rgb(20,37,31) }
            fun label(text: String, center: Offset) {
                val width = textPaint.measureText(text)+16.dp.toPx()
                val height = 26.dp.toPx()
                val x = center.x.coerceIn(width/2,maxOf(width/2,size.width-width/2))
                val y = center.y.coerceIn(height/2,maxOf(height/2,size.height-height/2))
                native.drawRoundRect(x-width/2,y-height/2,x+width/2,y+height/2,8f,8f,background)
                native.drawText(text,x,y-(textPaint.ascent()+textPaint.descent())/2,textPaint)
            }
            if (!calibrated && selected != null) {
                val shape = selected!!.corners
                label(if (automaticAvailable) "Auto: medir em cm" else "Defina a escala",
                    Offset(shape.map { position(it).x }.average().toFloat(),shape.map { position(it).y }.average().toFloat()))
            }
            fun edge(a: NormalizedPoint,b: NormalizedPoint,color: Color,caption: String?) {
                val start = position(a); val end = position(b)
                drawLine(color,start,end,2.5.dp.toPx())
                caption?.let { label(it,(start+end)/2f) }
            }
            if (reference.size == 2) edge(reference[0],reference[1],amber,referenceCaption ?: "Referência")
            val count = if (areaMode && points.size >= 3) points.size else (points.size-1).coerceAtLeast(0)
            repeat(count) { index ->
                val a = points[index]; val b = points[(index+1)%points.size]
                edge(a,b,lime,edgeCaption(PhotoCalibration.distancePixels(a,b,photo.width,photo.height)))
            }
            if (areaCaption != null && points.size >= 3)
                label(areaCaption,Offset(points.map { position(it).x }.average().toFloat(),points.map { position(it).y }.average().toFloat()))
            reference.forEach { drawCircle(amber,(pointSize.radiusDp*2/3).dp.toPx(),position(it)) }
            points.forEachIndexed { index, point ->
                val center = position(point)
                drawCircle(lime,pointSize.radiusDp.dp.toPx(),center)
                textPaint.color = android.graphics.Color.rgb(20,37,31)
                textPaint.textSize = minOf(11f,pointSize.radiusDp*1.4f).sp.toPx()
                native.drawText((index+1).toString(),center.x,center.y-(textPaint.ascent()+textPaint.descent())/2,textPaint)
            }
            loupe?.let { point ->
                val radius = 58.dp.toPx()
                val center = Offset((size.width-radius-12.dp.toPx()).coerceAtLeast(radius),radius+12.dp.toPx())
                val scale = (map.fit * zoom * 4).coerceAtLeast(1.5f)
                val matrix = Matrix().apply { setScale(scale,scale); postTranslate(center.x-point.x*photo.width*scale,center.y-point.y*photo.height*scale) }
                loupeShader.setLocalMatrix(matrix)
                native.drawCircle(center.x,center.y,radius,Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = loupeShader })
                drawCircle(Color.White,radius,center,style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                drawLine(Color.White,center-Offset(12.dp.toPx(),0f),center+Offset(12.dp.toPx(),0f),1.dp.toPx())
                drawLine(Color.White,center-Offset(0f,12.dp.toPx()),center+Offset(0f,12.dp.toPx()),1.dp.toPx())
            }
        }
        MeasurementDock(title, if (scanning) "Escaneando arestas…" else message) {
            MeasurementToolRow(leadingActions)
            MeasurementToolRow(listOf(
                MeasureAction("Objeto", "Selecionar objeto", MeasureIcon.OBJECT, selected = objectMode, action = {
                    objectMode = true; onReferenceModeChange(false)
                }),
                MeasureAction("Pontos", "Marcar pontos na foto", MeasureIcon.POINT, selected = !objectMode, action = {
                    if (objectMode) {
                        points.clear(); selected = null; pendingObject = null; matches = emptyList()
                        onAutomaticArea(null); onChanged(); onNotice(null)
                    }
                    objectMode = false
                    if (!calibrated) onReferenceModeChange(true)
                }),
                MeasureAction("Ponto", "Tamanho do ponto", MeasureIcon.POINT, action = onPointOptions),
                MeasureAction("Arestas", if (showEdges) "Ocultar arestas" else "Mostrar arestas", MeasureIcon.EDGES,
                    selected = showEdges, action = { showEdges = !showEdges }),
                MeasureAction("Reduzir", "Reduzir seleção", MeasureIcon.OBJECT,
                    enabled = selectionIndex > 0 && matches.isNotEmpty(), action = {
                        selectionIndex--; applySelection(matches[selectionIndex])
                    }),
                MeasureAction("Ampliar", "Ampliar seleção", MeasureIcon.OBJECT,
                    enabled = selectionIndex < matches.lastIndex, action = {
                        selectionIndex++; applySelection(matches[selectionIndex])
                    })
            ))
            MeasurementToolRow(listOf(1,2,4,8).map { level ->
                MeasureAction(level.toString()+"×", "Zoom da foto de $level vezes", selected = kotlin.math.abs(zoom-level)<.05f,
                    action = { viewport()?.let { map ->
                        val focus = selected?.corners?.let { list -> map.view(NormalizedPoint(
                            list.map { it.x }.average().toFloat(),list.map { it.y }.average().toFloat())) }
                        move(map.transform(level/zoom,focus?.first ?: map.viewWidth/2,focus?.second ?: map.viewHeight/2))
                    } })
            } + trailingActions)
        }
    }
}
