package br.com.medidor.app.ui

import android.Manifest
import android.graphics.Bitmap
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import br.com.medidor.app.domain.CountRegion
import br.com.medidor.app.domain.CameraPreviewGeometry
import br.com.medidor.app.domain.LiveRegionStabilizer
import br.com.medidor.app.domain.LiveObjectCounter
import br.com.medidor.app.domain.LiveCountStabilizer
import br.com.medidor.app.domain.PieceAppearance
import br.com.medidor.app.domain.SimilarObjectCounter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private data class CountFrame(
    val width: Int,
    val height: Int,
    val regions: List<CountRegion>,
    val pixels: IntArray,
    val count: Int
)

/**
 * Offline camera counting. The user taps one visible component to choose the
 * visual reference. Only matching color/shape signatures are counted.
 * This is visual similarity, NOT a semantic class detector.
 */
@Composable
fun LiveObjectCountScreen(onSave: (String, String) -> Unit = { _, _ -> }, onPhoto: (Bitmap, List<Offset>) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val owner = context as? ComponentActivity
    val ink = Color(0xFF14251F)
    val lime = Color(0xFFC0F565)
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<CountFrame?>(null) }
    var sensitivity by remember { mutableStateOf(45) }
    var reference by remember { mutableStateOf<PieceAppearance?>(null) }
    var stableCount by remember { mutableStateOf<Int?>(null) }
    var saved by remember { mutableStateOf(false) }
    var selectionNotice by remember { mutableStateOf<String?>(null) }
    var frozenPhoto by remember { mutableStateOf<Bitmap?>(null) }
    val locked = remember { AtomicBoolean(false) }

    // Analysis and UI run on different threads; the reference is thread safe.
    val target = remember { AtomicReference<PieceAppearance?>(null) }
    val generation = remember { AtomicInteger(0) }
    val threshold = remember { AtomicInteger(45) }
    // Avoid cancelling a finger gesture on every incoming camera frame.
    val latestFrame by rememberUpdatedState(result)
    val preview = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().background(ink).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Contagem de peças", color = Color.White,
                style = MaterialTheme.typography.titleLarge)
            Text("A câmera encontra contornos no próprio aparelho. Selecione " +
                "uma peça para contar outras de aparência semelhante.",
                color = Color.White)
            Button(
                onClick = { permission.launch(Manifest.permission.CAMERA) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = lime, contentColor = ink
                )
            ) { Text("Permitir câmera") }
        }
        return
    }

    DisposableEffect(owner, preview) {
        val disposed = AtomicBoolean(false)
        val worker = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        var provider: ProcessCameraProvider? = null
        var bound = false
        val future = if (owner != null) ProcessCameraProvider.getInstance(context) else null
        val stabilizer = LiveCountStabilizer()
        val regionStabilizer = LiveRegionStabilizer()
        var previousGeneration = -1
        fun bind() {
            val camera = provider ?: return
            val viewport = preview.viewPort ?: return
            if (disposed.get() || bound || owner == null) return
            try {
                val previewCase = Preview.Builder().build().apply {
                    setSurfaceProvider(preview.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                var last = 0L
                analysis.setAnalyzer(worker) { frame ->
                    try {
                        val now = SystemClock.elapsedRealtime()
                        if (!disposed.get() && !locked.get() && now - last >= 300) {
                            last = now
                            val sampled = LiveCameraFrameSampler.sample(frame)
                            if (sampled != null) {
                                val version = generation.get()
                                val exemplar = target.get()
                                val regions = LiveObjectCounter.detect(
                                    sampled.pixels,
                                    sampled.width, sampled.height,
                                    threshold.get(), reference = exemplar
                                )
                                if (version != previousGeneration) {
                                    stabilizer.reset()
                                    regionStabilizer.reset()
                                    previousGeneration = version
                                }
                                val selectedRegions = if (exemplar == null) regions else
                                    SimilarObjectCounter.matches(exemplar, regions)
                                val detected = selectedRegions.size
                                // A constant number is not enough: objects must remain
                                // at the same positions for consecutive camera frames.
                                val positionsStable = regionStabilizer.update(
                                    selectedRegions, sampled.width, sampled.height
                                )
                                val confirmed = stabilizer.update(detected)
                                    ?.takeIf { it == detected && positionsStable }
                                main.execute {
                                    if (!disposed.get() && !locked.get() && version == generation.get()) {
                                        result = CountFrame(
                                            sampled.width, sampled.height, regions, sampled.pixels, detected
                                        )
                                        stableCount = confirmed?.takeIf { it == detected }
                                        if (confirmed == detected && detected > 0) {
                                            frozenPhoto = Bitmap.createBitmap(sampled.pixels,sampled.width,sampled.height,Bitmap.Config.ARGB_8888)
                                            locked.set(true)
                                        }
                                        error = null
                                    }
                                }
                            } else main.execute { if (!disposed.get()) {
                                stableCount = null
                                error = "Não foi possível ler as cores desta câmera. Use Por foto para contar."
                            } }
                        }
                    } catch (_: Exception) {
                        stabilizer.reset()
                        regionStabilizer.reset()
                        main.execute { if (!disposed.get()) {
                            stableCount = null
                            error = "Não foi possível analisar esta imagem. Use Por foto para conferir a contagem."
                        } }
                    } finally {
                        frame.close()
                    }
                }
                val group = UseCaseGroup.Builder()
                    .addUseCase(previewCase)
                    .addUseCase(analysis)
                    .setViewPort(viewport)
                    .build()
                camera.unbindAll()
                camera.bindToLifecycle(
                    owner, CameraSelector.DEFAULT_BACK_CAMERA, group
                )
                bound = true
            } catch (e: Exception) {
                error = e.localizedMessage ?: "Não foi possível iniciar a câmera."
            }
        }
        val listener = View.OnLayoutChangeListener {
                _: View, _: Int, _: Int, _: Int, _: Int, _: Int, _: Int, _: Int, _: Int ->
            bind()
        }
        preview.addOnLayoutChangeListener(listener)
        future?.addListener({
            if (!disposed.get()) try {
                provider = future.get()
                bind()
            } catch (e: Exception) {
                error = e.localizedMessage ?: "Não foi possível abrir a câmera."
            }
        }, main)
        if (future == null) error = "Activity de câmera indisponível."
        onDispose {
            disposed.set(true)
            preview.removeOnLayoutChangeListener(listener)
            provider?.unbindAll()
            worker.shutdown()
        }
    }

    Column(
        Modifier.fillMaxSize().background(ink),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Contador de peças", color = Color.White,
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("CÂMERA AO VIVO · SEM UPLOAD",
                    color = Color.White.copy(alpha = .7f), fontSize = 10.sp)
            }
            TextButton(onClick = {
                locked.set(false)
                frozenPhoto = null
                result = null
                reference = null
                target.set(null)
                generation.incrementAndGet()
                stableCount = null
                saved = false
            }) {
                Text("Nova leitura", color = lime)
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
            frozenPhoto?.let { Image(it.asImageBitmap(),"Imagem fixada dos objetos",Modifier.fillMaxSize(),contentScale = ContentScale.Crop) }
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { position ->
                        val frame = latestFrame ?: return@detectTapGestures
                        if (size.width <= 0 || size.height <= 0) return@detectTapGestures
                        val geometry = CameraPreviewGeometry(
                            frame.width, frame.height, size.width.toFloat(), size.height.toFloat()
                        )
                        val pixel = geometry.imagePoint(position.x, position.y)
                            ?: return@detectTapGestures
                        val x = pixel.first.toInt().coerceIn(0, frame.width - 1)
                        val y = pixel.second.toInt().coerceIn(0, frame.height - 1)
                        val selected = LiveObjectCounter.selectAt(frame.pixels, frame.width, frame.height, x, y, sensitivity)
                            ?: SimilarObjectCounter.at(frame.regions, x, y)
                        val exemplar = selected?.let(SimilarObjectCounter::appearance)
                        if (exemplar != null) {
                            reference = exemplar
                            target.set(exemplar)
                            generation.incrementAndGet()
                            stableCount = null
                            if (locked.get()) {
                                val regions = LiveObjectCounter.detect(frame.pixels,frame.width,frame.height,sensitivity,reference = exemplar)
                                val count = SimilarObjectCounter.matches(exemplar,regions).size
                                result = CountFrame(frame.width,frame.height,regions,frame.pixels,count)
                                stableCount = count
                            }
                            saved = false
                            selectionNotice = null
                        } else {
                            selectionNotice = "Peça não identificada neste ponto. Toque no centro dela ou use Conferir foto."
                        }
                    }
                }
            ) {
                val frame = result ?: return@Canvas
                val geometry = CameraPreviewGeometry(
                    frame.width, frame.height, size.width, size.height
                )
                frame.regions.forEach { region ->
                    val matching = reference?.let {
                        SimilarObjectCounter.matches(it, region)
                    } == true
                    val color = if (matching) lime else
                        Color.White.copy(alpha = .5f)
                    val (left, top) = geometry.viewPoint(region.left.toFloat(), region.top.toFloat())
                    val (right, bottom) = geometry.viewPoint(
                        region.right + 1f, region.bottom + 1f
                    )
                    val stroke = if (matching) 3.dp.toPx() else 1.5.dp.toPx()
                    drawLine(color, Offset(left, top), Offset(right, top), stroke)
                    drawLine(color, Offset(right, top), Offset(right, bottom), stroke)
                    drawLine(color, Offset(right, bottom), Offset(left, bottom), stroke)
                    drawLine(color, Offset(left, bottom), Offset(left, top), stroke)
                    region.outline.forEach { p ->
                        drawCircle(
                            color, 1.2.dp.toPx(),
                            geometry.viewPoint(p.x.toFloat(), p.y.toFloat())
                                .let { Offset(it.first, it.second) }
                        )
                    }
                }
            }
            Text(
                if (locked.get()) "IMAGEM FIXADA · NOVA LEITURA PARA ESCANEAR DE NOVO"
                else if (reference == null) "CONTANDO OBJETOS · TOQUE PARA FILTRAR UMA PEÇA"
                else "VERDE: PEÇAS VISUALMENTE SEMELHANTES",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .background(ink.copy(alpha = .88f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        Column(
            Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).background(ink)
                .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(if (reference == null) "Objetos detectados" else "Peças do tipo selecionado",
                color = Color.White, fontSize = 13.sp)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    when {
                        stableCount == null -> result?.count?.let { "$it (conferindo)" } ?: "Iniciando câmera…"
                        else -> stableCount.toString()
                    },
                    fontSize = if (stableCount != null) 38.sp else 21.sp,
                    color = lime, fontWeight = FontWeight.SemiBold
                )
                Button(
                    enabled = stableCount != null && stableCount!! > 0 && !saved,
                    onClick = {
                        val count = stableCount ?: return@Button
                        onSave(
                            "Contagem de peças",
                            "$count ${if (reference == null) "objetos detectados" else "peças semelhantes"} (estimativa visual pela câmera)"
                        )
                        saved = true
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = lime, contentColor = ink
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Salvar contagem") }
            }
            OutlinedButton(enabled = result != null, onClick = {
                val frame = result ?: return@OutlinedButton
                val pieces = reference?.let { SimilarObjectCounter.matches(it, frame.regions) } ?: frame.regions
                onPhoto(Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888),
                    pieces.map { Offset((it.left + it.right + 1f) / (2 * frame.width), (it.top + it.bottom + 1f) / (2 * frame.height)) })
            }, modifier = Modifier.fillMaxWidth()) { Text("Conferir foto", color = lime) }
            selectionNotice?.let { Text(it, color = lime, fontSize = 12.sp) }
            if (stableCount == 0) Text("Nenhuma peça identificada. Separe as peças e use um fundo contrastante.",color = Color.White,fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    30 to "Sensível", 45 to "Normal", 65 to "Restrito"
                ).forEach { (choice, name) ->
                    OutlinedButton(
                        enabled = sensitivity != choice,
                        onClick = {
                            sensitivity = choice
                            locked.set(false)
                            frozenPhoto = null
                            threshold.set(choice)
                            generation.incrementAndGet()
                            stableCount = null
                            result = null
                            reference = null
                            target.set(null)
                            saved = false
                        }
                    ) { Text(name, fontSize = 11.sp, color = lime) }
                }
            }
            if (saved) {
                Text("Contagem salva no histórico deste aparelho.",
                    color = lime, fontSize = 12.sp)
            }
            error?.let {
                Text(it, color = Color(0xFFFFB0A0), fontSize = 12.sp)
            }
            Text(
                "Experimental: compara cor e formato, não reconhece categorias " +
                    "por IA. Separe as peças, use fundo uniforme e boa luz. " +
                    "Objetos encostados, cobertos ou muito parecidos podem alterar a contagem.",
                color = Color.White.copy(alpha = .7f), fontSize = 11.sp
            )
        }
    }
}
