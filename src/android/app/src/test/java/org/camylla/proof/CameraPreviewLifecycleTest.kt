package org.camylla.proof

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito.*

/** Run the production renderer; only the Android native GPU/Surface boundary is simulated. */
class CameraPreviewLifecycleTest {
    @Test fun invalidatedPreviewDuringRepeatedRotationsKeepsEncoderFramesFlowing() {
        Gpu().use { gpu ->
            var frames = 0
            val failures = mutableListOf<String>()
            CameraRenderer(1280, 720, gpu.encoder, { frames++ }, failures::add).use { renderer ->
                repeat(6) { rotation ->
                    val portrait = rotation % 2 == 0
                    val window = gpu.attach(renderer, if (portrait) 720 else 1280, if (portrait) 1280 else 720)
                    renderer.previewRotation = if (portrait) 90 else 0
                    renderer.frame()
                    // SurfaceView destruction wins the race with the camera handler's detach.
                    gpu.invalid = window
                    renderer.frame()
                    renderer.attach(null, 1, 1)
                    renderer.frame()
                }
                assertEquals("A lost phone preview must not end the camera session", emptyList<String>(), failures)
                assertEquals("Every camera frame must still reach the encoder", 18, gpu.encodedFrames)
                assertEquals("Preview loss must not interrupt frame completion", 18, frames)
                assertEquals("Each replacement preview must display frames", 6, gpu.previewFrames)
                assertFalse("Session renderer must survive rotation", renderer.closed)
            }
            assertEquals("Each preview EGL surface must be released once", 6, gpu.destroyedPreviews.size)
            assertEquals(6, gpu.destroyedPreviews.toSet().size)
        }
    }

    @Test fun encoderSurfaceFailureStillTerminatesRendering() {
        Gpu().use { gpu ->
            val failures = mutableListOf<String>()
            CameraRenderer(1280, 720, gpu.encoder, {}, failures::add).use { renderer ->
                gpu.invalid = gpu.output
                renderer.frame()
                assertEquals("Encoder failures remain session failures", 1, failures.size)
                assertEquals(0, gpu.encodedFrames)
            }
        }
    }

    private class Gpu : AutoCloseable {
        val encoder = mock(Surface::class.java)
        val output = mock(EGLSurface::class.java)
        private val display = mock(EGLDisplay::class.java)
        private val context = mock(EGLContext::class.java)
        private val config = mock(EGLConfig::class.java)
        private val previews = mutableListOf<EGLSurface>()
        val destroyedPreviews = mutableListOf<EGLSurface>()
        var invalid: EGLSurface? = null
        var encodedFrames = 0
        var previewFrames = 0
        private val egl = mockStatic(EGL14::class.java) { call ->
            when (call.method.name) {
                "eglGetDisplay" -> display
                "eglInitialize" -> true
                "eglChooseConfig" -> { call.getArgument<Array<EGLConfig?>>(3)[0] = config; true }
                "eglCreateContext" -> context
                "eglCreatePbufferSurface" -> mock(EGLSurface::class.java)
                "eglCreateWindowSurface" -> if (call.getArgument<Surface>(2) === encoder) output
                    else mock(EGLSurface::class.java).also { previews.add(it) }
                "eglMakeCurrent" -> call.getArgument<EGLSurface?>(1) !== invalid || invalid == null
                "eglSwapBuffers" -> {
                    val target = call.getArgument<EGLSurface>(1)
                    if (target === output) encodedFrames++ else previewFrames++
                    true
                }
                "eglDestroySurface" -> {
                    val target = call.getArgument<EGLSurface?>(1)
                    if (target in previews) destroyedPreviews.add(target!!)
                    true
                }
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        private val gl = mockStatic(GLES20::class.java) { call ->
            when (call.method.name) {
                "glGetProgramiv", "glGetShaderiv" -> { call.getArgument<IntArray>(2)[0] = 1; null }
                "glCreateProgram", "glCreateShader" -> 1
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        private val ext = mockStatic(EGLExt::class.java)
        private val textures = mockConstruction(SurfaceTexture::class.java) { texture, _ ->
            doAnswer { call ->
                val matrix = call.getArgument<FloatArray>(0)
                for (i in 0..15) matrix[i] = if (i % 5 == 0) 1f else 0f
                null
            }.`when`(texture).getTransformMatrix(any(FloatArray::class.java))
        }
        private val surfaces = mockConstruction(Surface::class.java) { surface, _ ->
            `when`(surface.isValid).thenReturn(true)
        }

        fun attach(renderer: CameraRenderer, width: Int, height: Int): EGLSurface {
            val surface = mock(Surface::class.java)
            `when`(surface.isValid).thenReturn(true)
            renderer.attach(surface, width, height)
            return previews.last()
        }

        override fun close() {
            surfaces.close(); textures.close(); ext.close(); gl.close(); egl.close()
        }
    }
}
