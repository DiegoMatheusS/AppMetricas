package br.com.medidor.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import br.com.medidor.app.domain.ModelFace
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private var texture = 0
    private var backgroundProgram = 0
    private var modelProgram = 0
    private var viewportWidth = 1
    private var viewportHeight = 1

    private val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val uv = FloatArray(8)
    private val quadBuffer by lazy { floatBuffer(quad) }
    private val uvBuffer by lazy { floatBuffer(uv) }
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
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        backgroundProgram = createProgram(
            """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition.xy, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
            """.trimIndent(),
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
            """.trimIndent()
        )
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
            s.setCameraTextureName(texture)
            val rotation = activity.display?.rotation ?: Surface.ROTATION_0
            s.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
            val frame = s.update()
            drawBackground(frame)
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

    private fun drawBackground(frame: Frame) {
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            quad,
            Coordinates2d.TEXTURE_NORMALIZED,
            uv
        )
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(backgroundProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(backgroundProgram, "uTexture"), 0)
        val position = GLES20.glGetAttribLocation(backgroundProgram, "aPosition")
        val coordinate = GLES20.glGetAttribLocation(backgroundProgram, "aTexCoord")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(coordinate)
        quadBuffer.position(0)
        uvBuffer.clear()
        uvBuffer.put(uv)
        uvBuffer.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, uvBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(coordinate)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
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

    private fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(data); position(0) }

    private fun createProgram(vertexShader: String, fragmentShader: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Falha shader: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Falha na ligação GLSL: ${GLES20.glGetProgramInfoLog(program)}" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return program
    }
}
