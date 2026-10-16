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
            val authorization = field(service, "captureAuthorization")
            val sender = field(service, "networkSender")
            val firstGeneration = service.cameraStatus.generation
            assertNotEquals("", authorization)
            val wakeLock = runCatching { field(service, "captureWakeLock") as? PowerManager.WakeLock }.getOrNull()
            assertTrue("The active session must keep its CPU/network work awake with the screen off", wakeLock?.isHeld == true)
            shell("input keyevent 223")
            // CameraDevice.onDisconnected reaches this exact callback on hardware that
            // evicts a locked camera. Do not depend on an emulator's eviction policy.
            val engine = field(service, "engine")!!
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
            instrumentation.runOnMainSync {
                context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            }
            await("Explicit stop did not release capture") { service.stats().state == "Stopped" }
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
            instrumentation.runOnMainSync { activity.finish() }
            identity.revoke(peer.id)
        }
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }
}
