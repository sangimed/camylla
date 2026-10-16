package org.camylla.proof

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Explicitly opt in with -e rotationSession true; needs a paired, running desktop. */
@RunWith(AndroidJUnit4::class)
class RotationSessionHardwareTest {
    @Suppress("DEPRECATION")
    @Test fun repeatedActivityRotationsRetainPairingTransportAndCaptureResources() {
        assumeTrue("Camera capture requires explicit test opt-in",
            InstrumentationRegistry.getArguments().getString("rotationSession") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val identity = ProductIdentity(context)
        val phone = identity.phoneId
        val peers = identity.desktops()
        val settings = CameraSettings(context)
        val peer = peers.firstOrNull { !it.revoked && it.id == settings.preferredPeer }
            ?: peers.first { !it.revoked }
        val current = AtomicReference<MainActivity>()
        val application = context.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { if (activity is MainActivity) current.set(activity) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        fun <T> onMain(block: () -> T): T {
            val result = AtomicReference<T>()
            instrumentation.runOnMainSync { result.set(block()) }
            return result.get()
        }
        fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).let {
            it.isAccessible = true; it.get(owner)
        }
        fun await(description: String, ready: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!ready()) {
                if (SystemClock.elapsedRealtime() >= deadline) fail("Timed out: $description")
                Thread.sleep(50)
            }
        }
        var initialOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        try {
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            initialOrientation = onMain { activity.requestedOrientation }
            onMain {
                ContextCompat.startForegroundService(context, Intent(context, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_START).putExtra(CaptureService.EXTRA_PEER, peer.id)
                    .putExtra(CaptureService.EXTRA_CAMERA, settings.preferredCamera)
                    .putExtra(CaptureService.EXTRA_MODE, settings.preferredMode))
            }
            await("paired stream starts") {
                onMain {
                    val service = field(current.get(), "service") as? CaptureService
                    service?.stats()?.let {
                        assertNotEquals("Capture failed: ${it.error}", "Error", it.state)
                        it.state == "Capturing" && it.network.state == "Streaming" && it.network.sentFrames > 0
                    } == true
                }
            }
            val service = onMain { field(current.get(), "service") as CaptureService }
            val resourceNames = listOf("engine", "encoder", "networkSender")
            val resources = onMain { resourceNames.associateWith { field(service, it)!! } }
            val engine = resources.getValue("engine") as CameraEngine
            val sender = resources.getValue("networkSender") as NetworkSender
            val session = sender.currentSession()
            val authorization = onMain { field(service, "captureAuthorization") }
            val generation = service.cameraStatus.generation
            assertNotNull(session)
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                val before = service.stats()
                onMain { current.get().requestedOrientation = orientation }
                await("rotation presents a preview while the same stream advances") {
                    onMain {
                        val value = service.stats()
                        assertNotEquals("Capture failed: ${value.error}", "Error", value.state)
                        val active = current.get()
                        val wanted = if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                            Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
                        val preview = field(active, "previewView") as SurfaceView
                        val status = service.cameraStatus
                        val rotation = when (active.windowManager.defaultDisplay.rotation) {
                            Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
                        }
                        val sensor = engine.entry.characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                        val expectedAngle = if (status.applied["orientationLock"] == 1.0)
                            status.applied["previewRotation"]?.toInt() else
                            (sensor - rotation * (if (engine.entry.facing == CameraCharacteristics.LENS_FACING_FRONT) -1 else 1) + 360) % 360
                        val renderer = field(engine, "renderer")
                        active.resources.configuration.orientation == wanted &&
                            field(active, "service") === service && preview.holder.surface.isValid &&
                            renderer != null && field(renderer, "preview") != null && status.previewRotation == expectedAngle &&
                            value.frames > before.frames && value.network.sentFrames > before.network.sentFrames
                    }
                }
                onMain {
                    for (name in resourceNames) assertSame("Rotation replaced $name", resources.getValue(name), field(service, name))
                    assertEquals(authorization, field(service, "captureAuthorization"))
                    assertEquals(generation, service.cameraStatus.generation)
                    assertFalse("Camera engine released", field(engine, "closed") as Boolean)
                }
                assertEquals("Transport session changed during rotation", session, sender.currentSession())
                assertEquals("Pairing changed during rotation", peers, ProductIdentity(context).desktops())
                assertEquals(phone, ProductIdentity(context).phoneId)
            }
        } finally {
            onMain {
                context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                current.get()?.let { it.requestedOrientation = initialOrientation; it.finish() }
            }
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }
}
