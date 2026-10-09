package org.camylla.proof

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLConfig
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20 as G
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/** Single camera texture, independent clean encoder output and phone-only monitoring.
 * All calls are confined to the camera handler. The renderer owns its input surface.
 */
internal class CameraRenderer(private val width: Int, private val height: Int, encoder: Surface,
    private val onFrame: () -> Unit, private val onError: (String) -> Unit) : AutoCloseable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private lateinit var config: EGLConfig
    private var context = EGL14.EGL_NO_CONTEXT
    private var output = EGL14.EGL_NO_SURFACE
    private var sample = EGL14.EGL_NO_SURFACE
    private var preview: EGLSurface? = null
    private var previewWidth = 1
    private var previewHeight = 1
    private var program = 0
    private var texture = 0
    private val transform = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }
    lateinit var inputTexture: SurfaceTexture
        private set
    lateinit var input: Surface
        private set
    var values: Map<String, Double> = emptyMap()
    var previewRotation = 0
    private var lastHistogram = 0L
    val histogram = AtomicReference(IntArray(64))
    var closed = false
        private set
    init {
        try {
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, 0x3142, 1, EGL14.EGL_NONE),
            0, configs, 0, 1, IntArray(1), 0))
        config = checkNotNull(configs[0])
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        output = EGL14.eglCreateWindowSurface(display, config, encoder, intArrayOf(EGL14.EGL_NONE), 0)
        check(output != EGL14.EGL_NO_SURFACE)
        sample = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 64, EGL14.EGL_HEIGHT, 36, EGL14.EGL_NONE), 0)
        makeCurrent(output)
        program = G.glCreateProgram()
        val vs = shader(G.GL_VERTEX_SHADER, VERTEX)
        val fs = shader(G.GL_FRAGMENT_SHADER, FRAGMENT)
        G.glAttachShader(program, vs); G.glAttachShader(program, fs); G.glLinkProgram(program)
        val status = IntArray(1); G.glGetProgramiv(program, G.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { G.glGetProgramInfoLog(program) }
        G.glDeleteShader(vs); G.glDeleteShader(fs)
        val names = IntArray(1); G.glGenTextures(1, names, 0); texture = names[0]
        G.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        G.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, G.GL_TEXTURE_MIN_FILTER, G.GL_LINEAR)
        G.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, G.GL_TEXTURE_MAG_FILTER, G.GL_LINEAR)
        G.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, G.GL_TEXTURE_WRAP_S, G.GL_CLAMP_TO_EDGE)
        G.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, G.GL_TEXTURE_WRAP_T, G.GL_CLAMP_TO_EDGE)
        inputTexture = SurfaceTexture(texture).apply { setDefaultBufferSize(width, height) }
        input = Surface(inputTexture)
        } catch (error: Exception) {
            // Construction can fail before the engine owns this instance.
            close()
            throw error
        }
    }
    fun attach(surface: Surface?, width: Int, height: Int) {
        preview?.let { EGL14.eglDestroySurface(display, it) }; preview = null
        if (surface != null && surface.isValid) {
            val window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(window != EGL14.EGL_NO_SURFACE)
            preview = window; previewWidth = width.coerceAtLeast(1); previewHeight = height.coerceAtLeast(1)
        }
    }
    fun frame() {
        if (closed) return
        try {
            makeCurrent(output)
            inputTexture.updateTexImage(); inputTexture.getTransformMatrix(transform)
            // The producer matrix includes sensor orientation/front mirroring, not
            // just the crop and GL Y flip. Applying it unchanged rotates a 16:9
            // buffer into 9:16 while draw() still fits 16:9: circles become ellipses.
            CameraTextureTransform.canonical(transform).copyInto(transform)
            draw(output, width, height, values["outputRotation"]?.toInt() ?: 0, values["outputMirror"] == 1.0, false)
            EGLExt.eglPresentationTimeANDROID(display, output, inputTexture.timestamp)
            check(EGL14.eglSwapBuffers(display, output))
            preview?.let {
                draw(it, previewWidth, previewHeight, previewRotation, values["previewMirror"] == 1.0, true)
                if (!EGL14.eglSwapBuffers(display, it)) { EGL14.eglDestroySurface(display, it); preview = null }
            }
            if (values["histogram"] == 1.0 && android.os.SystemClock.elapsedRealtime() - lastHistogram >= 500) {
                lastHistogram = android.os.SystemClock.elapsedRealtime()
                draw(sample, 64, 36, 0, false, false)
                val pixels = ByteBuffer.allocateDirect(64 * 36 * 4)
                G.glReadPixels(0, 0, 64, 36, G.GL_RGBA, G.GL_UNSIGNED_BYTE, pixels)
                val bins = IntArray(64)
                for (i in 0 until 64 * 36) {
                    val r = pixels.get(i * 4).toInt() and 255; val g = pixels.get(i * 4 + 1).toInt() and 255; val b = pixels.get(i * 4 + 2).toInt() and 255
                    bins[((r * 0.2126 + g * 0.7152 + b * 0.0722) / 4).toInt().coerceIn(0, 63)]++
                }
                histogram.set(bins)
            }
            onFrame()
        } catch (error: Exception) { onError("Camera rendering: ${error.message}") }
    }
    private fun draw(target: EGLSurface, w: Int, h: Int, rotation: Int, mirror: Boolean, aids: Boolean) {
        makeCurrent(target); G.glViewport(0, 0, w, h); G.glClearColor(0f, 0f, 0f, 1f); G.glClear(G.GL_COLOR_BUFFER_BIT)
        G.glUseProgram(program)
        val aspect = if (rotation % 180 == 0) width.toFloat() / height else height.toFloat() / width
        val canvasAspect = w.toFloat() / h
        val sx = minOf(1f, aspect / canvasAspect); val sy = minOf(1f, canvasAspect / aspect)
        G.glUniform2f(location("scale"), sx, sy)
        G.glUniform1f(location("rotation"), rotation.toFloat())
        G.glUniform1f(location("mirror"), if (mirror) 1f else 0f)
        G.glUniformMatrix4fv(location("transform"), 1, false, transform, 0)
        G.glUniform3f(location("adjust"), (values["brightness"] ?: 0.0).toFloat(),
            (values["contrast"] ?: 1.0).toFloat(), (values["saturation"] ?: 1.0).toFloat())
        G.glUniform3f(location("aids"), if (aids && values["grid"] == 1.0) 1f else 0f,
            if (aids && values["zebras"] == 1.0) 1f else 0f, if (aids && values["peaking"] == 1.0) 1f else 0f)
        G.glUniform2f(location("pixel"), 1f / width, 1f / height)
        G.glUniform2f(location("canvas"), w.toFloat(), h.toFloat())
        G.glActiveTexture(G.GL_TEXTURE0); G.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        G.glUniform1i(location("image"), 0)
        val position = G.glGetAttribLocation(program, "position")
        vertices.position(0); G.glVertexAttribPointer(position, 2, G.GL_FLOAT, false, 0, vertices); G.glEnableVertexAttribArray(position)
        G.glDrawArrays(G.GL_TRIANGLE_STRIP, 0, 4)
        check(G.glGetError() == G.GL_NO_ERROR) { "OpenGL draw failed" }
    }
    private fun location(name: String) = G.glGetUniformLocation(program, name)
    private fun makeCurrent(surface: EGLSurface) { check(EGL14.eglMakeCurrent(display, surface, surface, context)) }
    private fun shader(type: Int, source: String): Int {
        val id = G.glCreateShader(type); G.glShaderSource(id, source); G.glCompileShader(id)
        val status = IntArray(1); G.glGetShaderiv(id, G.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { G.glGetShaderInfoLog(id) }; return id
    }
    override fun close() {
        if (closed) return
        closed = true
        if (this::inputTexture.isInitialized) inputTexture.setOnFrameAvailableListener(null)
        if (this::input.isInitialized) input.release()
        if (this::inputTexture.isInitialized) inputTexture.release()
        runCatching {
            if (context != EGL14.EGL_NO_CONTEXT && output != EGL14.EGL_NO_SURFACE) {
                makeCurrent(output); G.glDeleteProgram(program); G.glDeleteTextures(1, intArrayOf(texture), 0)
            }
        }
        preview?.let { EGL14.eglDestroySurface(display, it) }
        EGL14.eglDestroySurface(display, output); EGL14.eglDestroySurface(display, sample)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context); EGL14.eglTerminate(display)
    }
    companion object {
        private const val VERTEX = """
            attribute vec2 position; uniform vec2 scale; uniform float rotation; uniform float mirror;
            uniform mat4 transform; varying vec2 uv; varying vec2 screen;
            void main() {
                gl_Position=vec4(position*scale,0.0,1.0);
                vec2 p=(position+1.0)*0.5; screen=p;
                if(mirror>0.5) p.x=1.0-p.x;
                if(rotation==90.0) p=vec2(1.0-p.y,p.x);
                else if(rotation==180.0) p=vec2(1.0-p.x,1.0-p.y);
                else if(rotation==270.0) p=vec2(p.y,1.0-p.x);
                uv=(transform*vec4(p,0.0,1.0)).xy;
            }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES image; uniform vec3 adjust; uniform vec3 aids; uniform vec2 pixel; uniform vec2 canvas;
            varying vec2 uv; varying vec2 screen;
            float luma(vec3 c){return dot(c,vec3(0.2126,0.7152,0.0722));}
            void main(){
                vec3 c=texture2D(image,uv).rgb; c=(c-0.5)*adjust.y+0.5+adjust.x;
                c=clamp(mix(vec3(luma(c)),c,adjust.z),0.0,1.0);
                if(aids.y>0.5 && luma(c)>0.95 && mod((screen.x*canvas.x+screen.y*canvas.y)/8.0,2.0)<1.0) c=vec3(1.0,0.8,0.0);
                if(aids.z>0.5){
                    float dx=abs(luma(texture2D(image,uv+vec2(pixel.x,0.0)).rgb)-luma(texture2D(image,uv-vec2(pixel.x,0.0)).rgb));
                    float dy=abs(luma(texture2D(image,uv+vec2(0.0,pixel.y)).rgb)-luma(texture2D(image,uv-vec2(0.0,pixel.y)).rgb));
                    if(dx+dy>0.18)c=vec3(0.0,1.0,0.3);
                }
                vec2 d=min(abs(screen-vec2(0.333333)),abs(screen-vec2(0.666667)))*canvas;
                if(aids.x>0.5 && min(d.x,d.y)<1.0)c=mix(c,vec3(1.0),0.6);
                gl_FragColor=vec4(c,1.0);
            }
        """
    }
}
