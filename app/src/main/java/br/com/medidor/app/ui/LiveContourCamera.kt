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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import br.com.medidor.app.domain.LiveContourTracking
import br.com.medidor.app.domain.LiveViewportMapping
import br.com.medidor.app.domain.ContourNormalizedArea
import br.com.medidor.app.domain.ContourVisualAssessment
import br.com.medidor.app.domain.ContourVisualQuality
import br.com.medidor.app.domain.NormalizedPoint
import br.com.medidor.app.domain.PlanarContourResult
import br.com.medidor.app.domain.PlanarContourSegmentation
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private data class ContourOverlay(
    val width: Int,
    val height: Int,
    val contour: PlanarContourResult?,
    val message: String,
    val assessment: ContourVisualAssessment? = null
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
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val owner = context as? ComponentActivity
    val latestPhoto by rememberUpdatedState(onPhoto)
    val latestError by rememberUpdatedState(onError)
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val worker = remember { Executors.newSingleThreadExecutor() }
    val selected = remember { AtomicReference<NormalizedPoint?>(NormalizedPoint(0.5f, 0.5f)) }
    val previous = remember { AtomicReference<PlanarContourResult?>(null) }
    val chosenArea = remember { AtomicReference<ContourNormalizedArea?>(null) }
    var areaMode by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var firstCorner by remember { mutableStateOf<NormalizedPoint?>(null) }
    var drawnArea by remember { mutableStateOf<ContourNormalizedArea?>(null) }
    val selectionGeneration = remember { AtomicInteger(0) }
    var overlay by remember { mutableStateOf<ContourOverlay?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var tolerance by remember { mutableStateOf(32) }
    val threshold = remember { java.util.concurrent.atomic.AtomicInteger(32) }
    val previewView = remember(context) {
        PreviewView(context).apply { scaleType=PreviewView.ScaleType.FILL_CENTER }
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
                analysis.setAnalyzer(worker) { proxy ->
                    try {
                        val now = SystemClock.elapsedRealtime()
                        if (!disposed.get() && now - lastFrame >= 250) {
                            lastFrame = now
                            val frame = LiveCameraFrameSampler.sample(proxy)
                            if (frame != null) {
                                val generation = selectionGeneration.get()
                                val point = selected.get()
                                val before = previous.get()
                                val bounds = chosenArea.get()?.pixels(frame.width,frame.height)
                                val detected = if (point != null)
                                    PlanarContourSegmentation.segment(
                                        frame.pixels,frame.width,frame.height,
                                        (point.x * (frame.width-1)).toInt()
                                            .coerceIn(0,frame.width-1),
                                        (point.y * (frame.height-1)).toInt()
                                            .coerceIn(0,frame.height-1),
                                        threshold.get(),bounds
                                    ) else null
                                val checked = if (point == null) null else
                                    LiveContourTracking.check(before,detected)
                                if (generation == selectionGeneration.get()) {
                                    val nextContour = checked?.result
                                    if (point != null && nextContour == null) {
                                        // Do not keep searching the old location
                                        // after a failed or unstable segmentation.
                                        selected.set(null)
                                    } else if (point != null && before != null &&
                                        nextContour != null) {
                                        LiveContourTracking.followSeed(before,nextContour,point)
                                            ?.let { selected.set(it) }
                                    }
                                    previous.set(nextContour)
                                    val visual = nextContour?.let {
                                        ContourVisualQuality.assess(it,before,bounds)
                                    }
                                    val output = ContourOverlay(frame.width,frame.height,
                                        nextContour,checked?.label
                                            ?: "Toque no objeto para selecionar o contorno.",
                                        visual)
                                    mainExecutor.execute {
                                        if (!disposed.get() &&
                                            generation == selectionGeneration.get()) {
                                            overlay = output
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        if (!disposed.get()) mainExecutor.execute {
                            if (!disposed.get()) {
                                overlay = ContourOverlay(1,1,null,
                                    "Falha no quadro; toque novamente para selecionar.")
                                selected.set(null)
                                previous.set(null)
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

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { previewView },modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize().pointerInput(areaMode,overlay?.width,overlay?.height) {
                detectTapGestures { touch ->
                    val n = LiveViewportMapping.point(touch.x,touch.y,
                        size.width.toFloat(),size.height.toFloat())
                        ?.let { NormalizedPoint(it.first,it.second) }
                    if (n != null) {
                        selectionGeneration.incrementAndGet()
                        selected.set(null)
                        previous.set(null)
                        val message = if (!areaMode) {
                            selected.set(n)
                            "Analisando a região selecionada…"
                        } else {
                            val region = chosenArea.get()
                            when {
                                region != null && region.contains(n) -> {
                                    selected.set(n)
                                    "Analisando a região dentro da área delimitada…"
                                }
                                region != null -> "Toque dentro da área delimitada ou redefina-a."
                                firstCorner == null -> {
                                    firstCorner = n
                                    "Toque no canto oposto para fechar a área."
                                }
                                else -> {
                                    val newArea = ContourNormalizedArea.fromCorners(
                                        firstCorner!!,n)
                                    if (newArea == null) {
                                        "Área pequena demais: escolha cantos mais distantes."
                                    } else {
                                        chosenArea.set(newArea)
                                        drawnArea = newArea
                                        firstCorner = null
                                        "Área definida. Agora toque no objeto dentro dela."
                                    }
                                }
                            }
                        }
                        overlay = overlay?.copy(contour = null,message=message,
                            assessment=null)
                            ?: ContourOverlay(1,1,null,message)
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
                val frame = overlay ?: return@Canvas
                val c = frame.contour ?: return@Canvas
                val color = Color(0xFF44E8B5)
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
        TextButton(
            onClick = { menuOpen = true },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
        ) {
            Text("⌃  Puxe para abrir controles", color = Color.White)
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
            Text(
                overlay?.contour?.let {
                    "Contorno detectado: largura ${(it.widthFraction*100).toInt()}% • altura ${(it.heightFraction*100).toInt()}% do quadro"
                } ?: (overlay?.message ?: "Procurando contornos… Toque no objeto para corrigir."),
                color = Color.White, style = MaterialTheme.typography.bodyMedium
            )
            Text("Valores relativos à imagem; centímetros exigem calibração.",
                color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
        if (menuOpen) {
            ModalBottomSheet(onDismissRequest = { menuOpen = false }) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {

        Text("Contorno ao vivo — experimental",
            style = MaterialTheme.typography.titleMedium)
        Text("Selecione diretamente o objeto ou delimite uma área para reduzir " +
            "a interferência de fundos com cores parecidas.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !areaMode,
                onClick = {
                    areaMode = false
                    chosenArea.set(null)
                    drawnArea = null
                    firstCorner = null
                    selected.set(null)
                    previous.set(null)
                    selectionGeneration.incrementAndGet()
                    overlay = overlay?.copy(contour=null,assessment=null,
                        message="Modo livre: toque no objeto.")
                },
                label = { Text("Toque livre") }
            )
            FilterChip(
                selected = areaMode,
                onClick = {
                    areaMode = true
                    chosenArea.set(null)
                    drawnArea = null
                    firstCorner = null
                    selected.set(null)
                    previous.set(null)
                    selectionGeneration.incrementAndGet()
                    overlay = overlay?.copy(contour=null,assessment=null,
                        message="Marque dois cantos e depois toque dentro do objeto.")
                },
                label = { Text("Delimitar área") }
            )
        }
        if (areaMode) {
            OutlinedButton(onClick = {
                chosenArea.set(null)
                drawnArea = null
                firstCorner = null
                selected.set(null)
                previous.set(null)
                selectionGeneration.incrementAndGet()
                overlay = overlay?.copy(contour=null,assessment=null,
                    message="Marque novamente dois cantos e toque dentro do objeto.")
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
                    threshold.set(choice)
                    previous.set(null)
                    selectionGeneration.incrementAndGet()
                    overlay = overlay?.copy(contour = null,assessment=null,
                        message = "Ajustando tolerância de contorno…")
                },enabled = tolerance != choice) {
                    Text(when(choice) {
                        18 -> "Restrito"
                        32 -> "Normal"
                        else -> "Amplo"
                    })
                }
            }
        }
        Button(enabled = capture != null,onClick = {
            val camera = capture ?: return@Button
            val file = File(context.cacheDir,
                "contorno_${SystemClock.elapsedRealtimeNanos()}.jpg")
            camera.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        try { latestPhoto(SampledPhotos.fromFile(file)) }
                        catch (e: Exception) {
                            latestError(e.localizedMessage ?: "Falha ao abrir a foto.")
                        } finally { file.delete() }
                    }
                    override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                        file.delete()
                        latestError(exception.localizedMessage ?: "Falha na captura.")
                    }
                }
            )
        }) { Text("Capturar foto para medir em cm") }
        Text("A seleção ao vivo NÃO mede distância, profundidade nem identifica " +
            "automaticamente o objeto. Para estimar centímetros, capture uma foto " +
            "e calibre uma referência física no mesmo plano. O contorno da câmera " +
            "é apenas uma aproximação visual, mesmo quando a estabilidade aparece alta.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

