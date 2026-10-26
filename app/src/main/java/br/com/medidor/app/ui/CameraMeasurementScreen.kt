package br.com.medidor.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import br.com.medidor.app.domain.BrazilianCoin
import br.com.medidor.app.domain.LengthUnit
import br.com.medidor.app.domain.NormalizedPoint
import br.com.medidor.app.domain.PhotoCalibration
import br.com.medidor.app.domain.PlanarContourSegmentation
import br.com.medidor.app.domain.PlanarContourResult
import br.com.medidor.app.domain.ReferenceMarkQuality
import java.io.File
import kotlin.math.min

/**
 * MVP camera-based planar measurement:
 * 2 taps define a coin's known diameter, 2 taps define the target distance.
 * Never use this for depth, distant tanks or non-coplanar objects.
 */
@Composable
fun CameraMeasurementScreen(onSave: (String, String) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cameraMode by remember { mutableStateOf("live") }
    var coin by remember { mutableStateOf(BrazilianCoin.TWENTY_FIVE_CENTS) }
    var mode by remember { mutableStateOf("coin") }
    var manualSize by remember { mutableStateOf("10") }
    var measureStyle by remember { mutableStateOf("distance") }
    var seed by remember { mutableStateOf<NormalizedPoint?>(null) }
    var tolerance by remember { mutableStateOf(32) }
    var manualUnit by remember { mutableStateOf(LengthUnit.CENTIMETERS) }
    var screenError by remember { mutableStateOf<String?>(null) }
    val points = remember { mutableStateListOf<NormalizedPoint>() }
    var saved by remember { mutableStateOf(false) }
    var selectedPointIndex by remember { mutableStateOf(0) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                bitmap = SampledPhotos.fromUri(context.contentResolver, uri)
                points.clear()
                seed = null
                saved = false
                screenError = null
            } catch (e: Exception) {
                screenError = "Não foi possível abrir a fotografia: ${e.localizedMessage}"
            }
        }
    }
    // The live analysis owns the entire portrait viewport. Photo calibration remains available
    // after capturing an analyzed frame; live sizes are only fractions of the image.
    if (bitmap == null && granted) {
        LiveContourCamera(
            onPhoto = {
                bitmap = it
                measureStyle = "contour"
                points.clear()
                seed = null
                saved = false
                screenError = null
            },
            onError = { error -> screenError = error }
        )
        return
    }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Medir por fotografia", style = MaterialTheme.typography.titleLarge)
        Text("Calibre com uma moeda ou distância conhecida no MESMO PLANO da medida.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (bitmap == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = cameraMode == "photo",
                    onClick = { cameraMode = "photo"; screenError = null },
                    label = { Text("Fotografia") }
                )
                FilterChip(
                    selected = cameraMode == "live",
                    onClick = { cameraMode = "live"; screenError = null },
                    label = { Text("Contorno ao vivo") }
                )
            }
            OutlinedButton(onClick = { pickPhoto.launch("image/*") }) {
                Text("Escolher foto da galeria")
            }
            if (granted) {
                if (cameraMode == "live") {
                    LiveContourCamera(
                        onPhoto = {
                            bitmap = it
                            measureStyle = "contour"
                            points.clear()
                            seed = null
                            saved = false
                            screenError = null
                        },
                        onError = { error -> screenError = error }
                    )
                } else {
                    CameraPreviewAndCapture(
                        onPhoto = {
                            bitmap = it
                            points.clear()
                            seed = null
                            saved = false
                            screenError = null
                        },
                        onError = { error -> screenError = error }
                    )
                }
            } else {
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("Permitir câmera")
                }
                Text("É possível usar a galeria sem dar permissão para a câmera.")
            }
        } else {
            val photo = bitmap!!
            Text("1. Tipo de medição:", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = measureStyle == "distance", onClick = {
                    measureStyle = "distance"; points.clear(); seed = null; saved = false
                }, label = { Text("Distância manual") })
                FilterChip(selected = measureStyle == "contour", onClick = {
                    measureStyle = "contour"; points.clear(); seed = null; saved = false
                }, label = { Text("Contorno por toque") })
            }
            Text("2. Referência para a escala:", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == "coin", onClick = {
                    mode = "coin"; points.clear(); seed = null; saved = false
                }, label = { Text("Moeda") })
                FilterChip(selected = mode == "manual", onClick = {
                    mode = "manual"; points.clear(); seed = null; saved = false
                }, label = { Text("Distância conhecida") })
            }
            if (mode == "coin") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(BrazilianCoin.entries) { item ->
                        FilterChip(
                            selected = coin == item,
                            onClick = { coin = item; saved = false },
                            label = { Text(item.label) }
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = manualSize,
                    onValueChange = { manualSize = it; saved = false },
                    label = { Text("Medida da referência") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LengthUnit.entries) { unit ->
                        FilterChip(
                            selected = manualUnit == unit,
                            onClick = { manualUnit = unit; saved = false },
                            label = { Text(unit.label) }
                        )
                    }
                }
            }
            val contour = remember(photo, seed, tolerance, measureStyle) {
                if (measureStyle != "contour" || seed == null) null
                else {
                    val w = 256
                    val h = (256f * photo.height / photo.width)
                        .toInt().coerceIn(8, 256)
                    val resized = Bitmap.createScaledBitmap(photo, w, h, true)
                    try {
                        val pixels = IntArray(w * h)
                        resized.getPixels(pixels, 0, w, 0, 0, w, h)
                        PlanarContourSegmentation.segment(
                            pixels, w, h,
                            (seed!!.x * (w - 1)).toInt().coerceIn(0,w-1),
                            (seed!!.y * (h - 1)).toInt().coerceIn(0,h-1),
                            tolerance
                        )
                    } finally {
                        if (resized !== photo) resized.recycle()
                    }
                }
            }
            if (measureStyle == "contour") {
                Text("Após marcar dois pontos da referência, toque dentro do objeto " +
                    "para selecionar a região de cor semelhante.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(18 to "Mais restrito", 32 to "Normal", 55 to "Mais amplo")
                        .forEach { (value, label) ->
                            FilterChip(selected = tolerance == value, onClick = {
                                tolerance = value; saved = false
                            }, label = { Text(label) })
                        }
                }
            }
            var photoZoom by remember(photo) { mutableStateOf(1f) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1f, 2f, 3f, 4f).forEach { factor ->
                    FilterChip(selected = photoZoom == factor,
                        onClick = { photoZoom = factor },
                        label = { Text(factor.toInt().toString() + "×") })
                }
            }
            Text("Zoom para posicionar pontos pequenos. Volte a 1× para ver a foto inteira.",
                style = MaterialTheme.typography.bodySmall)
            Box(Modifier.fillMaxWidth().height(340.dp).clipToBounds()) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    scaleX = photoZoom
                    scaleY = photoZoom
                }) {
                Image(
                    bitmap = photo.asImageBitmap(),
                    contentDescription = "Foto capturada para medição",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                Canvas(
                    Modifier.fillMaxSize().pointerInput(photo, measureStyle) {
                        detectTapGestures { touch ->
                            val mapped = photoPointFromTouch(touch, size, photo.width, photo.height)
                            if (mapped != null && points.size < 4) {
                                if (measureStyle == "contour" && points.size >= 2) {
                                    seed = mapped
                                } else if (points.size < (if (measureStyle == "contour") 2 else 4)) {
                                    points.add(mapped)
                                    selectedPointIndex = points.lastIndex
                                }
                                saved = false
                            }
                        }
                    }
                ) {
                    val rect = imageRect(size.width, size.height, photo.width, photo.height)
                    points.forEachIndexed { index, point ->
                        val center = Offset(rect.left + rect.width * point.x, rect.top + rect.height * point.y)
                        drawCircle(
                            color = if (index < 2) Color(0xFFFFB443) else Color(0xFF4EE0C1),
                            radius = 7.dp.toPx(),
                            center = center
                        )
                    }
                    if (points.size >= 2) {
                        val a = points[0]; val b = points[1]
                        drawLine(Color(0xFFFFB443),
                            Offset(rect.left + rect.width * a.x, rect.top + rect.height * a.y),
                            Offset(rect.left + rect.width * b.x, rect.top + rect.height * b.y),
                            strokeWidth = 3.dp.toPx())
                    }

                    if (measureStyle == "contour" && contour != null) {
                        val c = contour!!
                        c.outline.forEach { px ->
                            drawCircle(
                                Color(0xFF48F1AE), 1.3.dp.toPx(),
                                Offset(rect.left + rect.width * (px.x.toFloat()/c.width),
                                    rect.top + rect.height * (px.y.toFloat()/c.height))
                            )
                        }
                        val x0=rect.left+rect.width*c.minX/c.width
                        val x1=rect.left+rect.width*(c.maxX+1)/c.width
                        val y0=rect.top+rect.height*c.minY/c.height
                        val y1=rect.top+rect.height*(c.maxY+1)/c.height
                        drawLine(Color(0xFF48F1AE), Offset(x0,y0),Offset(x1,y0),2.dp.toPx())
                        drawLine(Color(0xFF48F1AE), Offset(x1,y0),Offset(x1,y1),2.dp.toPx())
                        drawLine(Color(0xFF48F1AE), Offset(x1,y1),Offset(x0,y1),2.dp.toPx())
                        drawLine(Color(0xFF48F1AE), Offset(x0,y1),Offset(x0,y0),2.dp.toPx())
                    }
                    if (points.size >= 4) {
                        val a = points[2]; val b = points[3]
                        drawLine(Color(0xFF4EE0C1),
                            Offset(rect.left + rect.width * a.x, rect.top + rect.height * a.y),
                            Offset(rect.left + rect.width * b.x, rect.top + rect.height * b.y),
                            strokeWidth = 3.dp.toPx())
                    }
                }
                }
            }
            if (points.isNotEmpty()) {
                Text("Ajuste fino dos pontos", fontWeight = FontWeight.Medium)
                Text("Selecione um ponto e mova em passos de 1 pixel da fotografia.",
                    style = MaterialTheme.typography.bodySmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(points.size) { index ->
                        FilterChip(
                            selected = selectedPointIndex == index,
                            onClick = { selectedPointIndex = index },
                            label = { Text(if (index < 2) "Referência ${index + 1}" else "Medida ${index - 1}") }
                        )
                    }
                }
                val chosen = selectedPointIndex.coerceIn(0, points.lastIndex)
                val pixelX = 1f / photo.width.coerceAtLeast(1)
                val pixelY = 1f / photo.height.coerceAtLeast(1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "←" to Pair(-pixelX, 0f),
                        "→" to Pair(pixelX, 0f),
                        "↑" to Pair(0f, -pixelY),
                        "↓" to Pair(0f, pixelY)
                    ).forEach { (label, delta) ->
                        OutlinedButton(onClick = {
                            val current = points[chosen]
                            points[chosen] = NormalizedPoint(
                                (current.x + delta.first).coerceIn(0f, 1f),
                                (current.y + delta.second).coerceIn(0f, 1f)
                            )
                            saved = false
                        }) { Text(label) }
                    }
                }
            }
            val prompt = when {
                points.isEmpty() -> "Marque o primeiro ponto da referência."
                points.size == 1 -> "Marque o segundo ponto da referência."
                measureStyle == "contour" && seed == null -> "Toque dentro do objeto para tentar detectar o contorno."
                measureStyle == "contour" -> "Confira o contorno estimado e as dimensões."
                points.size == 2 -> "Toque no início da distância."
                points.size == 3 -> "Toque no fim da distância."
                else -> "Medição concluída. Confira os pontos."
            }
            Text(prompt, fontWeight = FontWeight.Medium)
            val referenceMm = if (mode == "coin") coin.diameterMm
                else PhotoCalibration.manualReferenceMillimeters(manualSize, manualUnit)
            if (referenceMm == null) {
                Text("Digite uma medida positiva e válida.", color = MaterialTheme.colorScheme.error)
            }
            val referencePixels = if (points.size >= 2) {
                PhotoCalibration.distancePixels(points[0], points[1], photo.width, photo.height)
            } else null
            val referenceIsReadable = referencePixels != null &&
                ReferenceMarkQuality.isReadable(referencePixels)
            if (referencePixels != null &&
                ReferenceMarkQuality.needsLargerReference(referencePixels)) {
                Text("Referência utilizável, mas curta na fotografia. " +
                    "Para reduzir a sensibilidade a erros de toque, prefira " +
                    "uma referência com pelo menos 50 pixels de comprimento.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (referencePixels != null && !referenceIsReadable) {
                Text("Referência muito pequena na foto (menos de 20 pixels). " +
                    "Aproxime a câmera ou marque uma referência maior para reduzir " +
                    "erros de toque.", color = MaterialTheme.colorScheme.error)
            }
            val mm = if (points.size == 4 && referenceMm != null && referenceIsReadable) {
                val target = PhotoCalibration.distancePixels(points[2], points[3], photo.width, photo.height)
                PhotoCalibration.millimetersFromReference(
                    referenceMm, referencePixels!!, target)
            } else null
            if (measureStyle == "contour" && seed != null && contour == null) {
                Text("Região não isolada. Escolha outro ponto ou ajuste a tolerância. " +
                    "O fundo pode ter cor semelhante ao objeto.",
                    color = MaterialTheme.colorScheme.error)
            }
            if (measureStyle == "contour" && contour != null && points.size >= 2 &&
                referenceMm != null) {
                val reference = PhotoCalibration.distancePixels(
                    points[0], points[1], photo.width, photo.height
                )
                val widthMm = if (ReferenceMarkQuality.isReadable(reference)) PhotoCalibration.millimetersFromReference(
                    referenceMm, reference, contour.widthFraction * photo.width
                ) else null
                val heightMm = if (reference > 0) PhotoCalibration.millimetersFromReference(
                    referenceMm, reference, contour.heightFraction * photo.height
                ) else null
                if (widthMm != null && heightMm != null) {
                    ResultCard("Contorno no plano (estimado)",
                        "Largura: ${decimal(widthMm/10)} cm • Altura: ${decimal(heightMm/10)} cm")
                    Button(onClick = {
                        onSave("Contorno por fotografia",
                            "Largura ${decimal(widthMm)} mm | Altura ${decimal(heightMm)} mm (mesmo plano)")
                        saved = true
                    }) { Text("Salvar dimensões do contorno") }
                }
                Text("Contorno por semelhança de cor, sem reconhecimento semântico. " +
                    "As dimensões só são válidas para objeto e referência no mesmo plano; " +
                    "não mede profundidade, curvas 3D nem objetos afastados.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (points.size == 4 && mm == null && referenceMm != null && referenceIsReadable) {
                Text("A referência precisa ter dois pontos diferentes.", color = MaterialTheme.colorScheme.error)
            }
            if (mm != null) {
                ResultCard("Distância estimada no plano da moeda", "${decimal(mm)} mm • ${decimal(mm / 10)} cm")
                Button(onClick = {
                    onSave("Foto ${if (mode == "coin") coin.label else "referência manual"}",
                        "${decimal(mm)} mm (estimado, mesmo plano; ref. ${decimal(referenceMm!!)} mm)")
                    saved = true
                }) { Text("Salvar resultado") }
                if (saved) Text("Resultado salvo neste aparelho.")
            }
            if (points.isNotEmpty()) {
                OutlinedButton(onClick = { points.removeAt(points.lastIndex); saved = false }) {
                    Text("Desfazer último ponto")
                }
            }
            OutlinedButton(onClick = { points.clear(); seed = null; saved = false }) {
                Text("Recomeçar marcações")
            }
            OutlinedButton(onClick = { bitmap = null; points.clear(); seed = null; saved = false }) {
                Text("Escolher outra imagem")
            }
        }
        screenError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("ATENÇÃO: referência e objeto devem estar no mesmo plano e perspectiva semelhante. " +
            "Não há correção de perspectiva nem medição de profundidade.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CameraPreviewAndCapture(onPhoto: (Bitmap) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val owner = context as? ComponentActivity
    val view = remember(context) {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    DisposableEffect(owner, view) {
        var disposed = false
        var boundProvider: ProcessCameraProvider? = null
        if (owner != null) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!disposed) {
                    try {
                        val provider = future.get()
                        val preview = Preview.Builder().build().apply {
                            setSurfaceProvider(view.surfaceProvider)
                        }
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                        boundProvider = provider
                        imageCapture = capture
                    } catch (error: Exception) {
                        onError(error.localizedMessage ?: "Não foi possível inicializar a câmera.")
                    }
                }
            }, ContextCompat.getMainExecutor(context))
        } else {
            onError("Activity incompatível para abrir a câmera.")
        }
        onDispose {
            disposed = true
            boundProvider?.unbindAll()
            imageCapture = null
        }
    }

    Row(modifier = Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp - 100).coerceAtLeast(190).dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    AndroidView(factory = { view }, modifier = Modifier.weight(1f).fillMaxSize())
    Column(modifier = Modifier.width(154.dp), verticalArrangement = Arrangement.Center) {
    Text("Câmera", style = MaterialTheme.typography.titleSmall)
    Button(enabled = imageCapture != null, onClick = {
        val camera = imageCapture ?: return@Button
        val file = File(context.cacheDir, "medicao_${System.currentTimeMillis()}.jpg")
        camera.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    try {
                        onPhoto(SampledPhotos.fromFile(file))
                    } catch (error: Exception) {
                        onError(error.localizedMessage ?: "Não foi possível ler a fotografia.")
                    } finally {
                        file.delete()
                    }
                }
                override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                    file.delete()
                    onError(exception.localizedMessage ?: "Falha na captura.")
                }
            }
        )
    }, modifier = Modifier.fillMaxWidth()) { Text("Capturar") }
    Text("Toque em Capturar e calibre a foto para obter medidas.", style = MaterialTheme.typography.bodySmall)
    }
    }
}

private data class ImageRect(val left: Float, val top: Float, val width: Float, val height: Float)

private fun imageRect(viewWidth: Float, viewHeight: Float, photoWidth: Int, photoHeight: Int): ImageRect {
    val scale = min(viewWidth / photoWidth, viewHeight / photoHeight)
    val width = photoWidth * scale
    val height = photoHeight * scale
    return ImageRect((viewWidth - width) / 2, (viewHeight - height) / 2, width, height)
}

private fun photoPointFromTouch(touch: Offset, viewSize: IntSize, width: Int, height: Int): NormalizedPoint? {
    val rect = imageRect(viewSize.width.toFloat(), viewSize.height.toFloat(), width, height)
    if (rect.width <= 0f || rect.height <= 0f) return null
    val x = (touch.x - rect.left) / rect.width
    val y = (touch.y - rect.top) / rect.height
    return if (x in 0f..1f && y in 0f..1f) NormalizedPoint(x, y) else null
}
