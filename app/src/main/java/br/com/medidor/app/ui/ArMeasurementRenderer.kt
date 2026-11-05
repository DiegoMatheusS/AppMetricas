package br.com.medidor.app.ui

import android.graphics.PointF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import br.com.medidor.app.domain.PointMeasurementGeometry
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
    val dimensionIndex: Int = 0
)

internal class ArMeasurementRenderer(
    private val rotation: () -> Int,
    private val areaMode: Boolean,
    private val tankDimensions: List<String>,
    private val publish: (ArMeasurementFrame) -> Unit
) : GLSurfaceView.Renderer {
    @Volatile var session: Session? = null
    private val background = ArCameraBackground()
    private val tap = AtomicReference<PointF?>()
    private val anchors = mutableListOf<Anchor>()
    private val corners = mutableListOf<Pair<Double, Double>>()
    private val measuredDimensions = mutableListOf<Double>()
    private var areaPlane: Plane? = null
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var notice: String? = null
    private var latest = ArMeasurementFrame()
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val projectionView = FloatArray(16)
    private val clipPoint = FloatArray(4)
    private val worldPoint = floatArrayOf(0f, 0f, 0f, 1f)

    fun requestPoint(x: Float, y: Float) { tap.set(PointF(x, y)) }

    fun undo() {
        if (areaMode && corners.size > 1) {
            corners.removeAt(corners.lastIndex)
        } else {
            anchors.removeLastOrNull()?.detach()
            corners.clear()
            areaPlane = null
        }
        tap.set(null)
        latest = ArMeasurementFrame()
        notice = null
    }

    fun clear() {
        anchors.forEach(Anchor::detach)
        anchors.clear()
        corners.clear()
        areaPlane = null
        tap.set(null)
        latest = ArMeasurementFrame()
        notice = null
    }

    fun takeTankDimension(): List<Double>? {
        val length = latest.sides.singleOrNull() ?: return null
        if (measuredDimensions.size >= tankDimensions.size) return null
        measuredDimensions += length
        clear()
        return measuredDimensions.toList().takeIf { it.size == tankDimensions.size }
    }

    fun measurement(): ArMeasurementFrame = latest

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(.04f, .08f, .1f, 1f)
        background.initialize()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val arSession = session ?: return
        try {
            arSession.setCameraTextureName(background.texture)
            arSession.setDisplayGeometry(rotation(), viewportWidth, viewportHeight)
            val frame = arSession.update()
            background.draw(frame)
            val touch = tap.getAndSet(null)
            if (frame.camera.trackingState != TrackingState.TRACKING) {
                showUnavailable("Mova devagar a câmera em um local bem iluminado até recuperar o rastreamento.")
                return
            }
            if (anchors.any { it.trackingState == TrackingState.STOPPED }) {
                clear()
                notice = "A referência foi perdida. Marque os pontos novamente."
            }
            if (anchors.any { it.trackingState != TrackingState.TRACKING }) {
                showUnavailable("Rastreamento dos pontos pausado. Aponte de volta para a superfície.")
                return
            }
            if (touch != null) placePoint(frame, touch)
            updateOverlay(frame)
        } catch (_: CameraNotAvailableException) {
            showUnavailable("Câmera indisponível. Feche outros apps que usam a câmera e tente novamente.")
        } catch (_: Exception) {
            showUnavailable("A medição em AR foi interrompida. Volte e abra a ferramenta novamente.")
        }
    }

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
        notice = null
    }

    private fun rootPlane(plane: Plane): Plane {
        var root = plane
        while (true) root = root.subsumedBy ?: return root
    }

    private fun updateOverlay(frame: Frame) {
        val camera = frame.camera
        camera.getProjectionMatrix(projection, 0, .05f, 100f)
        camera.getViewMatrix(view, 0)
        Matrix.multiplyMM(projectionView, 0, projection, 0, view, 0)

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

        val dimensionIndex = measuredDimensions.size
        val dimension = tankDimensions.getOrNull(dimensionIndex)
        val message = notice ?: when {
            dimension != null && sides.isNotEmpty() -> "Confira os dois extremos e confirme a medida."
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
            message, dimensionIndex)
        publish(latest)
    }

    private fun showUnavailable(message: String) {
        latest = ArMeasurementFrame(message = message, dimensionIndex = measuredDimensions.size)
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
