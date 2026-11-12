package br.com.medidor.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import br.com.medidor.app.data.ArchitectPlanStore
import br.com.medidor.app.domain.ArPlacementMath
import br.com.medidor.app.domain.Architect3dGeometry
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.ImageInsufficientQualityException
import com.google.ar.core.exceptions.UnavailableException

/**
 * Embedded ARCore paper overlay. CameraX is unbound by ArchitectScanScreen
 * before this composable is shown, so ARCore can own the camera.
 * An AugmentedImage anchor is used for real tracked overlays, not a graphic
 * pinned to the display. Unsupported phones show a status instead.
 */
@Composable
internal fun ArchitectPaperArPreview(
    printedWidthCm: Float,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val latestStatus by rememberUpdatedState(onStatus)
    if (activity == null) {
        Text("A visualização AR exige uma Activity Android compatível.")
        return
    }
    val storage = remember(context) { ArchitectPlanStore(context.applicationContext) }
    val plan = remember(storage) { storage.load() }
    val floor = plan.floors.getOrNull(plan.selectedFloor)
    val faces = remember(plan) { Architect3dGeometry.model(plan, allFloors = true) }
    val bitmap = remember(floor?.imageFile) { storage.readImage(floor?.imageFile) }
    val scale = if (floor != null && printedWidthCm > 0f && printedWidthCm.isFinite())
        ArPlacementMath.imageModelScale(printedWidthCm.toDouble() / 100.0, floor)
    else null

    if (bitmap == null || floor?.calibrated != true || faces.isEmpty() ||
        scale == null || !scale.isFinite()) {
        Text("Para sobrepor a maquete, escaneie a planta, calibre a escala " +
            "e marque as paredes no editor 2D.")
        return
    }

    val renderer = remember(activity, plan, scale) {
        ArchitectArRenderer(
            activity, faces, ArchitectArActivity.MODE_PAPER, scale.toFloat()
        ) { status ->
            activity.runOnUiThread { latestStatus(status) }
        }.apply {
            referenceBitmap = bitmap
            paperWidthMeters = printedWidthCm / 100f
        }
    }
    val surface = remember(activity, renderer) {
        GLSurfaceView(activity).apply {
            setEGLContextClientVersion(2)
            setPreserveEGLContextOnPause(true)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    DisposableEffect(activity, surface, renderer) {
        var session: Session? = null
        var surfaceRunning = false
        var installationAsked = false
        fun stop() {
            if (surfaceRunning) {
                surface.onPause()
                surfaceRunning = false
            }
            renderer.session = null
            val old = session
            session = null
            try { old?.pause() } catch (_: Exception) { }
            old?.close()
        }
        fun start() {
            if (session != null || activity.isFinishing) return
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED) {
                latestStatus("Permita o uso da câmera para sobrepor o modelo no papel.")
                return
            }
            try {
                when (ArCoreApk.getInstance().requestInstall(activity, !installationAsked)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installationAsked = true
                        latestStatus("Instale ou atualize os Serviços do Google Play para RA.")
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }
                val candidate = Session(activity)
                try {
                    val database = AugmentedImageDatabase(candidate)
                    val reference = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    try {
                        database.addImage("planta", reference, printedWidthCm / 100f)
                    } finally {
                        reference.recycle()
                    }
                    val config = Config(candidate).apply {
                        focusMode = Config.FocusMode.AUTO
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                        augmentedImageDatabase = database
                    }
                    candidate.configure(config)
                    candidate.resume()
                    session = candidate
                    renderer.session = candidate
                    if (!surfaceRunning) {
                        surface.onResume()
                        surfaceRunning = true
                    }
                    latestStatus("Aponte para a planta impressa e mova o celular devagar.")
                } catch (e: Exception) {
                    candidate.close()
                    throw e
                }
            } catch (e: ImageInsufficientQualityException) {
                latestStatus("A planta tem poucos detalhes para o ARCore. " +
                    "Use impressão nítida, com contornos visíveis.")
            } catch (e: UnavailableException) {
                latestStatus("ARCore indisponível neste celular: ${e.localizedMessage}")
            } catch (e: Exception) {
                latestStatus("Não foi possível iniciar a realidade aumentada: " +
                    "${e.localizedMessage}")
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose {
            activity.lifecycle.removeObserver(observer)
            stop()
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
    }
}
