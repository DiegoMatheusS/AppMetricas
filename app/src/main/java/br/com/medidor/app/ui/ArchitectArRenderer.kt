package br.com.medidor.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import br.com.medidor.app.domain.ModelFace
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Minimal ARCore + GLES2 renderer. Uses ARCore camera preview texture and draws
 * opaque calibrated wall faces at a tracked image or a tapped horizontal plane.
 * AR model coordinates: X to right, Y up, Z toward image-top.
 */
internal class ArchitectArRenderer(
    private val activity: ComponentActivity,
    private val faces: List<ModelFace>,
    val mode: String,
    initialScale: Float,
    private val status: (String) -> Unit
) : GLSurfaceView.Renderer {
    @Volatile var session: Session? = null
        set(value) {
            if (field !== value) {
                field = value
                // Renderer state is reused across activity resume; old anchors belong to old session.
                clearRequested = true
            }
        }
    @Volatile var modelSize: Float = initialScale
    var referenceBitmap: Bitmap? = null
    var paperWidthMeters: Float = .21f

    private val tap = AtomicReference<Pair<Float, Float>?>(null)
    @Volatile private var clearRequested = false
    private var anchor: Anchor? = null
    private var trackedImage: AugmentedImage? = null
    private val background = ArCameraBackground()
    private var modelProgram = 0
    private var viewportWidth = 1
    private var viewportHeight = 1

    // Cache geometry buffers to reduce allocations and stalls during navigation.
    private val wallBuffers: List<FloatBuffer> by lazy {
        faces.map { face ->
            floatBuffer(FloatArray(face.corners.size * 3).also { verts ->
                face.corners.forEachIndexed { i, p ->
                    verts[i * 3] = p.x.toFloat()
                    verts[i * 3 + 1] = p.z.toFloat()
                    verts[i * 3 + 2] = (-p.y).toFloat()
                }
            })
        }
    }
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val pv = FloatArray(16)
    private val mvp = FloatArray(16)
    private val colors = floatArrayOf(
        .25f, .63f, .75f, 1f,
        .18f, .50f, .65f, 1f,
        .36f, .72f, .81f, 1f,
        .14f, .43f, .61f, 1f,
        .59f, .80f, .84f, 1f,
        .12f, .38f, .49f, 1f
    )

    fun requestPlacement(x: Float, y: Float) { tap.set(x to y) }
    fun clearAnchor() { clearRequested = true }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(.04f, .08f, .1f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        background.initialize()
        modelProgram = createProgram(
            """
            attribute vec3 aPosition;
            uniform mat4 uMvp;
            void main() { gl_Position = uMvp * vec4(aPosition.xyz, 1.0); }
            """.trimIndent(),
            """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
            """.trimIndent()
        )
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        try {
            s.setCameraTextureName(background.texture)
            val rotation = activity.display?.rotation ?: Surface.ROTATION_0
            s.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
            val frame = s.update()
            background.draw(frame)
            if (clearRequested) {
                try { anchor?.detach() } catch (_: Exception) { }
                anchor = null
                trackedImage = null
                clearRequested = false
            }
            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) {
                status("Acompanhando a câmera… mova devagar e melhore a iluminação.")
                return
            }
            if (mode == ArchitectArActivity.MODE_PAPER) {
                // getUpdatedTrackables contains updates, not every tracked frame.
                // Keep the image and inspect its live tracking state each frame.
                val newlyVisible = frame.getUpdatedTrackables(AugmentedImage::class.java)
                    .firstOrNull {
                        it.index == 0 && it.trackingState == TrackingState.TRACKING &&
                            it.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
                    }
                if (newlyVisible != null && (trackedImage == null || anchor == null)) {
                    try { anchor?.detach() } catch (_: Exception) { }
                    anchor = newlyVisible.createAnchor(newlyVisible.centerPose)
                    trackedImage = newlyVisible
                }
                val image = trackedImage
                when {
                    image == null -> {
                        status("Enquadre a folha impressa (pelo menos 25% da imagem da câmera).")
                        return
                    }
                    image.trackingState == TrackingState.STOPPED -> {
                        try { anchor?.detach() } catch (_: Exception) { }
                        anchor = null
                        trackedImage = null
                        status("Referência perdida. Aponte novamente para a folha.")
                        return
                    }
                    image.trackingState != TrackingState.TRACKING -> {
                        status("Rastreamento pausado. Mova devagar até recuperar a planta.")
                        return
                    }
                    image.trackingMethod == AugmentedImage.TrackingMethod.LAST_KNOWN_POSE -> {
                        status("Folha fora da câmera: última posição estimada. Não mova o papel.")
                    }
                    image.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING -> {
                        status("Planta reconhecida e rastreada visualmente.")
                    }
                    else -> {
                        status("Rastreamento visual perdido. Reenquadre a folha.")
                        return
                    }
                }
            } else {
                tap.getAndSet(null)?.let { (x, y) ->
                    val hit = frame.hitTest(x, y).firstOrNull { result ->
                        val plane = result.trackable as? Plane
                        plane != null && plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                            plane.trackingState == TrackingState.TRACKING &&
                            plane.isPoseInPolygon(result.hitPose)
                    }
                    if (hit != null) {
                        anchor?.detach()
                        anchor = hit.createAnchor()
                        status("Maquete posicionada. Mova o celular para observar.")
                    } else status("Nenhuma superfície horizontal detectada nesse ponto.")
                }
            }

            val target = anchor
            if (target?.trackingState != TrackingState.TRACKING) return
            camera.getProjectionMatrix(projection, 0, .05f, 100f)
            camera.getViewMatrix(view, 0)
            target.pose.toMatrix(model, 0)
            Matrix.multiplyMM(pv, 0, projection, 0, view, 0)
            Matrix.multiplyMM(mvp, 0, pv, 0, model, 0)
            drawWalls(mvp, modelSize)
        } catch (_: CameraNotAvailableException) {
            status("Câmera indisponível. Feche outros apps que estejam usando a câmera.")
        } catch (e: Exception) {
            status("Falha durante a visualização AR: ${e.localizedMessage}")
        }
    }

    private fun drawWalls(mvpMatrix: FloatArray, scale: Float) {
        GLES20.glUseProgram(modelProgram)
        val scaled = mvpMatrix.copyOf()
        Matrix.scaleM(scaled, 0, scale, scale, scale)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(modelProgram, "uMvp"),
            1, false, scaled, 0)
        val position = GLES20.glGetAttribLocation(modelProgram, "aPosition")
        val colorLocation = GLES20.glGetUniformLocation(modelProgram, "uColor")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        faces.forEachIndexed { index, face ->
            val verts = wallBuffers[index]
            verts.position(0)
            val faceColor = face.shade.coerceIn(0, 5) * 4
            GLES20.glUniform4fv(colorLocation, 1, colors, faceColor)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 0, verts)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, face.corners.size)
        }
        GLES20.glDisableVertexAttribArray(position)
    }
}
