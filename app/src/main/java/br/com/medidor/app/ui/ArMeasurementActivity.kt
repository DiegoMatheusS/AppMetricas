package br.com.medidor.app.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
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
import br.com.medidor.app.data.MeasurementPreferences
import br.com.medidor.app.domain.MeasurementPointSize
import br.com.medidor.app.domain.TankShape
import br.com.medidor.app.domain.CameraViewportMapping
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException
import java.text.NumberFormat
import java.util.Locale

internal class ArMeasurementActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_AREA = "measure_area"
        private const val EXTRA_AUTOMATIC = "automatic_measurement"
        const val EXTRA_NOTICE = "measurement_notice"
        const val EXTRA_SHAPE = "tank_shape"
        const val EXTRA_DIMENSIONS = "tank_dimensions"

        fun intent(context: Context, area: Boolean = false, shape: TankShape? = null, automatic: Boolean = true) =
            Intent(context, ArMeasurementActivity::class.java)
                .putExtra(EXTRA_AREA, area)
                .putExtra(EXTRA_SHAPE, shape?.name)
                .putExtra(EXTRA_AUTOMATIC, automatic)
    }

    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: ArMeasurementRenderer
    private lateinit var overlay: ArMeasurementOverlay
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var confirm: Button
    private lateinit var undo: Button
    private lateinit var automatic: Button
    private lateinit var manual: Button
    private lateinit var objectSelection: Button
    private enum class InputMode { AUTOMATIC, OBJECT, POINTS }
    private var inputMode = InputMode.AUTOMATIC
    private var session: Session? = null
    private var running = false
    private var askedForInstall = false
    private var askedForPermission = false
    private var saved = false
    private var areaMode = false
    private var tankShape: TankShape? = null
    private var dimensions = emptyList<String>()
    private lateinit var pointPreferences: MeasurementPreferences

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
        pointPreferences = MeasurementPreferences(applicationContext)
        overlay = ArMeasurementOverlay(this, ::updateControls, onMove = { index,x,y ->
            if (running && !saved) cameraTouch(x,y)?.let { renderer.requestMove(index,it.first,it.second) }
        }) { x, y ->
            if (running && !saved && inputMode != InputMode.AUTOMATIC) {
                cameraTouch(x,y)?.let {
                    renderer.requestPoint(it.first,it.second,selectObject = inputMode == InputMode.OBJECT)
                }
            }
        }
        renderer = ArMeasurementRenderer(
            { display?.rotation ?: Surface.ROTATION_0 }, areaMode, dimensions, overlay::publish
        )
        inputMode = if (intent.getBooleanExtra(EXTRA_AUTOMATIC,true)) InputMode.AUTOMATIC else InputMode.POINTS
        renderer.setAutomatic(inputMode != InputMode.POINTS)
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setPreserveEGLContextOnPause(true)
            setRenderer(this@ArMeasurementActivity.renderer)
        }
        overlay.cameraView = surface
        overlay.pointSize = pointPreferences.pointSize
        surface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePointRadius() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20,37,31))
        }
        val cameraFrame = FrameLayout(this).apply {
            contentDescription = "Visor livre da câmera"
            clipChildren = true
            addView(surface, FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
            addView(this@ArMeasurementActivity.overlay, FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        }
        root.addView(cameraFrame,LinearLayout.LayoutParams(MATCH_PARENT,0,1f))
        val footer = panel().apply {
            contentDescription = "Controles inferiores de medição"
            setPadding(dp(10),dp(10),dp(10),dp(10))
            addView(label(17f).apply {
                text = when {
                    tankShape != null -> "Tanque no ambiente"
                    areaMode -> "Área no ambiente"
                    else -> "Régua no ambiente"
                }
                typeface = Typeface.DEFAULT_BOLD
            })
            result = label(19f).apply { typeface = Typeface.DEFAULT_BOLD; visibility = View.GONE }
            addView(result)
            status = label(13f).apply {
                text = "Reconhecendo a superfície. Mova a câmera devagar."
                minHeight = dp(44)
                setPadding(0,dp(5),0,dp(5))
                setLineSpacing(dp(2).toFloat(),1f)
            }
            addView(status)
            fun row() = LinearLayout(context).also { addView(it, LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT)) }
            fun action(row: LinearLayout, text: String, description: String, drawable: Int, run: () -> Unit): Button =
                Button(context).apply {
                    this.text = text
                    contentDescription = description
                    isAllCaps = false
                    textSize = 11f
                    maxLines = 1
                    minWidth = 0; minHeight = 0
                    setPadding(dp(2),dp(3),dp(2),dp(3))
                    backgroundTintList = ColorStateList.valueOf(Color.rgb(38,57,46))
                    setTextColor(Color.WHITE)
                    val icon = ContextCompat.getDrawable(context,drawable)?.mutate()?.apply {
                        setTint(Color.rgb(192,245,101)); setBounds(0,0,dp(18),dp(18))
                    }
                    setCompoundDrawables(null,icon,null,null)
                    compoundDrawablePadding = dp(2)
                    setOnClickListener { run() }
                    row.addView(this,LinearLayout.LayoutParams(0,dp(54),1f).apply {
                        setMargins(dp(2),dp(3),dp(2),dp(3))
                    })
                }
            val modes = row()
            automatic = action(modes,"Auto","Medir automaticamente sem tocar",android.R.drawable.ic_menu_search) {
                setInputMode(InputMode.AUTOMATIC)
            }
            objectSelection = action(modes,"Objeto","Ativar seleção de objeto por toque",android.R.drawable.ic_menu_crop) {
                setInputMode(InputMode.OBJECT)
            }
            manual = action(modes,"Pontos","Ativar marcação de pontos",android.R.drawable.ic_menu_mylocation) {
                setInputMode(InputMode.POINTS)
            }
            action(modes,"Foto","Medir por foto com referência",android.R.drawable.ic_menu_camera) {
                photoFallback("Modo Foto: capture uma imagem e defina uma referência conhecida.")
            }
            val actions = row()
            action(actions,"Voltar","Voltar à câmera",android.R.drawable.ic_media_previous) { finish() }
            confirm = action(actions,if (tankShape == null) "Salvar" else "Confirmar", "Salvar medição",android.R.drawable.ic_menu_save) {
                confirm.isEnabled = false
                surface.queueEvent { confirmMeasurement() }
            }
            confirm.isEnabled = false
            undo = action(actions,"Desfaz.","Desfazer",android.R.drawable.ic_menu_revert) {
                saved = false
                surface.queueEvent {
                    if (inputMode == InputMode.POINTS) renderer.undo()
                    else {
                        if (tankShape != null) renderer.undo()
                        renderer.setAutomatic(true)
                    }
                }
            }
            undo.isEnabled = false
            action(actions,"Mais","Mais opções de medição",android.R.drawable.ic_menu_manage) {
                AlertDialog.Builder(this@ArMeasurementActivity).setTitle("Opções")
                    .setItems(arrayOf("Tamanho do ponto","Refazer medição","Ajuda")) { _, index ->
                        when (index) {
                            0 -> choosePointSize()
                            1 -> {
                                saved = false
                                surface.queueEvent {
                                    if (tankShape != null) renderer.restartTankMeasurement()
                                    renderer.setAutomatic(inputMode != InputMode.POINTS)
                                }
                            }
                            else -> AlertDialog.Builder(this@ArMeasurementActivity).setTitle("Medição automática")
                                .setMessage("Enquadre o objeto e mova a câmera devagar até reconhecer a superfície. Auto calcula sem tocar; arraste as pontas para corrigir a medida. O toque só seleciona objetos ou marca pontos quando Objeto ou Pontos estiver ativo. A medida aparece nas linhas, sem pedir uma referência amarela.\n\nA leitura depende do suporte do aparelho. Foto usa uma referência conhecida no mesmo plano.")
                                .setPositiveButton("Entendi",null).show()
                        }
                    }.show()
            }
        }
        root.addView(footer,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(safe.left,safe.top,safe.right,safe.bottom)
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
            photoFallback("Auto indisponível neste aparelho. Use Foto com uma referência conhecida.")
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
        fun select(button: Button, active: Boolean) {
            if (button.isSelected == active && button.tag == true) return
            button.tag = true
            button.isSelected = active
            val ink = Color.rgb(20,37,31)
            button.backgroundTintList = ColorStateList.valueOf(if (active) Color.rgb(192,245,101) else Color.rgb(38,57,46))
            button.setTextColor(if (active) ink else Color.WHITE)
            button.compoundDrawables[1]?.setTint(if (active) ink else Color.rgb(192,245,101))
        }
        select(automatic,inputMode == InputMode.AUTOMATIC)
        select(objectSelection,inputMode == InputMode.OBJECT)
        select(manual,inputMode == InputMode.POINTS)
        confirm.isEnabled = !saved && frame.canConfirm && measurement != null
        undo.isEnabled = frame.points.isNotEmpty() || (tankShape != null && frame.dimensionIndex > 0)
    }

    private fun setInputMode(mode: InputMode) {
        inputMode = mode
        saved = false
        surface.queueEvent {
            renderer.waitForSelection = mode == InputMode.OBJECT
            renderer.setAutomatic(mode != InputMode.POINTS)
        }
    }

    private fun photoFallback(message: String) {
        setResult(RESULT_CANCELED,Intent().putExtra(EXTRA_NOTICE,message))
        finish()
    }

    private fun label(size: Float) = TextView(this).apply {
        textSize = size
        setTextColor(Color.WHITE)
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(20,37,31))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun choosePointSize() {
        val choices = MeasurementPointSize.entries
        AlertDialog.Builder(this).setTitle("Tamanho do ponto")
            .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), choices.indexOf(overlay.pointSize)) { dialog, index ->
                overlay.pointSize = choices[index]
                runCatching { pointPreferences.pointSize = choices[index] }
                updatePointRadius()
                overlay.invalidate()
                dialog.dismiss()
            }.setNegativeButton("Fechar", null).show()
    }

    private fun updatePointRadius() {
        renderer.selectionRadiusFraction = overlay.pointSize.radiusDp * resources.displayMetrics.density /
            maxOf(surface.width, surface.height, 1)
    }

    private fun cameraTouch(x: Float,y: Float): Pair<Float,Float>? {
        val from = IntArray(2).also(overlay::getLocationInWindow)
        val to = IntArray(2).also(surface::getLocationInWindow)
        return CameraViewportMapping.cameraPoint(x,y,from[0],from[1],to[0],to[1],surface.width,surface.height)
    }
}

// Formatting runs on the UI thread for the overlay, controls and history.
private val arNumber = NumberFormat.getNumberInstance(Locale("pt", "BR")).apply {
    maximumFractionDigits = 2
}

private fun formatArLength(meters: Double) = if (meters >= 1) "${arNumber.format(meters)} m"
    else "${arNumber.format(meters * 100)} cm"

private fun formatArArea(squareMeters: Double) =
    if (squareMeters < .01) "${arNumber.format(squareMeters * 10_000)} cm²"
    else "${arNumber.format(squareMeters)} m²"

private class ArMeasurementOverlay(
    context: Context,
    private val updateControls: (ArMeasurementFrame) -> Unit,
    private val onMove: (Int, Float, Float) -> Unit,
    onPoint: (Float, Float) -> Unit
) : View(context) {
    var cameraView: View? = null
    var pointSize = MeasurementPointSize.NORMAL
    @Volatile private var frame = ArMeasurementFrame()
    private var displayedPoints = emptyList<android.graphics.PointF?>()
    private var dragged: Int? = null
    private var animatedSerial = -1L
    private var animationStart = 0L
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
        override fun onDown(e: MotionEvent): Boolean {
            dragged = displayedPoints.indices.filter { displayedPoints[it] != null }.minByOrNull {
                val p = displayedPoints[it]!!
                kotlin.math.hypot(p.x-e.x,p.y-e.y)
            }?.takeIf { val p = displayedPoints[it]!!; kotlin.math.hypot(p.x-e.x,p.y-e.y) <= pointSize.hitRadiusDp*density }
            return true
        }
        override fun onScroll(e1: MotionEvent?,e2: MotionEvent,distanceX: Float,distanceY: Float): Boolean {
            val index = dragged ?: return false
            onMove(index,e2.x,e2.y)
            return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            performClick()
            if (dragged == null) onPoint(e.x, e.y)
            return true
        }
    })

    fun publish(next: ArMeasurementFrame) {
        frame = next
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) dragged = null
        return handled
    }
    override fun performClick() = super.performClick()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = frame
        updateControls(current)
        val camera = cameraView ?: return
        val cameraOrigin = IntArray(2).also(camera::getLocationInWindow)
        val overlayOrigin = IntArray(2).also(::getLocationInWindow)
        fun map(point: PointF): PointF? = point.let {
            CameraViewportMapping.overlayPoint(it.x/current.viewportWidth,it.y/current.viewportHeight,
                overlayOrigin[0],overlayOrigin[1],cameraOrigin[0],cameraOrigin[1],camera.width,camera.height)
                ?.let { mapped -> PointF(mapped.first,mapped.second) }
        }
        val points = current.points.map { it?.let(::map) }
        displayedPoints = points
        if (current.selectionSerial != animatedSerial) {
            animatedSerial = current.selectionSerial
            animationStart = 0L
        }
        val selection = current.selectionOutline.mapNotNull(::map)
        if (selection.size >= 2) {
            if (animationStart == 0L) animationStart = SystemClock.uptimeMillis()
            val progress = ((SystemClock.uptimeMillis()-animationStart)/950f).coerceIn(0f,1f)
            ObjectSelectionEffect.draw(canvas,selection,RectF(0f,0f,width.toFloat(),height.toFloat()),progress,density)
            if (progress < 1) postInvalidateOnAnimation()
        }
        current.sides.forEachIndexed { index, meters ->
            val start = points[index] ?: return@forEachIndexed
            val end = points[(index + 1) % points.size] ?: return@forEachIndexed
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
        if (current.area != null && points.filterNotNull().size >= 3) {
            val visible = points.filterNotNull()
            val centerX = visible.map { it.x }.average().toFloat()
            val centerY = visible.map { it.y }.average().toFloat()
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
        for (point in points.filterNotNull()) {
            val radius = pointSize.radiusDp * density
            canvas.drawCircle(point.x, point.y, radius + 2*density, backing)
            canvas.drawCircle(point.x, point.y, radius, line)
        }
    }
}
