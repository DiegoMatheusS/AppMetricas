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
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ZoomState
import androidx.lifecycle.Observer
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import br.com.medidor.app.domain.CameraPreviewGeometry
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
    errorMessage: String? = null,
    onGallery: () -> Unit = {},
    tool: LiveMeasureTool = LiveMeasureTool.RULER,
    onBack: () -> Unit = {},
    onManual: () -> Unit = {},
    onAr: () -> Unit,
    onArPoints: () -> Unit = onAr,
    onSwitchTool: (LiveMeasureTool) -> Unit = {},
    onSelectionSeed: (NormalizedPoint?) -> Unit = {}
) {
    val context = LocalContext.current
    val owner = context as? ComponentActivity
    val latestPhoto by rememberUpdatedState(onPhoto)
    val latestSeed by rememberUpdatedState(onSelectionSeed)
    val latestError by rememberUpdatedState(onError)
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val worker = remember { Executors.newSingleThreadExecutor() }
    var showContours by remember { mutableStateOf(false) }
    var autoSearch by remember { mutableStateOf(true) }
    var tapMarker by remember { mutableStateOf<NormalizedPoint?>(null) }
    var areaMode by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var firstCorner by remember { mutableStateOf<NormalizedPoint?>(null) }
    var drawnArea by remember { mutableStateOf<ContourNormalizedArea?>(null) }
    var overlay by remember { mutableStateOf<ContourOverlay?>(null) }
    val latestOverlay by rememberUpdatedState(overlay)
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableStateOf(1f) }
    var minimumZoom by remember { mutableStateOf(1f) }
    var maximumZoom by remember { mutableStateOf(1f) }
    var tolerance by remember { mutableStateOf(32) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            clipChildren = true
            clipToPadding = true
        }
    }

    val selection = remember { AtomicReference(ContourSelection(ready = false)) }
    DisposableEffect(boundCamera, owner) {
        val info = boundCamera?.cameraInfo
        val observer = Observer<ZoomState> { state ->
            zoomRatio = state.zoomRatio
            minimumZoom = state.minZoomRatio
            maximumZoom = minOf(8f,state.maxZoomRatio)
        }
        if (owner != null) info?.zoomState?.observe(owner,observer)
        onDispose { info?.zoomState?.removeObserver(observer) }
    }
    fun restartSearch(message: String) {
        selection.set(ContourSelection(autoSearch, tapMarker, drawnArea, tolerance,
            ready = showContours && (!areaMode || drawnArea != null)))
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
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
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
                try {
                    boundCamera = provider.bindToLifecycle(owner,CameraSelector.DEFAULT_BACK_CAMERA,group)
                } catch (_: Exception) {
                    provider.unbindAll()
                    analysis.clearAnalyzer()
                    val photoGroup = UseCaseGroup.Builder().addUseCase(preview)
                        .addUseCase(imageCapture).setViewPort(viewPort).build()
                    boundCamera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, photoGroup)
                    latestError("Use Marcar pontos para medir. A análise ao vivo não está disponível neste aparelho.")
                }
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
            boundCamera = null
            overlay = null
        }
    }

    var capturing by remember { mutableStateOf(false) }
    fun captureMeasurement(seed: NormalizedPoint? = null) {
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
                        latestSeed(seed)
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

    val ink = Color(0xFF14251F)
    val lime = Color(0xFFC0F565)
    Column(Modifier.fillMaxSize().background(ink)) {
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()
            .semantics { contentDescription = "Visor livre da câmera" }) {
            AndroidView(factory = { previewView },modifier = Modifier.fillMaxSize().clipToBounds())
            Canvas(Modifier.fillMaxSize().pointerInput(areaMode) {
                detectTapGestures { touch ->
                    if (!showContours) {
                        if (!capturing) onAr()
                        return@detectTapGestures
                    }
                    val currentFrame = latestOverlay?.takeIf { it.width > 1 && it.height > 1 }
                    val normalized = currentFrame?.let {
                        CameraPreviewGeometry(
                            it.width, it.height, size.width.toFloat(), size.height.toFloat()
                        ).normalizedImagePoint(touch.x, touch.y)
                    } ?: LiveViewportMapping.point(
                        touch.x, touch.y, size.width.toFloat(), size.height.toFloat()
                    )
                    val n = normalized?.let { NormalizedPoint(it.first, it.second) }
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
                val currentFrame = overlay?.takeIf { it.width > 1 && it.height > 1 }
                val geometry = currentFrame?.let {
                    CameraPreviewGeometry(it.width, it.height, size.width, size.height)
                }
                // PreviewView uses FILL_CENTER: its image can be cropped, not stretched.
                fun marker(nx: Float, ny: Float): Offset {
                    val (x, y) = geometry?.normalizedViewPoint(nx, ny)
                        ?: (size.width * nx to size.height * ny)
                    return Offset(x, y)
                }
                drawnArea?.let { region ->
                    val color = Color(0xFFFFBF66)
                    val a = marker(region.left, region.top)
                    val b = marker(region.right, region.top)
                    val c = marker(region.right, region.bottom)
                    val d = marker(region.left, region.bottom)
                    drawLine(color, a, b, 2.dp.toPx())
                    drawLine(color, b, c, 2.dp.toPx())
                    drawLine(color, c, d, 2.dp.toPx())
                    drawLine(color, d, a, 2.dp.toPx())
                }
                firstCorner?.let { corner ->
                    drawCircle(Color(0xFFFFBF66), 7.dp.toPx(), marker(corner.x, corner.y))
                }
                tapMarker?.let { selected ->
                    val pos = marker(selected.x, selected.y)
                    drawCircle(Color(0xFFFFBF66), 10.dp.toPx(), pos)
                    drawCircle(Color(0xFF14251F), 5.dp.toPx(), pos)
                }
                if (!showContours) return@Canvas
                val c = overlay?.contour ?: return@Canvas
                val crop = CameraPreviewGeometry(c.width, c.height, size.width, size.height)
                fun pixel(x: Float, y: Float): Offset {
                    val (viewX, viewY) = crop.viewPoint(x, y)
                    return Offset(viewX, viewY)
                }
                val color = Color(0xFFC0F565)
                val a = pixel(c.minX.toFloat(), c.minY.toFloat())
                val b = pixel(c.maxX + 1f, c.minY.toFloat())
                val lowerRight = pixel(c.maxX + 1f, c.maxY + 1f)
                val d = pixel(c.minX.toFloat(), c.maxY + 1f)
                drawLine(color, a, b, 3.dp.toPx())
                drawLine(color, b, lowerRight, 3.dp.toPx())
                drawLine(color, lowerRight, d, 3.dp.toPx())
                drawLine(color, d, a, 3.dp.toPx())
                c.outline.forEach { point ->
                    drawCircle(color, 1.1.dp.toPx(),
                        pixel(point.x.toFloat(), point.y.toFloat()))
                }
            }
        }
        val message = errorMessage ?: when {
            capturing -> "Capturando a foto…"
            tool == LiveMeasureTool.TANK -> "Use Auto para medir no ambiente ou Manual para informar as dimensões."
            else -> "Auto ou Pontos calcula no ambiente, sem digitar medidas. Foto usa uma referência conhecida."
        }
        MeasurementDock(if (tool == LiveMeasureTool.TANK) "Tanques" else "Régua e área", message) {
            if (tool == LiveMeasureTool.TANK) {
                MeasurementToolRow(listOf(
                    MeasureAction("Voltar", icon = MeasureIcon.BACK, action = onBack),
                    MeasureAction("Auto", "Medir tanque no ambiente", MeasureIcon.AUTO, selected = true, action = onAr),
                    MeasureAction("Manual", "Dimensões manuais", MeasureIcon.RULER, action = onManual),
                    MeasureAction("Mais", "Mais opções da câmera", MeasureIcon.MORE, action = { menuOpen = true })
                ), columns = 4)
            } else {
                MeasurementToolRow(listOf(
                    MeasureAction("Voltar", icon = MeasureIcon.BACK, action = onBack),
                    MeasureAction("Régua", "Medir distância", MeasureIcon.RULER, selected = tool == LiveMeasureTool.RULER,
                        action = { onSwitchTool(LiveMeasureTool.RULER) }),
                    MeasureAction("Área", "Medir área", MeasureIcon.AREA, selected = tool == LiveMeasureTool.AREA,
                        action = { onSwitchTool(LiveMeasureTool.AREA) }),
                    MeasureAction("Auto", "Medir automaticamente no ambiente", MeasureIcon.AUTO, selected = true,
                        enabled = !capturing, action = onAr),
                    MeasureAction("Foto", "Marcar pontos na foto com referência", MeasureIcon.PHOTO,
                        enabled = capture != null && !capturing, action = { captureMeasurement() }),
                    MeasureAction("Mais", "Mais opções da câmera", MeasureIcon.MORE, action = { menuOpen = true })
                ))
                MeasurementToolRow(listOf(1f,2f,4f,8f).map { level ->
                    MeasureAction(level.toInt().toString()+"×", "Zoom de "+level.toInt()+" vezes",
                        selected = kotlin.math.abs(zoomRatio-level)<.05f,
                        enabled = boundCamera != null && !capturing && level in minimumZoom..maximumZoom,
                        action = {
                            val future = boundCamera?.cameraControl?.setZoomRatio(level)
                            future?.addListener({ runCatching { future.get() }.onFailure {
                                latestError("Não foi possível aplicar este zoom.")
                            } },mainExecutor)
                        })
                } + listOf(
                    MeasureAction("Galeria", "Abrir fotografia", MeasureIcon.GALLERY, action = onGallery),
                    MeasureAction("Pontos", "Marcar pontos no ambiente sem informar tamanho", MeasureIcon.POINT,
                        enabled = !capturing, action = onArPoints)
                ))
            }
        }
        if (menuOpen) {
            ModalBottomSheet(onDismissRequest = { menuOpen = false }) {
                Column(
                    Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {

        FilterChip(selected = showContours, onClick = {
            showContours = !showContours
            restartSearch(if (showContours) "Buscando contornos…" else "Contornos ocultos.")
        }, label = { Text("Mostrar contornos") })
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

