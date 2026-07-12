package app.motionwall

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val TAG = "MotionWall"

/**
 * Renders the ExoPlayer video through our own GL pipeline: SurfaceTexture (external OES) →
 * fragment shader → the wallpaper surface. This is what lets us (a) letterbox at the
 * video's real aspect — sharp, no auto-zoom, (b) fill on demand, (c) apply grayscale live —
 * none of which the raw MediaCodec→Surface path or Media3's (black-on-wallpaper) effects
 * pipeline can do. All GL work happens on one dedicated thread.
 */
class GLVideoRenderer(
    private val output: Surface,
    private val onInputSurface: (Surface) -> Unit,
    private val onStatus: (String) -> Unit,
) : SurfaceTexture.OnFrameAvailableListener {

    @Volatile var grayscale = false
    /** "fit" = whole video, native aspect (bars). "fill" = cover screen, native aspect (crop). */
    @Volatile var scale = "fit"

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    private var egl: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    private var aPos = 0; private var aTex = 0
    private var uST = 0; private var uScale = 0; private var uGray = 0
    private val stMatrix = FloatArray(16)

    private var surfaceW = 1; private var surfaceH = 1
    private var videoW = 0; private var videoH = 0

    private val quad: FloatBuffer = floats(floatArrayOf(
        // aPos      aTex
        -1f, -1f,   0f, 0f,
         1f, -1f,   1f, 0f,
        -1f,  1f,   0f, 1f,
         1f,  1f,   1f, 1f,
    ))

    fun start() {
        thread = HandlerThread("wally-gl").also { it.start() }
        handler = Handler(thread.looper)
        handler.post { runCatching { initGL() }.onFailure { fail("init", it) } }
    }

    fun setVideoSize(w: Int, h: Int) { handler.post { videoW = w; videoH = h } }
    fun resize(w: Int, h: Int) { handler.post { surfaceW = w; surfaceH = h } }

    fun release() {
        if (!::handler.isInitialized) return
        handler.post {
            surfaceTexture?.release(); inputSurface?.release()
            if (egl != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(egl, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(egl, eglSurface)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(egl, ctx)
                EGL14.eglTerminate(egl)
            }
        }
        thread.quitSafely()
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        handler.post { runCatching { drawFrame() }.onFailure { fail("draw", it) } }
    }

    private fun initGL() {
        egl = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(egl, IntArray(2), 0, IntArray(2), 1)
        val cfg = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(egl, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE), 0, cfg, 0, 1, IntArray(1), 0)
        ctx = EGL14.eglCreateContext(egl, cfg[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        eglSurface = EGL14.eglCreateWindowSurface(egl, cfg[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(egl, eglSurface, eglSurface, ctx)

        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uST = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uScale = GLES20.glGetUniformLocation(program, "uScale")
        uGray = GLES20.glGetUniformLocation(program, "uGray")

        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); texId = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(texId).apply { setOnFrameAvailableListener(this@GLVideoRenderer) }
        inputSurface = Surface(surfaceTexture)
        onStatus("GL ready")
        onInputSurface(inputSurface!!)
    }

    private fun drawFrame() {
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(stMatrix)

        GLES20.glViewport(0, 0, surfaceW, surfaceH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)   // letterbox bars
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)

        GLES20.glUniformMatrix4fv(uST, 1, false, stMatrix, 0)
        GLES20.glUniform2f(uScale, scaleX(), scaleY())
        GLES20.glUniform1f(uGray, if (grayscale) 1f else 0f)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        EGL14.eglSwapBuffers(egl, eglSurface)
    }

    // Scale the full-screen quad to the video's aspect: fit shrinks (bars), fill grows (crop).
    private fun scaleX(): Float {
        if (videoW == 0 || videoH == 0) return 1f
        val va = videoW.toFloat() / videoH; val sa = surfaceW.toFloat() / surfaceH
        return if (scale == "fill") { if (va > sa) va / sa else 1f } else { if (va > sa) 1f else va / sa }
    }
    private fun scaleY(): Float {
        if (videoW == 0 || videoH == 0) return 1f
        val va = videoW.toFloat() / videoH; val sa = surfaceW.toFloat() / surfaceH
        return if (scale == "fill") { if (va > sa) 1f else sa / va } else { if (va > sa) sa / va else 1f }
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, """
            attribute vec2 aPos; attribute vec2 aTex;
            uniform mat4 uSTMatrix; uniform vec2 uScale; varying vec2 vTex;
            void main() { gl_Position = vec4(aPos * uScale, 0.0, 1.0); vTex = (uSTMatrix * vec4(aTex, 0.0, 1.0)).xy; }
        """.trimIndent())
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 vTex;
            uniform samplerExternalOES uTex; uniform float uGray;
            void main() {
                vec4 c = texture2D(uTex, vTex);
                float g = dot(c.rgb, vec3(0.299, 0.587, 0.114));
                gl_FragColor = vec4(mix(c.rgb, vec3(g), uGray), 1.0);
            }
        """.trimIndent())
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("link: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("compile: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun floats(a: FloatArray) = ByteBuffer.allocateDirect(a.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    private fun fail(where: String, e: Throwable) {
        Log.e(TAG, "GL $where failed", e)
        onStatus("GL $where FAILED: ${e.message}")
    }
}
