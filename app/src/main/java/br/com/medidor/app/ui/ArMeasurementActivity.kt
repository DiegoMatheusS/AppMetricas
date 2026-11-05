package br.com.medidor.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import br.com.medidor.app.data.LocalHistory
import br.com.medidor.app.domain.TankShape
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException
import java.text.NumberFormat
import java.util.Locale

internal class ArMeasurementActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_AREA = "measure_area"
        const val EXTRA_SHAPE = "tank_shape"
        const val EXTRA_DIMENSIONS = "tank_dimensions"

        fun intent(context: Context, area: Boolean = false, shape: TankShape? = null) =
            Intent(context, ArMeasurementActivity::class.java)
                .putExtra(EXTRA_AREA, area)
                .putExtra(EXTRA_SHAPE, shape?.name)
    }

    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: ArMeasurementRenderer
    private lateinit var overlay: ArMeasurementOverlay
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var confirm: Button
    private lateinit var undo: Button
    private lateinit var clear: Button
    private lateinit var automatic: Button
    private lateinit var manual: Button
    private var session: Session? = null
    private var running = false
    private var askedForInstall = false
    private var askedForPermission = false
    private var saved = false
    private var areaMode = false
    private var tankShape: TankShape? = null
    private var dimensions = emptyList<String>()

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startSession()
        else status.text = "Permita o acesso à câmera para medir em AR."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askedForInstall = savedInstanceState?.getBoolean("asked_install") == true
        askedForPermission = savedInstanceState?.getBoolean("asked_permission") == true
        tankShape = TankShape.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SHAPE) }
        dimensions = when (tankShape) {
            TankShape.VERTICAL -> listOf("Diâmetro", "Altura")
            TankShape.HORIZONTAL -> listOf("Diâmetro", "Comprimento")
            TankShape.SPHERE -> listOf("Diâmetro")
            null -> emptyList()
        }
        areaMode = tankShape == null && intent.getBooleanExtra(EXTRA_AREA, false)
        overlay = ArMeasurementOverlay(this, ::updateControls) { x, y ->
            if (running && !saved) renderer.requestPoint(x, y)
        }
        renderer = ArMeasurementRenderer(
            { display?.rotation ?: Surface.ROTATION_0 }, areaMode, dimensions, overlay::publish
        )
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setPreserveEGLContextOnPause(true)
            setRenderer(this@ArMeasurementActivity.renderer)
        }

        val root = FrameLayout(this)
        root.addView(surface, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val header = panel().apply {
            addView(label(19f).apply {
                text = when {
                    tankShape != null -> "Tanque em AR"
                    areaMode -> "Área em AR"
                    else -> "Régua em AR"
                }
                typeface = Typeface.DEFAULT_BOLD
            })
            status = label(15f).apply { text = "Preparando a câmera em AR…" }
            addView(status)
            addView(LinearLayout(context).apply modes@{
                automatic = Button(context).apply {
                    text = "Automático"
                    setOnClickListener {
                        saved = false
                        surface.queueEvent { renderer.setAutomatic(true) }
                    }
                }
                manual = Button(context).apply {
                    text = "Marcar pontos"
                    setOnClickListener {
                        saved = false
                        surface.queueEvent { renderer.setAutomatic(false) }
                    }
                }
                addView(automatic, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(manual, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
        }
        root.addView(header, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        val footer = panel().apply {
            result = label(17f)
            addView(result)
            if (tankShape != null) {
                addView(label(13f).apply {
                    text = "Caminhe devagar em volta do tanque para a câmera estimar a profundidade. " +
                        "Confirme o diâmetro e depois a altura/comprimento. " +
                        "O volume útil exige dimensões INTERNAS verificadas."
                })
            }
            confirm = Button(context).apply {
                backgroundTintList = ColorStateList.valueOf(Color.rgb(192, 245, 101))
                setTextColor(Color.rgb(20, 37, 31))
                isAllCaps = false
                isEnabled = false
                text = if (tankShape == null) "Salvar medição" else "Confirmar dimensão"
                setOnClickListener {
                    isEnabled = false
                    surface.queueEvent { confirmMeasurement() }
                }
            }
            addView(confirm, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(LinearLayout(context).apply actions@{
                fun addAction(name: String, action: () -> Unit): Button =
                    Button(context).apply {
                        text = name
                        isAllCaps = false
                        backgroundTintList = ColorStateList.valueOf(Color.rgb(38, 57, 46))
                        setTextColor(Color.WHITE)
                        setOnClickListener { action() }
                        this@actions.addView(this, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    }
                undo = addAction("Desfazer") {
                    saved = false
                    surface.queueEvent { renderer.undo() }
                }
                clear = addAction("Refazer") {
                    saved = false
                    surface.queueEvent {
                        if (tankShape != null) renderer.restartTankMeasurement()
                        else renderer.clear()
                    }
                }
                undo.isEnabled = false
                clear.isEnabled = false
                addAction("Voltar") { finish() }
            })
        }
        root.addView(footer, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout())
            header.setPadding(safe.left + dp(16), safe.top + dp(12), safe.right + dp(16), dp(10))
            footer.setPadding(safe.left + dp(12), dp(8), safe.right + dp(12), safe.bottom + dp(6))
            insets
        }
        setContentView(root)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            startSession()
        } else if (!askedForPermission) {
            askedForPermission = true
            cameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            status.text = "Permita o acesso à câmera para medir em AR."
        }
    }

    private fun startSession() {
        if (running || isFinishing) return
        try {
            if (session == null) {
                val arCore = ArCoreApk.getInstance()
                if (arCore.requestInstall(this, !askedForInstall) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    askedForInstall = true
                    status.text = "Instale ou atualize os Serviços do Google Play para RA."
                    return
                }
                val newSession = Session(this)
                try {
                    val depthAvailable = newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                    if (tankShape != null && !depthAvailable) {
                        newSession.close()
                        status.text = "Este aparelho precisa de suporte a profundidade ARCore para medir tanques. Use as dimensões manuais."
                        return
                    }
                    newSession.configure(Config(newSession).apply {
                        focusMode = Config.FocusMode.AUTO
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        if (depthAvailable && !areaMode) depthMode = Config.DepthMode.AUTOMATIC
                    })
                    session = newSession
                } catch (e: Exception) {
                    newSession.close()
                    throw e
                }
            }
            val current = checkNotNull(session)
            current.resume()
            renderer.session = current
            running = true
            surface.onResume()
        } catch (_: UnavailableException) {
            status.text = "ARCore indisponível. Volte para usar a foto calibrada ou informar as dimensões."
        } catch (_: Exception) {
            status.text = "Não foi possível abrir a câmera em AR. Feche outros apps que usam a câmera e tente novamente."
        }
    }

    override fun onPause() {
        if (running) {
            running = false
            surface.onPause()
            renderer.session = null
            overlay.publish(ArMeasurementFrame())
            session?.pause()
            confirm.isEnabled = false
            undo.isEnabled = false
            clear.isEnabled = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        session?.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("asked_install", askedForInstall)
        outState.putBoolean("asked_permission", askedForPermission)
        super.onSaveInstanceState(outState)
    }

    private fun confirmMeasurement() {
        if (tankShape != null) {
            renderer.takeTankDimension()?.let { values ->
                runOnUiThread {
                    if (!isFinishing) {
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_DIMENSIONS, values.toDoubleArray())
                            .putExtra(EXTRA_SHAPE, tankShape?.name))
                        finish()
                    }
                }
            }
            return
        }
        val frame = renderer.measurement()
        runOnUiThread {
            if (!running || isFinishing || saved) return@runOnUiThread
            val details = if (areaMode) frame.area?.let(::formatArArea)
                else frame.sides.singleOrNull()?.let(::formatArLength)
            if (details != null) {
                LocalHistory(applicationContext).save(
                    if (areaMode) "Área em AR" else "Régua em AR", "Estimativa: $details"
                )
                saved = true
                confirm.isEnabled = false
            }
        }
    }

    private fun updateControls(frame: ArMeasurementFrame) {
        if (!running) return
        val dimension = dimensions.getOrNull(frame.dimensionIndex)
        val measurement = if (areaMode) frame.area?.let(::formatArArea)
            else frame.sides.singleOrNull()?.let(::formatArLength)
        val caption = listOfNotNull(
            dimension?.let { "${frame.dimensionIndex + 1}/${dimensions.size} • $it" },
            measurement
        ).joinToString(" · ")
        val message = if (saved) "Medição salva neste aparelho." else frame.message
        if (status.text != message) status.text = message
        if (result.text != caption) result.text = caption
        val visibility = if (caption.isEmpty()) View.GONE else View.VISIBLE
        if (result.visibility != visibility) result.visibility = visibility
        if (automatic.isEnabled == frame.automatic) {
            automatic.isEnabled = !frame.automatic
            manual.isEnabled = frame.automatic
            val activeColor = Color.rgb(192, 245, 101)
            val inactiveColor = Color.rgb(38, 57, 46)
            val ink = Color.rgb(20, 37, 31)
            automatic.backgroundTintList = ColorStateList.valueOf(if (frame.automatic) activeColor else inactiveColor)
            manual.backgroundTintList = ColorStateList.valueOf(if (frame.automatic) inactiveColor else activeColor)
            automatic.setTextColor(if (frame.automatic) ink else Color.WHITE)
            manual.setTextColor(if (frame.automatic) Color.WHITE else ink)
        }
        confirm.isEnabled = !saved && frame.canConfirm && measurement != null
        undo.isEnabled = frame.points.isNotEmpty() || (tankShape != null && frame.dimensionIndex > 0)
        clear.isEnabled = frame.points.isNotEmpty() || (tankShape != null && frame.dimensionIndex > 0)
    }

    private fun label(size: Float) = TextView(this).apply {
        textSize = size
        setTextColor(Color.WHITE)
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.argb(234, 20, 37, 31))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

// Formatting runs on the UI thread for the overlay, controls and history.
private val arNumber = NumberFormat.getNumberInstance(Locale("pt", "BR")).apply {
    maximumFractionDigits = 2
}

private fun formatArLength(meters: Double) =
    if (meters < 1) "${arNumber.format(meters * 100)} cm" else "${arNumber.format(meters)} m"

private fun formatArArea(squareMeters: Double) =
    if (squareMeters < .01) "${arNumber.format(squareMeters * 10_000)} cm²"
    else "${arNumber.format(squareMeters)} m²"

private class ArMeasurementOverlay(
    context: Context,
    private val updateControls: (ArMeasurementFrame) -> Unit,
    onPoint: (Float, Float) -> Unit
) : View(context) {
    @Volatile private var frame = ArMeasurementFrame()
    private val lime = Color.rgb(192, 245, 101)
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lime; strokeWidth = 3 * density }
    private val backing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 37, 31) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            performClick()
            onPoint(e.x, e.y)
            return true
        }
    })

    fun publish(next: ArMeasurementFrame) {
        frame = next
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event)
    override fun performClick() = super.performClick()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = frame
        updateControls(current)
        current.sides.forEachIndexed { index, meters ->
            val start = current.points[index] ?: return@forEachIndexed
            val end = current.points[(index + 1) % current.points.size] ?: return@forEachIndexed
            canvas.drawLine(start.x, start.y, end.x, end.y, line)
            val x = (start.x + end.x) / 2
            val y = (start.y + end.y) / 2
            if (x in 0f..width.toFloat() && y in 0f..height.toFloat()) {
                val text = formatArLength(meters)
                val halfWidth = label.measureText(text) / 2 + 5 * density
                val halfHeight = label.textSize * .65f
                canvas.drawRoundRect(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight,
                    5 * density, 5 * density, backing)
                canvas.drawText(text, x, y - (label.ascent() + label.descent()) / 2, label)
            }
        }
        if (current.area != null && current.points.filterNotNull().size >= 3) {
            val points = current.points.filterNotNull()
            val centerX = points.map { it.x }.average().toFloat()
            val centerY = points.map { it.y }.average().toFloat()
            if (centerX in 0f..width.toFloat() && centerY in 0f..height.toFloat()) {
                val caption = formatArArea(current.area)
                val hw = label.measureText(caption) / 2 + 10 * density
                val hh = label.textSize * .8f
                canvas.drawRoundRect(centerX - hw, centerY - hh,
                    centerX + hw, centerY + hh, 6 * density, 6 * density, backing)
                canvas.drawText(caption, centerX,
                    centerY - (label.ascent() + label.descent()) / 2, label)
            }
        }
        for (point in current.points.filterNotNull()) {
            canvas.drawCircle(point.x, point.y, 7 * density, backing)
            canvas.drawCircle(point.x, point.y, 5 * density, line)
        }
    }
}
