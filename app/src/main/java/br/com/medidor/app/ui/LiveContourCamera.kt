package br.com.medidor.app.ui

import android.view.View
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import br.com.medidor.app.domain.LiveContourTracking
import br.com.medidor.app.domain.LiveContourStabilizer
import br.com.medidor.app.domain.LiveContourCandidate
import br.com.medidor.app.domain.LiveViewportMapping
import br.com.medidor.app.domain.ContourNormalizedArea
import br.com.medidor.app.domain.ContourVisualAssessment
import br.com.medidor.app.domain.ContourVisualQuality
import br.com.medidor.app.domain.NormalizedPoint
import br.com.medidor.app.domain.PlanarContourResult
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

private data class ContourOverlay(
    val width: Int,
    val height: Int,
    val contour: PlanarContourResult?,
    val message: String,
    val assessment: ContourVisualAssessment? = null
)

private data class ContourSelection(
    val automatic: Boolean = true,
    val seed: NormalizedPoint? = null,
    val area: ContourNormalizedArea? = null,
    val tolerance: Int = 32,
    val ready: Boolean = true
)

/**
 * CameraX preview + frame analyzer, deliberately independent from photo-based
 * calibrated measurements. Tap an object to resample a color region every
 * ~250ms. This is NOT an ML object tracker or a depth sensor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveContourCamera(
    onPhoto: (Bitmap) -> Unit,
    onError: (String) -> Unit,
    tool: LiveMeasureTool = LiveMeasureTool.RULER,
    onBack: () -> Unit = {},
    onManual: () -> Unit = {},
    onAr: () -> Unit,
    onSwitchTool: (LiveMeasureTool) -> Unit = {}
) {
    val context = LocalContext.current
    val owner = context as? ComponentActivity
    val latestPhoto by rememberUpdatedState(onPhoto)
    val latestError by rememberUpdatedState(onError)
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val worker = remember { Executors.newSingleThreadExecutor() }
    var autoSearch by remember { mutableStateOf(true) }
    var tapMarker by remember { mutableStateOf<NormalizedPoint?>(null) }
    var areaMode by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var firstCorner by remember { mutableStateOf<NormalizedPoint?>(null) }
    var drawnArea by remember { mutableStateOf<ContourNormalizedArea?>(null) }
    var overlay by remember { mutableStateOf<ContourOverlay?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var tolerance by remember { mutableStateOf(32) }
    val previewView = remember(context) {
        PreviewView(context).apply { scaleType=PreviewView.ScaleType.FILL_CENTER }
    }

    val selection = remember { AtomicReference(ContourSelection()) }
    fun restartSearch(message: String) {
        selection.set(ContourSelection(autoSearch, tapMarker, drawnArea, tolerance,
            ready = !areaMode || drawnArea != null))
        overlay = ContourOverlay(1, 1, null, message)
    }

    DisposableEffect(owner, previewView) {
        val disposed = AtomicBoolean(false)
        var boundProvider: ProcessCameraProvider? = null
        var providerReady: ProcessCameraProvider? = null
        val future = if (owner != null) ProcessCameraProvider.getInstance(context) else null

        // PreviewView gets a valid ViewPort only after it has been laid out.
        // Share that ViewPort across preview, image analysis, and photo capture:
        // their crop rectangles then describe the same part of the sensor.
        fun bindWhenReady() {
            val provider = providerReady ?: return
            val viewPort = previewView.viewPort ?: return
            if (disposed.get() || boundProvider != null || owner == null) return
            try {
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                var lastFrame = 0L
                var currentSelection: ContourSelection? = null
                var stabilizer = LiveContourStabilizer()
                var followedSeed: NormalizedPoint? = null
                analysis.setAnalyzer(worker) { proxy ->
                    val input = selection.get()
                    try {
                        val now = SystemClock.elapsedRealtime()
                        if (!disposed.get() && input.ready && now - lastFrame >= 250) {
                            lastFrame = now
                            if (input !== currentSelection) {
                                currentSelection = input
                                stabilizer = LiveContourStabilizer()
                                followedSeed = input.seed
                            }
                            val frame = LiveCameraFrameSampler.sample(proxy)
                            if (frame != null) {
                                val before = stabilizer.previous
                                val bounds = input.area?.pixels(frame.width, frame.height)
                                val candidate = LiveContourCandidate.find(
                                    frame.pixels, frame.width, frame.height,
                                    if (input.automatic) null else followedSeed,
                                    input.tolerance, bounds, before
                                )
                                val checked = stabilizer.update(candidate, input.automatic)
                                val contour = checked.result
                                if (contour != null && before != null) {
                                    followedSeed = followedSeed?.let {
                                        LiveContourTracking.followSeed(before, contour, it) ?: it
                                    }
                                }
                                val visual = contour?.let { ContourVisualQuality.assess(it, before, bounds) }
                                val output = ContourOverlay(frame.width, frame.height, contour, checked.label, visual)
                                mainExecutor.execute {
                                    if (!disposed.get() && input === selection.get()) overlay = output
                                }
                            }
                        }
                    } catch (_: Exception) {
                        mainExecutor.execute {
                            if (!disposed.get() && input === selection.get()) {
                                overlay = ContourOverlay(1, 1, null, "Falha no quadro. Tentando novamente…")
                            }
                        }
                    } finally {
                        proxy.close()
                    }
                }
                val group = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(imageCapture)
                    .addUseCase(analysis)
                    .setViewPort(viewPort)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(owner,CameraSelector.DEFAULT_BACK_CAMERA,group)
                boundProvider = provider
                capture = imageCapture
            } catch (e: Exception) {
                latestError(e.localizedMessage ?: "Falha ao iniciar câmera com análise.")
            }
        }

        val onLayout = View.OnLayoutChangeListener {
                _: View, _: Int, _: Int, _: Int, _: Int,
                _: Int, _: Int, _: Int, _: Int ->
            bindWhenReady()
        }
        previewView.addOnLayoutChangeListener(onLayout)
        if (future != null) {
            future.addListener({
                if (!disposed.get()) try {
                    providerReady = future.get()
                    bindWhenReady()
                } catch (e: Exception) {
                    latestError(e.localizedMessage ?: "Não foi possível abrir a câmera.")
                }
            },mainExecutor)
        } else {
            latestError("A câmera exige uma Activity Android compatível.")
        }
        onDispose {
            disposed.set(true)
            previewView.removeOnLayoutChangeListener(onLayout)
            boundProvider?.unbindAll()
            worker.shutdown()
            capture = null
            overlay = null
        }
    }

    var capturing by remember { mutableStateOf(false) }
    fun captureMeasurement() {
        val camera = capture ?: return
        if (capturing) return
        capturing = true
        val file = File(
            context.cacheDir, "medicao_${SystemClock.elapsedRealtimeNanos()}.jpg"
        )
        camera.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    try {
                        latestPhoto(SampledPhotos.fromFile(file))
                    } catch (e: Exception) {
                        latestError(e.localizedMessage ?: "Falha ao abrir imagem capturada.")
                    } finally {
                        capturing = false
                        file.delete()
                    }
                }
                override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                    capturing = false
                    file.delete()
                    latestError(exception.localizedMessage ?: "Falha ao capturar imagem.")
                }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { previewView },modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize().pointerInput(areaMode) {
                detectTapGestures { touch ->
                    val n = LiveViewportMapping.point(touch.x,touch.y,
                        size.width.toFloat(),size.height.toFloat())
                        ?.let { NormalizedPoint(it.first,it.second) }
                    if (n != null) {
                        val message = if (!areaMode) {
                            autoSearch = false
                            tapMarker = n
                            "Buscando contorno no ponto selecionado…"
                        } else {
                            val region = drawnArea
                            when {
                                region != null && region.contains(n) -> {
                                    autoSearch = false
                                    tapMarker = n
                                    "Buscando contorno dentro da área delimitada…"
                                }
                                region != null -> "Toque dentro da área delimitada ou redefina-a."
                                firstCorner == null -> {
                                    firstCorner = n
                                    "Toque no canto oposto para fechar a área."
                                }
                                else -> {
                                    val newArea = ContourNormalizedArea.fromCorners(firstCorner!!, n)
                                    if (newArea == null) {
                                        "Área pequena demais. Escolha cantos mais distantes."
                                    } else {
                                        drawnArea = newArea
                                        firstCorner = null
                                        "Área definida. Toque no objeto para refinar a busca."
                                    }
                                }
                            }
                        }
                        restartSearch(message)
                    }
                }
            }) {
                val region = drawnArea
                if (region != null) {
                    val color = Color(0xFFFFBF66)
                    val left = size.width*region.left
                    val right = size.width*region.right
                    val top = size.height*region.top
                    val bottom = size.height*region.bottom
                    drawLine(color,Offset(left,top),Offset(right,top),2.dp.toPx())
                    drawLine(color,Offset(right,top),Offset(right,bottom),2.dp.toPx())
                    drawLine(color,Offset(right,bottom),Offset(left,bottom),2.dp.toPx())
                    drawLine(color,Offset(left,bottom),Offset(left,top),2.dp.toPx())
                }
                firstCorner?.let { corner ->
                    drawCircle(Color(0xFFFFBF66),7.dp.toPx(),
                        Offset(size.width*corner.x,size.height*corner.y))
                }
                tapMarker?.let { marker ->
                    val pos = Offset(size.width * marker.x, size.height * marker.y)
                    drawCircle(Color(0xFFFFBF66), 10.dp.toPx(), pos)
                    drawCircle(Color(0xFF14251F), 5.dp.toPx(), pos)
                }
                val frame = overlay ?: return@Canvas
                val c = frame.contour ?: return@Canvas
                val color = Color(0xFFC0F565)
                val top = size.height*c.minY.toFloat()/c.height
                val bottom = size.height*(c.maxY+1f)/c.height
                val left = size.width*c.minX.toFloat()/c.width
                val right = size.width*(c.maxX+1f)/c.width
                drawLine(color,Offset(left,top),Offset(right,top),3.dp.toPx())
                drawLine(color,Offset(right,top),Offset(right,bottom),3.dp.toPx())
                drawLine(color,Offset(right,bottom),Offset(left,bottom),3.dp.toPx())
                drawLine(color,Offset(left,bottom),Offset(left,top),3.dp.toPx())
                c.outline.forEach { p ->
                    drawCircle(color,1.1.dp.toPx(),
                        Offset(size.width*(p.x.toFloat()/c.width),
                            size.height*(p.y.toFloat()/c.height)))
                }
            }
        }
        val ink = Color(0xFF14251F)
        val lime = Color(0xFFC0F565)
        Column(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.background(ink.copy(alpha = 0.91f),
                        RoundedCornerShape(60.dp))
                ) { Text("‹", fontSize = 28.sp, color = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (tool == LiveMeasureTool.TANK) "Tanques" else "Régua e área",
                        color = Color.White, fontWeight = FontWeight.Medium,
                        fontSize = 17.sp
                    )
                    Text(
                        if (tool == LiveMeasureTool.TANK) "CONTORNO AO VIVO"
                        else "MEDIÇÃO PELA CÂMERA",
                        color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp
                    )
                }
                TextButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.background(ink.copy(alpha = 0.91f),
                        RoundedCornerShape(60.dp))
                ) { Text("•••", fontSize = 16.sp, color = Color.White) }
            }
            Row(
                Modifier.fillMaxWidth().background(ink.copy(alpha = 0.96f),
                    RoundedCornerShape(35.dp)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val labels = if (tool == LiveMeasureTool.TANK)
                    listOf("Câmera", "Manual")
                else listOf("Régua", "Área")
                labels.forEachIndexed { index, label ->
                    val selectedTab = if (tool == LiveMeasureTool.TANK) index == 0
                        else (tool == LiveMeasureTool.RULER && index == 0) ||
                            (tool == LiveMeasureTool.AREA && index == 1)
                    Box(
                        Modifier.weight(1f)
                            .background(if (selectedTab) lime else Color.Transparent,
                                RoundedCornerShape(28.dp))
                            .clickable {
                                if (tool == LiveMeasureTool.TANK) {
                                    if (index == 1) onManual()
                                } else {
                                    onSwitchTool(if (index == 0) LiveMeasureTool.RULER
                                        else LiveMeasureTool.AREA)
                                }
                            }.padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 13.sp,
                            color = if (selectedTab) ink else Color.White,
                            fontWeight = FontWeight.Medium)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    when {
                        overlay?.contour != null -> "✓ Contorno visual detectado"
                        overlay == null -> "◉ Iniciando análise..."
                        else -> "Sem contorno — veja orientação"
                    },
                    color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.background(ink.copy(alpha = 0.93f),
                        RoundedCornerShape(24.dp)).padding(horizontal = 10.dp, vertical = 7.dp)
                )
                TextButton(onClick = onAr) {
                    Text("Medir em AR", color = lime, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(ink.copy(alpha = 0.98f),
                    RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                when (tool) {
                    LiveMeasureTool.RULER -> "Dois pontos. Uma medida."
                    LiveMeasureTool.AREA -> "Contorne a área"
                    LiveMeasureTool.TANK -> "Contorne o tanque"
                },
                color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp
            )
            Text(
                when (tool) {
                    LiveMeasureTool.RULER ->
                        "Capture a imagem e toque nos dois extremos da medida. Uma referência calibrada no mesmo plano é necessária para obter centímetros."
                    LiveMeasureTool.AREA ->
                        "Capture a imagem, depois marque os cantos da área em sequência. Calibre com uma referência no mesmo plano para calcular m²."
                    LiveMeasureTool.TANK ->
                        "Acompanhe o contorno visual ou escolha Medir em AR para marcar as dimensões do tanque."
                },
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                overlay?.message ?: "Preparando análise pela câmera...",
                color = if (overlay?.contour != null) lime
                    else Color.White.copy(alpha = .8f),
                fontSize = 11.sp
            )
            overlay?.contour?.let {
                Text(
                    "Região: ${(it.widthFraction*100).toInt()}% da largura × " +
                        "${(it.heightFraction*100).toInt()}% da altura do quadro",
                    color = lime, fontSize = 12.sp
                )
            }
            Button(
                onClick = {
                    if (tool == LiveMeasureTool.TANK) onManual()
                    else captureMeasurement()
                },
                enabled = tool == LiveMeasureTool.TANK || (capture != null && !capturing),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = lime, contentColor = ink
                ),
                shape = RoundedCornerShape(13.dp)
            ) {
                Text(
                    if (tool == LiveMeasureTool.TANK) "Informar dimensões do tanque"
                    else if (capturing) "Capturando..." else "Marcar pontos na câmera",
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                if (tool == LiveMeasureTool.TANK)
                    "A capacidade precisa do diâmetro e da altura medidos. Nenhuma medida é inventada."
                else "Sem referência calibrada não há resultado em metros ou m².",
                fontSize = 10.sp, color = Color.White.copy(alpha = 0.65f)
            )
        }
        if (menuOpen) {
            ModalBottomSheet(onDismissRequest = { menuOpen = false }) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {

        Text("Contorno ao vivo — experimental",
            style = MaterialTheme.typography.titleMedium)
        Text("A busca automática procura objetos isolados. Se não encontrar, " +
            "toque na parte central do objeto ou delimite uma região para ajudar.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            enabled = !autoSearch,
            onClick = {
                autoSearch = true
                tapMarker = null
                restartSearch("Buscando objetos automaticamente…")
            }
        ) { Text("Buscar contorno automaticamente") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !areaMode,
                onClick = {
                    areaMode = false
                    drawnArea = null
                    firstCorner = null
                    autoSearch = true
                    tapMarker = null
                    restartSearch("Buscando contornos automaticamente.")
                },
                label = { Text("Toque livre") }
            )
            FilterChip(
                selected = areaMode,
                onClick = {
                    areaMode = true
                    drawnArea = null
                    firstCorner = null
                    autoSearch = true
                    tapMarker = null
                    restartSearch("Marque dois cantos para delimitar a área.")
                },
                label = { Text("Delimitar área") }
            )
        }
        if (areaMode) {
            OutlinedButton(onClick = {
                drawnArea = null
                firstCorner = null
                autoSearch = true
                tapMarker = null
                restartSearch("Marque dois cantos para delimitar a área.")
            }) { Text("Redefinir área") }
        }

        Text(overlay?.message ?: "Preparando câmera…",
            style = MaterialTheme.typography.bodyMedium)
        overlay?.contour?.let { contour ->
            Text("Largura: ${(contour.widthFraction*100).toInt()}% do quadro • " +
                "Altura: ${(contour.heightFraction*100).toInt()}% do quadro " +
                "(não são centímetros).",
                style = MaterialTheme.typography.bodySmall)
        }
        overlay?.assessment?.let { assessment ->
            Text("Estabilidade visual: ${assessment.rating.display}. " +
                assessment.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = if (assessment.rating ==
                    br.com.medidor.app.domain.ContourVisualRating.STABLE)
                    MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in listOf(18,32,55)) {
                OutlinedButton(onClick = {
                    tolerance = choice
                    restartSearch(if (areaMode && drawnArea == null)
                        "Marque dois cantos para delimitar a área."
                        else "Ajustando tolerância de contorno…")
                },enabled = tolerance != choice) {
                    Text(when(choice) {
                        18 -> "Restrito"
                        32 -> "Normal"
                        else -> "Amplo"
                    })
                }
            }
        }
        if (tool != LiveMeasureTool.TANK) Button(
            enabled = capture != null && !capturing,
            onClick = { menuOpen = false; captureMeasurement() }
        ) { Text("Capturar e marcar pontos") }
        Text(
            if (tool == LiveMeasureTool.TANK)
                "O contorno mostra proporções do quadro. Use Medir em AR para marcar " +
                    "os extremos do tanque e enviar as dimensões à calculadora."
            else "Contorno ao vivo não calcula profundidade ou escala real. Capture " +
                "e calibre uma referência no mesmo plano antes de registrar uma medida.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
                }
            }
        }
    }
}

