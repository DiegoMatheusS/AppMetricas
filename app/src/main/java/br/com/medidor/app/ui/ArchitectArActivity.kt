package br.com.medidor.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import br.com.medidor.app.data.ArchitectPlanStore
import br.com.medidor.app.domain.ArPlacementMath
import br.com.medidor.app.domain.Architect3dGeometry
import br.com.medidor.app.domain.PlanImageFeatures
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.ImageInsufficientQualityException

/**
 * Optional ARCore experience. In paper mode the imported 2D plan is the tracked marker;
 * fallback mode anchors to a horizontal plane after the user taps.
 * No background upload, face capture or network API.
 */
class ArchitectArActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MODE = "placement_mode"
        const val EXTRA_PAPER_WIDTH_CM = "printed_sheet_width_cm"
        const val MODE_PAPER = "paper"
        const val MODE_TABLE = "table"
    }

    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: ArchitectArRenderer
    private lateinit var status: TextView
    private var surfaceRunning = false
    private var installationAsked = false
    private var permissionAsked = false
    private var lastStatus = ""
    private var referenceLooksSparse = false
    @Volatile private var activeSession: Session? = null

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) initializeSession()
        else showStatus("Permissão de câmera necessária para usar a realidade aumentada.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val project = ArchitectPlanStore(applicationContext).load()
        val selected = project.floors.getOrNull(project.selectedFloor)
        val faces = Architect3dGeometry.model(project, allFloors = true)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_TABLE
        val widthCm = intent.getFloatExtra(EXTRA_PAPER_WIDTH_CM, 21f)
        if (faces.isEmpty() || selected == null || !selected.calibrated) {
            TextView(this).apply {
                text = "Não há maquete calibrada para exibir. Volte e marque as paredes da planta."
                textSize = 18f
                setPadding(40, 70, 40, 40)
                setContentView(this)
            }
            return
        }
        val paperBitmap = if (mode == MODE_PAPER)
            ArchitectPlanStore(applicationContext).readImage(selected.imageFile) else null
        val paperScale = ArPlacementMath.imageModelScale(widthCm / 100.0, selected)
        if (mode == MODE_PAPER && (paperBitmap == null || paperScale == null || widthCm <= 0f)) {
            TextView(this).apply {
                text = "Importe e calibre a planta e informe a largura impressa para ancorá-la no papel."
                textSize = 18f
                setPadding(40, 70, 40, 40)
                setContentView(this)
            }
            return
        }

        // Local heuristic; ARCore is responsible for validating real image tracking.
        if (mode == MODE_PAPER && paperBitmap != null) {
            val preview = android.graphics.Bitmap.createScaledBitmap(paperBitmap, 96, 96, true)
            try {
                val pixels = IntArray(preview.width * preview.height)
                preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
                referenceLooksSparse = PlanImageFeatures.edgeFraction(
                    pixels, preview.width, preview.height
                ) < .012
            } finally {
                if (preview !== paperBitmap) preview.recycle()
            }
        }
        renderer = ArchitectArRenderer(this, faces, mode, paperScale?.toFloat() ?: .05f) { message ->
            showStatus(message)
        }
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setPreserveEGLContextOnPause(true)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { _, event ->
                if (mode == MODE_TABLE && event.actionMasked == MotionEvent.ACTION_UP) {
                    renderer.requestPlacement(event.x, event.y)
                }
                true
            }
        }
        val root = FrameLayout(this)
        root.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 18, 16, 12)
            setBackgroundColor(Color.argb(175, 10, 24, 29))
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            text = if (mode == MODE_PAPER)
                "Aponte para a planta impressa e mova devagar o celular." +
                    (if (referenceLooksSparse) " A imagem tem poucos detalhes; o reconhecimento pode falhar." else "")
            else "Mova a câmera até detectar a mesa. Toque para posicionar a maquete."
        }
        overlay.addView(status)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun addButton(label: String, action: () -> Unit) {
            buttons.addView(Button(this).apply {
                text = label
                textSize = 12f
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        addButton("Sair") { finish() }
        if (mode == MODE_TABLE) {
            addButton("Recolocar") {
                surface.queueEvent { renderer.clearAnchor() }
                showStatus("Toque na superfície novamente.")
            }
            addButton("–") { renderer.modelSize = (renderer.modelSize * .8f).coerceAtLeast(.005f) }
            addButton("+") { renderer.modelSize = (renderer.modelSize * 1.25f).coerceAtMost(.5f) }
        } else {
            addButton("Menor") { renderer.modelSize = (renderer.modelSize * .85f).coerceAtLeast(.005f) }
            addButton("Maior") { renderer.modelSize = (renderer.modelSize * 1.15f).coerceAtMost(.5f) }
        }
        overlay.addView(buttons)
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))
        setContentView(root)
        if (mode == MODE_PAPER && paperBitmap != null) {
            renderer.referenceBitmap = paperBitmap
            renderer.paperWidthMeters = widthCm / 100f
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::renderer.isInitialized) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            if (!permissionAsked) {
                permissionAsked = true
                askCamera.launch(Manifest.permission.CAMERA)
            }
            return
        }
        initializeSession()
    }

    private fun initializeSession() {
        if (!::renderer.isInitialized || isFinishing || activeSession != null) return
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installationAsked)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installationAsked = true
                    showStatus("Instale ou atualize os Serviços do Google Play para RA.")
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
            val newSession = Session(this)
            try {
                val config = Config(newSession).apply {
                    focusMode = Config.FocusMode.AUTO
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                }
                if (renderer.mode == MODE_PAPER) {
                    val bitmap = renderer.referenceBitmap ?: error("Planta não encontrada.")
                    val database = AugmentedImageDatabase(newSession)
                    val argb = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    try {
                        database.addImage("planta", argb, renderer.paperWidthMeters)
                    } finally {
                        argb.recycle()
                    }
                    config.augmentedImageDatabase = database
                }
                newSession.configure(config)
                newSession.resume()
                activeSession = newSession
                renderer.session = newSession
                if (!surfaceRunning) {
                    surface.onResume()
                    surfaceRunning = true
                }
                showStatus(if (renderer.mode == MODE_PAPER)
                    "Enquadre a planta impressa ocupando ao menos 25% da imagem. " +
                        (if (referenceLooksSparse) "Imagem pouco detalhada; experimente uma planta mais nítida." else "")
                else "Mova devagar o aparelho e toque na mesa para posicionar.")
            } catch (e: Exception) {
                newSession.close()
                throw e
            }
        } catch (e: ImageInsufficientQualityException) {
            showStatus("O ARCore rejeitou a imagem por falta de detalhes. " +
                "Importe uma planta mais nítida ou use AR na mesa.")
        } catch (e: UnavailableException) {
            showStatus("Realidade aumentada indisponível neste aparelho: ${e.localizedMessage}")
        } catch (e: Exception) {
            showStatus("Não foi possível iniciar AR: ${e.localizedMessage}. Verifique a planta e o suporte ARCore.")
        }
    }

    override fun onPause() {
        if (::surface.isInitialized && surfaceRunning) {
            surface.onPause()
            surfaceRunning = false
        }
        val s = activeSession
        if (::renderer.isInitialized) renderer.session = null
        activeSession = null
        try { s?.pause() } catch (_: Exception) { }
        s?.close()
        super.onPause()
    }

    private fun showStatus(message: String) {
        if (message == lastStatus) return
        lastStatus = message
        runOnUiThread {
            if (::status.isInitialized && !isFinishing) status.text = message
        }
    }
}
