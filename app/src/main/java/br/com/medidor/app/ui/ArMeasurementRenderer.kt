package br.com.medidor.app.ui

import android.graphics.PointF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import br.com.medidor.app.domain.ArFrameValidity
import br.com.medidor.app.domain.ArMeasurementChecks
import br.com.medidor.app.domain.ArMeasurementStability
import br.com.medidor.app.domain.AutomaticContourGeometry
import br.com.medidor.app.domain.LiveContourTracking
import br.com.medidor.app.domain.PlanarContourResult
import br.com.medidor.app.domain.Point3d
import br.com.medidor.app.domain.PointMeasurementGeometry
import br.com.medidor.app.vision.VisualBoundaryDetector
import com.google.ar.core.Coordinates2d
import com.google.ar.core.HitResult
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.Anchor
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

internal data class ArMeasurementFrame(
    val points: List<PointF?> = emptyList(),
    val sides: List<Double> = emptyList(),
    val area: Double? = null,
    val message: String = "",
    val dimensionIndex: Int = 0,
    val automatic: Boolean = true,
    val canConfirm: Boolean = false,
    val viewportWidth: Int = 1,
    val viewportHeight: Int = 1
)

internal class ArMeasurementRenderer(
    private val rotation: () -> Int,
    private val areaMode: Boolean,
    private val tankDimensions: List<String>,
    private val publish: (ArMeasurementFrame) -> Unit
) : GLSurfaceView.Renderer {
    private val frameValidity = ArFrameValidity()
    @Volatile var session: Session? = null
        set(value) {
            if (field !== value) {
                frameValidity.invalidate()
                latest = latest.copy(canConfirm = false)
                resetStabilityRequested = true
            }
            field = value
        }
    @Volatile private var resetStabilityRequested = false
    private var worldSession: Session? = null
    private val automaticStability = ArMeasurementStability()
    private val anchorStability = ArMeasurementStability()
    private var pendingSurface: Plane? = null
    private var lastAnalysisTimestamp = 0L
    private val background = ArCameraBackground()
    private val tap = AtomicReference<PointF?>()
    private data class PointEdit(val index: Int, val normalized: PointF)
    private val edit = AtomicReference<PointEdit?>()
    private val anchors = mutableListOf<Anchor>()
    private val corners = mutableListOf<Pair<Double, Double>>()
    private val measuredDimensions = mutableListOf<Double>()
    private var areaPlane: Plane? = null
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var notice: String? = null
    @Volatile private var latest = ArMeasurementFrame()
    private var automatic = true
    private var lastAnalysis = 0L
    private var pendingBoundary: PlanarContourResult? = null
    private var confirmations = 0
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val projectionView = FloatArray(16)
    private val clipPoint = FloatArray(4)
    private val worldPoint = floatArrayOf(0f, 0f, 0f, 1f)

    /** Coordinates are normalized in the actual camera View, independent of the GL buffer size. */
    fun requestPoint(x: Float, y: Float) { if (x in 0f..1f && y in 0f..1f) tap.set(PointF(x, y)) }
    fun requestMove(index: Int, x: Float, y: Float) {
        if (x in 0f..1f && y in 0f..1f) edit.set(PointEdit(index,PointF(x,y)))
    }

    fun setAutomatic(enabled: Boolean) {
        clear()
        automatic = enabled
    }

    fun undo() {
        edit.set(null)
        automatic = false
        if (tankDimensions.isNotEmpty() && anchors.isEmpty() &&
            measuredDimensions.isNotEmpty()) {
            measuredDimensions.removeAt(measuredDimensions.lastIndex)
        } else if (areaMode && corners.size > 1) {
            corners.removeAt(corners.lastIndex)
        } else {
            anchors.removeLastOrNull()?.let { anchor ->
                try { anchor.detach() } catch (_: Exception) { }
            }
            corners.clear()
            areaPlane = null
        }
        tap.set(null)
        anchorStability.reset()
        automaticStability.reset()
        latest = ArMeasurementFrame()
        notice = null
    }

    fun clear() {
        edit.set(null)
        anchors.forEach { anchor ->
            try { anchor.detach() } catch (_: Exception) { }
        }
        anchors.clear()
        corners.clear()
        areaPlane = null
        tap.set(null)
        latest = ArMeasurementFrame()
        notice = null
        resetStability()
    }

    private fun resetStability() {
        pendingBoundary = null
        pendingSurface = null
        confirmations = 0
        automaticStability.reset()
        anchorStability.reset()
    }

    /** Discard both confirmed and unconfirmed tank dimensions and start a new scan. */
    fun restartTankMeasurement() {
        measuredDimensions.clear()
        clear()
    }

    fun takeTankDimension(): List<Double>? {
        val length = measurement().sides.singleOrNull() ?: return null
        if (!length.isFinite() || length !in 0.02..30.0) {
            notice = "Dimensão fora do intervalo de 2 cm a 30 m. Refaça os pontos."
            return null
        }
        if (measuredDimensions.size >= tankDimensions.size) return null
        measuredDimensions += length
        clear()
        return measuredDimensions.toList().takeIf { it.size == tankDimensions.size }
    }

    fun measurement(): ArMeasurementFrame =
        if (session != null && latest.canConfirm && frameValidity.canConfirm(SystemClock.elapsedRealtime())) latest
        else latest.copy(sides = emptyList(), area = null, canConfirm = false)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(.04f, .08f, .1f, 1f)
        background.initialize()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        edit.set(null)
        tap.set(null)
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val arSession = session ?: return
        try {
            if (worldSession !== arSession) {
                clear()
                measuredDimensions.clear()
                frameValidity.reset()
                lastAnalysisTimestamp = 0L
                worldSession = arSession
            }
            if (resetStabilityRequested) {
                resetStability()
                resetStabilityRequested = false
            }
            arSession.setCameraTextureName(background.texture)
            arSession.setDisplayGeometry(rotation(), viewportWidth, viewportHeight)
            val frame = arSession.update()
            background.draw(frame)
            val camera = frame.camera
            val tracking = camera.trackingState == TrackingState.TRACKING
            if (!frameValidity.update(frame.timestamp, tracking, SystemClock.elapsedRealtime())) {
                tap.set(null)
                resetStability()
                showUnavailable(if (tracking) "Aguardando uma nova imagem da câmera…"
                    else "Mova devagar a câmera em um local bem iluminado até recuperar o rastreamento.")
                return
            }
            camera.getProjectionMatrix(projection, 0, .05f, 100f)
            camera.getViewMatrix(view, 0)
            Matrix.multiplyMM(projectionView, 0, projection, 0, view, 0)
            val touch = tap.getAndSet(null)?.let { PointF(it.x*viewportWidth,it.y*viewportHeight) }
            if (anchors.any { it.trackingState == TrackingState.STOPPED }) {
                clear()
                notice = "A referência foi perdida. Marque os pontos novamente."
            }
            if (anchors.any { it.trackingState != TrackingState.TRACKING }) {
                resetStability()
                showUnavailable("Rastreamento dos pontos pausado. Aponte de volta para a superfície.")
                return
            }
            val moved = edit.getAndSet(null)
            if (moved != null) {
                movePoint(frame,moved.index,PointF(moved.normalized.x*viewportWidth,moved.normalized.y*viewportHeight))
            } else if (touch != null) {
                if (automatic) {
                    clear()
                    automatic = false
                }
                placePoint(frame, touch)
            } else if (automatic && anchors.isEmpty()) {
                detectAutomatically(frame)
            }
            updateOverlay(frame)
        } catch (_: CameraNotAvailableException) {
            showUnavailable("Câmera indisponível. Feche outros apps que usam a câmera e tente novamente.")
        } catch (_: Exception) {
            showUnavailable("A medição em AR foi interrompida. Volte e abra a ferramenta novamente.")
        }
    }

    private fun detectAutomatically(frame: Frame) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalysis < 650 || frame.timestamp <= lastAnalysisTimestamp) return
        lastAnalysis = now
        lastAnalysisTimestamp = frame.timestamp
        try {
            val boundary = frame.acquireCameraImage().use { image ->
                val sampled = ArCameraImageSampler.sample(image)
                val found = VisualBoundaryDetector.find(sampled.pixels, sampled.width, sampled.height)
                    ?: return@use null
                val source = FloatArray(found.corners.size * 2)
                found.corners.forEachIndexed { index, corner ->
                    source[index * 2] = (corner.x + .5f) * image.width / sampled.width
                    source[index * 2 + 1] = (corner.y + .5f) * image.height / sampled.height
                }
                val screen = FloatArray(source.size)
                frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, source, Coordinates2d.VIEW, screen)
                found to List(found.corners.size) { PointF(screen[it * 2], screen[it * 2 + 1]) }
            }
            if (boundary == null) {
                resetStability()
                notice = "Enquadre um objeto inteiro, com bordas visíveis e fundo contrastante."
                return
            }
            val (found, screen) = boundary
            val sameContour = LiveContourTracking.overlap(pendingBoundary, found.region) >= .6
            if (!sameContour) automaticStability.reset()
            confirmations = if (sameContour) confirmations + 1 else 1
            pendingBoundary = found.region
            if (confirmations < 3) {
                notice = "Contorno encontrado. Mantenha o celular firme para confirmar."
                return
            }
            val polygonOnScreen = screen.map { it.x.toDouble() to it.y.toDouble() }
            val centroid = AutomaticContourGeometry.centroid(polygonOnScreen) ?: run {
                rejectAutomatic("Enquadre uma superfície com contorno fechado e sem cruzamentos.")
                return
            }
            val center = PointF(centroid.first.toFloat(), centroid.second.toFloat())
            fun hits(point: PointF): List<HitResult> {
                val inset = if (tankDimensions.isEmpty()) 0f else .01f
                val x = point.x * (1 - inset) + center.x * inset
                val y = point.y * (1 - inset) + center.y * inset
                return frame.hitTest(x, y).filter { it.distance in .2f..8f }
            }
            if (tankDimensions.isEmpty()) {
                val centerHit = hits(center).firstOrNull {
                    val plane = it.trackable as? Plane
                    plane != null && plane.trackingState == TrackingState.TRACKING && plane.isPoseInPolygon(it.hitPose)
                } ?: run {
                    rejectAutomatic("Contorno visível. Mova devagar a câmera para reconhecer a superfície.")
                    return
                }
                val plane = rootPlane(centerHit.trackable as Plane)
                val positions = screen.map { point ->
                    hits(point).firstOrNull {
                        val candidate = it.trackable as? Plane
                        candidate != null && candidate.trackingState == TrackingState.TRACKING &&
                            rootPlane(candidate) == plane &&
                            candidate.isPoseInPolygon(it.hitPose)
                    }?.hitPose?.translation ?: run {
                        rejectAutomatic("Aguarde o reconhecimento de toda a superfície do contorno.")
                        return
                    }
                }
                if (pendingSurface != plane) automaticStability.reset()
                pendingSurface = plane
                val candidates = positions.map(::point3d)
                val selected = if (areaMode) candidates else
                    ArMeasurementChecks.rulerEdge(candidates, automaticStability.referencePoints) ?: run {
                        rejectAutomatic("Aguarde a câmera reconhecer uma borda completa.")
                        return
                    }
                val stable = stableAutomatic(selected, frame) ?: return
                if (areaMode) {
                    val local = stable.map { centerHit.hitPose.inverse().transformPoint(floatPoint(it)) }
                    val polygon = local.map { it[0].toDouble() to it[2].toDouble() }
                    if (local.any { abs(it[1]) > .03f } ||
                        PointMeasurementGeometry.polygonAreaSquareMeters(polygon) == null) {
                        rejectAutomatic("A superfície ainda está instável. Tente outro ângulo.")
                        return
                    }
                    anchors += centerHit.createAnchor()
                    areaPlane = plane
                    corners += polygon
                } else {
                    anchors += checkNotNull(session).createAnchor(com.google.ar.core.Pose.makeTranslation(floatPoint(stable[0])))
                    try {
                        anchors += checkNotNull(session).createAnchor(com.google.ar.core.Pose.makeTranslation(floatPoint(stable[1])))
                    } catch (e: Exception) {
                        clear()
                        throw e
                    }
                }
            } else {
                val centerHit = hits(center).firstOrNull {
                    it.trackable is DepthPoint && it.trackable.trackingState == TrackingState.TRACKING
                } ?: run {
                    rejectAutomatic("Mova-se devagar até reconhecer a profundidade do tanque.")
                    return
                }
                val centerWorld = centerHit.hitPose.translation
                val up = project(centerWorld.copyOf().also { it[1] += .25f })
                val down = project(centerWorld.copyOf().also { it[1] -= .25f })
                if (up == null || down == null || hypot((up.x - down.x).toDouble(), (up.y - down.y).toDouble()) < 4) {
                    rejectAutomatic("Enquadre a lateral do tanque para medir suas dimensões.")
                    return
                }
                val vertical = (tankDimensions.firstOrNull() == "Diâmetro" &&
                    tankDimensions.lastOrNull() == "Comprimento" && measuredDimensions.isEmpty()) ||
                    (tankDimensions.lastOrNull() == "Altura" && measuredDimensions.isNotEmpty())
                val gravity = (up.x - down.x).toDouble() to (up.y - down.y).toDouble()
                val direction = if (vertical) gravity else -gravity.second to gravity.first
                val axis = AutomaticContourGeometry.axisEndpoints(polygonOnScreen, direction) ?: run {
                    rejectAutomatic("Enquadre o tanque inteiro, com as duas extremidades visíveis.")
                    return
                }
                val ends = axis.map { (x, y) -> PointF(x.toFloat(), y.toFloat()) }.map { point ->
                    hits(point).firstOrNull { it.trackable is DepthPoint &&
                        it.trackable.trackingState == TrackingState.TRACKING } ?: run {
                        rejectAutomatic("Mova-se devagar até reconhecer a profundidade dos extremos.")
                        return
                    }
                }.map { point3d(it.hitPose.translation) }
                if (!ArMeasurementChecks.tankSection(ends, point3d(centerWorld), vertical)) {
                    rejectAutomatic("Confira o enquadramento. Os extremos precisam pertencer ao tanque e ao eixo da medida.")
                    return
                }
                val stable = stableAutomatic(ends, frame) ?: return
                anchors += checkNotNull(session).createAnchor(com.google.ar.core.Pose.makeTranslation(floatPoint(stable[0])))
                try {
                    anchors += checkNotNull(session).createAnchor(com.google.ar.core.Pose.makeTranslation(floatPoint(stable[1])))
                } catch (e: Exception) { clear(); throw e }
            }
            notice = "Medida fixada. Arraste as pontas para ajustar ou confirme."
        } catch (_: NotYetAvailableException) {
            rejectAutomatic("Preparando a imagem da câmera…")
        }
    }

    private fun rejectAutomatic(message: String) {
        automaticStability.reset()
        pendingSurface = null
        notice = message
    }

    private fun stableAutomatic(points: List<Point3d>, frame: Frame): List<Point3d>? {
        val stable = automaticStability.observe(points, areaMode, frame.timestamp, SystemClock.elapsedRealtime())
        if (stable == null) notice = "Conferindo a medida em 3D (${automaticStability.count}/3). Mantenha o objeto enquadrado."
        return stable
    }

    private fun point3d(point: FloatArray) = Point3d(point[0].toDouble(), point[1].toDouble(), point[2].toDouble())
    private fun floatPoint(point: Point3d) = floatArrayOf(point.x.toFloat(), point.y.toFloat(), point.z.toFloat())

    private fun placePoint(frame: Frame, touch: PointF) {
        val complete = if (areaMode) corners.size >= 12 else anchors.size >= 2
        if (complete) return
        val hits = frame.hitTest(touch.x, touch.y).filter { it.distance in .2f..8f }
        if (areaMode) {
            val selected = areaPlane?.let(::rootPlane)
            val hit = hits.firstOrNull {
                val plane = it.trackable as? Plane
                plane != null && plane.trackingState == TrackingState.TRACKING &&
                    plane.isPoseInPolygon(it.hitPose) && (selected == null || rootPlane(plane) == selected)
            }
            if (hit == null) {
                notice = if (selected == null) "Mova a câmera para detectar uma superfície plana."
                else "Marque todos os cantos na mesma superfície."
                return
            }
            if (selected == null) {
                val anchor = hit.createAnchor()
                anchors += anchor
                areaPlane = hit.trackable as Plane
                corners += 0.0 to 0.0
            } else {
                val local = anchors.first().pose.inverse().transformPoint(hit.hitPose.translation)
                val x = local[0].toDouble()
                val z = local[2].toDouble()
                if (abs(local[1]) > .03f || hypot(x, z) > 8.0) {
                    notice = "Escolha um canto da mesma superfície a até 8 m do primeiro ponto."
                    return
                }
                if (corners.any { hypot(x - it.first, z - it.second) < .01 }) {
                    notice = "Marque um canto diferente, afastado pelo menos 1 cm."
                    return
                }
                corners += x to z
            }
        } else {
            val hit = hits.firstOrNull {
                val surface = it.trackable
                surface.trackingState == TrackingState.TRACKING && when (surface) {
                    is DepthPoint -> true
                    is Plane -> tankDimensions.isEmpty() && surface.isPoseInPolygon(it.hitPose)
                    else -> false
                }
            }
            if (hit == null) {
                notice = if (tankDimensions.isNotEmpty())
                    "Sem profundidade nesse ponto. Aproxime-se e aponte para a superfície do tanque."
                else "Mova a câmera até detectar a superfície e toque novamente."
                return
            }
            if (anchors.isNotEmpty() && distance(anchors.first().pose.translation, hit.hitPose.translation) < .01) {
                notice = "Toque em um ponto a pelo menos 1 cm do primeiro."
                return
            }
            anchors += hit.createAnchor()
        }
        anchorStability.reset()
        notice = null
    }

    private fun movePoint(frame: Frame,index: Int,touch: PointF) {
        val hits = frame.hitTest(touch.x,touch.y).filter { it.distance in .2f..8f && it.trackable.trackingState == TrackingState.TRACKING }
        if (areaMode) {
            if (index !in corners.indices) return
            val plane = areaPlane?.let(::rootPlane) ?: return
            val hit = hits.firstOrNull { val p = it.trackable as? Plane
                p != null && rootPlane(p) == plane && p.isPoseInPolygon(it.hitPose) } ?: run {
                notice = "Arraste a ponta sobre a mesma superfície."; return
            }
            val local = anchors.first().pose.inverse().transformPoint(hit.hitPose.translation)
            if (abs(local[1]) > .03f || hypot(local[0].toDouble(),local[2].toDouble()) > 8.0) return
            corners[index] = local[0].toDouble() to local[2].toDouble()
        } else {
            if (index !in anchors.indices) return
            val hit = hits.firstOrNull { it.trackable is DepthPoint ||
                (tankDimensions.isEmpty() && (it.trackable as? Plane)?.isPoseInPolygon(it.hitPose) == true) } ?: run {
                notice = "A câmera ainda não reconheceu a superfície nessa posição."; return
            }
            val replacement = hit.createAnchor()
            anchors[index].detach()
            anchors[index] = replacement
        }
        automatic = false
        anchorStability.reset()
        notice = "Medida ajustada. As pontas continuam fixas no ambiente."
    }

    private fun rootPlane(plane: Plane): Plane {
        var root = plane
        while (true) root = root.subsumedBy ?: return root
    }

    private fun updateOverlay(frame: Frame) {
        val area = if (areaMode) PointMeasurementGeometry.polygonAreaSquareMeters(corners) else null
        val worldPoints = if (areaMode && corners.isNotEmpty()) {
            val origin = anchors.first().pose
            corners.map { (x, z) -> origin.transformPoint(floatArrayOf(x.toFloat(), 0f, z.toFloat())) }
        } else anchors.map { it.pose.translation }
        val sides = if (areaMode) {
            val count = if (area == null) (corners.size - 1).coerceAtLeast(0) else corners.size
            List(count) { index ->
                val a = corners[index]
                val b = corners[(index + 1) % corners.size]
                hypot(a.first - b.first, a.second - b.second)
            }
        } else if (worldPoints.size == 2) {
            listOf(distance(worldPoints[0], worldPoints[1])).filter { it.isFinite() && it >= .01 }
        } else emptyList()

        val hasMeasurement = if (areaMode) area != null else sides.isNotEmpty()
        if (hasMeasurement) anchorStability.observe(worldPoints.map(::point3d), areaMode,
            frame.timestamp, SystemClock.elapsedRealtime())
        else anchorStability.reset()
        val canConfirm = hasMeasurement && anchorStability.stable && frameValidity.canConfirm(SystemClock.elapsedRealtime())
        val dimensionIndex = measuredDimensions.size
        val dimension = tankDimensions.getOrNull(dimensionIndex)
        val message = if (hasMeasurement && !canConfirm) "Conferindo estabilidade da medida (${anchorStability.count}/3)…"
            else notice ?: when {
            dimension != null && sides.isNotEmpty() -> "Confira os dois extremos e confirme a medida."
            dimension != null && worldPoints.isEmpty() && automatic -> "Enquadre o tanque inteiro para detectar ${dimension.lowercase()}."
            dimension != null && worldPoints.isEmpty() -> "Toque no primeiro extremo de ${dimension.lowercase()}."
            dimension != null -> "Mova-se devagar e toque no extremo oposto, no mesmo eixo."
            area != null -> "Área fechada. Você pode adicionar cantos em sequência ou salvar."
            areaMode && corners.size >= 3 -> "Confira a ordem dos cantos e desfaça os cruzamentos."
            areaMode -> "Toque nos cantos em sequência, sobre uma única superfície plana."
            sides.isNotEmpty() -> "Confira a medida na linha ou desfaça o último ponto."
            worldPoints.isEmpty() -> "Mova o celular devagar e toque no início da medida."
            else -> "Toque no outro extremo. O primeiro ponto acompanha a superfície."
        }
        latest = ArMeasurementFrame(worldPoints.map(::project), sides, area,
            message, dimensionIndex, automatic, canConfirm, viewportWidth, viewportHeight)
        publish(latest)
    }

    private fun showUnavailable(message: String) {
        edit.set(null)
        frameValidity.invalidate()
        resetStability()
        latest = ArMeasurementFrame(message = message, dimensionIndex = measuredDimensions.size, automatic = automatic)
        publish(latest)
    }

    private fun project(point: FloatArray): PointF? {
        point.copyInto(worldPoint, endIndex = 3)
        Matrix.multiplyMV(clipPoint, 0, projectionView, 0, worldPoint, 0)
        val w = clipPoint[3]
        if (w <= .05f) return null
        val x = (clipPoint[0] / w + 1) * viewportWidth / 2
        val y = (1 - clipPoint[1] / w) * viewportHeight / 2
        return if (x.isFinite() && y.isFinite()) PointF(x, y) else null
    }

    private fun distance(a: FloatArray, b: FloatArray): Double {
        val x = a[0].toDouble() - b[0]
        val y = a[1].toDouble() - b[1]
        val z = a[2].toDouble() - b[2]
        return sqrt(x * x + y * y + z * z)
    }
}
