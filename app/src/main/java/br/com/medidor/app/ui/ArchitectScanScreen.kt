package br.com.medidor.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import br.com.medidor.app.data.ArchitectPlanStore
import java.io.File

/**
 * Capture a physical paper floor plan using the live camera preview.
 * A captured image is stored as a 2D reference and must still be reviewed,
 * calibrated and traced; no walls/scale are inferred from pixels.
 */
@Composable
fun ArchitectScanScreen(onBack: () -> Unit, onCaptured: () -> Unit) {
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
    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val view = remember(context) {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    DisposableEffect(granted, owner, view) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        if (granted && owner != null) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!disposed) try {
                    val service = future.get()
                    val livePreview = Preview.Builder().build().apply {
                        setSurfaceProvider(view.surfaceProvider)
                    }
                    val cameraCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    service.unbindAll()
                    service.bindToLifecycle(
                        owner, CameraSelector.DEFAULT_BACK_CAMERA,
                        livePreview, cameraCapture
                    )
                    provider = service
                    capture = cameraCapture
                } catch (e: Exception) {
                    error = e.localizedMessage ?: "Falha ao iniciar a câmera."
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            disposed = true
            provider?.unbindAll()
            capture = null
        }
    }

    Box(Modifier.fillMaxSize().background(ink)) {
        if (granted) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val x0 = size.width * .08f
                val x1 = size.width * .92f
                val y0 = size.height * .19f
                val y1 = size.height * .77f
                val span = 29.dp.toPx()
                val stroke = 3.dp.toPx()
                val corners = listOf(
                    Offset(x0, y0), Offset(x1, y0),
                    Offset(x0, y1), Offset(x1, y1)
                )
                corners.forEachIndexed { index, point ->
                    val horizontal = if (index % 2 == 0) span else -span
                    val vertical = if (index < 2) span else -span
                    drawLine(lime, point, Offset(point.x + horizontal, point.y), stroke)
                    drawLine(lime, point, Offset(point.x, point.y + vertical), stroke)
                }
            }
        }
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.background(ink.copy(alpha = .88f),
                    RoundedCornerShape(30.dp))
            ) { Text("‹", fontSize = 25.sp, color = Color.White) }
            Column {
                Text("Arquiteto", fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("NOVA PLANTA", fontSize = 10.sp,
                    color = Color.White.copy(alpha = .75f))
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(ink.copy(alpha = .97f),
                    RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Da planta ao espaço", fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp, color = Color.White)
            Text(
                "Enquadre toda a folha, sem dobras ou sombras. Depois confirme " +
                    "uma medida real para ajustar a escala.",
                fontSize = 12.sp, color = Color.White.copy(alpha = .75f)
            )
            if (!granted) {
                Button(onClick = { request.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = lime, contentColor = ink
                    )) { Text("Permitir câmera") }
            } else {
                Button(
                    enabled = capture != null && !busy,
                    onClick = {
                        val camera = capture ?: return@Button
                        busy = true
                        val file = File(context.cacheDir,
                            "planta_${SystemClock.elapsedRealtimeNanos()}.jpg")
                        camera.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(
                                    outputFileResults: ImageCapture.OutputFileResults
                                ) {
                                    var bitmap: Bitmap? = null
                                    try {
                                        bitmap = SampledPhotos.fromFile(file)
                                        val storage = ArchitectPlanStore(context.applicationContext)
                                        val current = storage.load()
                                        val floorIndex = current.selectedFloor
                                        val floor = current.floors[floorIndex]
                                        val name = storage.saveImage(bitmap)
                                        val floors = current.floors.toMutableList()
                                        floors[floorIndex] = floor.copy(
                                            imageFile = name,
                                            imageWidth = bitmap.width,
                                            imageHeight = bitmap.height,
                                            referenceStart = null,
                                            referenceEnd = null,
                                            walls = emptyList(),
                                            openings = emptyList(),
                                            alignmentPoint = null,
                                            secondAlignmentPoint = null
                                        )
                                        storage.save(current.copy(floors = floors))
                                        onCaptured()
                                    } catch (e: Exception) {
                                        error = "Falha ao salvar planta: ${e.localizedMessage}"
                                    } finally {
                                        bitmap?.recycle()
                                        file.delete()
                                        busy = false
                                    }
                                }
                                override fun onError(
                                    exception: androidx.camera.core.ImageCaptureException
                                ) {
                                    error = exception.localizedMessage ?: "Falha ao capturar planta."
                                    file.delete()
                                    busy = false
                                }
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = lime, contentColor = ink
                    )
                ) { Text(if (busy) "Salvando planta..." else "Escanear planta",
                    fontWeight = FontWeight.SemiBold) }
            }
            error?.let {
                Text(it, color = Color(0xFFFFC2B2), fontSize = 12.sp)
            }
            Text(
                "O desenho será aberto na revisão 2D. Paredes e escala " +
                    "não são reconhecidas automaticamente.",
                color = Color.White.copy(alpha = .65f), fontSize = 10.sp
            )
        }
    }
}
