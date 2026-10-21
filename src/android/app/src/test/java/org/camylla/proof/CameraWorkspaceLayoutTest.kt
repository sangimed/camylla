package org.camylla.proof

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraWorkspaceLayoutTest {
    @Test fun `layout adapts to available width height and font scale`() {
        assertEquals(CameraWorkspaceLayout.Mode.COMPACT, CameraWorkspaceLayout.choose(390, 600, 1f))
        assertEquals(CameraWorkspaceLayout.Mode.STACKED, CameraWorkspaceLayout.choose(700, 900, 1f))
        assertEquals(CameraWorkspaceLayout.Mode.SPLIT, CameraWorkspaceLayout.choose(900, 500, 1f))
        assertEquals(CameraWorkspaceLayout.Mode.SPLIT, CameraWorkspaceLayout.choose(900, 320, 1f))
        assertEquals(CameraWorkspaceLayout.Mode.SPLIT, CameraWorkspaceLayout.choose(1200, 420, 1f))
        assertEquals(CameraWorkspaceLayout.Mode.COMPACT, CameraWorkspaceLayout.choose(900, 800, 1.5f))
    }
}
