package br.com.medidor.app.ui

import android.Manifest
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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import br.com.medidor.app.domain.CountRegion
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
    val regions: List<CountRegion>
)

/**
 * Offline camera counting. The user taps one visible component to choose the
 * visual reference. Only matching color/shape signatures are counted.
 * This is visual similarity, NOT a semantic class detector.
 */
@Composable
fun LiveObjectCountScreen(onSave: (String, String) -> Unit = { _, _ -> }) {
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
                        if (!disposed.get() && now - last >= 300) {
                            last = now
                            val sampled = LiveCameraFrameSampler.sample(frame)
                            if (sampled != null) {
                                val regions = LiveObjectCounter.detect(
                                    sampled.pixels,
                                    sampled.width, sampled.height,
                                    threshold.get(), reference = target.get()
                                )
                                val version = generation.get()
                                if (version != previousGeneration) {
                                    stabilizer.reset()
                                    previousGeneration = version
                                }
                                val exemplar = target.get()
                                val detected = if (exemplar == null) null else
                                    SimilarObjectCounter.matches(exemplar, regions).size
                                val confirmed = detected?.let { stabilizer.update(it) }
                                main.execute {
                                    if (!disposed.get() && version == generation.get()) {
                                        result = CountFrame(
                                            sampled.width, sampled.height, regions
                                        )
                                        stableCount = confirmed
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        stabilizer.reset()
                        main.execute { if (!disposed.get()) stableCount = null }
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
                reference = null
                target.set(null)
                generation.incrementAndGet()
                stableCount = null
                saved = false
            }) {
                Text("Mudar peça", color = lime)
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { position ->
                        val frame = latestFrame ?: return@detectTapGestures
                        if (size.width <= 0 || size.height <= 0) return@detectTapGestures
                        val x = (position.x * frame.width / size.width).toInt()
                            .coerceIn(0, frame.width - 1)
                        val y = (position.y * frame.height / size.height).toInt()
                            .coerceIn(0, frame.height - 1)
                        val selected = SimilarObjectCounter.at(frame.regions, x, y)
                        val exemplar = selected?.let(SimilarObjectCounter::appearance)
                        if (exemplar != null) {
                            reference = exemplar
                            target.set(exemplar)
                            generation.incrementAndGet()
                            stableCount = null
                            saved = false
                        }
                    }
                }
            ) {
                val frame = result ?: return@Canvas
                frame.regions.forEach { region ->
                    val matching = reference?.let {
                        SimilarObjectCounter.matches(it, region)
                    } == true
                    val color = if (matching) lime else
                        Color.White.copy(alpha = .5f)
                    val left = size.width * region.left / frame.width
                    val top = size.height * region.top / frame.height
                    val right = size.width * (region.right + 1f) / frame.width
                    val bottom = size.height * (region.bottom + 1f) / frame.height
                    val stroke = if (matching) 3.dp.toPx() else 1.5.dp.toPx()
                    drawLine(color, Offset(left, top), Offset(right, top), stroke)
                    drawLine(color, Offset(right, top), Offset(right, bottom), stroke)
                    drawLine(color, Offset(right, bottom), Offset(left, bottom), stroke)
                    drawLine(color, Offset(left, bottom), Offset(left, top), stroke)
                    region.outline.forEach { p ->
                        drawCircle(
                            color, 1.2.dp.toPx(),
                            Offset(
                                size.width * p.x / frame.width,
                                size.height * p.y / frame.height
                            )
                        )
                    }
                }
            }
            Text(
                if (reference == null) "TOQUE EM UMA PEÇA PARA SELECIONAR O TIPO"
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
            Modifier.fillMaxWidth().background(ink)
                .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Peças do mesmo tipo visual",
                color = Color.White, fontSize = 13.sp)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    when {
                        reference == null -> "Selecione uma peça"
                        stableCount == null -> "Analisando…"
                        else -> stableCount.toString()
                    },
                    fontSize = if (stableCount != null) 38.sp else 21.sp,
                    color = lime, fontWeight = FontWeight.SemiBold
                )
                Button(
                    enabled = reference != null && stableCount != null,
                    onClick = {
                        val count = stableCount ?: return@Button
                        onSave(
                            "Contagem de peças",
                            "$count peças semelhantes (estimativa visual pela câmera)"
                        )
                        saved = true
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = lime, contentColor = ink
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Salvar contagem") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    30 to "Sensível", 45 to "Normal", 65 to "Restrito"
                ).forEach { (choice, name) ->
                    OutlinedButton(
                        enabled = sensitivity != choice,
                        onClick = {
                            sensitivity = choice
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
