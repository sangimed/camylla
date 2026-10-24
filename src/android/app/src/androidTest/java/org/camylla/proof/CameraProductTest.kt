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
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Device tests inspect characteristics and synthesize GPU input. They never open a camera. */
@RunWith(AndroidJUnit4::class)
class CameraProductTest {
    @Test fun mobileNavigationKeepsCaptureActionsVisibleAndSeparatesInfrequentWork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.targetContext.getSharedPreferences("mobile-ui", Context.MODE_PRIVATE).edit().clear().commit()
        var activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        fun views(view: android.view.View): List<android.view.View> =
            listOf(view) + if (view is android.view.ViewGroup)
                (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        try {
            instrumentation.runOnMainSync {
                val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                fun button(description: String) = views(root).filterIsInstance<android.widget.Button>()
                    .single { it.contentDescription?.toString()?.startsWith(description) == true && it.isShown }
                val settings = button("Settings")
                val connections = button("Connections")
                val compactStatus = views(root).filterIsInstance<android.widget.TextView>()
                    .single { it.contentDescription?.startsWith("Compact connection and capture status") == true }
                assertTrue("Connection/capture state should remain visible on Camera", compactStatus.isShown)
                val visibleCaptureState = compactStatus.text.toString().substringAfterLast(" · ")
                assertTrue("Accessible status should expose the visible capture state",
                    compactStatus.contentDescription.contains("Capture state: $visibleCaptureState."))
                assertTrue("Camera toolbar actions should preserve touch feedback", settings.background is android.graphics.drawable.RippleDrawable)
                assertTrue("Only one capture action should be exposed while stopped", views(root).filterIsInstance<android.widget.Button>()
                    .count { it.isShown && it.text.toString() in setOf("Start camera", "Stop camera") } == 1)
                connections.performClick()
                assertTrue("Connections destination should expose pairing", views(root).filterIsInstance<android.widget.Button>()
                    .any { it.isShown && it.text.toString() == "Pair a PC" })
                assertTrue("Connections must provide a path back to Camera", views(root).filterIsInstance<android.widget.Button>()
                    .any { it.isShown && it.contentDescription == "Back to Camera" })
                button("Back to Camera").performClick()
                settings.performClick()
                fun preference(title: String): android.view.View = views(root)
                    .filterIsInstance<android.widget.LinearLayout>()
                    .single { row -> views(row).filterIsInstance<android.widget.TextView>().any { it.text.toString() == title } && row.isClickable }
                assertFalse("Unsupported Power and Screen page must not be advertised", views(root)
                    .filterIsInstance<android.widget.TextView>().any { it.text.toString() == "Power and Screen" })
                preference("Video").performClick()
                assertTrue("Settings detail must expose a single back-to-settings action", views(root).filterIsInstance<android.widget.Button>()
                    .any { it.isShown && it.contentDescription == "Back to settings" })
                assertFalse("Category detail must not show the parent Back to Camera action at the same time", views(root)
                    .filterIsInstance<android.widget.Button>().any { it.isShown && it.contentDescription == "Back to Camera" })
                button("Back to settings").performClick()
                button("Back to Camera").performClick()
                settings.performClick()
                preference("Camera").performClick()
                preference("Advanced camera controls").performClick()
                assertTrue("Advanced category must expose real camera controls", views(root).filterIsInstance<android.widget.Button>()
                    .any { it.isShown && it.text.toString() == "Save preset" })
                assertTrue("Opening advanced camera controls must return to Camera", compactStatus.isShown)
            }
            instrumentation.runOnMainSync {
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            instrumentation.waitForIdleSync()
            activity = requireNotNull(resumedMainActivity(instrumentation)) { "Landscape MainActivity did not resume" }
            instrumentation.runOnMainSync {
                fun button(text: String) = views(activity.findViewById(android.R.id.content)).filterIsInstance<android.widget.Button>()
                    .single { it.text.toString().equals(text, ignoreCase = true) }
                val allViews = views(activity.findViewById(android.R.id.content))
                val railVisible = allViews.filterIsInstance<android.widget.TextView>()
                    .any { it.text.toString() == "CONNECTION / VIDEO" && it.isShown }
                val compactStatus = allViews.filterIsInstance<android.widget.TextView>()
                    .single { it.contentDescription?.startsWith("Compact connection and capture status") == true }
                assertEquals("Connection status should appear in either the landscape rail or compact toolbar, without duplication",
                    !railVisible, compactStatus.isShown)
                assertTrue("Landscape capture action must remain reachable", button("Start camera").isShown)
            }
            instrumentation.runOnMainSync {
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            instrumentation.waitForIdleSync()
            activity = requireNotNull(resumedMainActivity(instrumentation)) { "Portrait MainActivity did not resume" }
            instrumentation.runOnMainSync {
                val all = views(activity.findViewById(android.R.id.content)).filterIsInstance<android.widget.Button>()
                val start = all.single { it.text.toString().equals("Start camera", ignoreCase = true) }
                val stop = all.single { it.text.toString().equals("Stop camera", ignoreCase = true) }
                assertTrue("Start action not visible", start.getGlobalVisibleRect(android.graphics.Rect()))
                assertFalse("Stop action should stay hidden while idle", stop.isShown)
            }
        } finally {
            val current = resumedMainActivity(instrumentation) ?: activity
            instrumentation.runOnMainSync { current.finish() }
            instrumentation.targetContext.getSharedPreferences("mobile-ui", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun resumedMainActivity(instrumentation: android.app.Instrumentation): MainActivity? {
        val active = AtomicReference<MainActivity?>()
        instrumentation.runOnMainSync {
            active.set(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull())
        }
        return active.get()
    }

    @Test fun cameraWorkspaceUsesCompactDestinationToolbarAndTruthfulCaptureAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        fun views(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        try {
            instrumentation.runOnMainSync {
                val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val buttons = views(root).filterIsInstance<android.widget.Button>().filter { it.isShown }
                assertTrue("Settings must be reachable from the compact camera toolbar", buttons.any { it.contentDescription == "Settings" })
                assertTrue("Peer/state affordance must open Connections", buttons.any { it.contentDescription?.contains("Connections") == true })
                assertFalse("Camera workspace must not retain the three destination dashboard", buttons.any { it.text.toString() == "Camera" })
                val compactStatus = views(root).filterIsInstance<android.widget.TextView>()
                    .single { it.contentDescription?.startsWith("Compact connection and capture status") == true }
                assertTrue("Portrait Camera must keep a visible connection and capture status", compactStatus.isShown)
                assertTrue("Compact status must identify the actual capture state", compactStatus.contentDescription.contains("Capture state:"))
                assertEquals("Exactly one explicit capture action is visible while stopped", 1,
                    buttons.count { it.text.toString() == "Start camera" || it.text.toString() == "Stop camera" })
                val start = buttons.single { it.text.toString() == "Start camera" }
                if (compactStatus.text.toString().startsWith("Not paired")) {
                    assertFalse("Start must be disabled until a PC is paired", start.isEnabled)
                    assertTrue("Disabled Start must look visibly unavailable", start.alpha < 1f)
                    assertTrue("Disabled Start must explain the pairing requirement", start.contentDescription.contains("Pair a PC first"))
                }
                val preview = views(root).filterIsInstance<android.view.SurfaceView>().single()
                val bounds = android.graphics.Rect()
                assertTrue("Preview viewport should be visible and usable", preview.getGlobalVisibleRect(bounds) && bounds.width() >= MobileUi.dp(activity, 180))
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun mobileControlsAcceptLateDescriptorsAndRuntimeUnavailability() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val panel = CameraControlsPanel(instrumentation.targetContext) { null }
            fun buttons(view: android.view.View): List<android.widget.Button> =
                if (view is android.widget.Button) listOf(view)
                else if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
                else emptyList()
            panel.update(CameraStatus(generation = 7, message = "Changing camera"))
            val available = CameraStatus(generation = 7, options = listOf(CameraOption("torch", "Torch")), applied = mapOf("torch" to 0.0))
            panel.update(available)
            assertTrue(buttons(panel).any { it.text.toString() == "Torch\nOff" && it.isEnabled })
            panel.update(available.copy(applied = mapOf("torch" to 1.0)))
            val torch = buttons(panel).single { it.text.toString() == "Torch\nOn" }
            assertTrue(torch.isEnabled)
            assertTrue("Enabled toggle should expose its persistent active state", torch.isSelected)
            assertTrue("Camera control should provide Android press feedback", torch.background is android.graphics.drawable.RippleDrawable)
            panel.update(available.copy(options = listOf(CameraOption("torch", "Torch", reason = "No flash unit"))))
            assertTrue(buttons(panel).any { it.text.toString() == "Torch\nUnavailable" && !it.isEnabled && it.contentDescription.contains("No flash unit") })
        }
    }

    @Test fun darkCameraControlLabelsAndSliderUseReadableProductColors() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val panel = CameraControlsPanel(instrumentation.targetContext) { null }
            panel.update(CameraStatus(
                generation = 9,
                options = listOf(CameraOption("zoom", "Digital zoom", min = 1.0, max = 4.0, step = 0.1, unit = "x")),
                applied = mapOf("zoom" to 1.0),
                message = "Applying digital zoom"
            ))
            fun views(view: android.view.View): List<android.view.View> =
                listOf(view) + if (view is android.view.ViewGroup)
                    (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
            val label = views(panel).filterIsInstance<android.widget.TextView>()
                .single { it.text.toString().startsWith("Digital zoom:") }
            assertEquals("Slider label must contrast with dark controls", MobileUi.TEXT, label.currentTextColor)
            val slider = views(panel).filterIsInstance<android.widget.SeekBar>().single()
            assertEquals("Slider accent should use the product blue", MobileUi.BLUE, slider.thumbTintList?.defaultColor)
            val state = views(panel).filterIsInstance<android.widget.TextView>()
                .single { it.text.toString().contains("Applying digital zoom") }
            assertEquals("Inline status should use the readable muted color", MobileUi.MUTED, state.currentTextColor)
        }
    }

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

    @Test fun rendererPreservesCirclesOnIndependentPortraitPreviewAndLandscapeOutput() {
        val thread = HandlerThread("camylla-aspect-test").apply { start() }
        val handler = Handler(thread.looper)
        fun reader(w: Int, h: Int) = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN)
        val output = reader(128, 72); val preview = reader(90, 160)
        val renderer = AtomicReference<CameraRenderer>()
        val failure = AtomicReference<Throwable>()
        val frameSignal = AtomicReference<CountDownLatch>()
        val initialized = CountDownLatch(1)
        handler.post {
            try {
                val r = CameraRenderer(128, 72, output.surface, { frameSignal.get()?.countDown() },
                    { failure.set(AssertionError(it)); frameSignal.get()?.countDown() })
                renderer.set(r); r.attach(preview.surface, 90, 160)
                r.inputTexture.setOnFrameAvailableListener({ r.frame() }, handler)
            } catch (error: Throwable) { failure.set(error) } finally { initialized.countDown() }
        }
        fun assertCircle(reader: ImageReader, description: String) {
            val image = reader.acquireLatestImage() ?: throw AssertionError("Missing $description image")
            image.use { frame ->
                var left = frame.width; var right = -1; var top = frame.height; var bottom = -1
                val plane = frame.planes[0]
                for (y in 0 until frame.height) for (x in 0 until frame.width) {
                    if ((plane.buffer.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 255) > 180) {
                        left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                    }
                }
                val width = right - left + 1; val height = bottom - top + 1
                assertTrue("$description missing circle", width > 8 && height > 8)
                assertTrue("$description stretched circle: ${width}x$height", kotlin.math.abs(width - height) <= 2)
                assertTrue("$description not centred horizontally", kotlin.math.abs(left + right + 1 - frame.width) <= 2)
                assertTrue("$description not centred vertically", kotlin.math.abs(top + bottom + 1 - frame.height) <= 2)
            }
        }
        try {
            assertTrue(initialized.await(5, TimeUnit.SECONDS)); failure.get()?.let { throw it }
            for (rotation in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
                val ready = CountDownLatch(1); frameSignal.set(ready)
                handler.post {
                    try {
                        val r = renderer.get()
                        r.values = mapOf("outputRotation" to rotation.toDouble(), "outputMirror" to if (mirror) 1.0 else 0.0,
                            "previewMirror" to if (mirror) 1.0 else 0.0)
                        r.previewRotation = rotation
                        val canvas = r.input.lockCanvas(null); canvas.drawColor(Color.BLACK)
                        canvas.drawCircle(64f, 36f, 12f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
                        r.input.unlockCanvasAndPost(canvas)
                    } catch (error: Throwable) { failure.set(error); ready.countDown() }
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS)); failure.get()?.let { throw it }
                assertCircle(output, "output rotation=$rotation mirror=$mirror")
                assertCircle(preview, "preview rotation=$rotation mirror=$mirror")
            }
        } finally {
            val done = CountDownLatch(1)
            handler.post { runCatching { renderer.get()?.close() }; done.countDown() }
            done.await(5, TimeUnit.SECONDS); output.close(); preview.close(); thread.quitSafely()
        }
    }
}
