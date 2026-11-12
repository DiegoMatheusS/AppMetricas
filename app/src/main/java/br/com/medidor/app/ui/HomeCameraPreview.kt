package br.com.medidor.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

/** Home camera is a preview only: calibrated measurements remain on the measurement screen. */
@Composable
internal fun HomeCameraPreview() {
    val context = LocalContext.current
    val owner = context as? ComponentActivity
    var allowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED)
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
    }
    if (!allowed) {
        TextButton(onClick = { request.launch(Manifest.permission.CAMERA) }) {
            Text("Ativar prévia da câmera", color = Color.White)
        }
        return
    }
    val view = remember(context) { PreviewView(context) }
    DisposableEffect(view, owner) {
        var provider: ProcessCameraProvider? = null
        var bound = false
        var closed = false
        val main = ContextCompat.getMainExecutor(context)
        val future = if (owner != null) ProcessCameraProvider.getInstance(context) else null
        fun bind() {
            val p = provider ?: return
            if (closed || bound || view.width == 0 || view.height == 0 || owner == null) return
            try {
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(view.surfaceProvider)
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                bound = true
            } catch (_: Exception) { /* Camera may be unavailable on the device. */ }
        }
        val listener = View.OnLayoutChangeListener {
            _: View, _: Int, _: Int, _: Int, _: Int, _: Int, _: Int, _: Int, _: Int -> bind()
        }
        view.addOnLayoutChangeListener(listener)
        future?.addListener({
            if (!closed) try { provider = future.get(); bind() } catch (_: Exception) {}
        },main)
        onDispose {
            closed = true
            view.removeOnLayoutChangeListener(listener)
            if (bound) provider?.unbindAll()
        }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}
