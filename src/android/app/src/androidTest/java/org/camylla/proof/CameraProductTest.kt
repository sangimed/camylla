package org.camylla.proof

import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.graphics.Color
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Device tests inspect characteristics and synthesize GPU input. They never open a camera. */
@RunWith(AndroidJUnit4::class)
class CameraProductTest {
    @Test fun cameraCommandsRejectCoercionWrongFieldsAndInvalidBounds() {
        fun command() = org.json.JSONObject().put("type","camera_command").put("version",3).put("session_id","ab".repeat(16))
            .put("generation",1).put("request_id",1L).put("control","zoom").put("value",2.0)
        M4Wire.cameraRequest(command())
        val mutations = listOf("request_id" to -1, "request_id" to 1.5, "request_id" to "1", "generation" to 0,
            "generation" to 2147483648L, "value" to "2", "control" to "x".repeat(41), "session_id" to "ab")
        for ((key,value) in mutations) {
            try { M4Wire.cameraRequest(command().put(key,value)); fail("Accepted invalid $key=$value") }
            catch (_: IllegalArgumentException) { }
        }
        try { M4Wire.cameraRequest(command().put("unexpected",true)); fail("Accepted unknown field") }
        catch (_: IllegalArgumentException) { }
        val focus = command().apply { remove("control"); remove("value"); put("type","camera_focus"); put("x",0.25); put("y",0.75) }
        M4Wire.cameraRequest(focus)
        try { M4Wire.cameraRequest(focus.put("x",1.01)); fail("Accepted out-of-image focus coordinate") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun catalogAndSettingsRemainBoundedAndNeverRestoreAuthorizationOrLight() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = CameraCatalog.list(context.getSystemService(CameraManager::class.java))
        assertTrue("No baseline video camera exposed", catalog.isNotEmpty())
        for (camera in catalog) for (mode in camera.modes) {
            val options = camera.options(mode)
            assertTrue(options.size <= 40)
            assertEquals(options.size, options.map { it.key }.toSet().size)
            for (o in options) {
                assertTrue(o.min.isFinite() && o.max.isFinite() && o.min <= o.max && o.step > 0)
                assertTrue(o.json().toString().toByteArray().size < 3500)
                assertNotNull(o.validate(Double.NaN))
            }
        }
        val testName = "camera-settings-test-${java.util.UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(testName, mode)
        }
        try {
            val settings = CameraSettings(isolated)
            settings.save("test-camera", "HD", mapOf("zoom" to 2.0, "torch" to 1.0, "focusLock" to 1.0, "aeLock" to 1.0))
            assertEquals(mapOf("zoom" to 2.0), CameraSettings(isolated).values("test-camera", "HD"))
            settings.savePreset("Desk", "test-camera", "HD", mapOf("torch" to 1.0, "zoom" to 2.0, "wbLock" to 1.0))
            val preset = org.json.JSONObject(CameraSettings(isolated).presets().getValue("Desk"))
            assertFalse(preset.getJSONObject("values").has("torch"))
            assertFalse(preset.getJSONObject("values").has("wbLock"))
            settings.deletePreset("Desk"); assertTrue(settings.presets().isEmpty())
            settings.reset("test-camera", "HD"); assertTrue(settings.values("test-camera", "HD").isEmpty())
            val identity = ProductIdentity(context)
            assertTrue("Test accidentally started capture", context.getSystemService(android.app.ActivityManager::class.java)
                .getRunningServices(100).none { it.service.className == CaptureService::class.java.name && it.foreground })
        } finally { context.deleteSharedPreferences(testName) }
    }
    @Test fun rendererKeepsMonitoringOffEncoderAndAppliesSoftwareBrightness() {
        val thread = HandlerThread("camylla-renderer-test").apply { start() }
        val handler = Handler(thread.looper)
        val renderer = AtomicReference<CameraRenderer>()
        val failure = AtomicReference<Throwable>()
        val reader = ImageReader.newInstance(128, 72, PixelFormat.RGBA_8888, 3,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN)
        val initialized = CountDownLatch(1)
        val firstFrame = CountDownLatch(1)
        handler.post {
            try {
                val r = CameraRenderer(128,72,reader.surface, { firstFrame.countDown() }, { failure.set(AssertionError(it)); firstFrame.countDown() })
                renderer.set(r)
                r.values = mapOf("grid" to 1.0, "zebras" to 1.0, "peaking" to 1.0, "brightness" to 0.0, "contrast" to 1.0, "saturation" to 1.0)
                r.inputTexture.setOnFrameAvailableListener({ r.frame() }, handler)
                val canvas = r.input.lockCanvas(null); canvas.drawColor(Color.WHITE); r.input.unlockCanvasAndPost(canvas)
            } catch (error: Throwable) { failure.set(error) } finally { initialized.countDown() }
        }
        try {
            assertTrue(initialized.await(5,TimeUnit.SECONDS)); failure.get()?.let { throw it }
            assertTrue(firstFrame.await(5,TimeUnit.SECONDS)); failure.get()?.let { throw it }
            var image: android.media.Image? = null
            for (attempt in 0 until 50) { image = reader.acquireLatestImage(); if (image != null) break; Thread.sleep(20) }
            assertNotNull("Renderer did not deliver an output image", image)
            image!!.use { frame ->
                val plane = frame.planes[0]; val bytes = plane.buffer
                // White output stays white: preview-only zebra/grid colors cannot leak into it.
                for (y in listOf(10,24,36,48,60)) for (x in listOf(10,42,64,85,110)) {
                    val offset = y * plane.rowStride + x * plane.pixelStride
                    assertTrue((bytes.get(offset).toInt() and 255) > 240)
                    assertTrue((bytes.get(offset+1).toInt() and 255) > 240)
                    assertTrue((bytes.get(offset+2).toInt() and 255) > 240)
                }
            }
            val adjusted = CountDownLatch(1)
            handler.post {
                try {
                    val r = renderer.get(); r.values = r.values + ("brightness" to -0.5)
                    val canvas = r.input.lockCanvas(null); canvas.drawColor(Color.WHITE); r.input.unlockCanvasAndPost(canvas)
                } catch (error: Throwable) { failure.set(error) } finally { adjusted.countDown() }
            }
            assertTrue(adjusted.await(5,TimeUnit.SECONDS)); failure.get()?.let { throw it }
            image = null
            for (attempt in 0 until 100) { image = reader.acquireLatestImage(); if (image != null) break; Thread.sleep(20) }
            assertNotNull(image)
            image!!.use { frame ->
                val plane = frame.planes[0]; val offset = 36 * plane.rowStride + 64 * plane.pixelStride
                val red = plane.buffer.get(offset).toInt() and 255
                assertTrue("Software brightness did not change clean output: $red", red in 110..145)
            }
        } finally {
            val done = CountDownLatch(1)
            handler.post { runCatching { renderer.get()?.close() }; done.countDown() }
            done.await(5,TimeUnit.SECONDS); reader.close(); thread.quitSafely()
        }
    }
    @Test fun rendererQuarterTurnsAndMirrorsMatchFocusCoordinateConvention() {
        val thread = HandlerThread("camylla-transform-test").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(128,72,PixelFormat.RGBA_8888,3,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN)
        val renderer = AtomicReference<CameraRenderer>()
        val failure = AtomicReference<Throwable>()
        val initialized = CountDownLatch(1)
        handler.post {
            try {
                val r = CameraRenderer(128,72,reader.surface, {}, { failure.set(AssertionError(it)) })
                renderer.set(r); r.inputTexture.setOnFrameAvailableListener({ r.frame() },handler)
            } catch (error: Throwable) { failure.set(error) } finally { initialized.countDown() }
        }
        try {
            assertTrue(initialized.await(5,TimeUnit.SECONDS)); failure.get()?.let { throw it }
            for (rotation in listOf(0,90,180,270)) for (mirror in listOf(false,true)) {
                val drawn = CountDownLatch(1)
                handler.post {
                    try {
                        val r = renderer.get(); r.values = mapOf("outputRotation" to rotation.toDouble(), "outputMirror" to if (mirror) 1.0 else 0.0)
                        val canvas = r.input.lockCanvas(null)
                        val paint = android.graphics.Paint()
                        for ((rect,color) in listOf(android.graphics.Rect(0,0,64,36) to Color.RED,
                            android.graphics.Rect(64,0,128,36) to Color.GREEN, android.graphics.Rect(0,36,64,72) to Color.BLUE,
                            android.graphics.Rect(64,36,128,72) to Color.WHITE)) { paint.color = color; canvas.drawRect(rect,paint) }
                        r.input.unlockCanvasAndPost(canvas)
                    } catch (error: Throwable) { failure.set(error) } finally { drawn.countDown() }
                }
                assertTrue(drawn.await(5,TimeUnit.SECONDS)); failure.get()?.let { throw it }
                var image: android.media.Image? = null
                for (attempt in 0 until 100) { image = reader.acquireLatestImage(); if (image != null) break; Thread.sleep(20) }
                failure.get()?.let { throw it }; assertNotNull(image)
                image!!.use { frame ->
                    val imageWidth = if (rotation % 180 == 0) 128.0 else 72.0 * 72 / 128
                    val imageRect = FocusCoordinates.Rect((128-imageWidth)/2,0.0,(128+imageWidth)/2,72.0)
                    val full = FocusCoordinates.Rect(0.0,0.0,1.0,1.0)
                    for (u in listOf(0.25,0.75)) for (v in listOf(0.25,0.75)) {
                        val x = imageRect.left + u * imageWidth; val y = v * 72
                        val target = FocusCoordinates.sensorPoint(x,y,imageRect,full,rotation,mirror)!!
                        val expected = if (target.y < 0.5) { if (target.x < 0.5) Color.RED else Color.GREEN } else { if (target.x < 0.5) Color.BLUE else Color.WHITE }
                        val plane = frame.planes[0]; val offset = y.toInt()*plane.rowStride + x.toInt()*plane.pixelStride
                        val channels = listOf(Color.red(expected),Color.green(expected),Color.blue(expected))
                        for (i in channels.indices) assertEquals("rotation=$rotation mirror=$mirror x=$u y=$v channel=$i", channels[i], plane.buffer.get(offset+i).toInt() and 255)
                    }
                }
            }
        } finally {
            val done = CountDownLatch(1); handler.post { runCatching { renderer.get()?.close() }; done.countDown() }
            done.await(5,TimeUnit.SECONDS); reader.close(); thread.quitSafely()
        }
    }
}
