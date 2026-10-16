package org.camylla.proof

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
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

/** Uses real camera/encoder/service resources; only the Camera2 disconnect is injected. */
@RunWith(AndroidJUnit4::class)
class SleepSessionTest {
    @Test fun cameraLossDuringLockPreservesAuthorizationAndRecoversAfterWake() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        val identity = ProductIdentity(context)
        // A test peer with an unreachable local endpoint exercises the real reconnect worker.
        val peer = TrustedDesktop("71".repeat(32), "Sleep regression", "127.0.0.1",
            Base64.encodeToString(identity.certificate.encoded, Base64.NO_WRAP))
        identity.trust(peer)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val connected = CountDownLatch(1)
        val serviceRef = AtomicReference<CaptureService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                serviceRef.set((binder as CaptureService.LocalBinder).service)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            val service = serviceRef.get()
            instrumentation.runOnMainSync {
                context.startForegroundService(Intent(context, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_START).putExtra(CaptureService.EXTRA_PEER, peer.id))
            }
            await("Initial camera capture: ${service.stats()}") { service.stats().state == "Capturing" }
            val keepScreenOn = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            await("Active capture did not enable keep-screen-on") {
                activity.window.attributes.flags and keepScreenOn != 0
            }
            instrumentation.runOnMainSync { activity.recreate() }
            await("Recreated activity did not restore keep-screen-on for the active session") {
                resumedMainActivity(instrumentation)?.window?.attributes?.flags?.and(keepScreenOn) != 0
            }
            val activeActivity = resumedMainActivity(instrumentation)!!
            val originalTimeout = shellOutput("settings get system screen_off_timeout").trim()
            assertTrue("Could not read emulator screen timeout", originalTimeout.toLongOrNull() != null)
            shell("settings put system screen_off_timeout 3000")
            try {
                SystemClock.sleep(4_000)
                assertTrue("Active capture should prevent normal screen timeout", context
                    .getSystemService(PowerManager::class.java).isInteractive)
            } finally {
                shell("settings put system screen_off_timeout $originalTimeout")
            }
            val authorization = field(service, "captureAuthorization")
            val sender = field(service, "networkSender")
            val firstGeneration = service.cameraStatus.generation
            assertNotEquals("", authorization)
            val wakeLock = runCatching { field(service, "captureWakeLock") as? PowerManager.WakeLock }.getOrNull()
            assertTrue("The active session must keep its CPU/network work awake with the screen off", wakeLock?.isHeld == true)
            val engine = field(service, "engine")!!
            shell("input keyevent 223")
            await("Screen-off transition was not observed") { field(service, "cameraInterruptedByLock") == true }
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            // Deliver the Camera2 error after unlock to cover callbacks delayed by the
            // camera service while the device was locked.
            CameraEngine::class.java.getDeclaredMethod("fatal", String::class.java).apply { isAccessible = true }
                .invoke(engine, "Camera disconnected")
            instrumentation.waitForIdleSync()
            assertEquals("Lock/camera loss must not end the user's authorization", authorization,
                field(service, "captureAuthorization"))
            assertSame("Recovery must keep the existing network worker", sender, field(service, "networkSender"))
            assertTrue(ProductIdentity(context).desktops().any { it.id == peer.id && !it.revoked })
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            await("Camera did not recover after wake: ${service.stats()}") {
                service.stats().state == "Capturing" && service.cameraStatus.generation > firstGeneration
            }
            assertEquals(authorization, field(service, "captureAuthorization"))
            assertSame(sender, field(service, "networkSender"))
            val settings = CameraSettings(context)
            val appliedControls = mapOf("zoom" to 2.25, "exposureCompensation" to -1.0)
            val recoveryGeneration = service.cameraStatus.generation
            val lockedEngine = field(service, "engine")!!
            shell("input keyevent 223")
            await("Second screen-off transition was not observed") { field(service, "cameraInterruptedByLock") == true }
            instrumentation.runOnMainSync {
                setField(service, "cameraStatus", service.cameraStatus.copy(applied = appliedControls))
                CameraEngine::class.java.getDeclaredMethod("fatal", String::class.java).apply { isAccessible = true }
                    .invoke(lockedEngine, "Camera disconnected")
            }
            await("Camera did not suspend before explicit stop") {
                field(service, "engine") == null && service.cameraStatus.generation > recoveryGeneration
            }
            await("Suspended camera retained keep-screen-on") { activeActivity.window.attributes.flags and keepScreenOn == 0 }
            val cameraId = service.cameraStatus.camera
            val mode = service.cameraStatus.mode
            assertEquals(appliedControls, settings.values(cameraId, mode))
            instrumentation.runOnMainSync {
                context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            }
            await("Explicit stop did not release capture") { service.stats().state == "Stopped" }
            await("Stop did not clear keep-screen-on") { activeActivity.window.attributes.flags and keepScreenOn == 0 }
            assertEquals("Stop during locked suspension must preserve applied camera settings", appliedControls,
                settings.values(cameraId, mode))
            assertEquals("", field(service, "captureAuthorization"))
            assertNull(field(service, "engine"))
            assertNull(field(service, "encoder"))
            assertNull(field(service, "networkSender"))
            assertFalse("Stop must release the CPU wake lock", wakeLock!!.isHeld)
        } finally {
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            context.unbindService(connection)
            resumedMainActivity(instrumentation)?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            identity.revoke(peer.id)
        }
    }

    private fun shell(command: String) {
        shellOutput(command)
    }
    private fun shellOutput(command: String): String {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8)
        }
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private fun setField(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(owner, value)
    private fun resumedMainActivity(instrumentation: android.app.Instrumentation): MainActivity? {
        val active = AtomicReference<MainActivity?>()
        instrumentation.runOnMainSync {
            active.set(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull())
        }
        return active.get()
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }
}
