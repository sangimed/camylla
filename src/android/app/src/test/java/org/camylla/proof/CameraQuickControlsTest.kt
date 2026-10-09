package org.camylla.proof

import org.junit.Assert.*
import org.junit.Test

class CameraQuickControlsTest {
    @Test fun zoomShowsConfirmedRatioAndRejectsALensWithoutRange() {
        val status = CameraStatus(options = listOf(CameraOption("zoom", "Zoom", 1.0, 4.0)), applied = mapOf("zoom" to 2.25))
        assertTrue(CameraQuickControls.present("zoom", status).label.contains("2.25×"))
        assertTrue(CameraQuickControls.present("zoom", status).enabled)
        val fixed = status.copy(options = listOf(CameraOption("zoom", "Zoom", 1.0, 1.0)))
        assertFalse(CameraQuickControls.present("zoom", fixed).enabled)
        assertTrue(CameraQuickControls.present("zoom", fixed).reason!!.contains("No digital zoom"))
    }

    @Test fun autoPrefersContinuousVideoAndNeverClaimsFixedFocusIsAuto() {
        assertEquals(3, CameraQuickControls.autoMode(linkedMapOf(0 to "Manual", 4 to "Photo", 3 to "Video")))
        assertEquals(4, CameraQuickControls.autoMode(mapOf(4 to "Photo")))
        assertNull(CameraQuickControls.autoMode(mapOf(0 to "Fixed", 1 to "Single AF")))
        val fixed = CameraStatus(options = listOf(CameraOption("focusMode", "Fixed", choices = mapOf(0 to "Fixed"))))
        val action = CameraQuickControls.present("focusMode", fixed)
        assertFalse(action.enabled)
        assertTrue(action.reason!!.contains("unavailable"))
    }

    @Test fun remoteAppliedTorchAndLockReplaceLocalPresentationWithoutOptimism() {
        val options = listOf(CameraOption("torch", "Torch"), CameraOption("focusLock", "Lock"))
        val off = CameraStatus(options = options, applied = mapOf("torch" to 0.0, "focusLock" to 0.0))
        assertTrue(CameraQuickControls.present("torch", off).label.endsWith("Off"))
        val pending = off.copy(pending = setOf("torch"))
        assertTrue(CameraQuickControls.present("torch", pending).label.contains("Off · Applying"))
        assertFalse(CameraQuickControls.present("torch", pending).enabled)
        val remote = off.copy(applied = mapOf("torch" to 1.0, "focusLock" to 1.0))
        assertTrue(CameraQuickControls.present("torch", remote).label.endsWith("On"))
        assertTrue(CameraQuickControls.present("focusLock", remote).label.contains("Locked · Unlock"))
        assertFalse(CameraQuickControls.present("focusLock", remote.copy(pending = setOf("focusDistance"))).enabled)
    }

    @Test fun missingResultsAndHardwareReasonsDisableActions() {
        assertFalse(CameraQuickControls.present("torch", CameraStatus(options = listOf(CameraOption("torch", "Torch")))).enabled)
        val unavailable = CameraStatus(options = listOf(CameraOption("torch", "Torch", reason = "No flash unit")), applied = mapOf("torch" to 1.0))
        val action = CameraQuickControls.present("torch", unavailable)
        assertFalse(action.enabled)
        assertEquals("Torch: No flash unit", action.reason)
        assertTrue(action.label.endsWith("Unavailable"))
        assertFalse(CameraQuickControls.present("torch", CameraStatus()).enabled)
    }
}
